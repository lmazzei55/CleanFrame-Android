// Isolated, read-only SDK probe. Parent discards all SDK stdout/stderr.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { decodeSnapshot } = require('./status.cjs');
process.on('disconnect', () => process.exit(0));
const watching = process.argv[4] === 'watch';
let streaming = false;
(async () => {
  let stage = 'load-sdk';
  const responses = [];
  let throttled = false;
  try {
    const config = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
    const sdk = path.resolve(process.argv[3]);
    const axios = require(path.join(sdk, 'node_modules/axios/dist/node/axios.cjs'));
    const create = axios.create.bind(axios);
    axios.create = function (...args) {
      const client = create(...args);
      function record(response) {
        const url = String(response?.config?.url || '');
        const endpoint = url.includes('/email/login') ? (url.includes('/v2/') ? 'login-v2' : 'login-v1') : url.includes('user_center_info') ? 'user-info' : 'other';
        if (response?.status === 429) throttled = true;
        responses.push({ endpoint, http: Number(response?.status) || null, apiCode: ['code', 'res_code', 'result_code'].map(k => response?.data?.[k]).find(v => typeof v === 'number') ?? null, tokenReturned: !!response?.data?.access_token });
      }
      client.interceptors.response.use(r => { record(r); return r; }, e => { record(e.response); return Promise.reject(e); });
      return client;
    };
    // SDK's eufyLogin swallows failures, preventing its intended v2 -> v1 fallback.
    const { EufyApi } = require(path.join(sdk, 'build/cjs/api/EufyApi.js'));
    const originalLogin = EufyApi.prototype.eufyLogin;
    EufyApi.prototype.eufyLogin = async function (...args) {
      if (throttled) throw new Error('Authentication cooldown required');
      const session = await originalLogin.apply(this, args);
      if (!session?.access_token) throw new Error('No session returned');
      return session;
    };
    const { EufyLogin } = require(path.join(sdk, 'build/cjs/controllers/Login.js'));
    const login = new EufyLogin(config.email, config.password, config.clientId);
    stage = 'login';
    const sessionFile = path.join(path.dirname(process.argv[2]), 'session.json');
    let cached;
    try { cached = JSON.parse(fs.readFileSync(sessionFile, 'utf8')); } catch (_) { }
    if (cached?.mqttOnly && cached.clientId === config.clientId && cached.session?.access_token && Date.now() - cached.savedAt < 3600000) {
      login.eufyApi.session = cached.session; login.eufyApi.userInfo = cached.userInfo;
      login.mqttCredentials = cached.mqttCredentials;
    } else {
      await login.login({ mqtt: true, tuya: true });

    }
    stage = 'discovery';
    await login.getDevices(); // Authentication and discovery only: no connect/start/dock commands.
    const candidates = [...login.cloudDevices, ...login.mqttDevices].filter(d => d.deviceModel === 'T2280');
    const unique = new Map();
    for (const device of candidates) {
      const old = unique.get(device.deviceId);
      if (!old || Object.keys(device.dps || {}).length > Object.keys(old.dps || {}).length) unique.set(device.deviceId, device);
    }
    if (unique.size !== 1) {
      process.send({ ok: false, reason: unique.size ? 'multiple-c20' : 'no-c20' });
    } else {
      const device = [...unique.values()][0];
      if (device.mqtt && login.eufyApi?.session?.access_token) fs.writeFileSync(sessionFile, JSON.stringify({ mqttOnly: true, clientId: config.clientId,
        session: login.eufyApi.session, userInfo: login.eufyApi.userInfo, mqttCredentials: login.mqttCredentials, savedAt: Date.now() }), { mode: 0o600 });
      const { decode } = require(path.join(sdk, 'build/cjs/lib/utils.js'));
      const snapshot = await decodeSnapshot(device.dps, device.apiType, decode);
      // Never send account data, keys, raw DPS, maps or identifiers to stdout.
      process.send({ ok: true, snapshot, model: 'T2280', transport: device.mqtt ? 'mqtt-candidate' : 'cloud-candidate',
        apiType: device.apiType === 'novel' ? 'novel' : 'legacy',
        fields: Object.keys(device.dps || {}).filter(k => /^\d{1,4}$/.test(k)).sort(),
        hasData: Object.keys(device.dps || {}).length > 0,
        // Private follow-up selector, saved only to ignored local configuration.
        selector: crypto.createHash('sha256').update(String(device.deviceId)).digest('hex') });
      if (watching) {
        streaming = true;
        async function poll() {
          try {
            const latest = device.mqtt ? await login.getMqttDevice(device.deviceId) : await login.getCloudDevice(device.deviceId);
            if (throttled) { process.send({ ok: false, reason: 'rate-limited', responses: responses.slice(-12) }); return; }
            process.send({ ok: true, snapshot: await decodeSnapshot(latest?.online === false || latest?.is_online === false ? null : latest?.dps, device.apiType, decode) });
          } catch (_) { process.send({ ok: true, snapshot: { configured: true, state: 'unknown', receivedAt: 0 } }); }
          setTimeout(poll, 30000);
        }
        setTimeout(poll, 30000);
      }
    }
  } catch (error) { process.send({ ok: false, reason: 'login-or-discovery-failed', stage, errorKind: ['TypeError', 'Error', 'AxiosError'].includes(error?.name) ? error.name : 'Other', responses: responses.slice(0, 12) }); }
  finally { if (!streaming) { process.disconnect(); process.exit(0); } }
})();

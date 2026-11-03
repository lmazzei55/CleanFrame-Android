import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, statSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const probe = fileURLToPath(new URL('./probe.mjs', import.meta.url));
for (const scenario of ['one', 'empty', 'multiple', 'failure']) test('Discovery isolation: ' + scenario, () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'eufy-probe-test-'));
  try {
    const sdk = path.join(dir, 'sdk');
    for (const folder of ['build/cjs/controllers', 'build/cjs/api', 'build/cjs/lib', 'node_modules/axios/dist/node']) mkdirSync(path.join(sdk, folder), { recursive: true });
    writeFileSync(path.join(sdk, 'node_modules/axios/dist/node/axios.cjs'), 'module.exports = { create() { return { interceptors: { response: { use() {} } } }; } };');
    writeFileSync(path.join(sdk, 'build/cjs/lib/utils.js'), 'exports.decode = async function () { throw new Error("Undecodable"); };');
    writeFileSync(path.join(sdk, 'build/cjs/api/EufyApi.js'), 'exports.EufyApi = class { async eufyLogin() { return { access_token: "PRIVATE_TOKEN" }; } };');
    const devices = scenario === 'empty' ? [] : scenario === 'multiple' ? [{ deviceModel: 'T2280', deviceId: 'private-one', dps: {} }, { deviceModel: 'T2280', deviceId: 'private-two', dps: {} }]
      : [{ deviceModel: 'T2280', deviceId: 'private-one', apiType: 'legacy', mqtt: false, dps: { 104: 87, 15: 'running', 106: 'SECRET_RAW_VALUE' } }];
    writeFileSync(path.join(sdk, 'build/cjs/controllers/Login.js'), `exports.EufyLogin = class { cloudDevices = ${JSON.stringify(devices)}; mqttDevices = []; async login() { console.log('PRIVATE_PASSWORD'); console.error('PRIVATE_TOKEN'); ${scenario === 'failure' ? "throw new Error('PRIVATE_PASSWORD');" : ''} } async getDevices() {} };`);
    const credentials = path.join(dir, 'credentials.json'), output = path.join(dir, 'probe.json');
    writeFileSync(credentials, JSON.stringify({ email: 'private@example.invalid', password: 'PRIVATE_PASSWORD', clientId: 'client' }), { mode: 0o600 });
    const run = spawnSync(process.execPath, [probe, credentials, sdk, output], { encoding: 'utf8', timeout: 5000 });
    const logs = run.stdout + run.stderr;
    for (const secret of ['PRIVATE_PASSWORD', 'PRIVATE_TOKEN', 'SECRET_RAW_VALUE', 'private@example.invalid', 'private-one', 'private-two']) assert.ok(!logs.includes(secret), 'No secrets in parent diagnostics');
    assert.equal(run.status, scenario === 'one' ? 0 : 1);
    if (scenario === 'one') {
      const saved = readFileSync(output, 'utf8');
      assert.ok(!saved.includes('SECRET_RAW_VALUE') && !saved.includes('private-one'));
      assert.equal(JSON.parse(saved).transport, 'cloud-candidate');
      assert.equal(statSync(output).mode & 0o777, 0o600);
    }
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

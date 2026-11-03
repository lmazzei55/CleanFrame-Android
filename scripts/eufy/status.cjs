// Explicit protocol decoding. Never use SDK convenience getters with optimistic fallbacks.
const labels = { standby: 'idle', sleep: 'idle', sleeping: 'idle', cleaning: 'cleaning', running: 'cleaning', spot: 'cleaning',
  charging: 'charging', completed: 'docked', go_home: 'returning', recharge: 'returning', paused: 'paused', fault: 'error' };
const own = (object, key) => Object.prototype.hasOwnProperty.call(object || {}, key);
async function decodeSnapshot(dps, type, decode, now = Date.now()) {
  let state = 'unknown', battery = null, errorCode = null;
  dps = dps && typeof dps === 'object' ? dps : {};
  if (type === 'novel') {
    try {
      const work = await decode('./proto/cloud/work_status.proto', 'WorkStatus', dps['153']);
      if (typeof work.state === 'string') state = labels[work.state.toLowerCase()] || 'unknown';
      if (state === 'charging' && work.charging?.state === 'DONE') state = 'docked';
    } catch (_) { /* Missing/undecodable status stays unknown. */ }
    try {
      const value = typeof dps['163'] === 'number' ? dps['163'] : (await decode('./proto/cloud/misc.proto', 'Battery', dps['163'])).level;
      if (typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= 100) battery = value;
    } catch (_) { }
    try {
      const errors = await decode('./proto/cloud/error_code.proto', 'ErrorCode', dps['177']);
      errorCode = [...(errors.error || []), ...(errors.warn || [])].find(code => Number.isInteger(code) && code > 0 && code <= 2147483647) ?? null;
    } catch (_) { }
  } else {
    if (typeof dps['15'] === 'string') state = labels[dps['15'].toLowerCase()] || 'unknown';
    if (typeof dps['104'] === 'number' && Number.isInteger(dps['104']) && dps['104'] >= 0 && dps['104'] <= 100) battery = dps['104'];
    if (typeof dps['106'] === 'number' && Number.isInteger(dps['106']) && dps['106'] > 0 && dps['106'] <= 2147483647) errorCode = dps['106'];
  }
  if (errorCode !== null) state = 'error';
  const valid = state !== 'unknown' || battery !== null || errorCode !== null;
  return { configured: true, state, battery, errorCode, receivedAt: valid ? now : 0 };
}
module.exports = { decodeSnapshot };

import { test } from 'node:test';
import assert from 'node:assert/strict';
import status from './status.cjs';
const { decodeSnapshot } = status;
const fail = async () => { throw new Error('bad protobuf'); };
test('Missing and malformed readings never become charging or battery zero', async () => {
  for (const dps of [null, {}, {153: 'invalid',163:'invalid'}, {15:'nonsense',104:101}]) {
    const value = await decodeSnapshot(dps, dps?.[15] ? 'legacy' : 'novel', fail, 123);
    assert.equal(value.state, 'unknown'); assert.equal(value.battery, null); assert.equal(value.receivedAt, 0);
  }
});
test('Legacy return is not docked; zero battery retained; fault overrides', async () => {
  assert.equal((await decodeSnapshot({15:'go_home',104:0}, 'legacy', fail)).state, 'returning');
  assert.equal((await decodeSnapshot({15:'running',104:0}, 'legacy', fail)).battery, 0);
  assert.equal((await decodeSnapshot({15:'running',106:7}, 'legacy', fail)).state, 'error');
});
test('Novel battery protobuf, returning, dock completion and fault decode explicitly', async () => {
  const decode = async (_, type) => type === 'WorkStatus' ? {state:'GO_HOME'} : type === 'Battery' ? {level:72} : {error:[7]};
  const value = await decodeSnapshot({153:'work',163:'battery',177:'error'}, 'novel', decode, 123);
  assert.equal(value.battery,72); assert.equal(value.state,'error'); assert.equal(value.errorCode,7); assert.equal(value.receivedAt,123);
  const dock = await decodeSnapshot({}, 'novel', async (_,type) => type==='WorkStatus' ? {state:'CHARGING',charging:{state:'DONE'}} : {}, 123);
  assert.equal(dock.state,'docked'); assert.equal(dock.battery,null);
});
test('Unrecognized protocol content is not an all-clear', async () => {
  const value = await decodeSnapshot({}, 'novel', async () => ({}), 123);
  assert.equal(value.state,'unknown'); assert.equal(value.receivedAt,0);
});

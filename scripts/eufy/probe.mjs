// Only sanitized discovery results cross the worker boundary. SDK diagnostics may contain secrets.
import { fork } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { writeFileSync } from 'node:fs';
import path from 'node:path';
const [credentials, sdk, output] = process.argv.slice(2);
if (!credentials || !sdk || !output) throw new Error('Use setup.py to configure the private probe.');
const worker = fork(fileURLToPath(new URL('./probe-worker.cjs', import.meta.url)), [credentials, sdk], { stdio: ['ignore', 'ignore', 'ignore', 'ipc'] });
let received = false;
const timer = setTimeout(() => { worker.kill(); console.error('Eufy discovery timed out. No vacuum commands were sent.'); process.exitCode = 1; }, 60000);
worker.on('message', result => {
  received = true; clearTimeout(timer);
  if (!result || !result.ok) {
    if (result?.responses?.some(r => r.http === 429)) writeFileSync(path.join(path.dirname(output), 'cooldown.json'), JSON.stringify({ retryAfter: Date.now() + 15 * 60000 }), { mode: 0o600 });
    const messages = { 'no-c20': 'No C20 was identified. Login or unified-app discovery may be unsupported; this is not proof that the vacuum is absent.',
      'multiple-c20': 'More than one C20 found. A device selector is needed before proceeding.',
      'login-or-discovery-failed': 'Login/discovery failed. Check the local credentials and account compatibility. Do not disable two-factor authentication.' };
    if (result?.stage) console.error('Stage: ' + result.stage + '; error type: ' + result.errorKind + '; sanitized responses: ' + JSON.stringify(result.responses));
    console.error(messages[result?.reason] || 'Eufy discovery failed.'); process.exitCode = 1; return;
  }
  writeFileSync(output, JSON.stringify({ ...result, checkedAt: new Date().toISOString() }, null, 2), { mode: 0o600 });
  console.log('C20/T2280 identified. Candidate transport: ' + result.transport + '.');
  console.log(result.hasData ? 'Status fields were returned; field decoding still needs verification.' : 'No status fields returned yet; no live status can be claimed.');
  console.log('Sanitized discovery result saved locally. No vacuum commands were sent.');
});
worker.on('exit', () => { clearTimeout(timer); if (!received) { console.error('Discovery stopped without a result.'); process.exitCode = 1; } });

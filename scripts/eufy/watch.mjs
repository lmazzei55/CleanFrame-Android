// Foreground Mac experiment. Auth remains in a silent child; only normalized status reaches ADB.
import { fork, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
const root = fileURLToPath(new URL('../../', import.meta.url));
const sdk = path.join(root, '.local/eufy-sdk-review');
const revision = spawnSync('git', ['rev-parse', 'HEAD'], { cwd: sdk, encoding: 'utf8' });
if (revision.stdout?.trim() !== '8872307101a7032aa9f359781a3783fc50b0d380') throw new Error('Unreviewed SDK revision.');
const config = JSON.parse(readFileSync(path.join(root, '.local/eufy-status/tv.json'), 'utf8'));
if (!/^\d{1,5}$/.test(String(config.port)) || !/^[a-zA-Z0-9._:-]{1,100}$/.test(config.serial)) throw new Error('Invalid private TV target.');
const adb = path.join(root, '.local/android-tools/platform-tools/adb');
const quote = value => "'" + value.replace(/'/g, "'\\''") + "'";
function send(snapshot) {
  const run = spawnSync(adb, ['-P', String(config.port), '-s', config.serial, 'shell', 'am', 'broadcast', '-a', 'com.mazzei.cleanframe.VACUUM_STATUS', '-n', 'com.mazzei.cleanframe/.VacuumStatusReceiver', '--es', 'snapshot', quote(JSON.stringify(snapshot))], { encoding: 'utf8', timeout: 10000 });
  return !run.error && run.status === 0 && /result=1\b/.test(run.stdout);
}
if (process.argv.includes('--clear')) {
  console.log(send({ configured: false }) ? 'TV vacuum card cleared.' : 'Could not clear the card; check TV connection.');
  process.exit(0);
}
try {
  const cooldown = JSON.parse(readFileSync(path.join(root, '.local/eufy-status/cooldown.json'), 'utf8'));
  if (cooldown.retryAfter > Date.now()) { console.error('Eufy authentication cooldown active. No login attempted.'); process.exit(1); }
} catch (error) { if (error.code !== 'ENOENT') throw error; }
let updates = 0, last = 0;
const worker = fork(fileURLToPath(new URL('./probe-worker.cjs', import.meta.url)), [path.join(root, '.local/eufy-status/credentials.json'), path.join(root, '.local/eufy-sdk-review'), 'watch'], { stdio: ['ignore', 'ignore', 'ignore', 'ipc'] });
const startup = setTimeout(() => { console.error('Eufy startup timed out.'); worker.kill(); process.exitCode = 1; }, 60000);
worker.on('message', value => {
  clearTimeout(startup);
  if (!value?.ok) {
    if (value?.responses?.some(r => r.http === 429)) writeFileSync(path.join(root, '.local/eufy-status/cooldown.json'), JSON.stringify({retryAfter:Date.now()+15*60000}), {mode:0o600});
    console.error('Eufy login/discovery failed. Run the private setup again.'); worker.kill(); process.exitCode = 1; return; }
  if (value.snapshot) {
    last = Date.now();
    const sent = send(value.snapshot); updates++;
    console.log(new Date().toLocaleTimeString() + ': ' + (sent ? 'TV status delivered' : 'TV delivery failed') + ' (' + value.snapshot.state + ', battery ' + (value.snapshot.battery ?? 'unknown') + ').');
    if (process.argv.includes('--once')) { if (!sent) process.exitCode = 1; worker.kill(); }
  }
});
const watchdog = setInterval(() => { if (last && Date.now() - last > 90000) console.log('No recent Eufy response. TV status will expire automatically.'); }, 60000);
worker.on('exit', () => { clearTimeout(startup); clearInterval(watchdog); if (!updates) process.exitCode = 1; });
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { worker.kill(); clearTimeout(startup); clearInterval(watchdog); process.exit(0); });
console.log('Read-only Eufy status helper running. Ctrl+C stops it; the TV expires old data after 90 seconds.');

"""Private Mac Eufy discovery setup. Never print credentials or SDK diagnostics."""
import argparse
import getpass
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
LOCAL = ROOT / '.local/eufy-status'
SDK = ROOT / '.local/eufy-sdk-review'
REVISION = '8872307101a7032aa9f359781a3783fc50b0d380'
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--prepare', action='store_true', help='Download pinned SDK and prepare isolated dependencies; no Eufy login.')
p.add_argument('--gui', action='store_true', help='Use native macOS dialogs; password input is hidden.')
p.add_argument('--reuse', action='store_true', help='Retry discovery using the existing private credentials.')
args = p.parse_args()
os.umask(0o077)
LOCAL.mkdir(parents=True, exist_ok=True)
LOCAL.chmod(0o700)

def run_private(command, cwd=None):
    # Dependency output only, never used for the credential-bearing worker.
    result = subprocess.run(command, cwd=cwd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if result.returncode:
        raise SystemExit('SDK preparation failed. Review the pinned dependency/build setup; no Eufy login was attempted.')

if args.prepare:
    if not SDK.exists():
        run_private(['git', 'clone', '--no-checkout', 'https://github.com/martijnpoppen/eufy-clean.git', str(SDK)])
        run_private(['git', 'checkout', '--detach', REVISION], SDK)
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=SDK, text=True).strip()
    if actual != REVISION:
        raise SystemExit('SDK revision mismatch; refusing to run an unreviewed revision.')
    run_private(['npm', 'ci', '--ignore-scripts', '--no-audit', '--no-fund'], SDK)
    run_private([str(SDK / 'node_modules/.bin/tsc'), '--project', 'tsconfig.cjs.json'], SDK)
    for source in (SDK / 'src').rglob('*'):
        if source.is_file() and source.suffix in ('.proto', '.js', '.tgz'):
            target = SDK / 'build/cjs' / source.relative_to(SDK / 'src')
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    print('Pinned SDK prepared. No Eufy login performed.')
    sys.exit(0)

if not (SDK / 'build/cjs/controllers/Login.js').exists():
    raise SystemExit('First run: python3 scripts/eufy/setup.py --prepare')
if subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=SDK, text=True).strip() != REVISION:
    raise SystemExit('SDK revision mismatch.')
cooldown = LOCAL / 'cooldown.json'
if cooldown.exists():
    until = json.loads(cooldown.read_text()).get('retryAfter', 0)
    if until > time.time() * 1000:
        raise SystemExit('Eufy authentication cooldown is active. Retry after ' + time.strftime('%H:%M', time.localtime(until / 1000)) + '. No login attempted.')
credentials = LOCAL / 'credentials.json'
if not args.reuse:
    if args.gui:
        def prompt(message, hidden=False):
            code = 'text returned of (display dialog ' + json.dumps(message) + ' with title "CleanFrame · Eufy experiment" default answer ""' + (' with hidden answer' if hidden else '') + ' buttons {"Cancel", "Continue"} default button "Continue" cancel button "Cancel")'
            result = subprocess.run(['osascript', '-e', code], capture_output=True, text=True)
            if result.returncode:
                raise SystemExit('Setup cancelled. No credentials changed.')
            return result.stdout.rstrip('\n')
        email = prompt('Eufy account email. This experimental community connector runs only on this Mac.')
        password = prompt('Eufy password. It will be saved privately on this Mac for the experiment, never on the TV or in GitHub.', True)
    else:
        email = input('Eufy account email (stored locally): ').strip()
        password = getpass.getpass('Eufy password (hidden, stored locally): ')
    if not email or not password:
        raise SystemExit('Empty credentials. Nothing saved.')
    temp = credentials.with_suffix('.tmp')
    temp.write_text(json.dumps({'email': email, 'password': password, 'clientId': uuid.uuid4().hex}))
    temp.chmod(0o600)
    temp.replace(credentials)
    del password
if not credentials.exists():
    raise SystemExit('No private credentials saved. Run setup without --reuse.')
credentials.chmod(0o600)
print('Checking Eufy login and C20 discovery. No cleaning or docking commands will be sent.', flush=True)
result = subprocess.run(['node', str(ROOT / 'scripts/eufy/probe.mjs'), str(credentials), str(SDK), str(LOCAL / 'probe.json')])
sys.exit(result.returncode)

"""Remote-control an Android/Google TV over the Android TV Remote v2 protocol (no ADB).

Sends the same keys and app links as the phone remote. It reads the foreground app but
never changes Home/startup settings, accessibility services or installed packages.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as XML

ROOT = Path(__file__).resolve().parents[1]
REMOTE = ROOT / '.local/android/remote'
CONFIG = REMOTE / 'device.json'
CERT = REMOTE / 'cert.pem'
KEY = REMOTE / 'key.pem'
VENV_PYTHON = REMOTE / 'venv/bin/python'
# Verified against the TCL Smart TV Pro; newer releases need Python 3.10+.
LIBRARY = 'androidtvremote2==0.0.13'
STOCK_HOME = 'com.google.android.apps.tv.launcherx'
ANDROID = '{http://schemas.android.com/apk/res/android}'

parser = argparse.ArgumentParser()
parser.add_argument('action', choices=['setup', 'pair', 'status', 'key', 'launch', 'home-check'])
parser.add_argument('values', nargs='*', help='key names for key (HOME, DPAD_DOWN, MUTE...), a link for launch')
parser.add_argument('--host')
parser.add_argument('--python', default='/usr/bin/python3', help='interpreter for setup')
args = parser.parse_args()

if args.action == 'setup':
    # macOS Local Network privacy silently blocks python.org/Homebrew interpreters from
    # reaching the TV ("No route to host"); Apple's own /usr/bin/python3 is allowed.
    subprocess.run([args.python, '-m', 'venv', str(REMOTE / 'venv')], check=True)
    subprocess.run([str(VENV_PYTHON), '-m', 'pip', 'install', '-q', LIBRARY], check=True)
    raise SystemExit('Remote environment ready. Next: python3 scripts/android-remote.py pair --host TV_ADDRESS')

if VENV_PYTHON.exists() and Path(sys.prefix).resolve() != (REMOTE / 'venv').resolve():
    os.execv(str(VENV_PYTHON), [str(VENV_PYTHON), *sys.argv])
try:
    from androidtvremote2 import AndroidTVRemote, CannotConnect, ConnectionClosed, InvalidAuth
except ImportError:
    raise SystemExit('Run: python3 scripts/android-remote.py setup')

config = json.loads(CONFIG.read_text()) if CONFIG.exists() else {}
host = args.host or config.get('host')
if not host:
    raise SystemExit('Pass --host TV_ADDRESS once; it is saved to .local/android/remote/device.json.')
app_id = XML.parse(ROOT / 'android/AndroidManifest.xml').getroot().get('package')


def make_remote():
    # enable_ime is what makes the TV report the foreground app.
    return AndroidTVRemote('CleanFrame', str(CERT), str(KEY), host)


async def connected():
    remote = make_remote()
    try:
        await remote.async_connect()
    except InvalidAuth:
        raise SystemExit('Not paired with this TV. Run: python3 scripts/android-remote.py pair')
    except CannotConnect:
        raise SystemExit('Cannot reach the TV at ' + host + '. Check it is on and on this network.')
    # The TV pushes the foreground app shortly after connecting.
    for _ in range(20):
        if remote.current_app:
            break
        await asyncio.sleep(0.1)
    return remote


async def foreground_after(remote, seconds):
    previous = remote.current_app
    for _ in range(int(seconds * 10)):
        await asyncio.sleep(0.1)
        if remote.current_app != previous:
            break
    await asyncio.sleep(0.5)
    return remote.current_app


async def pair():
    REMOTE.mkdir(parents=True, exist_ok=True)
    remote = make_remote()
    await remote.async_generate_cert_if_missing()
    name, _ = await remote.async_get_name_and_mac()
    print('Pairing with ' + name + '. The TV shows a code.')
    await remote.async_start_pairing()
    while True:
        try:
            await remote.async_finish_pairing(input('Code shown on the TV: ').strip())
            break
        except InvalidAuth:
            print('That code was not accepted. Try again.')
        except ConnectionClosed:
            print('The TV closed the pairing session; starting again.')
            await remote.async_start_pairing()
    CONFIG.write_text(json.dumps({'host': host}, indent=2) + '\n')
    print('Paired. Certificate and address saved under .local/android/remote/.')


async def main():
    if args.action == 'pair':
        return await pair()
    remote = await connected()
    try:
        if args.action == 'status':
            info = remote.device_info or {}
            print('TV: ' + ' '.join(str(info.get(k, '')) for k in ('manufacturer', 'model', 'sw_version')).strip())
            print('On: ' + str(remote.is_on))
            print('Foreground: ' + (remote.current_app or 'unknown'))
            print('Volume: ' + json.dumps(remote.volume_info))
        elif args.action == 'key':
            if not args.values:
                raise SystemExit('Name at least one key, e.g. key HOME')
            for name in args.values:
                remote.send_key_command(name.upper())
                await asyncio.sleep(0.3)
            print('Foreground: ' + (await foreground_after(remote, 2) or 'unknown'))
        elif args.action == 'launch':
            if len(args.values) != 1:
                raise SystemExit('Give one app link, e.g. launch https://www.youtube.com')
            remote.send_launch_app_command(args.values[0])
            print('Foreground: ' + (await foreground_after(remote, 4) or 'unknown'))
        else:
            # Presses Home once and reports where it went: tells whether CleanFrame's
            # Home service is bound (it is unbound after every reboot or APK update).
            print('Pressing Home on the TV.')
            remote.send_key_command('HOME')
            landed = await foreground_after(remote, 3)
            print('Foreground: ' + (landed or 'unknown'))
            if landed == app_id:
                print('Home returns to CleanFrame.')
            elif landed == STOCK_HOME:
                raise SystemExit('Home went to Google TV. Open CleanFrame and re-enable the Home button from its prompt.')
            else:
                raise SystemExit('Home result is unclear; check the TV.')
    finally:
        remote.disconnect()


asyncio.run(main())

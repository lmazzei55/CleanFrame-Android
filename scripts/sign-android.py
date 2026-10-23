"""Re-sign a CI test APK using a persistent, private installation key on this Mac."""
import argparse
import hashlib
import os
from pathlib import Path
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True, help='JDK home containing bin/java and bin/keytool')
    parser.add_argument('--build-tools', type=Path, required=True, help='Android SDK build-tools directory')
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'dist/CleanFrame-Android-local.apk')
    args = parser.parse_args()
    jdk, build_tools = args.jdk.resolve(), args.build_tools.resolve()
    if args.input.resolve() == args.output.resolve():
        raise SystemExit('Keep the CI artifact intact: input and output must differ.')
    if not args.input.is_file():
        raise SystemExit('Input APK does not exist.')
    for tool in [jdk / 'bin/java', jdk / 'bin/keytool', build_tools / 'lib/apksigner.jar']:
        if not tool.is_file():
            raise SystemExit('Missing signing tool: ' + str(tool))
    private = ROOT / '.local/android/signing'
    private.mkdir(parents=True, exist_ok=True, mode=0o700)
    private.chmod(0o700)
    key, password = private / 'installation.p12', private / 'password.txt'
    if key.exists() and not password.exists():
        raise SystemExit('Existing key has no password file. Restore its password; do not replace the key.')
    if not password.exists():
        with os.fdopen(os.open(password, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as stream:
            stream.write(secrets.token_urlsafe(40) + '\n')
    if not key.exists():
        subprocess.run([str(jdk / 'bin/keytool'), '-genkeypair', '-keystore', str(key), '-storetype', 'PKCS12',
            '-storepass:file', str(password), '-keypass:file', str(password), '-alias', 'cleanframe',
            '-dname', 'CN=CleanFrame Local Installation', '-keyalg', 'RSA', '-keysize', '3072',
            '-validity', '10000', '-noprompt'], check=True)
        key.chmod(0o600)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    signer = [str(jdk / 'bin/java'), '-jar', str(build_tools / 'lib/apksigner.jar')]
    subprocess.run(signer + ['sign', '--ks', str(key), '--ks-key-alias', 'cleanframe',
        '--ks-pass', 'file:' + str(password),
        '--out', str(args.output), str(args.input)], check=True)
    subprocess.run(signer + ['verify', '--verbose', '--print-certs', str(args.output)], check=True)
    digest = hashlib.sha256(args.output.read_bytes()).hexdigest()
    args.output.with_suffix('.apk.sha256').write_text(digest + '  ' + args.output.name + '\n')
    print('Signed with the persistent local installation key: ' + str(args.output))


if __name__ == '__main__':
    main()

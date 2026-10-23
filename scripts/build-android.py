"""Build a local test APK with official Android SDK tools; no Gradle/server required."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import tempfile
import zipfile
from font_assets import ROOT, write_widget_archive


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def main():
    sdk_path = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    if not sdk_path:
        raise SystemExit('Set ANDROID_SDK_ROOT to your Android SDK. See android/README.md.')
    sdk = Path(sdk_path)
    tools = sdk / 'build-tools/34.0.0'
    android_jar = sdk / 'platforms/android-34/android.jar'
    for path in [android_jar, tools / 'aapt2', tools / 'd8', tools / 'zipalign', tools / 'apksigner']:
        if not path.is_file():
            raise SystemExit('Missing SDK component: ' + str(path))
    for command in ['javac', 'keytool']:
        if not shutil.which(command):
            raise SystemExit('Install JDK 17 and put ' + command + ' on PATH.')
    output = ROOT / 'dist/CleanFrame-Android-test.apk'
    output.parent.mkdir(exist_ok=True)
    local = ROOT / '.local/android'
    local.mkdir(parents=True, exist_ok=True)
    key = local / 'debug.keystore'
    if not key.exists():
        run('keytool', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android',
            '-alias', 'androiddebugkey', '-dname', 'CN=Android Debug,O=Android,C=US',
            '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-noprompt')
    # Keep the local signing key for in-place updates. CI keys are ephemeral test keys.
    with tempfile.TemporaryDirectory(prefix='build-', dir=local) as temp:
        work = Path(temp)
        ui = work / 'assets/ui'
        ui.mkdir(parents=True)
        archive = work / 'ui.zip'
        write_widget_archive(archive, include_private=False)
        with zipfile.ZipFile(archive) as bundle:
            bundle.extractall(ui)
        for notice in ['LICENSE', 'THIRD_PARTY_NOTICES.md']:
            shutil.copy2(ROOT / notice, ui / notice)
        res = work / 'res'
        shutil.copytree(ROOT / 'android/res', res)
        shutil.copy2(ROOT / 'app/icon.png', res / 'drawable/icon.png')
        classes, dex, generated = work / 'classes', work / 'dex', work / 'generated'
        for folder in [classes, dex, generated]:
            folder.mkdir()
        run(tools / 'aapt2', 'compile', '--dir', res, '-o', work / 'resources.zip')
        run(tools / 'aapt2', 'link', '-o', work / 'base.apk', '--manifest', ROOT / 'android/AndroidManifest.xml',
            '-I', android_jar, '-A', work / 'assets', '--java', generated, work / 'resources.zip')
        sources = sorted((ROOT / 'android/src').rglob('*.java')) + sorted(generated.rglob('*.java'))
        run('javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-bootclasspath', android_jar,
            '-d', classes, *sources)
        run(tools / 'd8', '--lib', android_jar, '--min-api', '26', '--output', dex, *sorted(classes.rglob('*.class')))
        with zipfile.ZipFile(work / 'base.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
            for file in dex.glob('*.dex'):
                apk.write(file, file.name)
        run(tools / 'zipalign', '-f', '4', work / 'base.apk', work / 'aligned.apk')
        run(tools / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
            '--out', output, work / 'aligned.apk')
        run(tools / 'apksigner', 'verify', '--verbose', output)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix('.apk.sha256').write_text(digest + '  ' + output.name + '\n')
    print('Built ' + str(output) + '\nSHA256 ' + digest)


if __name__ == '__main__':
    main()

"""Compile and exercise the production Android-independent Home policy on JDK 17."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java_home = os.environ.get('JAVA_HOME')
candidate = root / '.local/android-signing/jdk-17.0.21+9/Contents/Home'
if not java_home and candidate.is_dir():
    java_home = str(candidate)
javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
if not javac or not java:
    raise SystemExit('JDK 17 is required; set JAVA_HOME.')
with tempfile.TemporaryDirectory(prefix='cleanframe-native-') as temp:
    sources = [root / 'android/src/com/mazzei/cleanframe/HomeLaunchPolicy.java',
        root / 'android/src/com/mazzei/cleanframe/VacuumFeedPolicy.java',
        *sorted((root / 'tests/native').rglob('*.java'))]
    subprocess.run([javac, '-d', temp, *map(str, sources)], check=True)
    for name in ['HomeLaunchPolicyTest', 'VacuumFeedPolicyTest']:
        subprocess.run([java, '-cp', temp, 'com.mazzei.cleanframe.' + name], check=True)

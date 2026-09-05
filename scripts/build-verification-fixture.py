#!/usr/bin/env python3
"""Build a separate, non-debuggable two-DEX APK for opt-in device verification."""
import os
from pathlib import Path
import subprocess
import zipfile

root = Path(__file__).resolve().parent.parent
sdk = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk'))
java = Path(os.environ['JAVA_HOME']) / 'bin'
tools = sdk / 'build-tools/36.0.0'
android = sdk / 'platforms/android-36/android.jar'
src = root / 'src/test/fixtures/android'
out = root / 'build/verification'
for name in ('classes', 'dex-main', 'dex-second'):
    (out / name).mkdir(parents=True, exist_ok=True)

def run(*args):
    subprocess.run([str(x) for x in args], check=True)

run(java / 'javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-cp', android,
    '-d', out / 'classes', *src.glob('*.java'))
classes = out / 'classes/com/apkharden/verification'
run(tools / 'd8', '--min-api', '23', '--output', out / 'dex-main', '--lib', android,
    '--classpath', out / 'classes', *[p for p in classes.glob('*.class') if p.name != 'SecretFeature.class'])
run(tools / 'd8', '--min-api', '23', '--output', out / 'dex-second', '--lib', android, classes / 'SecretFeature.class')
unsigned = out / 'verification-unsigned.apk'
run(tools / 'aapt2', 'link', '-I', android, '--manifest', src / 'AndroidManifest.xml', '-o', unsigned)
with zipfile.ZipFile(unsigned, 'a') as apk:
    apk.write(out / 'dex-main/classes.dex', 'classes.dex')
    apk.write(out / 'dex-second/classes.dex', 'classes2.dex')
# This is the repository's public test-only keystore, never a production credential.
run(tools / 'apksigner', 'sign', '--ks', root / 'src/test/resources/test.jks',
    '--ks-pass', 'pass:123456', '--key-pass', 'pass:123456', '--ks-key-alias', 'test',
    '--out', out / 'verification.apk', unsigned)
print(out / 'verification.apk')

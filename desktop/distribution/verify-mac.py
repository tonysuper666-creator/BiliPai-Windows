#!/usr/bin/env python3
"""Reproduce the updater JVM suite in a fresh macOS checkout with JDK 21.

The POSIX generator adapter and compile-only native/JS constants match TON-987.
This is source/JVM verification, never a Windows package or release-gate receipt.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[2])
parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
parser.add_argument('--out', type=Path, required=True)
args = parser.parse_args()
if sys.version_info[:2] != (3, 12):
    parser.error('Use Python 3.12 for the documented POSIX generator adapter')
if sys.platform != 'darwin':
    parser.error('This wrapper is for macOS; Windows native verification is separate')
if args.java_home is None:
    parser.error('Pass --java-home <JDK 21 directory>; the machine default may be JDK 17')
jdk = args.java_home.resolve()
result = subprocess.run([str(jdk / 'bin/java'), '-version'], text=True, capture_output=True)
if result.returncode or not re.search(r'version "21(?:\.|\")', result.stderr + result.stdout):
    parser.error('The selected Java home must contain JDK 21')
root, out = args.repo.resolve(), args.out.resolve()
if (root / 'desktop/build').exists():
    parser.error('Use a fresh worktree/clone with no desktop/build; this command must not rely on warm generated outputs')
out.mkdir(parents=True, exist_ok=False)
temporary = out / 'tmp'
temporary.mkdir()
pin = json.loads((root / 'desktop/native/diagnostic-share/approved-development-build.json').read_text())['dllSha256Bytes']
if not re.fullmatch('[0-9a-f]{64}', pin):
    raise ValueError('Invalid reviewed diagnostic hash pin')
native = root / 'desktop/build/generated/native-diagnostic-share/kotlin/com/bilipai/desktop/diagnostics/DesktopNativeDiagnosticShareAssetHash.kt'
js = root / 'desktop/build/generated/js-worker/com/bilipai/desktop/plugins/js/DesktopJsWorkerAssetHash.kt'
native.parent.mkdir(parents=True)
js.parent.mkdir(parents=True)
native.write_text('package com.bilipai.desktop.diagnostics\ninternal object DesktopNativeDiagnosticShareAssetHash { const val sha256: String = "' + pin + '" }\n')
js.write_text('// COMPILE-ONLY POSIX STUB, NOT A VERIFIED WINDOWS ASSET\npackage com.bilipai.desktop.plugins.js\ninternal const val DESKTOP_JS_WORKER_CATALOG_SHA256 = "' + '0' * 64 + '"\n')
adapter = Path(__file__).resolve().parent / 'mac-python'
env = {**os.environ, 'JAVA_HOME': str(jdk), 'PYTHON_EXECUTABLE': sys.executable,
       'PYTHONPATH': str(adapter) + os.pathsep + str(root / 'desktop/tools'),
       'BILIPAI_VERIFY_TMP': str(temporary), 'TMPDIR': str(temporary), 'GRADLE_OPTS': '-Xmx256m',
       'PYTHONDONTWRITEBYTECODE': '1'}
env['PATH'] = str(jdk / 'bin') + os.pathsep + env['PATH']
init = out / 'test-environment.init.gradle'
init.write_text("allprojects { tasks.withType(org.gradle.api.tasks.testing.Test).configureEach { systemProperty 'java.io.tmpdir', System.getenv('BILIPAI_VERIFY_TMP') } }\n")
command = ['bash', str(root / 'gradlew'), '-p', 'desktop', '--init-script', str(init), '--console=plain',
           '--no-daemon', '--no-build-cache', '--rerun-tasks', '--max-workers=1', '--no-parallel',
           '-Pkotlin.compiler.execution.strategy=in-process',
           '-Dorg.gradle.jvmargs=-Xmx8g -Xss16m -XX:MaxMetaspaceSize=2g -XX:ReservedCodeCacheSize=256m -Dfile.encoding=UTF-8',
           '-x', 'prepareNativeDiagnosticShare', '-x', 'fetchJsWorkerJdk', '-x', 'prepareJsWorker', '-x', 'prepareJsWorkerResources',
           ':compileKotlin', ':compileTestKotlin', ':test', '--tests', 'com.bilipai.desktop.update.*']
print('Cold checkout verified; JDK 21 selected; Windows path-prefix adapter enabled', flush=True)
with (out / 'gradle.log').open('w') as log:
    log.write('$ ' + repr(command) + '\n')
    log.flush()
    code = subprocess.run(command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT).returncode
counts = {key: 0 for key in ['tests', 'failures', 'errors', 'skipped']}
for file in (root / 'desktop/build/test-results/test').glob('TEST-*.xml'):
    suite = ET.parse(file).getroot()
    for key in counts:
        counts[key] += int(suite.get(key, 0))
summary = {'exit': code, **counts, 'scope': 'macOS source/JVM only; compile adapters are not Windows release evidence'}
(out / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary), flush=True)
if code:
    print('\n'.join((out / 'gradle.log').read_text().splitlines()[-30:]))
sys.exit(code if code else (0 if counts['tests'] > 0 else 1))

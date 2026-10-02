from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent
main = root.parents[2]
candidate = main.parent / 'BiliPai-v023'
toolchain = main.parent / 'toolchain'
gradle_home = toolchain / 'gradle-home'
gradle = gradle_home / 'wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle.bat'
log = root / 'integrated-targeted-test.log'
result = root / 'integrated-targeted-test.json'
assert not log.exists() and not result.exists()
command = [str(gradle), '-p', 'desktop', 'test', '--tests',
           'com.bilipai.desktop.settings.DesktopNavigationInteractionSettingsTest', '--console=plain']
env = os.environ.copy()
env.update(JAVA_HOME=str(toolchain / 'jdk/jdk-21.0.12.1+1'),
           GRADLE_USER_HOME=str(gradle_home), PYTHON_EXECUTABLE=sys.executable)
with log.open('wb') as output:
    process = subprocess.Popen(command, cwd=candidate, env=env,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    for line in iter(process.stdout.readline, b''):
        output.write(line); output.flush()
        sys.stdout.buffer.write(line); sys.stdout.buffer.flush()
    code = process.wait()
report = candidate / 'desktop/build/test-results/test/TEST-com.bilipai.desktop.settings.DesktopNavigationInteractionSettingsTest.xml'
summary = {'command': command, 'exitCode': code, 'fullProductCompile': True,
           'testClassFilter': command[5], 'actualProductOverrides': 0,
           'logSha256Bytes': hashlib.sha256(log.read_bytes()).hexdigest()}
if code == 0:
    raw = report.read_bytes()
    xml = ET.fromstring(raw)
    summary['junit'] = {key: xml.attrib[key] for key in ('tests', 'failures', 'errors', 'skipped')}
    assert summary['junit'] == {'tests': '6', 'failures': '0', 'errors': '0', 'skipped': '0'}
    (root / 'targeted-test.xml').write_bytes(raw)
    summary['reportSha256Bytes'] = hashlib.sha256(raw).hexdigest()
    manifest = json.loads((main / 'desktop/.local/settings-navigation-interaction-parity/frozen-handoff.json').read_text())
    summary['generatedByteChecks'] = []
    for item in manifest['targets']:
        if item['kind'] != 'generated-template': continue
        path = Path('\\\\?\\' + str(candidate / item['target']))
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        assert digest == item['sha256Bytes'], item['target']
        summary['generatedByteChecks'].append({'path': item['target'], 'sha256Bytes': digest})
result.write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
print(json.dumps(summary, indent=2))
sys.exit(code)

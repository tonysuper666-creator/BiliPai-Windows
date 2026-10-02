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
assert (root / 'native-baseline-fix-installation.json').exists(), 'Reviewed native replacement fix must be installed before acceptance'
log = root / 'actual-build.log'
assert not log.exists()
command = [str(gradle), '-p', 'desktop', 'classes', 'test',
           '--tests', 'com.bilipai.desktop.settings.DesktopFullNavigationSettingsTest',
           '--tests', 'com.bilipai.desktop.settings.DesktopNavigationInteractionSettingsTest', '--console=plain']
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
registry = json.loads((candidate / 'desktop/upstream-sources.json').read_bytes())
assert len(registry['sources']) == 1180
summary = {'command': command, 'exitCode': code, 'actualProductOverrides': 0,
           'sourceCount': len(registry['sources']), 'testCompileOnly': False,
           'testExecutionClaimed': False, 'rootRuntimeAccepted': False,
           'networkAccepted': False, 'nativeReplacementFixtureIsSeparate': True,
           'logSha256Bytes': hashlib.sha256(log.read_bytes()).hexdigest()}
if code == 0:
    receipt_raw = (candidate / 'desktop/build/generated/native-diagnostic-share/producer-receipt.json').read_bytes()
    receipt = json.loads(receipt_raw)
    assert receipt['passed'] and receipt['resolvedGraphMatchesReviewedBuild']
    assert receipt['stagedDllSha256Bytes'] == '22b3636176561782b055345f3c663b5674247bd9cda37f5d94945886f60dd7d3'
    (root / 'actual-native-producer-receipt.json').write_bytes(receipt_raw)
    summary['freshNativeProducerPassed'] = True
    suites = []
    for name, expected_count in [('DesktopFullNavigationSettingsTest', 14), ('DesktopNavigationInteractionSettingsTest', 6)]:
        xml = candidate / ('desktop/build/test-results/test/TEST-com.bilipai.desktop.settings.' + name + '.xml')
        raw = xml.read_bytes()
        suite = ET.fromstring(raw)
        assert suite.attrib['tests'] == str(expected_count)
        assert all(suite.attrib[field] == '0' for field in ['failures', 'errors', 'skipped'])
        (root / (name + '.xml')).write_bytes(raw)
        suites.append({'name': name, 'tests': expected_count,
                       'failures': 0, 'errors': 0, 'skipped': 0,
                       'xmlSha256Bytes': hashlib.sha256(raw).hexdigest()})
    summary.update(testExecutionClaimed=True, integratedJUnit=suites, integratedJUnitTotal=20)
(root / 'actual-build.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
print(json.dumps(summary, indent=2)); sys.exit(code)

from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('lane')
parser.add_argument('--attempt', type=int, default=1)
parser.add_argument('--test')
args = parser.parse_args()
main = Path(__file__).resolve().parents[3]
root = (main / args.lane).resolve()
assert root.is_relative_to((main / 'desktop/.local').resolve())
candidate = main.parent / 'BiliPai-v023'
toolchain = main.parent / 'toolchain'
gradle_home = toolchain / 'gradle-home'
gradle = gradle_home / 'wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle.bat'
installation = json.loads((root / 'installation.json').read_bytes())
for row in installation['sourceTargets']:
    raw = Path('\\\\?\\' + str(candidate / row['path'])).read_bytes()
    assert hashlib.sha256(raw).hexdigest() == row['afterSha256Bytes']
log = root / f'actual-build{args.attempt:02}.log'
assert not log.exists()
command = [str(gradle), '-p', 'desktop', 'classes']
if args.test:
    assert args.test.startswith('com.') and '/' not in args.test
    command += ['test', '--tests', args.test]
else:
    command += ['compileTestKotlin']
command += ['--console=plain']
env = os.environ.copy()
env.update(JAVA_HOME=str(toolchain / 'jdk/jdk-21.0.12.1+1'),
    GRADLE_USER_HOME=str(gradle_home), PYTHON_EXECUTABLE=sys.executable)
with log.open('wb') as output:
    process = subprocess.Popen(command, cwd=candidate, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    for line in iter(process.stdout.readline, b''):
        output.write(line)
        output.flush()
        sys.stdout.buffer.write(line)
        sys.stdout.buffer.flush()
    code = process.wait()
registry = json.loads((candidate / 'desktop/upstream-sources.json').read_bytes())
summary = dict(command=command, exitCode=code, actualProductOverrides=0,
    sourceCount=len(registry['sources']), resourceCount=len(registry['resources']),
    mainClassesCompiled=code == 0, testClassesCompiled=code == 0, testsExecuted=False,
    rootRuntimeAccepted=False, networkAccepted=False, desktopPackageUpdated=False,
    allFeaturesComplete=False, logSha256Bytes=hashlib.sha256(log.read_bytes()).hexdigest())
assert summary['sourceCount'] == installation['sourceCount']
assert summary['resourceCount'] == installation['resourceCount']
if code == 0:
    if args.test:
        xml_path = candidate / f'desktop/build/test-results/test/TEST-{args.test}.xml'
        xml = xml_path.read_bytes()
        suite = ET.fromstring(xml)
        assert int(suite.attrib['tests']) > 0
        assert all(suite.attrib[field] == '0' for field in ('failures', 'errors', 'skipped'))
        (root / 'integrated-test.xml').write_bytes(xml)
        summary.update(testsExecuted=True, junitTests=int(suite.attrib['tests']),
            junitFailures=0, junitErrors=0, junitSkipped=0,
            junitXmlSha256Bytes=hashlib.sha256(xml).hexdigest())
    receipt = (candidate / 'desktop/build/generated/native-diagnostic-share/producer-receipt.json').read_bytes()
    data = json.loads(receipt)
    assert data['passed'] and data['resolvedGraphMatchesReviewedBuild'] and not data['cachedOutputTrusted']
    assert data['stagedDllSha256Bytes'] == '22b3636176561782b055345f3c663b5674247bd9cda37f5d94945886f60dd7d3'
    graph = Path(data['actualBuildGraph']).read_bytes()
    assert hashlib.sha256(graph).hexdigest() == data['actualBuildGraphSha256Bytes']
    (root / 'actual-native-producer-receipt.json').write_bytes(receipt)
    (root / 'actual-native-producer-input-graph.json').write_bytes(graph)
    summary['freshNativeShareProducerPassed'] = True
(root / f'actual-build{args.attempt:02}.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
print(json.dumps(summary, indent=2))
sys.exit(code)

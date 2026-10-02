from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys

root = Path(__file__).resolve().parent
main = root.parents[2]
candidate = main.parent / 'BiliPai-v023'
toolchain = main.parent / 'toolchain'
gradle_home = toolchain / 'gradle-home'
gradle = gradle_home / 'wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle.bat'
assert (root / 'installation.json').exists()
log = root / 'actual-build01.log'
assert not log.exists()
command = [str(gradle), '-p', 'desktop', 'classes', 'compileTestKotlin', '--console=plain']
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
assert summary['sourceCount'] == 1198 and summary['resourceCount'] == 244
if code == 0:
    receipt = (candidate / 'desktop/build/generated/native-diagnostic-share/producer-receipt.json').read_bytes()
    data = json.loads(receipt)
    assert data['passed'] and data['resolvedGraphMatchesReviewedBuild'] and not data['cachedOutputTrusted']
    assert data['stagedDllSha256Bytes'] == '22b3636176561782b055345f3c663b5674247bd9cda37f5d94945886f60dd7d3'
    graph = Path(data['actualBuildGraph']).read_bytes()
    assert hashlib.sha256(graph).hexdigest() == data['actualBuildGraphSha256Bytes']
    (root / 'actual-native-producer-receipt.json').write_bytes(receipt)
    (root / 'actual-native-producer-input-graph.json').write_bytes(graph)
    summary['freshNativeShareProducerPassed'] = True
(root / 'actual-build01.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
print(json.dumps(summary, indent=2))
sys.exit(code)

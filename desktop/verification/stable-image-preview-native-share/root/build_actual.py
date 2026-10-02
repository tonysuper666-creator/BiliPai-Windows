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
log = root / 'actual-build.log'
result = root / 'actual-build.json'
assert not log.exists() and not result.exists()
command = [str(gradle), '-p', 'desktop', 'classes', 'compileTestKotlin', '--console=plain']
env = os.environ.copy()
env.update(JAVA_HOME=str(toolchain / 'jdk/jdk-21.0.12.1+1'),
           GRADLE_USER_HOME=str(gradle_home), PYTHON_EXECUTABLE=sys.executable)
with log.open('wb') as output:
    process = subprocess.Popen(command, cwd=candidate, env=env,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    for line in iter(process.stdout.readline, b''):
        output.write(line); output.flush(); sys.stdout.buffer.write(line); sys.stdout.buffer.flush()
    code = process.wait()
summary = {'command': command, 'exitCode': code, 'actualProductOverrides': 0,
           'sourceCount': 1174, 'testCompileOnly': True, 'testExecutionClaimed': False,
           'freshNativeProducerExecutedByClasses': False,
           'logSha256Bytes': hashlib.sha256(log.read_bytes()).hexdigest()}
if code == 0:
    generated = candidate / 'desktop/build/generated/native-diagnostic-share'
    receipt = (generated / 'producer-receipt.json').read_bytes()
    (root / 'actual-native-producer-receipt.json').write_bytes(receipt)
    asset = candidate / 'desktop/resources/common/native/windows-x64/bilipai-diagnostic-share.dll'
    pin = hashlib.sha256(asset.read_bytes()).hexdigest()
    assert pin == '22b3636176561782b055345f3c663b5674247bd9cda37f5d94945886f60dd7d3'
    source = generated / 'kotlin/com/bilipai/desktop/diagnostics/DesktopNativeDiagnosticShareAssetHash.kt'
    raw = Path('\\\\?\\'+str(source)).read_bytes()
    assert pin.encode() in raw
    (root / 'actual-generated-native-hash.kt.txt').write_bytes(raw)
    summary.update(freshNativeProducerExecutedByClasses=True, nativeAssetSha256Bytes=pin,
                   nativeProducerReceiptSha256Bytes=hashlib.sha256(receipt).hexdigest(),
                   generatedNativeHashSha256Bytes=hashlib.sha256(raw).hexdigest())
result.write_text(json.dumps(summary, indent=2)+'\n', encoding='utf-8')
print(json.dumps(summary, indent=2)); sys.exit(code)

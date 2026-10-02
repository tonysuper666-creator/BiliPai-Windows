from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys

here = Path(__file__).resolve().parent
main = here.parents[2]
candidate = main.parent / 'BiliPai-v023'
toolchain = main.parent / 'toolchain'
gradle_home = toolchain / 'gradle-home'
gradle = gradle_home / 'wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle.bat'
log = candidate / 'desktop/.local/stable-build-repair/classes-84.log'
export = here / 'stable-classpath-84.json'
assert not log.exists() and not export.exists()
assert not (main / 'desktop/.local/stable-product-snapshot-84/manifest.json').exists()
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=candidate, text=True).strip()
assert head == 'a5ba7265ffcc0157cd9cdab768e0a211fa9e4478'
assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=candidate)
command = [str(gradle), '-p', 'desktop', '-I', str(here / 'export-stable-classpath.init.gradle'),
           '-DstableClasspathOutput=' + str(export), 'classes', 'exportStableRuntimeClasspath', '--console=plain']
env = os.environ.copy()
env.update(JAVA_HOME=str(toolchain / 'jdk/jdk-21.0.12.1+1'),
           GRADLE_USER_HOME=str(gradle_home), PYTHON_EXECUTABLE=sys.executable)
log.parent.mkdir(parents=True, exist_ok=True)
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
summary = dict(command=command, exitCode=code, candidateHead=head,
               actualProductOverrides=0, sourceCount=len(registry['sources']),
               resourceCount=len(registry['resources']), testsExecuted=False,
               rootRuntimeAccepted=False, desktopExeReplaced=False,
               logSha256Bytes=hashlib.sha256(log.read_bytes()).hexdigest())
if code == 0:
    assert export.exists()
    summary['classpathExportSha256Bytes'] = hashlib.sha256(export.read_bytes()).hexdigest()
(here / 'classes-84-summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
print(json.dumps(summary, indent=2))
sys.exit(code)

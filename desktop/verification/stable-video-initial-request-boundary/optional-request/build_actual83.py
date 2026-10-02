import json
import os
import subprocess
import sys
from pathlib import Path

packet = Path(__file__).resolve().parent
main = packet.parents[2]
workspace = main.parent
candidate = workspace / 'BiliPai-v023'
toolchain = workspace / 'toolchain'
java = toolchain / 'jdk/jdk-21.0.12.1+1'
gradle_home = toolchain / 'gradle-home'
gradle = gradle_home / 'wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle.bat'
audit = main / 'desktop/.local/upstream-v023-audit'
log = candidate / 'desktop/.local/stable-build-repair/classes-83.log'
classpath = audit / 'stable-classpath-83.json'
result = packet / 'build-83.json'
assert all(path.exists() for path in (java, gradle, audit))
assert not any(path.exists() for path in (log, classpath, result))
command = [str(gradle), '-p', 'desktop', 'classes', 'exportStableRuntimeClasspath',
           '--console=plain', '-I', str(audit / 'export-stable-classpath.init.gradle'),
           '-DstableClasspathOutput=' + str(classpath)]
environment = os.environ.copy()
environment.update(JAVA_HOME=str(java), GRADLE_USER_HOME=str(gradle_home),
                   PYTHON_EXECUTABLE=sys.executable)
log.parent.mkdir(parents=True, exist_ok=True)
with log.open('wb') as output:
    process = subprocess.Popen(command, cwd=candidate, env=environment,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    for line in iter(process.stdout.readline, b''):
        output.write(line)
        output.flush()
        sys.stdout.buffer.write(line)
        sys.stdout.buffer.flush()
    code = process.wait()
result.write_text(json.dumps({'command': command, 'exitCode': code,
    'actualProductOverrides': 0, 'log': str(log), 'classpath': str(classpath)}, indent=2) + '\n', encoding='utf-8')
sys.exit(code)

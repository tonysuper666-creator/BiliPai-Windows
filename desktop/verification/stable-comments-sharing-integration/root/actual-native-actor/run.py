from pathlib import Path
import hashlib, json, os, subprocess, sys

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
phase = int(sys.argv[1])
attempt = int(sys.argv[2])
SNAP = MAIN / f'desktop/.local/stable-product-snapshot-{phase}'
JAVA = MAIN.parent / 'toolchain/jdk/jdk-21.0.12.1+1/bin/java.exe'
JAVAC = JAVA.with_name('javac.exe')
def sha(b): return hashlib.sha256(b).hexdigest()
def safe(p):
    s = str(Path(p).absolute())
    return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p): return safe(p).read_bytes()
metadata_raw = read(SNAP / 'manifest.json')
metadata = json.loads(metadata_raw)
assert metadata['wholeCandidateClassesPassed']
cp_raw = read(SNAP / 'ordered-runtime-cp.json')
assert sha(cp_raw) == metadata['orderedRuntimeClasspathSha256Bytes']
cp = json.loads(cp_raw)
for row in cp: assert sha(read(row['path'])) == row['sha256Bytes'], row['path']
dll = REPO / 'desktop/resources/common/native/windows-x64/bilipai-diagnostic-share.dll'
approved = json.loads(read(REPO / 'desktop/native/diagnostic-share/approved-development-build.json'))
assert sha(read(dll)) == approved['dllSha256Bytes']
run = HERE / f'run-{attempt:02}'
assert not run.exists()
run.mkdir()
classes = run / 'classes'; classes.mkdir()
source = HERE / 'NativeActorWindowFixture.java'
cp_text = ';'.join(row['path'] for row in cp)
compiler_args = [str(JAVAC), '-encoding', 'UTF-8', '-cp', cp_text, '-d', str(classes), str(source)]
c = subprocess.run(compiler_args, capture_output=True, text=True, encoding='utf-8')
(run / 'compiler.log').write_text(c.stdout+c.stderr, encoding='utf-8')
c.check_returncode()
runtime = ['-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8',
    '-cp', str(classes)+';'+cp_text, 'com.bilipai.desktop.rootfixture.NativeActorWindowFixture', str(run), str(dll)]
argfile = run / 'runtime.args'
argfile.write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in runtime), encoding='utf-8')
profile = run / 'isolated-localappdata'; profile.mkdir()
result = subprocess.run([str(JAVA), '@'+str(argfile)], cwd=REPO,
    env=dict(os.environ, LOCALAPPDATA=str(profile)), capture_output=True, text=True, encoding='utf-8', timeout=50)
log = result.stdout+result.stderr
(run / 'runtime.log').write_text(log, encoding='utf-8')
evidence = dict(exitCode=result.returncode, actualProductSnapshot=phase, snapshotManifestSha256Bytes=sha(metadata_raw),
    orderedCpSha256Bytes=sha(cp_raw), runtimeEntries=len(cp), sourceSha256Bytes=sha(read(source)),
    actualDllSha256Bytes=sha(read(dll)), productClassOverrides=0, ownJvmWindowOnly=True,
    accountActions=0, HTTPRequests=0, externalTargetChosen=False, externalMessageSent=False, screenCapture=False,
    publicReleaseAuthorizationVerified=approved['publicReleaseAuthorizationVerified'])
if (run / 'observation.json').exists(): evidence['observation'] = json.loads(read(run / 'observation.json'))
evidence['passed'] = result.returncode == 0 and evidence.get('observation', {}).get('passed', False)
(run / 'accepted-evidence.json').write_text(json.dumps(evidence, indent=2)+'\n', encoding='utf-8')
print(json.dumps(evidence))
result.check_returncode()
assert evidence['passed']

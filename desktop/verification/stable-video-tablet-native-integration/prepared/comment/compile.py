from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, zipfile

H = Path(__file__).resolve().parent
MAIN = H.parents[2]
PREFIX = chr(92) * 2 + '?' + chr(92)

def wide(p):
    s = os.path.abspath(p)
    return Path(s if s.startswith(PREFIX) else PREFIX + s)

def sha(p):
    return hashlib.sha256(wide(p).read_bytes()).hexdigest()

snapshot_name, run = sys.argv[1:3]
snapshot = MAIN / 'desktop/.local' / snapshot_name
cp_file = snapshot / 'ordered-runtime-cp.json'
cp = json.loads(wide(cp_file).read_text(encoding='utf-8'))
for row in cp:
    assert sha(row['path']) == row['sha256Bytes'], row['path']

spec = importlib.util.spec_from_file_location('compiler', MAIN / 'desktop/.local/source9-appearance/compile-miuix.py')
compiler = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compiler)
out = H / ('compile-' + run)
wide(out).mkdir(exist_ok=False)
source = H / 'generated/com/android/purebilibili/feature/video/screen/DesktopOriginalCommentUrlNavigation.kt'
sources = [source]
if len(sys.argv) > 3:
    sources.append(H / 'fixture/PlaybackInvocationFixture.kt')
for p in sources:
    target = out / 'source-inputs' / p.relative_to(H)
    wide(target).parent.mkdir(parents=True, exist_ok=True)
    wide(target).write_bytes(wide(p).read_bytes())
args = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-module-name', 'com_bilipai_desktop_bilipai_windows',
        '-Xplugin=' + str(compiler.PLUGIN), '-Xfriend-paths=' + cp[1]['path'],
        '-cp', ';'.join(row['path'] for row in cp), '-d', str(out / 'classes')] + [str(p) for p in sources]
wide(out / 'compile.args').write_text('\n'.join('"' + arg.replace('\\', '/') + '"' for arg in args), encoding='utf-8', newline='\n')
result = subprocess.run([str(compiler.JAVA), '-Dfile.encoding=UTF-8', '-Xmx3g', '-cp', ';'.join(map(str, compiler.COMPILER)),
                         'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '@' + str(out / 'compile.args')],
                        capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=120)
wide(out / 'compile.log').write_text(result.stdout + result.stderr, encoding='utf-8', newline='\n')
classes = sorted(wide(out / 'classes').rglob('*.class')) if result.returncode == 0 else []
old = set()
for row in cp[:3]:
    with zipfile.ZipFile(wide(row['path'])) as z:
        old.update(z.namelist())
new = {p.relative_to(wide(out / 'classes')).as_posix() for p in classes}
overlap = sorted(old & new)
assert not overlap, overlap
if classes:
    with zipfile.ZipFile(wide(out / 'candidate.jar'), 'w', compression=zipfile.ZIP_DEFLATED) as z:
        for p in classes:
            z.write(p, p.relative_to(wide(out / 'classes')).as_posix())
metadata = dict(preparedOnly=True, fullVmMounted=False, snapshot=str(snapshot), manifestSha=sha(snapshot / 'manifest.json'),
                cpSha=sha(cp_file), runtimeEntries=len(cp), compileExit=result.returncode, inputSources=len(sources),
                classes=len(classes), productOverlaps=overlap, sources=[dict(path=str(p), sha256Bytes=sha(p)) for p in sources])
wide(out / 'result.json').write_text(json.dumps(metadata, indent=2) + '\n', encoding='utf-8', newline='\n')
if result.returncode == 0 and len(sources) > 1:
    runtime = subprocess.run([str(compiler.JAVA), '-Dfile.encoding=UTF-8', '-cp', ';'.join([str(out / 'candidate.jar')] + [row['path'] for row in cp]),
                              'com.bilipai.desktop.ui.PlaybackInvocationFixture', str(out / 'fixture-result.json')],
                             capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=90)
    wide(out / 'runtime.log').write_text(runtime.stdout + runtime.stderr, encoding='utf-8', newline='\n')
    metadata['runtimeExit'] = runtime.returncode
    wide(out / 'result.json').write_text(json.dumps(metadata, indent=2) + '\n', encoding='utf-8', newline='\n')
    print((runtime.stdout + runtime.stderr).encode('ascii', 'backslashreplace').decode())
    if runtime.returncode:
        sys.exit(runtime.returncode)
for row in cp:
    assert sha(row['path']) == row['sha256Bytes'], row['path']
print((result.stdout + result.stderr).encode('ascii', 'backslashreplace').decode())
print(json.dumps(metadata, ensure_ascii=True))
sys.exit(result.returncode)

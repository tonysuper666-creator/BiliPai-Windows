"""Independent candidate compiler. No Gradle, production writes or runtime acceptance."""
from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys, zipfile
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
PREFIX = chr(92)*2+'?'+chr(92)
def safe(p):
    s = str(Path(p).absolute())
    return Path(s if s.startswith(PREFIX) else PREFIX+s)
def digest(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p, s):
    p = safe(p); p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(s, encoding='utf-8', newline='\n')
CP_FILE = REPO/'desktop/.local/image-save-main-integration/main-product-snapshot-04/ordered-runtime-cp.json'
CP_PIN = '7a3141310fa355d8618c5bc2be90f36a2c13c8ed36f2d981bf4a14c2232165e2'
assert digest(CP_FILE) == CP_PIN
ROWS = json.loads(safe(CP_FILE).read_text(encoding='utf-8'))
assert len(ROWS) == 92
def check_cp():
    for row in ROWS: assert digest(row['path']) == row['sha256Bytes'], row['path']
spec = importlib.util.spec_from_file_location('vote_compiler', REPO/'desktop/.local/source9-appearance/compile-miuix.py')
compiler = importlib.util.module_from_spec(spec); spec.loader.exec_module(compiler)
def compile(name, sources, extra=()):
    check_cp()
    output = HERE/(name+'.jar')
    args = ['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path'] for r in ROWS),
            '-Xplugin='+str(compiler.PLUGIN), '-Xfriend-paths='+ROWS[1]['path'],
            '-module-name','com_bilipai_desktop_bilipai_windows', '-d',str(output)] + list(extra) + list(map(str,sources))
    file = HERE/(name+'.args')
    write(file, '\n'.join('"'+str(a).replace('\\','/')+'"' for a in args))
    result = subprocess.run([str(compiler.JAVA),'-Xmx3g','-cp',';'.join(map(str,compiler.COMPILER)),
                'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(file)],capture_output=True,text=True,encoding='utf-8',timeout=180)
    write(HERE/(name+'.log'), result.stdout+result.stderr)
    print((result.stdout+result.stderr)[-14000:]); result.check_returncode()
    check_cp()
    current = set(zipfile.ZipFile(safe(output)).namelist())
    product = set().union(*(set(zipfile.ZipFile(safe(r['path'])).namelist()) for r in ROWS[:3]))
    overlap = sorted(n for n in current & product if n.endswith('.class'))
    write(HERE/(name+'-evidence.json'),json.dumps(dict(candidateOnly=True, mainAcceptance=False,
      main04Classpath=CP_PIN, cpEntries=len(ROWS), candidateJarSha256Bytes=digest(output),
      compiler='Kotlin 2.4.0 / Compose compiler 2.4.0', friendModule='com_bilipai_desktop_bilipai_windows',
      sources=[dict(path=str(p.relative_to(HERE)),sha256Bytes=digest(p)) for p in sources],
      productClassOverrides=overlap, nativeWindow=False, HTTP=False),indent=2))
    return output
def sources():
    return sorted((HERE/'direct').rglob('*.kt')) + sorted((HERE/'generated').rglob('*.kt')) + \
      [HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopVideoVotes.kt',
       HERE/'prepared/consumer-reference/DanmakuOverlay.kt'] + \
      sorted((HERE/'compile-references').glob('*.kt'))
if __name__ == '__main__': compile('candidate-ui-01',sources())

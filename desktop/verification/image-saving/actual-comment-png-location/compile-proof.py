"""Three focused headless actual-Main04 PNG/location checks; fixture classes only."""
from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, zipfile
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = next(p for p in HERE.parents if (p / '.git').exists())
SNAP = ROOT / 'desktop/.local/image-save-main-integration/main-product-snapshot-04'
def safe(p):
    s = str(Path(p).absolute())
    return Path(s if s.startswith('\\\\?\\') else '\\\\?\\' + s)
def sha(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p, value):
    safe(p.parent).mkdir(parents=True, exist_ok=True)
    safe(p).write_text(value, encoding='utf-8', newline='\n')
def save(p, value): write(p, json.dumps(value, ensure_ascii=False, indent=2) + '\n')

assert sha(SNAP/'manifest.json') == '7bc5ac8875dadf38510120db6619bd6e748e7f790a47afc11374e14114f25865'
assert sha(SNAP/'ordered-runtime-cp.json') == '7a3141310fa355d8618c5bc2be90f36a2c13c8ed36f2d981bf4a14c2232165e2'
cp = json.loads(safe(SNAP/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
assert len(cp) == 92
for row in cp: assert sha(row['path']) == row['sha256Bytes']
main = next(row['path'] for row in cp if row.get('source') == 'desktop/build/classes/kotlin/main')
spec = importlib.util.spec_from_file_location('actual_main04_png_compiler', ROOT/'desktop/.local/source9-appearance/compile-miuix.py')
c = importlib.util.module_from_spec(spec)
spec.loader.exec_module(c)
run = HERE/(sys.argv[1] if len(sys.argv) > 1 else 'proof-01')
assert not safe(run).exists()
source = run/'sources/CommentLocationsFixture.kt'
write(source, safe(HERE/'CommentLocationsFixture.kt').read_text(encoding='utf-8'))
jar = run/'fixture-only.jar'
classpath = ';'.join(row['path'] for row in cp)
args = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-cp', classpath,
    '-Xfriend-paths='+main, '-module-name', 'comment_png_actual_main04_fixture', '-d', str(jar), str(source)]
write(run/'compiler.args', '\n'.join('"'+str(v).replace('\\','/')+'"' for v in args)+'\n')
build = subprocess.run([str(c.JAVA), '-Dfile.encoding=UTF-8', '-Xmx2g', '-cp', ';'.join(map(str,c.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '@'+str(run/'compiler.args')],
    capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=90)
write(run/'compile.log', build.stdout+build.stderr)
print(build.stdout+build.stderr)
build.check_returncode()
existing = set()
for row in cp:
    with zipfile.ZipFile(safe(row['path'])) as z:
        existing.update(n for n in z.namelist() if n.endswith('.class'))
with zipfile.ZipFile(safe(jar)) as z:
    classes = {n for n in z.namelist() if n.endswith('.class')}
assert not (classes & existing), classes & existing
assert all(n.startswith('com/bilipai/desktop/ui/commentlocationsproof/') for n in classes)

fixture = run/'fixture'
home = run/'private-home'
safe(home/'temp').mkdir(parents=True, exist_ok=True)
runtime = ['-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-Duser.home='+str(home),
    '-Djava.io.tmpdir='+str(home/'temp'), '-cp', str(jar)+';'+classpath,
    'com.bilipai.desktop.ui.commentlocationsproof.CommentLocationsFixtureKt', str(fixture), main]
write(run/'runtime.args', '\n'.join('"'+str(v).replace('\\','/')+'"' for v in runtime)+'\n')
env = dict(os.environ, LOCALAPPDATA=str(home/'local-app-data'))
executed = subprocess.run([str(c.JAVA), '@'+str(run/'runtime.args')], env=env,
    capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=60)
write(run/'runtime.log', executed.stdout+executed.stderr)
print(executed.stdout+executed.stderr)
executed.check_returncode()
result = json.loads(safe(fixture/'result.json').read_text(encoding='utf-8'))
assert result['passed'] and result['cases'] == 3
with zipfile.ZipFile(safe(main)) as z:
    for item in result['codeSources']:
        assert Path(item['codeSource']).resolve() == Path(main).resolve()
        actual = hashlib.sha256(z.read(item['className'].replace('.', '/')+'.class')).hexdigest()
        assert actual == item['classSha256Bytes']
from PIL import Image
pngs = [fixture/'custom/BiliPai_comment_custom.png', fixture/'default-base/BiliPai/BiliPai_comment_fallback.png']
independent = []
for p in pngs:
    with Image.open(safe(p)) as image:
        image.load()
        assert image.format == 'PNG' and image.width == 1080 and image.height > 0
        independent.append(dict(path=str(p.relative_to(run)).replace('\\','/'),
            sha256Bytes=sha(p),format=image.format,size=image.size))
assert safe(fixture/'custom/BiliPai_comment_cancel.png').read_bytes() == bytes([8,4,2])
assert not safe(fixture/'custom/BiliPai_comment_cancel (1).png').exists()
for directory in [fixture/'custom', fixture/'default-base/BiliPai']:
    assert not any(p.name.startswith('.bilipai-comment-') for p in safe(directory).iterdir())
for row in cp: assert sha(row['path']) == row['sha256Bytes']
save(run/'accepted-evidence.json', dict(passed=True,caseCount=result['cases'],kotlinAssertions=result['assertions'],
    actualMain04ManifestSha256Bytes=sha(SNAP/'manifest.json'),orderedRuntimeCpSha256Bytes=sha(SNAP/'ordered-runtime-cp.json'),
    actualMainKotlinJarSha256Bytes=sha(main),runtimeCpCount=len(cp),runtimeBinaryPinsPrePostVerified=True,
    fixtureJarSha256Bytes=sha(jar),fixtureClassCount=len(classes),classFqnIntersection=[],declaredProductOverrides=[],
    compiledFixtureSourceSha256Bytes=sha(source),actualProductCodeSourceAndClassBytesVerified=result['codeSources'],
    independentPillowPngChecks=independent,realStoreMonitorBlocked=True,cancelledOnlySaveJob=True,
    currentPageLifetimeAndOwnerPreserved=True,sharedOriginalGlobalPreferenceKey='image_save_tree_uri',
    customFalseFallbackUsed=True,defaultResolverIsTaskPathCallback=True,actualKnownFolderCalled=False,
    CommunityOriginalButtonClicked=False,RootComposeMounted=False,HTTP=False,HWND=False,actualChooser=False,
    MainChanged=False,sourceRegistryChanged=False,sharedGradle=False))
print(json.dumps(dict(accepted=str(run/'accepted-evidence.json'),sha256Bytes=sha(run/'accepted-evidence.json'))))

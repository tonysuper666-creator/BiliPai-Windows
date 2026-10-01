"""Test source only; actual immutable Main04 runtime, no product overrides or HWND."""
from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, zipfile
sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
SNAP = HERE.parent/'image-save-main-integration/main-product-snapshot-04'
MANIFEST_PIN = '7bc5ac8875dadf38510120db6619bd6e748e7f790a47afc11374e14114f25865'
CP_PIN = '7a3141310fa355d8618c5bc2be90f36a2c13c8ed36f2d981bf4a14c2232165e2'
def safe(p):
    value = str(Path(p).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p, text): safe(p).write_text(text, encoding='utf-8', newline='\n')
def load(p, name):
    spec = importlib.util.spec_from_file_location(name, p)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module
def verify():
    assert sha(SNAP/'manifest.json') == MANIFEST_PIN
    assert sha(SNAP/'ordered-runtime-cp.json') == CP_PIN
    for item in items: assert sha(item['path']) == item['sha256Bytes'], item['path']
manifest = json.loads(safe(SNAP/'manifest.json').read_text(encoding='utf-8'))
items = json.loads(safe(SNAP/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
assert manifest['frozen'] and len(items) == 92
assert len([i for i in items if 'source' in i]) == 3
verify()
compiler = load(HERE.parent/'source9-appearance/compile-miuix.py', 'main04_fixture_compiler')
attempts = HERE/'attempts'; attempts.mkdir(exist_ok=True)
number = len(list(attempts.glob('run-*')))+1
attempt = attempts/f'run-{number:02d}'; attempt.mkdir()
source = HERE/'MountedSettingsFixture.kt'
safe(attempt/source.name).write_bytes(safe(source).read_bytes())
cp = [i['path'] for i in items]
main = next(i['path'] for i in items if i.get('source') == 'desktop/build/classes/kotlin/main')
fixture = attempt/'fixture.jar'
args = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-cp', ';'.join(cp),
        '-Xplugin='+str(compiler.PLUGIN), '-Xfriend-paths='+main,
        '-module-name', 'actual_main04_image_settings_fixture', '-d', str(fixture), str(source)]
write(attempt/'compiler.args', '\n'.join('"'+v.replace('\\','/')+'"' for v in args))
compiled = subprocess.run([str(compiler.JAVA), '-Xmx1g', '-Dfile.encoding=UTF-8', '-cp', ';'.join(map(str,compiler.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '@'+str(attempt/'compiler.args')], capture_output=True, text=True,
    encoding='utf-8', errors='replace', timeout=100)
write(attempt/'compile.log', compiled.stdout+compiled.stderr)
print((compiled.stdout+compiled.stderr)[-13000:]); compiled.check_returncode()
with zipfile.ZipFile(safe(fixture)) as jar: fixture_classes = {n for n in jar.namelist() if n.endswith('.class')}
for item in manifest['artifacts']:
    with zipfile.ZipFile(safe(item['path'])) as jar: assert not fixture_classes.intersection(jar.namelist())
private = attempt/'private-environment'; private.mkdir()
proof = attempt/'proof'; proof.mkdir()
native = attempt/'native'; native.mkdir()
skiko = next(i for i in items if 'skiko-awt-runtime-windows-x64-' in Path(i['path']).name)
native_items = []
with zipfile.ZipFile(safe(skiko['path'])) as jar:
    for name in ['skiko-windows-x64.dll', 'icudtl.dat']:
        destination = native/name
        safe(destination).write_bytes(jar.read(name))
        assert len(str(destination)) < 260
        native_items.append(dict(path=str(destination), sha256Bytes=sha(destination), sourceJar=skiko, entry=name))
env = os.environ.copy()
for name in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']: env[name] = str(private)
runtime = ['-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-Djava.net.useSystemProxies=false',
    '-Duser.home='+str(private), '-Djava.io.tmpdir='+str(private), '-Djna.tmpdir='+str(private),
    '-Dskiko.library.path='+str(native), '-XX:ErrorFile='+str(attempt/'jvm-fatal.log'),
    '-Dproof.main.jar='+main, '-cp', str(fixture)+';'+';'.join(cp),
    'com.bilipai.desktop.settingsfixture.MountedSettingsFixtureKt', str(proof), str(private)]
write(attempt/'runtime.args', '\n'.join('"'+v.replace('\\','/')+'"' for v in runtime))
ran = subprocess.run([str(compiler.JAVA), '@'+str(attempt/'runtime.args')], env=env, capture_output=True,
    text=True, encoding='utf-8', errors='replace', timeout=75)
write(attempt/'runtime.log', ran.stdout+ran.stderr)
print((ran.stdout+ran.stderr)[-15000:])
verify()
evidence = dict(snapshotManifestSha256=MANIFEST_PIN, orderedRuntimeCpSha256=CP_PIN,
    immutableRuntime=items, testSourceSha256=sha(source), compiledSourceSha256=sha(attempt/source.name),
    fixtureJarSha256=sha(fixture), fixtureClassCount=len(fixture_classes), productClassOverlap=0,
    compiler=[dict(path=str(p),sha256Bytes=sha(p)) for p in compiler.COMPILER],
    composeCompiler=dict(path=str(compiler.PLUGIN),sha256Bytes=sha(compiler.PLUGIN)),
    isolatedSkikoAssets=native_items,
    fixtureSourceOnly=True, actualMain=True, productOverrides=0, privateEnvironment=str(private),
    sharedGradleInvoked=False, mainEdited=False, nativeWindow=False, realSystemChooser=False,
    fullShellMounted=False, actualMainSettingsTreeMounted=True, exitCode=ran.returncode)
write(attempt/'compile-runtime-evidence.json', json.dumps(evidence, indent=2))
ran.check_returncode()
write(HERE/'latest-success.json', json.dumps(dict(attempt=str(attempt), evidenceSha256=sha(attempt/'compile-runtime-evidence.json'),
    resultSha256=sha(proof/'result.json')), indent=2))
print('SUCCESS:', attempt)

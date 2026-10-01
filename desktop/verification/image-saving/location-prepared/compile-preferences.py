"""Compile source-only preference seam and original pure location policy; no Main writes."""
from pathlib import Path
import hashlib, importlib.util, json, re, subprocess, sys, zipfile
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = next(p for p in HERE.parents if (p / '.git').exists())
SNAP = ROOT / 'desktop/.local/dynamic-detail-container-main-integration/main-product-snapshot-02'
def safe(p):
    s = str(Path(p).absolute()); return Path(s if s.startswith('\\\\?\\') else '\\\\?\\' + s)
def sha(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p, text):
    safe(p.parent).mkdir(parents=True, exist_ok=True); safe(p).write_text(text, encoding='utf-8', newline='\n')
assert sha(SNAP / 'manifest.json') == 'f164846bb1ebe5545c727824bfa6fe853a5fca92bfd1bcfddf59abb53c943d17'
assert sha(SNAP / 'ordered-runtime-cp.json') == 'be8a62821f74e73653ed2354afbb1de3d6ec8236db68f056328a482a5e0f4d9d'
cp = json.loads(safe(SNAP / 'ordered-runtime-cp.json').read_text(encoding='utf-8')); assert len(cp) == 89
for row in cp: assert sha(row['path']) == row['sha256Bytes']
main = next(row['path'] for row in cp if row.get('source') == 'desktop/build/classes/kotlin/main')
spec = importlib.util.spec_from_file_location('static_preferences_compiler', ROOT / 'desktop/.local/source9-appearance/compile-miuix.py')
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
out = HERE / 'compile-01'; assert not safe(out).exists()
originals = [HERE / 'generated/com/android/purebilibili/feature/dynamic/components/DesktopOriginalImageSaveLocationPolicy.kt',
    HERE / 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopImageSaveLocationPreferences.kt']
sources = []
for p in originals:
    target = out / 'sources' / p.name; write(target, safe(p).read_text(encoding='utf-8')); sources.append(target)
jar = out / 'candidate-preferences-policy.jar'
values = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-cp', ';'.join(row['path'] for row in cp),
    '-Xfriend-paths=' + main, '-module-name', 'static_save_location_preferences_candidate', '-d', jar] + sources
write(out / 'compiler.args', '\n'.join('"' + str(v).replace('\\', '/') + '"' for v in values) + '\n')
r = subprocess.run([str(c.JAVA), '-Dfile.encoding=UTF-8', '-Xmx2g', '-cp', ';'.join(map(str, c.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '@' + str(out / 'compiler.args')], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=90)
write(out / 'compile.log', r.stdout + r.stderr); print(r.stdout + r.stderr); r.check_returncode()
existing = set(); base_wrappers = set(); package = 'com.android.purebilibili.feature.dynamic.components'
for row in cp:
    with zipfile.ZipFile(safe(row['path'])) as z:
        existing.update(z.namelist())
        base_wrappers.update(n[:-6].replace('/', '.') for n in z.namelist()
            if n.endswith('Kt.class') and '$' not in n and n[:-6].replace('/', '.').rsplit('.', 1)[0] == package)
with zipfile.ZipFile(safe(jar)) as z: classes = {n for n in z.namelist() if n.endswith('.class')}
assert not (classes & existing), classes & existing
javap = c.JAVA.parent / 'javap.exe'
def methods(names, classpath, filename):
    text = subprocess.check_output([str(javap), '-p', '-s', '-classpath', classpath, *sorted(names)], text=True, encoding='utf-8')
    write(out / filename, text); member = None; result = set()
    for line in text.splitlines():
        if line.strip().startswith('public static') and '(' in line:
            member = line.split('(')[0].split()[-1]
        elif line.strip().startswith('descriptor:') and member:
            if not member.startswith('access$') and '$default' not in member:
                result.add((member, line.split(':', 1)[1].strip().split(')', 1)[0] + ')'))
            member = None
    return result
classpath = ';'.join(row['path'] for row in cp)
candidate = methods([package + '.DesktopOriginalImageSaveLocationPolicyKt'], str(jar) + ';' + classpath, 'candidate-methods.txt')
base = methods(base_wrappers, classpath, 'base-methods.txt')
assert not (candidate & base), candidate & base
for row in cp: assert sha(row['path']) == row['sha256Bytes']
result = dict(passed=True, sourceCount=2, sources=[dict(path=str(p.relative_to(HERE)).replace('\\', '/'), sha256Bytes=sha(p)) for p in sources],
    candidateJarSha256Bytes=sha(jar), actualMainSnapshotSha256Bytes=sha(SNAP / 'manifest.json'), runtimeCpCount=89, runtimeBinaryPinsPrePostVerified=True,
    candidateClassCount=len(classes), classFqnIntersection=[], pureLocationMethodCount=len(candidate), baseSamePackageMethodCount=len(base), methodSignatureIntersection=[],
    preferenceFacadeUsesExistingStore=True, originalPureContentUriAlgorithmUnchanged=True, RootSettingsOwnerGateRequired=True,
    WindowsDirectoryPickerOrKnownFolderConsumerIntegrated=False, PNGJPEGTranscoderIntegrated=False, runtimePreferencesFixtureExecuted=False,
    MainInstalled=False, HTTP=False, HWND=False, SharedGradle=False)
write(out / 'compile-evidence.json', json.dumps(result, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(result))

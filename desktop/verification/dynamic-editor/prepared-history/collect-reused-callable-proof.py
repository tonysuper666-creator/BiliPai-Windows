from pathlib import Path
import hashlib
import json
import subprocess
import zipfile

HERE = Path(__file__).resolve().parent
REPO = next(p for p in HERE.parents if (p / '.git').exists())

def safe(path):
    value = str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)

def write(path, value):
    safe(path).write_text(value, encoding='utf-8', newline='\n')

evidence = json.loads(safe(HERE / 'reused-callable-evidence.json').read_text(encoding='utf-8'))
jar = REPO / 'desktop/.local/dynamic-full-card-main-product-snapshot-lifecycle-final/main-kotlin.jar'
assert hashlib.sha256(safe(jar).read_bytes()).hexdigest() == evidence['actualMainJarSha256Bytes']
with zipfile.ZipFile(safe(jar)) as archive:
    assert hashlib.sha256(archive.read(evidence['classEntry'])).hexdigest() == evidence['classSha256Bytes']
javap = REPO.parent / 'toolchain/jdk/jdk-21.0.12.1+1/bin/javap.exe'
result = subprocess.run([str(javap), '-classpath', str(jar), '-p',
        'com.android.purebilibili.feature.dynamic.components.DesktopOriginalDynamicNativeSegmentedControlKt'],
        capture_output=True, text=True, encoding='utf-8', check=True)
write(HERE / 'reused-callable-javap.log', result.stdout + result.stderr)
declaration = next(line.strip() for line in result.stdout.splitlines()
                   if line.strip().startswith('public static final void DynamicAdaptiveSegmentedControl'))
evidence.pop('signature', None)
evidence['actualJvmPublicSignature'] = declaration
classes = HERE / 'classes-attempt13'
consumers = []
for path in safe(classes).rglob('*.class'):
    if b'DesktopOriginalDynamicNativeSegmentedControlKt' in path.read_bytes():
        consumers.append(Path(str(path).removeprefix('\\\\?\\')).relative_to(classes).as_posix())
assert any('DesktopOriginalDynamicCreateVoteDialog' in item for item in consumers), consumers
assert not safe(classes / evidence['classEntry']).exists()
assert not safe(classes / 'com/android/purebilibili/feature/dynamic/components/DesktopOriginalDynamicAdaptiveSegmentedControlKt.class').exists()
evidence['actualCompiledConsumers'] = sorted(consumers)
evidence['compiledSingleOwnerChecked'] = True
write(HERE / 'reused-callable-evidence.json', json.dumps(evidence, indent=2) + '\n')
print(json.dumps(dict(reusedActualCallable=True, consumers=len(consumers), duplicateClassProduced=False)))

from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, tempfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
SNAPSHOT = REPO / 'desktop/.local/network-proxy-product-snapshot/manifest.json'
def digest(path):
    return hashlib.sha256(Path('\\\\?\\' + str(Path(path).absolute())).read_bytes()).hexdigest()
assert digest(SNAPSHOT) == 'c2c892064267e6a1e8c5425a3c4095a2070faacfe5debb1c07f43fae9957016d'
items = [dict(path=item['path'], sha256Bytes=item['sha256Bytes']) for item in json.loads(SNAPSHOT.read_text())['artifacts']]
prior = json.loads((REPO / 'desktop/.local/settings-network-proxy-parity/dependency-identities.json').read_text())
assert isinstance(prior, list)
for item in prior:
    if item['path'] not in [entry['path'] for entry in items] and 'main-kotlin.jar' not in item['path'] and 'main-java.jar' not in item['path'] and 'main-resources.jar' not in item['path']:
        items.append(item)
for item in items:
    assert digest(item['path']) == item.get('sha256Bytes', item.get('sha256')), item['path']
spec = importlib.util.spec_from_file_location('discovery_baseline_compiler', REPO / 'desktop/.local/source9-appearance/compile-miuix.py')
compiler = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compiler)
source = HERE / 'BaselineDiscoveryRestoreRepro.kt'
classes = HERE / 'baseline-classes'
classes.mkdir(exist_ok=True)
classpath = ';'.join(item['path'] for item in items)
arguments = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-cp', classpath,
             '-Xfriend-paths=' + ','.join(item['path'] for item in items[:3]),
             '-module-name', 'actual_discovery_restore_baseline', '-d', str(classes), str(source)]
argument_file = HERE / 'compiler.args'
argument_file.write_text('\n'.join('"' + argument.replace('\\', '/') + '"' for argument in arguments), encoding='utf-8')
result = subprocess.run([str(compiler.JAVA), '-Dfile.encoding=UTF-8', '-cp', ';'.join(map(str, compiler.COMPILER)),
                         'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '@' + str(argument_file)],
                        capture_output=True, text=True, encoding='utf-8', timeout=60)
(HERE / 'compile-baseline.log').write_text(result.stdout + result.stderr, encoding='utf-8')
result.check_returncode()
sandbox = Path(tempfile.mkdtemp(prefix='drb-', dir=HERE.parent))
environment = os.environ.copy()
for name in ['APPDATA', 'LOCALAPPDATA', 'USERPROFILE', 'HOME', 'TEMP', 'TMP']:
    environment[name] = str(sandbox)
runtime_arguments = ['-Dfile.encoding=UTF-8', '-Djava.awt.headless=true',
                     '-Duser.home=' + str(sandbox), '-Djava.io.tmpdir=' + str(sandbox),
                     '-cp', str(classes) + ';' + classpath,
                     'com.bilipai.desktop.data.BaselineDiscoveryRestoreReproKt', str(sandbox)]
runtime_file = HERE / 'runtime.args'
runtime_file.write_text('\n'.join('"' + argument.replace('\\', '/') + '"' for argument in runtime_arguments), encoding='utf-8')
result = subprocess.run([str(compiler.JAVA), '@' + str(runtime_file)],
                        env=environment, capture_output=True, text=True, encoding='utf-8', timeout=30)
(HERE / 'baseline-runtime.log').write_text(result.stdout + result.stderr, encoding='utf-8')
print(result.stdout + result.stderr)
result.check_returncode()
record = dict(passed=True, baselineBugReproduced=True, mainModified=False, currentProductOverride=False,
              snapshotManifestSha256=digest(SNAPSHOT), fixtureSourceSha256=digest(source),
              dependencies=items, childEnvironment=str(sandbox), actualResult=json.loads((sandbox / 'result.json').read_text()))
(HERE / 'baseline-evidence.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8', newline='\n')

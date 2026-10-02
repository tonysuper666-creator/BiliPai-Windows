"""Fixture-only compilation/runtime. Requires Root-delivered immutable snapshot pins.
No production source, prospective JAR, original packet class, or Gradle call is accepted.
"""
from pathlib import Path
import argparse, hashlib, importlib.util, json, os, subprocess, sys, zipfile
sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding='utf8')
P = Path(__file__).resolve().parent
M = P.parents[2]

def wide(path): return Path('\\\\?\\' + os.path.abspath(path))
def sha(path): return hashlib.sha256(wide(path).read_bytes()).hexdigest()
def load(path): return json.loads(wide(path).read_text(encoding='utf8'))
def write(path, value):
    wide(path).write_text(value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf8', newline='\n')

parser = argparse.ArgumentParser()
parser.add_argument('mode', choices=['compile', 'run'])
parser.add_argument('--snapshot', required=True, type=int)
parser.add_argument('--manifest-sha', required=True)
parser.add_argument('--cp-sha', required=True)
parser.add_argument('--entries', required=True, type=int)
parser.add_argument('--resources-dir', required=True, type=Path)
parser.add_argument('--mpv-sha', required=True)
parser.add_argument('--diagnostic-sha', required=True)
parser.add_argument('--number', default='01')
parser.add_argument('--root-issued-snapshot', required=True, action='store_true', help='Use only after Root explicitly provides this snapshot pins and authorizes this cohort.')
args = parser.parse_args()
assert args.snapshot >= 81 and args.entries == 101 and args.root_issued_snapshot
snapshot = M / f'desktop/.local/stable-product-snapshot-{args.snapshot}'
assert load(snapshot / 'manifest.json')['phase'] == 'actual-whole-stable-classes' + str(args.snapshot)
cp = load(snapshot / 'ordered-runtime-cp.json')
assert len(cp) == args.entries
resources = args.resources_dir.resolve()
native = resources / 'native/windows-x64/libmpv-2.dll'
diagnostic = resources / 'native/windows-x64/bilipai-diagnostic-share.dll'
source = P / 'RootMediaFixture.kt'

def check_pins():
    assert sha(snapshot / 'manifest.json') == args.manifest_sha
    assert sha(snapshot / 'ordered-runtime-cp.json') == args.cp_sha
    for row in cp: assert sha(row['path']) == row['sha256Bytes'], row['path']
    assert sha(native) == args.mpv_sha
    assert sha(diagnostic) == args.diagnostic_sha

check_pins()
out = P / ('runs/actual' + str(args.snapshot) + '-' + args.number)
jar = out / 'root-media-fixture.jar'
spec = importlib.util.spec_from_file_location('compiler', M / 'desktop/.local/source9-appearance/compile-miuix.py')
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
main_jar = next(row['path'] for row in cp if row.get('source') == 'desktop/build/classes/kotlin/main')
required = ['com/bilipai/desktop/ui/' + name + '.class' for name in (
    'DesktopOriginalVideoShellOwner', 'DesktopOriginalVideoRootAssembler', 'DesktopOriginalVideoOwnerAssembly',
    'DesktopOriginalVideoSectionWindowsPlatform', 'DesktopOriginalVideoRootWindowPlatformsImpl',
    'DesktopOriginalVideoRootWindowEnvironment')]
with zipfile.ZipFile(wide(main_jar)) as z:
    actual_names = set(z.namelist())
    for name in required: assert name in actual_names, name

if args.mode == 'compile':
    assert not wide(out).exists(), 'Each attempt is preserved; choose a fresh --number.'
    wide(out).mkdir(parents=True)
    wide(out / source.name).write_bytes(wide(source).read_bytes())
    pins = dict(snapshot=args.snapshot, manifestSHA=args.manifest_sha, orderedCPSHA=args.cp_sha,
        runtime=cp, sourceSHA=sha(source), nativeSHA=sha(native), diagnosticSHA=sha(diagnostic),
        productionOverrides=0, prospectiveReferences=[], compileProductSources=False)
    write(out / 'pins-before.json', pins)
    compiler_args = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-module-name', 'root_media_actual_fixture',
        '-Xplugin=' + str(c.PLUGIN), '-Xfriend-paths=' + main_jar,
        '-cp', ';'.join(row['path'] for row in cp), '-d', str(out / 'classes'), str(out / source.name)]
    write(out / 'compiler.args', '\n'.join('"' + a.replace('\\', '/') + '"' for a in compiler_args))
    result = subprocess.run([str(c.JAVA), '-Xmx3g', '-Dfile.encoding=UTF-8', '-cp', ';'.join(map(str, c.COMPILER)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '@' + str(out / 'compiler.args')], capture_output=True, timeout=180)
    wide(out / 'compile.log').write_bytes(result.stdout + result.stderr)
    check_pins()
    write(out / 'compile-result.json', dict(passed=result.returncode == 0, exitCode=result.returncode, fixtureOnly=True,
        productionOverrides=0, snapshot=args.snapshot, sourceSHA=sha(source)))
    if result.returncode:
        print((result.stdout + result.stderr).decode('utf8', errors='replace')); raise SystemExit(result.returncode)
    with zipfile.ZipFile(wide(jar), 'w', zipfile.ZIP_DEFLATED) as z:
        for path in sorted(wide(out / 'classes').rglob('*')):
            if path.is_file(): z.writestr(path.relative_to(wide(out / 'classes')).as_posix(), path.read_bytes())
    with zipfile.ZipFile(wide(jar)) as fixture:
        assert not sorted(name for name in fixture.namelist() if name.endswith('.class') and name in actual_names)
    write(out / 'fixture-jar-pin.json', dict(sha256Bytes=sha(jar), productionOverlap=0))
    print('Fixture-only compile PASS; runtime not executed.')
else:
    assert load(out / 'compile-result.json')['passed']
    assert sha(jar) == load(out / 'fixture-jar-pin.json')['sha256Bytes']
    pins = load(out / 'pins-before.json')
    assert pins['sourceSHA'] == sha(source)
    output = out / 'fixture-owned-runtime'
    assert not wide(output).exists()
    wide(output).mkdir()
    env = os.environ.copy()
    isolated_local = output / 'local-appdata'; wide(isolated_local).mkdir()
    env['LOCALAPPDATA'] = str(isolated_local.resolve())
    command = [str(c.JAVA), '-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8',
        '-Dbilipai.mpv.path=' + str(native), '-Dcompose.application.resources.dir=' + str(resources),
        '-cp', ';'.join([str(jar)] + [row['path'] for row in cp]),
        'com.bilipai.desktop.rootmediafixture.RootMediaFixtureKt', str(output.resolve())]
    write(out / 'runtime-command.json', dict(command=command, isolatedLOCALAPPDATA=env['LOCALAPPDATA'], globalInput=False))
    result = subprocess.run(command, env=env, capture_output=True, timeout=300)
    wide(out / 'runtime.log').write_bytes(result.stdout + result.stderr)
    check_pins(); assert pins['sourceSHA'] == sha(source)
    write(out / 'pins-after.json', pins)
    proof = load(output / 'root-media-proof.json')
    with zipfile.ZipFile(wide(main_jar)) as product:
        for name, origin in proof['classOrigins'].items():
            assert Path(origin['origin'].removeprefix('file:/').replace('%20', ' ')).as_posix().lower() == Path(main_jar).as_posix().lower()
            assert hashlib.sha256(product.read(name.replace('.', '/') + '.class')).hexdigest() == origin['sha256Bytes']
    write(out / 'acceptance-result.json', dict(status=proof['status'], assertions=proof['assertions'], productSourceOverrides=0,
        actualSnapshot=args.snapshot, immutableEntries=101, loadedOriginsBytesAllActual=True, ownedSkiaOnly=True,
        seededSuccess=False, realAccount=False, globalInput=False, unchangedMainEntry=False,
        detailSuccessAccepted=proof["videoDetailSuccessAccepted"], VideoToVideoCarrierAccepted=proof["physicalVideoToVideoCarrierAccepted"], PiPTransferAccepted=proof["PiPTransferAccepted"], SMTCButtonAccepted=False))
    print(json.dumps(load(out / 'acceptance-result.json'), indent=2))
    raise SystemExit(0 if result.returncode == 0 and proof['status'] == 'PASS_ACTUAL_GUEST_MEDIA_ROOT' else 1)

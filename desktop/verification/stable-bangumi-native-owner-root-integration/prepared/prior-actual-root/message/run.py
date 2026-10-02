"""Compile and run only an acceptance fixture over a Root-issued actual product snapshot.
No product source overlay, synthetic API, alternate Search VM or user-data path is used.
"""
from pathlib import Path
from urllib.parse import unquote, urlparse
import argparse, hashlib, importlib.util, json, os, subprocess, sys, tempfile, zipfile
sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding='utf8')
P = Path(__file__).resolve().parent
M = P.parents[2]
def wide(path):
    value=os.fspath(path)
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + os.path.abspath(value))
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
parser.add_argument('--code-head', required=True)
parser.add_argument('--number', default='01')
parser.add_argument('--root-issued-snapshot', required=True, action='store_true')
args = parser.parse_args()
assert args.snapshot >= 88 and args.entries == 105 and args.root_issued_snapshot
assert len(args.code_head) == 40
snapshot = M / f'desktop/.local/stable-product-snapshot-{args.snapshot}'
assert load(snapshot / 'manifest.json')['phase'] == 'actual-whole-stable-classes' + str(args.snapshot)
cp = load(snapshot / 'ordered-runtime-cp.json')
assert len(cp) == args.entries
resources = args.resources_dir.resolve()
native = resources / 'native/windows-x64/libmpv-2.dll'
diagnostic = resources / 'native/windows-x64/bilipai-diagnostic-share.dll'
source = P / 'RootMessageFixture.kt'
def check_pins():
    assert sha(snapshot / 'manifest.json') == args.manifest_sha
    assert sha(snapshot / 'ordered-runtime-cp.json') == args.cp_sha
    for row in cp: assert sha(row['path']) == row['sha256Bytes'], row['path']
    assert sha(native) == args.mpv_sha
    assert sha(diagnostic) == args.diagnostic_sha
check_pins()
out = P / ('runs/actual' + str(args.snapshot) + '-' + args.number)
jar = out / 'root-message-fixture.jar'
spec = importlib.util.spec_from_file_location('compiler', M / 'desktop/.local/source9-appearance/compile-miuix.py')
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
main_jar = next(row['path'] for row in cp if row.get('source') == 'desktop/build/classes/kotlin/main')
required = ['com/bilipai/desktop/DesktopShellKt.class', 'com/bilipai/desktop/ui/DesktopOriginalRootStackKt.class', 'com/bilipai/desktop/ui/DesktopOriginalMessagePagesRoot.class', 'com/bilipai/desktop/ui/DesktopOriginalMessagePagesRootKt.class', 'com/bilipai/desktop/ui/CommunityUiSupportKt.class', 'com/android/purebilibili/feature/message/MessageCenterScreenKt.class', 'com/android/purebilibili/feature/message/ChatScreenKt.class', 'com/android/purebilibili/feature/message/feed/ReplyMeScreenKt.class', 'com/android/purebilibili/feature/message/feed/AtMeScreenKt.class', 'com/android/purebilibili/feature/message/feed/LikeMeScreenKt.class', 'com/android/purebilibili/feature/message/feed/SystemNoticeScreenKt.class']
with zipfile.ZipFile(wide(main_jar)) as z:
    actual_names = set(z.namelist())
    for name in required: assert name in actual_names, name
if args.mode == 'compile':
    assert not wide(out).exists(), 'Attempts are preserved; choose a fresh --number.'
    wide(out).mkdir(parents=True)
    wide(out / source.name).write_bytes(wide(source).read_bytes())
    pins = dict(snapshot=args.snapshot, codeHEAD=args.code_head, manifestSHA=args.manifest_sha, orderedCPSHA=args.cp_sha,
        runtime=cp, sourceSHA=sha(source), runnerSHA=sha(Path(__file__)), nativeSHA=sha(native), diagnosticSHA=sha(diagnostic),
        requiredProductClasses=required, productionOverrides=0, prospectiveReferences=[], compileProductSources=False)
    write(out / 'pins-before.json', pins)
    compiler_args = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-module-name', 'root_message_actual_fixture',
        '-Xplugin=' + str(c.PLUGIN), '-Xfriend-paths=' + main_jar,
        '-cp', ';'.join(row['path'] for row in cp), '-d', str(out / 'classes'), str(out / source.name)]
    write(out / 'compiler.args', '\n'.join('"' + a.replace('\\', '/') + '"' for a in compiler_args))
    result = subprocess.run([str(c.JAVA), '-Xmx3g', '-Dfile.encoding=UTF-8', '-cp', ';'.join(map(str, c.COMPILER)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '@' + str(out / 'compiler.args')], capture_output=True, timeout=180)
    wide(out / 'compile.log').write_bytes(result.stdout + result.stderr)
    check_pins(); assert pins['sourceSHA'] == sha(source)
    write(out / 'compile-result.json', dict(passed=result.returncode == 0, exitCode=result.returncode, fixtureOnly=True,
        productionOverrides=0, snapshot=args.snapshot, sourceSHA=sha(source)))
    if result.returncode:
        print((result.stdout + result.stderr).decode('utf8', errors='replace')); raise SystemExit(result.returncode)
    with zipfile.ZipFile(wide(jar), 'w', zipfile.ZIP_DEFLATED) as z:
        for path in sorted(wide(out / 'classes').rglob('*')):
            if path.is_file(): z.writestr(path.relative_to(wide(out / 'classes')).as_posix(), path.read_bytes())
    with zipfile.ZipFile(wide(jar)) as fixture:
        overlap = sorted(name for name in fixture.namelist() if name.endswith('.class') and name in actual_names)
        assert not overlap, overlap
        assert all(name.startswith('com/bilipai/desktop/rootmessagefixture/') for name in fixture.namelist() if name.endswith('.class'))
    write(out / 'fixture-jar-pin.json', dict(sha256Bytes=sha(jar), productionOverlap=0))
    print('Fixture-only compile PASS; runtime not executed.')
else:
    assert load(out / 'compile-result.json')['passed']
    assert sha(jar) == load(out / 'fixture-jar-pin.json')['sha256Bytes']
    pins = load(out / 'pins-before.json')
    assert pins['sourceSHA'] == sha(source) and pins['runnerSHA'] == sha(Path(__file__))
    assert not wide(out / 'runtime-owned-path.json').exists()
    # Use the same owned short OS-temp pattern as existing updater/native fixtures.
    # actual87-01's deep native ICU startup failure stays preserved; no file is relocated or patched.
    output = Path(tempfile.mkdtemp(prefix=f'bp-s{args.snapshot}-{args.number}-')).resolve()
    write(out / 'runtime-owned-path.json',dict(path=str(output),ownedTemp=True,longNativePathAccepted=False,
        defaultUserDirectoryReused=False,sourceNativeResourcesCopied=False))
    env = os.environ.copy()
    isolated_local = output / 'local-appdata'; wide(isolated_local).mkdir()
    isolated_temp = output / 'temp'; wide(isolated_temp).mkdir()
    isolated_roaming = output / 'roaming-appdata'; wide(isolated_roaming).mkdir()
    isolated_profile = output / 'profile'; wide(isolated_profile).mkdir()
    env['LOCALAPPDATA'] = str(isolated_local.resolve())
    env['APPDATA'] = str(isolated_roaming.resolve())
    env['USERPROFILE'] = str(isolated_profile.resolve())
    env['TEMP'] = env['TMP'] = str(isolated_temp.resolve())
    command = [str(c.JAVA), '-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8',
        '-Dskiko.renderApi=SOFTWARE',
        '-Djava.io.tmpdir=' + str(isolated_temp.resolve()),
        '-Duser.home=' + str(isolated_profile.resolve()),
        '-Dbilipai.mpv.path=' + str(native), '-Dcompose.application.resources.dir=' + str(resources),
        '-cp', ';'.join([str(jar)] + [row['path'] for row in cp]),
        'com.bilipai.desktop.rootmessagefixture.RootMessageFixtureKt', str(output.resolve())]
    write(out / 'runtime-command.json', dict(command=command, isolatedLOCALAPPDATA=env['LOCALAPPDATA'],
        isolatedTemp=env['TEMP'], isolatedAPPDATA=env['APPDATA'], isolatedUSERPROFILE=env['USERPROFILE'],
        globalInput=False, ownedWindowOnly=True, appAndImageNetworkFailClosed=True,
        fixtureComposeRenderApi='SOFTWARE',defaultDirect3DStartupAccepted=False))
    timed_out = False
    try:
        result = subprocess.run(command, env=env, capture_output=True, timeout=240)
        wide(out / 'runtime.log').write_bytes(result.stdout + result.stderr)
        exit_code = result.returncode
    except subprocess.TimeoutExpired as error:
        timed_out = True; exit_code = None
        wide(out / 'runtime.log').write_bytes((error.stdout or b'') + (error.stderr or b''))
    check_pins(); assert pins['sourceSHA'] == sha(source)
    write(out / 'pins-after.json', pins)
    proof_path = output / 'root-message-proof.json'
    if not wide(proof_path).exists():
        write(out / 'acceptance-result.json', dict(status='TIMEOUT' if timed_out else 'FAIL_WITHOUT_PROOF',
            exitCode=exit_code, actualSnapshot=args.snapshot, codeHEAD=args.code_head, productionOverrides=0,
            actualRootMessageGuestAccepted=False, realAccount=False, newEXEDeployed=False))
        print(json.dumps(load(out / 'acceptance-result.json'), indent=2)); raise SystemExit(1)
    proof = load(proof_path)
    # Curate only this fixture's proof/screenshots; do not copy stores, cookies, native extraction or cache trees.
    evidence = out / 'runtime-evidence'; wide(evidence).mkdir()
    for path in [proof_path] + sorted(wide(output).glob('actual-root-message-*.png')):
        wide(evidence / path.name).write_bytes(wide(path).read_bytes())
    with zipfile.ZipFile(wide(main_jar)) as product:
        for name, origin in proof['classOrigins'].items():
            loaded = unquote(urlparse(origin['origin']).path).lstrip('/')
            assert Path(loaded).as_posix().lower() == Path(main_jar).as_posix().lower(), (name, origin)
            assert hashlib.sha256(product.read(name.replace('.', '/') + '.class')).hexdigest() == origin['sha256Bytes']
    log_text=wide(out / 'runtime.log').read_text(encoding='utf8',errors='replace')
    unhandled=[marker for marker in ['Error was captured in composition.', 'Exception in thread "AWT-EventQueue', 'Exception in thread "main"'] if marker in log_text]
    accepted = not timed_out and not unhandled and exit_code == 0 and proof['status'] == 'PASS_ACTUAL_ROOT_MESSAGE_GUEST_UI'
    status='FAIL_UNHANDLED_PRODUCT_ERROR' if unhandled and proof['status']=='PASS_ACTUAL_ROOT_MESSAGE_GUEST_UI' else proof['status']
    write(out / 'acceptance-result.json', dict(status=status, fixtureProofStatus=proof['status'], assertions=proof['assertions'], exitCode=exit_code,
        timedOut=timed_out, codeHEAD=args.code_head, actualSnapshot=args.snapshot, immutableEntries=args.entries,
        unhandledProductErrorMarkers=unhandled,
        productSourceOverrides=0, loadedOriginsBytesAllActual=True, ownedWindowOnly=True,
        actualRootMessageGuestAccepted=accepted, RootMounted=proof['RootMounted'],
        authenticatedMessagePagesAccepted=proof['authenticatedMessagePagesAccepted'],
        actualOwnedCanvasPointerEvents=proof['actualOwnedCanvasPointerEvents'],
        actualOwnedCanvasKeyboardEvents=proof['actualOwnedCanvasKeyboardEvents'],
        appAndImageNetworkFailClosed=proof['appAndImageNetworkFailClosed'],
        fixtureDenyProxyConnections=proof['fixtureDenyProxyConnections'],
        fixtureComposeRenderApi='SOFTWARE',defaultDirect3DStartupAccepted=False,longNativePathAccepted=False,
        syntheticApi=False, sameRootHttpClient=True, realAccount=False, globalInput=False,
        businessNetworkAllAccepted=False, nativePlaybackAccepted=False, newEXEDeployed=False, unchangedMainEntry=False))
    print(json.dumps(load(out / 'acceptance-result.json'), indent=2)); raise SystemExit(0 if accepted else 1)

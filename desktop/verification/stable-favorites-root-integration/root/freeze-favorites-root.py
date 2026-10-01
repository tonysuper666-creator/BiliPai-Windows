from pathlib import Path
import hashlib, json, subprocess, xml.etree.ElementTree as ET
HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
OUT = REPO / 'desktop/verification/stable-favorites-root-integration'
assert not OUT.exists()
def wide(p): return Path('\\\\?\\' + str(p.absolute()))
def read(p): return wide(p).read_bytes()
def sha(b): return hashlib.sha256(b).hexdigest()
def pin(p, digest):
    b = read(p)
    assert sha(b) == digest, p
    return b
rows, seen, excluded = [], set(), []
def put(name, b):
    assert name not in seen, name
    seen.add(name)
    p = wide(OUT / name)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(b)
    rows.append(dict(path=name, sha256Bytes=sha(b), sizeBytes=len(b)))
for prefix, directory, digest, size_key in [
    ('prepared-root', 'stable-favorites-root-wiring', 'f936ef8fac172cf6e71d11b4cefd0bb257d99127656546d898c19e23e05ea38f', 'size'),
    ('actual17-original-ui', 'stable-favorites-main17-ui-proof', '17b1144a92de580182ab481b1c907f642dd81862bc099fa0f6ea5ab83c0cb9fc', 'bytes')]:
    lane = MAIN / 'desktop/.local' / directory
    raw = pin(lane / 'frozen-handoff.json', digest)
    manifest = json.loads(raw)
    for r in manifest['artifacts']:
        b = pin(lane / r['path'], r['sha256Bytes'])
        assert len(b) == r[size_key]
        if Path(r['path']).suffix in ('.jar', '.class', '.dll', '.exe', '.png'):
            excluded.append(dict(cohort=prefix, **r))
        else: put(prefix + '/' + r['path'], b)
    put(prefix + '/frozen-handoff.json', raw)
for name in ('install-favorites-root.py', 'favorites-root-install.json', 'prove-favorites-main22.py'):
    put('root/' + name, read(HERE / name))
put('root/freeze-favorites-root.py', read(Path(__file__)))
for name in ('classes-21.log', 'classes-22.log', 'favorites-root-tests-22.log'):
    put('root/' + name, read(REPO / 'desktop/.local/stable-build-repair' / name))
for name, digest in [('manifest.json', '90df091a72910d314030cac755dcc980a8fc711111b8e5d20886fbf22e182cba'),
                     ('ordered-runtime-cp.json', 'f871605f15d537b71a0981bc556b47979f68d413447dfc1d97cad373c43374fb')]:
    put('root/snapshot22/' + name, pin(MAIN / 'desktop/.local/stable-product-snapshot-22' / name, digest))
proof = MAIN / 'desktop/.local/stable-favorites-main22-integration-proof'
result = json.loads(read(proof / 'result.json'))
assert result['passed'] and result['productionClassOverrides'] == 0 and result['assertions'] == 28
for name in ('RootFavoritesWiringFixture.kt', 'compile.args', 'compile.log', 'run.log', 'result.json'):
    put('root/actual22/' + name, read(proof / name))
tests = []
for short in ('ListenAudioLifecycleTest', 'ListenWriterBarrierTest', 'DesktopMusicRootIntegrationTest'):
    name = 'TEST-com.bilipai.desktop.audio.' + short + '.xml'
    b = read(REPO / 'desktop/build/test-results/test' / name)
    t = ET.fromstring(b)
    assert all(t.attrib[k] == '0' for k in ('failures', 'errors', 'skipped'))
    tests.append(dict(name=t.attrib['name'], methods=int(t.attrib['tests']), failures=0, errors=0, skipped=0))
    put('root/junit/' + name, b)
assert sum(t['methods'] for t in tests) == 17
for name in ('DesktopShell.kt', 'audio/ListenAudioSession.kt', 'ui/DesktopOriginalFavoritesHost.kt',
             'ui/DesktopFavoritesRootEntry.kt', 'ui/DesktopFavoriteQueueBridge.kt'):
    put('root/installed/' + name, read(REPO / 'desktop/src/main/kotlin/com/bilipai/desktop' / name))
put('root/installed/DesktopFavoriteEnvironment.kt', read(REPO / 'desktop/src/main/kotlin/com/android/purebilibili/feature/list/DesktopFavoriteEnvironment.kt'))
put('root/final-test/ListenAudioLifecycleTest.kt', read(REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/audio/ListenAudioLifecycleTest.kt'))
put('root/installed-local-hunks.patch', subprocess.check_output(['git', 'diff', '--', 'desktop/src/main', 'desktop/src/test/kotlin/com/bilipai/desktop/audio/ListenAudioLifecycleTest.kt'], cwd=REPO))
report = dict(upstreamTag='v0.2.3', upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
    wholeClasses22Passed=True, sourceIdentities=748, resources=210,
    originalFullFavoritesRootSourceMounted=True, oldSimplifiedFavoritesRootRemoved=True,
    originalRetainedVmAndConflatedChannels=True, actualMeasuredViewport=True,
    originalFullFolderContinuationAndSameNativeQueues=True, appendOnlyUnseenBvidsAndPreservePreparedCid=True,
    realNextAndLoadedPauseRetainQueueOwner=True, pendingForeignSourceAndOrdinaryOpenRetireOldOwner=True,
    callbackTimeOwnerChecksAndOldSaveableKeysCleanup=True, sameGlobalPreferencesAndNativeShareActor=True,
    actualProduct22QueueProofGroups=4, actualProduct22QueueProofAssertions=28,
    actualProduct22ProofOverrides=0, originalUiProductPhase=17, originalUiThemes=2,
    originalUiAssertions=26, originalUiPointerPairs=20, originalUiEditableTextActions=4,
    junitMethods=17, junitSuites=tests,
    initialClasses21Failure='Root attempted to read the manual environment owner predicate before it was exposed; added isOwned using the existing predicate/scope, assertOwned delegates unchanged, classes22 succeeds',
    originalMiuixLabelSlotBehaviorRetained=True,
    actualRootMountedUiAccepted=False, actualVideoAudioDecodingAccepted=False,
    realOnlineFolderContinuationAccepted=False, realAccountOrSystemShareAccepted=False,
    desktopExeReplaced=False, publicReleaseAccepted=False,
    proofScope='Actual product22 six owners/VM classes, synthetic memory APIs and unattached native actors; original17 offscreen UI uses synthetic folders and real Compose events; no socket/HWND/account mutation',
    pending=['Mounted Root navigation/list-state restoration and actual account folder continuation',
             'Complete Home/FrostedBottomBar/music/settings/player/danmaku/space UI',
             'Full Root/PiP/DPI/native sharing and desktop EXE delivery'], excludedTaskBinaries=excluded)
put('integration-report.json', (json.dumps(report, indent=2) + '\n').encode())
raw = (json.dumps(dict(schema='raw-artifact-manifest-v1', artifacts=rows), indent=2) + '\n').encode()
wide(OUT / 'artifact-manifest.json').write_bytes(raw)
print(json.dumps(dict(artifacts=len(rows), manifestSha256Bytes=sha(raw), junitMethods=17)))

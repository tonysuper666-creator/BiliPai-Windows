from pathlib import Path
import hashlib, json, xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = MAIN / 'desktop/.local/stable-danmaku-list-menu-parity'
OUT = REPO / 'desktop/verification/stable-danmaku-list-menu-integration'
assert not OUT.exists()
def wide(p): return Path('\\\\?\\' + str(p.absolute()))
def read(p): return wide(p).read_bytes()
def sha(b): return hashlib.sha256(b).hexdigest()
def pin(p, digest):
    b = read(p)
    assert sha(b) == digest, p
    return b
rows, names, excluded = [], set(), []
def put(name, b):
    assert name not in names, name
    names.add(name)
    p = wide(OUT / name)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(b)
    rows.append(dict(path=name, sha256Bytes=sha(b), sizeBytes=len(b)))
raw = pin(LANE / 'frozen-handoff.json', 'd1625f76f8af2adf198e3354951f5b88dc831bd936b588de868455f7b71c5fdb')
manifest = json.loads(raw)
for r in manifest['artifacts']:
    b = pin(LANE / r['path'], r['sha256Bytes'])
    assert len(b) == r['bytes']
    if Path(r['path']).suffix in ('.jar', '.class', '.dll', '.exe', '.png'):
        excluded.append(r)
    else: put('prepared/' + r['path'], b)
put('prepared/frozen-handoff.json', raw)
for name in ('install-danmaku-sources.py', 'danmaku-source-install.json',
             'correct-danmaku-members-placement.py', 'danmaku-placement-correction.json'):
    put('root/' + name, read(HERE / name))
put('root/freeze-danmaku-source.py', read(Path(__file__)))
for name in ('classes-19.log', 'classes-20.log', 'danmaku-source-tests-20.log',
             'danmaku-source-tests-20b.log', 'danmaku-source-tests-20c.log'):
    put('root/' + name, read(REPO / 'desktop/.local/stable-build-repair' / name))
assert b'BUILD SUCCESSFUL' in read(REPO / 'desktop/.local/stable-build-repair/classes-20.log')
assert b'BUILD SUCCESSFUL' in read(REPO / 'desktop/.local/stable-build-repair/danmaku-source-tests-20c.log')
for name, digest in [('manifest.json', '41439c3242e279bc0dc09a20501bdcb36e13cda7f66ab2bbbe537e7538d3ecf2'),
                     ('ordered-runtime-cp.json', '4d1499c7ddbeeddbf31427505930ab0c3d5c6a3aaaf8dc08d2a585edca720232')]:
    put('root/snapshot20/' + name, pin(MAIN / 'desktop/.local/stable-product-snapshot-20' / name, digest))
tests = []
for short in ('DanmakuTest', 'AdvancedDanmakuTest', 'DanmakuWindowLoaderTest', 'DanmakuPluginAdapterTest'):
    name = 'TEST-com.bilipai.desktop.danmaku.' + short + '.xml'
    b = read(REPO / 'desktop/build/test-results/test' / name)
    t = ET.fromstring(b)
    assert all(t.attrib[k] == '0' for k in ('failures', 'errors', 'skipped'))
    tests.append(dict(name=t.attrib['name'], methods=int(t.attrib['tests']), failures=0, errors=0, skipped=0))
    put('root/junit/' + name, b)
for name in ('DanmakuTest.kt', 'DanmakuWindowLoaderTest.kt'):
    put('root/final-tests/' + name, read(REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/danmaku' / name))
for name in ('danmaku/DanmakuParser.kt', 'danmaku/DanmakuOverlay.kt', 'DesktopPlaybackController.kt',
             'data/DesktopDynamicCardOperations.kt'):
    put('root/installed/' + name, read(REPO / 'desktop/src/main/kotlin/com/bilipai/desktop' / name))
registry = json.loads(read(REPO / 'desktop/upstream-sources.json'))
assert len(registry['sources']) == 748 and len(registry['resources']) == 210
for entries in (registry['sources'], registry['resources']):
    assert len(entries) == len({r['path'] for r in entries})
    for r in entries:
        b = read(REPO / r['path'])
        if r.get('hashNormalization', 'lf') != 'raw': b = b.decode().replace('\r\n', '\n').encode()
        assert sha(b) == r['sha256'], r['path']
assert sum(t['methods'] for t in tests) == 26
report = dict(upstreamTag=registry['upstreamTag'], upstreamCommit=registry['upstreamCommit'],
    sourceIdentities=748, resources=210, all958CurrentSourceAndResourcePinsVerified=True,
    fullOriginalDanmakuPoolAndContextMenuCompiled=True, wholeClasses20Passed=True,
    originalParserFactoriesAndProtocolAndSessionCompiled=True, sameApiAndSessionAuthority=True,
    originalXmlAndProtobufPayloadRetainedSeparatelyFromBoundedDisplay=True,
    nativeOwnerEstablishedBeforeOnlineDanmakuLoad=True, cidSourceVersionAndEpochRequired=True,
    originalRowLongClickMadeReachableWithCombinedClickable=True,
    rootOriginalDanmakuHostMounted=False, originalBlockPreferencesRendererConsumerWired=False,
    nativeOverlayLongPressAccepted=False, actualRootUiAccepted=False,
    junitMethods=26, junitSuites=tests,
    initialClasses19Rejected='New members were initially inside the strict original Editor marker range; unchanged Danmaku fragment moved before that range, without changing any verifier or existing protocol family',
    initialAddedTestFailure='Test expected x3 suffix to add four characters; actual three-character suffix expectation corrected from 304 to 303, no production change',
    initialInstallRecordIsHistoricalBeforePlacementCorrection=True,
    proofScope='Original isolated memory-transport session tests plus actual candidate parser/window/plugin JUnit; no socket/account mutation/HWND/codec acceptance',
    desktopExeReplaced=False, publicReleaseAccepted=False,
    pending=['Actual Root list/menu/text-selection provider and same-owner seek binding',
             'Original full settings panel and actual renderer block-rule consumer',
             'Native overlay item hit testing and full player interaction',
             'Full Root/account/PiP/DPI and EXE delivery'], excludedTaskBinaries=excluded)
put('integration-report.json', (json.dumps(report, indent=2) + '\n').encode())
raw = (json.dumps(dict(schema='raw-artifact-manifest-v1', artifacts=rows), indent=2) + '\n').encode()
wide(OUT / 'artifact-manifest.json').write_bytes(raw)
print(json.dumps(dict(artifacts=len(rows), manifestSha256Bytes=sha(raw), junitMethods=26)))

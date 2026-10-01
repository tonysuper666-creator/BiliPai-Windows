from pathlib import Path
import hashlib,json,xml.etree.ElementTree as ET

HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-danmaku-render-integration'
assert not (OUT/'artifact-manifest.json').exists()
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def pin(p,h):
    b=read(p);assert sha(b)==h,p;return b
rows=[];excluded=[]
def put(name,b):
    p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True)
    if p.exists():assert p.read_bytes()==b,name
    else:p.write_bytes(b)
    rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
def tree(label,path):
    for p in sorted(wide(path).rglob('*'),key=str):
        if not p.is_file():continue
        rel=p.relative_to(wide(path)).as_posix()
        if p.suffix in ('.jar','.class','.pyc','.png') or '__pycache__' in rel:
            excluded.append(dict(path=str(p),sha256Bytes=sha(read(p))));continue
        put(label+'/'+rel,read(p))

for name,expected in (
    ('stable-danmaku-render-config-consumers-parity','6d221c053309abd218b55a129634726bf5f99f28d4458a374069351468b69103'),
    ('stable-danmaku-monitor-passive-delta','ab13142a81130473f2fa4aaf2dee90816a5cccf24ba175cc499cd480f51f59a5')):
    lane=REPO/'desktop/.local'/name
    raw=pin(lane/'frozen-handoff.json',expected);m=json.loads(raw)
    put('prepared/'+name+'/frozen-handoff.json',raw)
    for r in m['evidence']:
        b=pin(r['path'],r['sha256Bytes']);rel=r['relative']
        if Path(rel).suffix in ('.jar','.class','.png','.pyc'):
            excluded.append(r);continue
        put('prepared/'+name+'/'+rel,b)

snap=MAIN/'desktop/.local/stable-product-snapshot-31'
raw=pin(snap/'manifest.json','999bc989c995f877761b22c5c00a6a0d99819100d8cef591a25f94b5d56a3a5d');m=json.loads(raw)
assert m['wholeCandidateClassesPassed'] and m['sourceRegistryCount']==773
put('root/snapshot31/manifest.json',raw)
cp=pin(snap/'ordered-runtime-cp.json','0da93f5423ef53986a1939d97b37114a97e4a8e64b646052d34eaeaa916a6dee')
put('root/snapshot31/ordered-runtime-cp.json',cp)
for r in json.loads(cp):pin(r['path'],r['sha256Bytes'])

proof=MAIN/'desktop/.local/stable-danmaku-render-main31-proof'
receipt=json.loads(pin(proof/'result.json','7b9d5029431b57ffd23d7b3a3bce1f7647604d47353f4229c0d7d8af04f0d71c'))
assert receipt['passed'] and receipt['assertions']==2745 and receipt['productionOverrides']==0
tree('root/actual31-render',proof)
settings=MAIN/'desktop/.local/stable-danmaku-main31-advanced-settings-proof'
s=json.loads(read(settings/'result.json'));assert s['passed'] and s['assertions']==61 and s['productionOverrides']==0
tree('root/actual31-settings',settings)
# Preserve the compile rejection of unchanged old fixture calls and duration assumptions.
tree('root/old-fixture-required-api-rejection',MAIN/'desktop/.local/stable-danmaku-main31-integration-proof')
tree('root/install',HERE/'danmaku-render-consumers-install')
for name in ('install-danmaku-render-consumers.py','prove-danmaku-render-actual31.py',
             'prove-danmaku-advanced-settings-actual-product.py','prove-danmaku-advanced-settings-adapted-product.py',
             'advanced-settings-proof-runner-adaptation.json','advanced-settings-adapted-runner.json',
             'prepare-advanced-settings-fixture.py','audit-jvm-method-names.py','jvm-method-name-audit-31.json'):
    put('root/'+name,read(HERE/name))
for phase in ('classes-31','test-danmaku-31','test-danmaku-31-fixed','test-danmaku-31-final','test-danmaku-31-pinned-budget'):
    b=read(REPO/('desktop/.local/stable-build-repair/'+phase+'.log'))
    if phase in ('classes-31','test-danmaku-31-pinned-budget'):assert b'BUILD SUCCESSFUL' in b
    put('root/'+phase+'.log',b)
tests=0
for name in ('DanmakuTest','LiveDanmakuPluginTest'):
    p=REPO/f'desktop/build/test-results/test/TEST-com.bilipai.desktop.danmaku.{name}.xml'
    b=read(p);x=ET.fromstring(b);assert x.attrib['failures']=='0' and x.attrib['errors']=='0'
    tests+=int(x.attrib['tests']);put('root/junit/'+p.name,b)
assert tests==13
put('root/current-live-style-test.kt',read(REPO/'desktop/src/test/kotlin/com/bilipai/desktop/danmaku/LiveDanmakuPluginTest.kt'))
put('root/freeze-danmaku-render-actual31.py',read(Path(__file__)))
report=dict(targetTag='v0.2.3',targetCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
    sourceRegistryCount=773,resourceCount=213,wholeCandidateClassesPhase=31,wholeClassesPassed=True,
    originalConfigDurationLineBudgetLayerMapLiveAdmissionAndScalarConsumersCompiled=True,
    originalWeightUsesPacketIsSelfNotGuessedMID=True,fullOriginalProducerSelectedOutputs=16,
    all92RuntimeEntriesPinnedBeforeAndAfter=True,productionOverrides=0,
    actualAdvancedPureAWTAssertions=2725,actualFixtureMonitorAssertions=12,actualHiddenWindowsMonitorAssertions=8,
    actualSettingsAndOwnershipAssertions=61,actualGradleJunitMethods=13,
    actualHiddenMonitorPhysicalMode=[3840,2160],actualRootWindowAccepted=False,
    ordinaryOverlayDefaultMouseThroughMatchesAllFiveOriginalCalls=True,
    longPressMenuUsesOriginalListEntryAndCommandVotingRemainsSeparate=True,
    ordinaryOverlayCaptureHitTestRequiredByOriginalDefault=False,
    originalStoredDurationsStillAllow50Seconds=True,originalRendererScrollClampMs=20000,originalRendererPinnedClampMs=15000,
    oldFixtureTimingAssumptionsAndCompileFailuresPreserved=True,
    fullByteDanceCollisionEngineOrFullLiveQueueParityAccepted=False,
    actualRootPlaybackOrAccountsAccepted=False,desktopExeReplaced=False,
    pending=['Original server WebMask path and native clip owner','Portrait SCREEN_TOP anchor','Full ByteDance collision/special engine and full original live queue/image lifecycle','Whole mounted Root/native playback and EXE delivery'],
    excludedTaskBinaries=excluded)
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
manifest=(json.dumps(dict(schema='raw-artifact-manifest-v1',artifacts=rows),indent=2)+'\n').encode()
wide(OUT/'artifact-manifest.json').write_bytes(manifest)
wide(OUT/'README.md').write_text('''# Original v0.2.3 Danmaku configuration consumers

Use the sole existing original source producer for complete Config/RenderConfig, display-band policy, layer mapping, original live configuration/admission and two exact engine budgets. Existing Windows scheduler/paint adapters consume those values through required physical monitor and platform-font ports. Four advanced scalars project from the same original settings store. Original protobuf isSelf alone bypasses video weight filtering; live uses its original distinct admission order.

Whole classes31 passes with773 source identities and213 resources. Immutable actual31 runtime, zero product overrides and92 pinned entries:2725 pure/AWT assertions,12 explicit monitor-mode fixture assertions,8 actual hidden Windows monitor/font/chrome checks,61 settings/ownership assertions, and13 actual Gradle JUnit methods pass. The hidden test window reads3840x2160; this is platform-port acceptance, not the real application Root. Failed legacy fixture/three test attempts remain recorded. Fixture timing now follows original20s scroll/15s pinned clamps while original stored50s values remain intact; production algorithms were not loosened for tests.

Original ordinary, offline, portrait and fullscreen overlays all default to passive/mouse-through. This corrects the historical generic hit-test pending label in prepared213; original list longpress and separate command votes retain their distinct entry points. Android ByteDance collision/special engines, complete original live queue/image lifecycle, server WebMask native clip and portrait SCREEN_TOP remain open. No full Root/account/native playback acceptance or EXE deployment is claimed. Historical prepared213/32 and actual26/27/30 evidence are unchanged.
''',encoding='utf-8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),manifestSHA256=sha(manifest),actualAssertions=2745+61,actualJunitMethods=tests)))

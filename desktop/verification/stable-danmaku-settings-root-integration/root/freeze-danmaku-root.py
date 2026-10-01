from pathlib import Path
import hashlib,json,sys,xml.etree.ElementTree as ET
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';phase=int(sys.argv[1])
OUT=REPO/'desktop/verification/stable-danmaku-settings-root-integration';assert not OUT.exists()
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def pin(p,d):
    b=read(p);assert sha(b)==d,p;return b
rows=[];seen=set();excluded=[]
def put(name,b):
    assert name not in seen,name;seen.add(name);p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
    rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
for prefix,lane,digest in (
    ('prepared-settings',MAIN/'desktop/.local/stable-danmaku-settings-panel-parity','51acaae885d7cf690f2b17aaf4ea856b02aae81e8b25c0086a5ffb01dddeb6a8'),
    ('prepared-shared-visibility',MAIN/'desktop/.local/stable-shared-dock-visibility-supplement','8e9d75a953d931b779c83c482422c21df12a8d6e784c556b0f380470bbe5f5be')):
    raw=pin(lane/'frozen-handoff.json',digest);m=json.loads(raw);put(prefix+'/frozen-handoff.json',raw)
    for r in m['artifacts']:
        b=pin(lane/r['path'],r['sha256Bytes'])
        if 'size' in r:assert len(b)==r['size']
        if Path(r['path']).suffix in ('.jar','.class','.exe','.dll','.png'):excluded.append(dict(cohort=prefix,**r))
        else:put(prefix+'/'+r['path'],b)
lane=REPO/'desktop/.local/stable-danmaku-root-consumers-parity'
raw=pin(lane/'frozen-handoff.json','51551c0d5070f6980e7511c052816fee4e5b0a1cb517e82f4f60208dce5eb01f');m=json.loads(raw)
put('prepared-consumers/frozen-handoff.json',raw)
for r in m['evidence']:
    relative=wide(r['path']).relative_to(wide(lane)).as_posix();put('prepared-consumers/'+relative,pin(r['path'],r['sha256Bytes']))
for r in m['payload']:put('prepared-consumers/prepared/'+r['target'],pin(r['source'],r['sha256Bytes']))
for name in ('proof/RootDanmakuConsumerProof.kt','compile-03/compile.log','proof.py'):
    if wide(lane/name).exists():put('prepared-consumers/'+name,read(lane/name))
scripts=('install-danmaku-settings.py','danmaku-settings-install.json','install-danmaku-root-consumers.py',
         'danmaku-root-consumers-install.json','install-danmaku-harness-and-dock-supplement.py',
         'danmaku-harness-and-dock-supplement-install.json','prove-danmaku-actual-product.py','freeze-product.py')
for name in scripts:put('root/'+name,read(HERE/name))
put('root/freeze-danmaku-root.py',read(Path(__file__)))
for dirname in ('danmaku-settings-install-baseline','danmaku-root-consumers-install-baseline','danmaku-harness-install-baseline'):
    root=HERE/dirname
    for p in sorted(wide(root).rglob('*'),key=str):
        if p.is_file():put('root/'+dirname+'/'+p.relative_to(wide(root)).as_posix(),read(p))
put('root/classes-'+str(phase)+'.log',read(REPO/f'desktop/.local/stable-build-repair/classes-{phase:02}.log'))
put('root/tests-'+str(phase)+'.log',read(REPO/f'desktop/.local/stable-build-repair/danmaku-root-tests-{phase}.log'))
snap=MAIN/f'desktop/.local/stable-product-snapshot-{phase}';raw=read(snap/'manifest.json');meta=json.loads(raw)
assert meta['wholeCandidateClassesPassed'] and meta['sourceRegistryCount']==770
put('root/snapshot'+str(phase)+'/manifest.json',raw)
cp=pin(snap/'ordered-runtime-cp.json',meta['orderedRuntimeClasspathSha256Bytes']);put('root/snapshot'+str(phase)+'/ordered-runtime-cp.json',cp)
for r in json.loads(cp):pin(r['path'],r['sha256Bytes'])
proof=MAIN/f'desktop/.local/stable-danmaku-main{phase}-integration-proof';result=json.loads(read(proof/'result.json'))
assert result['passed'] and result['assertions']==61 and result['productionOverrides']==0
for p in sorted(wide(proof).rglob('*'),key=str):
    if p.is_file() and p.suffix in ('.kt','.json','.args','.log'):put('root/actual'+str(phase)+'/'+p.relative_to(wide(proof)).as_posix(),read(p))
tests=[]
for p in sorted((REPO/'desktop/build/test-results/test').glob('TEST-*.xml')):
    b=read(p);t=ET.fromstring(b);assert all(t.attrib[k]=='0' for k in ('failures','errors','skipped'))
    tests.append(dict(name=t.attrib['name'],methods=int(t.attrib['tests'])));put('root/junit/'+p.name,b)
assert len(tests)==7 and sum(t['methods'] for t in tests)==45
targets=set()
for name in ('danmaku-settings-install.json','danmaku-root-consumers-install.json'):
    targets.update(r['path'] for r in json.loads(read(HERE/name))['installedFiles'])
targets.add('desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuSettings.kt')
targets.add('desktop/tools/extract-upstream-shared-liquid-tabs.py')
targets.update(r['path'] for r in json.loads(read(HERE/'danmaku-harness-and-dock-supplement-install.json'))['harnessChanges'])
for target in sorted(targets):put('root/final-installed/'+target,read(REPO/target))
for dirname in ('original-danmaku-settings','original-danmaku-list-menu'):
    root=REPO/'desktop/build/generated'/dirname
    for p in sorted(wide(root).rglob('*'),key=str):
        if p.is_file() and p.suffix in ('.kt','.json'):put('root/actual-generated/'+dirname+'/'+p.relative_to(wide(root)).as_posix(),read(p))
put('root/source-parity-report.json',read(HERE/f'danmaku-root-source-parity-{phase}.json'))
report=dict(upstreamTag='v0.2.3',upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',sourceIdentities=770,resources=213,
    wholeClassesPhase=phase,wholeClassesPassed=True,original2030LinePanelSourceRetained=True,fullOriginalBlockManagerRetained=True,
    originalCloudRulesAndConfigFourMethodsSameOps=True,original700MsCloudStateMountedOutsideTransientModal=True,
    originalPreferencesSameGlobalStore=True,originalSupportedFieldsAllOverlayAndSourceLoadConsumersWired=True,
    originalRulesFullLengthAndCountRetained=True,oldWindowsDanmakuRowsRetiredAndAbsentOnlyMigrationRetained=True,
    requiredActualPresentationBinding=True,physicalMonitorWindowsAdapterExplicit=True,allActualRuleStreamReadsCheckCallerCancellationAndPageOwner=True,
    sameCommentOwnerAndGalleryProvider=True,uniqueOriginalTextSelectionSheetUsed=True,fixedCidSourceVersionAndEpochOwner=True,
    rootLocalHunks=33,existingOverlayAbsentHarnessesRequiredRendererPortExplicit=True,
    actualProductProofGroups=6,actualProductProofAssertions=61,productionOverrides=0,junitMethods=45,junitSuites=tests,
    sharedOriginalVisibilityOverloads=2,soleSharedProducerExtended=True,
    actualRootPanelPointerAccepted=False,actualNativeOverlayHitTestAccepted=False,realSystemWindowChooserDisplayAccepted=False,
    realAccountCloudRequestsAccepted=False,fullAndroidRendererParityAccepted=False,desktopExeReplaced=False,
    pending=['Original fixedVelocity/staticToScroll/massive/weight/whole track-duration pipeline',
             'Smart native mask and portrait SCREEN_TOP/separate portrait-fullscreen owner',
             'Actual full settings/list/menu pointer and file chooser/native HWND runtime acceptance',
             'Full Home/Music/Player/Space Root and EXE delivery'],
    scope='Actual whole product classes, fixture-only zero production overrides, real store/projection/Scheduler filters and raw duration bounds, original config/rule protocol through an in-memory Retrofit interceptor, original Compose cloud effects, caller cancellation/typed presentation checks; no socket/account/HWND.',
    excludedTaskBinaries=excluded)
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
raw=(json.dumps(dict(schema='raw-artifact-manifest-v1',artifacts=rows),indent=2)+'\n').encode();wide(OUT/'artifact-manifest.json').write_bytes(raw)
print(json.dumps(dict(artifacts=len(rows),manifestSHA256=sha(raw),junitMethods=45,actualProductAssertions=61)))

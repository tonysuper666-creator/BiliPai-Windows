from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-danmaku-runtime-integration';assert not (OUT/'artifact-manifest.json').exists()
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def pin(p,d):
    b=read(p);assert sha(b)==d,p;return b
rows=[];excluded=[]
def put(name,b):
    p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True)
    if p.exists():assert p.read_bytes()==b,name
    else:p.write_bytes(b)
    rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
lane=MAIN/'desktop/.local/stable-danmaku-settings-main26-ui-proof'
raw=pin(lane/'frozen-handoff.json','f8e3c7bc6317e8bbbe35e857036432da4344bdc109f2e8446f2ec706cb29bf46');m=json.loads(raw)
put('prepared-ui/frozen-handoff.json',raw)
for r in m['artifactRows']:
    b=pin(r['path'],r['sha256Bytes']);assert len(b)==r['bytes']
    if Path(r['relativePath']).suffix in ('.jar','.class','.png','.exe','.dll'):
        excluded.append(r);continue
    put('prepared-ui/'+r['relativePath'],b)
for name in ('correct-danmaku-compose-return.py','audit-jvm-method-names.py','jvm-method-name-audit-26.json','jvm-method-name-audit-27.json','prove-danmaku-actual-product.py','freeze-product.py'):
    put('root/'+name,read(HERE/name))
for p in sorted(wide(HERE/'danmaku-compose-return-correction').rglob('*'),key=str):
    if p.is_file():put('root/source-correction/'+p.relative_to(wide(HERE/'danmaku-compose-return-correction')).as_posix(),read(p))
put('root/freeze-danmaku-runtime.py',read(Path(__file__)))
put('root/classes-27.log',read(REPO/'desktop/.local/stable-build-repair/classes-27.log'))
snapshot=MAIN/'desktop/.local/stable-product-snapshot-27'
raw=pin(snapshot/'manifest.json','908d5cfdb65a99622fd2822771641b17d2bf35efe501a71ffcd231eace8c1c53');meta=json.loads(raw)
put('root/snapshot27/manifest.json',raw)
cp=pin(snapshot/'ordered-runtime-cp.json',meta['orderedRuntimeClasspathSha256Bytes']);put('root/snapshot27/ordered-runtime-cp.json',cp)
for r in json.loads(cp):pin(r['path'],r['sha256Bytes'])
proof=MAIN/'desktop/.local/stable-danmaku-main27-integration-proof';result=json.loads(read(proof/'result.json'))
assert result['passed'] and result['assertions']==61 and result['productionOverrides']==0
for p in sorted(wide(proof).rglob('*'),key=str):
    if p.is_file() and p.suffix in ('.json','.kt','.args','.log'):put('root/actual27/'+p.relative_to(wide(proof)).as_posix(),read(p))
ui=json.loads(pin(lane/'runs/04-actual27/accepted-comparison.json','44971dafbbb991389cccfee3910bbdb1de7fda54b5cc6e94de2b79d6f88f1eb9'))
static=json.loads(read(HERE/'jvm-method-name-audit-27.json'));assert static['issueCount']==0 and static['classCount']==9917
report=dict(upstreamTag='v0.2.3',upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',actualWholeClassesPhase=27,wholeClassesPassed=True,
    invalidMethodReferenceClassesBefore=2,invalidMethodReferenceClassesAfter=0,staticClassCount=9917,
    sourceCorrectionFiles=2,inlineLabeledReturnsReplacedByStructuredGuards=True,cloudSettingsActorMountedOutsideTransientModal=True,
    actualProductSettingsAndConsumerAssertions=61,fullSettingsPanelUiStyles=2,fullSettingsPanelUiAssertions=32,
    actualComposePointerPairs=58,actualEditableTextActions=6,productionClassOverrides=0,
    historicalActual26FailureRetained=True,prior179And515And451AndActual26FrozenCohortsUnmodified=True,
    candidateSourceIdentityCount=770,actualHostAndRootAndListHostJvmClassLoadAccepted=True,
    actualFullSettingsHostIsolatedSceneAccepted=True,actualFullRootMounted=False,systemWindowOrNativeChooserUsed=False,
    realAccountOrNetworkCloudUsed=False,fullOriginalAndroidRendererParityAccepted=False,desktopExeReplaced=False,
    uiAcceptance=ui,excludedTaskBinaries=excluded)
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
raw=(json.dumps(dict(schema='raw-artifact-manifest-v1',artifacts=rows),indent=2)+'\n').encode()
wide(OUT/'artifact-manifest.json').write_bytes(raw)
readme='''# Full original danmaku settings runtime follow-up

Target remains v0.2.3. Actual26 compiled but the first real full-panel mount failed before drawing: Compose emitted invalid JVM NON_LOCAL_RETURN.<anonymous> references in SettingsHost and Root. Preserve that failure and its original receipts. Two local source guards remove inline labeled returns without changing the original panel, preference setters, nullable loading semantics or the cloud actor lifetime.

Actual27 whole compilation passes. Static inspection of 9917 Kotlin classes finds zero invalid JVM method names. The same actual frozen product passes the previous six groups /61 settings and consumer assertions with zero product-class overrides. Full SettingsHost now mounts in Material and Miuix isolated Compose scenes: 32 assertions, 58 actual pointer pairs and six editable-text actions exercise presentation-scoped persistence, shared font keys, all three tabs, advanced settings and the complete block manager add/edit/cancel/save/import flow.

01–03 failures remain intact; 04 uses the real Miuix thumb drag because a track tap does not commit that original slider. Input chooser and cloud responses are explicit in-memory fixtures. This does not accept the full account-owned Root, physical HWND/IME/system chooser, original Android renderer or a deployed EXE. Actual27 carries its own same-byte Miuix5157 dependency copy, separate from the next5c91 upgrade.
'''
wide(OUT/'README.md').write_bytes(readme.encode())
print(json.dumps(dict(rawArtifacts=len(rows),manifestSHA256=sha(raw),actualPhase=27,uiAssertions=32,pointerPairs=58,consumerAssertions=61)))

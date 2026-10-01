from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-miuix-nav-integration';assert not (OUT/'artifact-manifest.json').exists()
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def pin(p,h):
    b=read(p);assert sha(b)==h,p;return b
rows=[]
def put(name,b):
    p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True)
    if p.exists():assert p.read_bytes()==b
    else:p.write_bytes(b)
    rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
def tree(label,path):
    for p in sorted(wide(path).rglob('*'),key=str):
        if p.is_file() and p.suffix in ('.kt','.py','.json','.log','.args','.patch','.md'):
            put(label+'/'+p.relative_to(wide(path)).as_posix(),read(p))
lane=MAIN/'desktop/.local/stable-miuix5c91-nav-source-review'
raw=pin(lane/'evidence-manifest.json','40c49cf00959b09a650304edb5117b7c02e958ae58775d401209ec9929b18e63');m=json.loads(raw)
put('prepared/evidence-manifest.json',raw)
for r in m['artifactRows']:put('prepared/'+r['relativePath'],pin(lane/r['relativePath'],r['sha256Bytes']))
snap=MAIN/'desktop/.local/stable-product-snapshot-32'
raw=pin(snap/'manifest.json','28118f431eb78418791426c914690143faedeeaef14cc11fb216ac648caad413');put('root/snapshot32/manifest.json',raw)
cp=pin(snap/'ordered-runtime-cp.json','aba962efa0ce8cdd882f692e87a95d533e229766e091b4b5fa6f9d68f8b3101c');put('root/snapshot32/ordered-runtime-cp.json',cp)
for r in json.loads(cp):pin(r['path'],r['sha256Bytes'])
core=MAIN/'desktop/.local/stable-miuix-nav-main32-proof-02'
a=json.loads(pin(core/'accepted.json','ab025e30c1dabdadf73ba6301556962c735332f8402af95fa647db1dfb046fba'));assert a['passed'] and a['assertions']==32 and a['productionOverrides']==0
tree('root/actual32-nav',core);tree('root/first-fixture-list-identity-failure',MAIN/'desktop/.local/stable-miuix-nav-main32-proof')
dock=MAIN/'desktop/.local/stable-miuix-nav-main32-dock-ui-proof';d=json.loads(read(dock/'result.json'))
assert d['passed'] and d['assertions']==120 and d['pointerPairs']==38 and d['productionClassOverrides']==0
tree('root/actual32-dock',dock);tree('root/install',HERE/'miuix5c91-nav-install')
for name in ('install-miuix5c91-nav.py','prove-miuix-nav-actual32.py','prove-miuix-nav-actual32-02.py',
    'nav32-fixture-list-read-correction.json','nav32-runtime-graph-audit.json','prove-miuix-nav32-dock-product.py',
    'nav32-dock-runner-adaptation.json','jvm-method-name-audit-32.json'):
    put('root/'+name,read(HERE/name))
audit=json.loads(read(HERE/'nav32-runtime-graph-audit.json'));assert len(audit['normalClassFQNDuplicates'])==0 and audit['entryCount']==97
library_audit=json.loads(read(core/'library-static-method-name-audit.json'));assert library_audit['issues']==[]
put('root/classes-32.log',read(REPO/'desktop/.local/stable-build-repair/classes-32.log'))
put('root/installed-upstream-provenance.json',read(REPO/'desktop/third-party/miuix5157/upstream-provenance.json'))
put('root/freeze-miuix-nav32.py',read(Path(__file__)))
report=dict(targetTag='v0.2.3',wholeClassesPhase=32,wholeClassesPassed=True,soleSourceProject=':miuix5157',
    librarySourceCommit='5c91d5e5ce1a2fc7e8bdc1258a881c555102bbca',canonicalSourceFiles=225,compiledOriginalSourceFiles=195,
    fullOriginalNavSources=30,navSourcesByteIdentical=True,actualNavCoreAssertions=32,actualDockUiAssertions=120,actualDockPointerPairs=38,
    productionOverrides=0,all97RuntimeEntriesPinnedBeforeAndAfter=True,normalClassFQNDuplicates=0,
    libraryStaticMethodNameScannedClasses=library_audit['classes'],invalidLibraryMethodNameClassCount=0,
    explicitOriginalLifecycleDependencyGraphResolved=True,previousLifecycleRuntimeCompose296Retired=True,
    mainProductBytesUnchangedFrom31=True,fixtureSnapshotListValueReadCorrectionOnly=True,
    fullRootNavigationAndActualWindowDispatcherAccepted=False,accountNativePlaybackOrExeAccepted=False,
    pending=['Actual retained Home/Root routing and navigation dispatcher binding','Entry saveable/lifecycle rendering and native Escape/drag acceptance','Actual full Windows EXE delivery'])
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
raw=(json.dumps(dict(schema='raw-artifact-manifest-v1',artifacts=rows),indent=2)+'\n').encode()
wide(OUT/'artifact-manifest.json').write_bytes(raw)
wide(OUT/'README.md').write_text('''# Complete original Miuix Nav source closure

Append all30 original Nav Kotlin sources to the same225-file/195-compiled-source :miuix5157 project, pinned5c91d5e5. Preserve every source byte, original entry/saveable/lifecycle/predictive-back policy and Skiko actuals. Declare the original lifecycle/serialization modules explicitly: actual32 has97 immutable runtime entries, no duplicate normal JVM class names, and retires the prior2.9.6 runtime-compose artifact. Main application class bytes match actual31; source-built UI library changes.

Whole classes32 passes. Zero production overrides:32 actual original navigation-core assertions verify push/pop/CID identity, real serialized sealed routes, state corruption rejection, reconciliation and retained per-entry ViewModelStore cleanup; seven loaded class sources/bytes match the same library. Its complete classfile method-name scan is clean. Original Dock/Frosted/audio fixtures pass120 assertions/38 pointer pairs on the97-entry graph. First fixture error used SnapshotStateList identity equality; only the fixture now reads toList values and preserves the failure.

This proves library/core and isolated Compose scenes. Full Root routes, actual NavigationEventDispatcher/window Escape and drag, native playback/accounts and desktop EXE remain pending. Original Skiko bridge is itself no-op and corner radius0.dp; adding sources alone does not constitute Root input integration. Prepared40 and prior actual30/31 evidence remain unchanged.
''',encoding='utf-8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),manifestSHA256=sha(raw),navAssertions=32,dockAssertions=120)))

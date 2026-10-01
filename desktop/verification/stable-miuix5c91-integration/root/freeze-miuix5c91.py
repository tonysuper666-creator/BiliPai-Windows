from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-miuix5c91-integration';assert not (OUT/'artifact-manifest.json').exists()
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
lane=MAIN/'desktop/.local/stable-miuix5c91-source-audit'
raw=pin(lane/'frozen-handoff.json','023eccf2d42011cd34357017e5a84b812fadf6c1e44be58c4b0b6b13f4ef6c30');m=json.loads(raw)
put('prepared-source/frozen-handoff.json',raw)
for name in ('audit-result.json','install-contract.json','actual-fork-source-pins.json','api-source-review.json','full-source-diff.json','official/fetch-receipt.json','official/commit-5c91.json','original-app-inputs/gradle/libs.versions.toml','original-app-inputs/app/build.gradle.kts'):
    r=next(r for r in m['artifacts'] if r['path']==name);put('prepared-source/'+name,pin(lane/name,r['sha256Bytes']))
for p in sorted(wide(lane/'platform-hunks').glob('*'),key=str):put('prepared-source/platform-hunks/'+p.name,read(p))
for name in ('install-miuix5c91.py','correct-miuix5c91-rail-adapter.py','retain-original-rail-facade.py','prepare-miuix-ui-proof-runner.py','prove-miuix5c91-dock-product.py','miuix5c91-proof-runner-adaptation.json','audit-jvm-method-names.py','jvm-method-name-audit-30.json'):
    put('root/'+name,read(HERE/name))
for p in sorted(wide(HERE/'miuix5c91-install').rglob('*'),key=str):
    if p.is_file():put('root/install/'+p.relative_to(wide(HERE/'miuix5c91-install')).as_posix(),read(p))
for phase in (28,29,30):put('root/classes-'+str(phase)+'.log',read(REPO/f'desktop/.local/stable-build-repair/classes-{phase:02}.log'))
snap=MAIN/'desktop/.local/stable-product-snapshot-30';raw=pin(snap/'manifest.json','e69b7b08805fc7a6aaec8557e8ba1cfd2368b701f99ecb94edc672efd22b6b69');meta=json.loads(raw)
put('root/snapshot30/manifest.json',raw);cp=pin(snap/'ordered-runtime-cp.json',meta['orderedRuntimeClasspathSha256Bytes']);put('root/snapshot30/ordered-runtime-cp.json',cp)
for r in json.loads(cp):pin(r['path'],r['sha256Bytes'])
fork=next(r for r in json.loads(cp) if 'miuix5157-jvm-' in r['path']);assert fork['sha256Bytes']=='e574a17de8136c12212f41c90ff35ba7b46818eef25e83b7c9f9386015da843e'
dock=MAIN/'desktop/.local/stable-miuix5c91-main30-dock-ui-proof';result=json.loads(read(dock/'result.json'))
assert result['passed'] and result['assertions']==120 and result['pointerPairs']==38 and result['productionClassOverrides']==0
for p in sorted(wide(dock).rglob('*'),key=str):
    if p.is_file() and p.suffix in ('.kt','.json','.args','.log'):put('root/actual30-dock/'+p.relative_to(wide(dock)).as_posix(),read(p))
lane=MAIN/'desktop/.local/stable-danmaku-settings-main30-ui-proof'
raw=pin(lane/'frozen-handoff.json','0934f5e71009b8879518ac25133cb46d829073b28b82b947d6eaab87870d5ff0');m=json.loads(raw);put('actual30-settings/frozen-handoff.json',raw)
for r in m['artifactRows']:
    b=pin(r['path'],r['sha256Bytes']);assert len(b)==r['bytes']
    if Path(r['relativePath']).suffix in ('.jar','.class','.png'):excluded.append(r);continue
    put('actual30-settings/'+r['relativePath'],b)
panel=json.loads(read(lane/'acceptance-receipt.json'));assert panel['status']=='PASS' and panel['counts']['assertions']==32 and panel['counts']['pointerPairs']==58
provenance=read(REPO/'desktop/third-party/miuix5157/upstream-provenance.json');source=json.loads(provenance)
assert source['commit']=='5c91d5e5ce1a2fc7e8bdc1258a881c555102bbca' and len(source['files'])==195
put('root/installed-upstream-provenance.json',provenance)
put('root/installed-preferences-producer.py',read(REPO/'desktop/tools/extract-upstream-preferences.py'))
original=read(REPO/'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppNavigationComponents.kt').decode().replace('\r\n','\n')
generated=read(REPO/'desktop/build/generated/preferences/com/android/purebilibili/core/ui/components/AppNavigationComponents.kt').decode().replace('\r\n','\n')
header='// GENERATED from design-system/src/main/java/com/android/purebilibili/core/ui/components/AppNavigationComponents.kt; do not edit.\n// LF-normalized SHA-256: '+sha(original.encode())+'\n'
assert generated==header+original
put('root/original-navigation-facade-byte-identical.kt',generated.encode())
put('root/freeze-miuix5c91.py',read(Path(__file__)))
report=dict(upstreamTag='v0.2.3',upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',originalAppPinnedMiuixCommit=source['commit'],soleProject=':miuix5157',canonicalSourceFiles=195,compiledOriginalSourceFiles=165,
    librarySha256Bytes=fork['sha256Bytes'],wholeClassesPhase=30,wholeClassesPassed=True,oldRailPlatformSubstitutionRetired=True,fullOriginalNavigationFacadeBodyByteIdentical=True,generatedProvenanceHeaderLines=2,
    dockAssertions=120,dockPointerPairs=38,settingsAssertions=32,settingsPointerPairs=58,settingsEditableTextActions=6,
    productionClassOverrides=0,newParallelForkOrExternalLibraryJar=False,all92RuntimeEntriesPinned=True,
    fullOriginalMiuixNavModuleCompiled=False,fullRootNavigationMounted=False,systemWindowOrAccountOrSocketAccepted=False,desktopExeReplaced=False,
    excludedTaskBinaries=excluded,pending=['same-source-project original Nav module closure','Actual Home/native Root integration and EXE delivery'])
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
raw=(json.dumps(dict(schema='raw-artifact-manifest-v1',artifacts=rows),indent=2)+'\n').encode();wide(OUT/'artifact-manifest.json').write_bytes(raw)
wide(OUT/'README.md').write_bytes(b'''# Original v0.2.3 Miuix source upgrade

The app pins Miuix5c91. Upgrade the existing sole :miuix5157 source project to that exact commit: 195 canonical original files /165 compiled Kotlin source files, unchanged existing module wiring and binary dependency graph. Retire only the old5157 expanded=false -> state=null compatibility substitution. The generated v0.2.3 navigation facade now matches original source byte-for-byte. Keep both failed28 and29 build attempts; final whole classes30 passes.

Fresh actual30 immutable product/dependency graph passes the original full Dock/Frosted/audio fixtures (120 assertions, 38 pointer pairs) and the byte-identical full Settings fixture in two styles (32 assertions, 58 pointer pairs, six text edits). Zero production class overrides; all92 runtime entries pinned before and after. These are isolated real Compose scenes with declared fixture ports, not full account/window/native playback or deployed EXE acceptance. Original Nav module is the next same-project closure; it is not compiled by this slice. Historical5157, actual26/27 and prepared451 records remain unchanged.
''')
print(json.dumps(dict(rawArtifacts=len(rows),manifestSHA256=sha(raw),phase=30,dockAssertions=120,settingsAssertions=32)))

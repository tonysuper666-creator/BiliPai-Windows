from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';OUT=REPO/'desktop/verification/stable-original-root-navigation-integration';assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest();rows=[];excluded=[]
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def put(name,data):
 p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists();p.write_bytes(data);rows.append(dict(path=name,sha256Bytes=sha(data),sizeBytes=len(data)))
def register(name,data):
 if Path(name).suffix in ('.jar','.class','.kotlin_module','.pyc'):
  excluded.append(dict(path=name,sha256Bytes=sha(data),reason='Rebuildable proof output'))
 else:put(name,data)
def tree(folder,prefix):
 for p in sorted(wide(folder).rglob('*'),key=str):
  if p.is_file():register(prefix+'/'+p.relative_to(wide(folder)).as_posix(),p.read_bytes())
lane=MAIN/'desktop/.local/stable-home-root-route-assembly-parity';raw=wide(lane/'frozen-handoff.json').read_bytes();assert sha(raw)=='787815173a14836f7c6d3d8c7577318dbd74b83bb90ca56063a00e51cfdade04';put('prepared/frozen-handoff.json',raw);manifest=json.loads(raw);assert len(manifest['artifacts'])==87
for r in manifest['artifacts']:
 data=wide(lane/r['path']).read_bytes();assert sha(data)==r['sha256Bytes'];register('prepared/'+r['path'],data)
for folder in ['original-root-navigation-install46','root-navigation-classes-actual46','root-navigation-classes-actual46-02']:tree(HERE/folder,'root/'+folder)
for name in ['install-original-root-navigation46.py','repair-root-chrome-ownership46.py','prove-root-navigation-classes46.py','freeze-root-navigation46.py','jvm-method-name-audit-46.json']:put('root/'+name,(HERE/name).read_bytes())
put('root/installed-final-extract-upstream-root-home-navigation.py',(REPO/'desktop/tools/extract-upstream-root-home-navigation.py').read_bytes())
for name in ['manifest.json','ordered-runtime-cp.json']:put('root/snapshot46/'+name,(MAIN/'desktop/.local/stable-product-snapshot-46'/name).read_bytes())
for name in ['classes-46-attempt01.log','classes-46.log','export-46.log']:put('root/'+name,(REPO/'desktop/.local/stable-build-repair'/name).read_bytes())
proof=json.loads((HERE/'root-navigation-classes-actual46-02/result.json').read_bytes());assert proof['passed'] and proof['productionOverrides']==0
report=dict(wholeClassesPhase=46,wholeClassesPassed=True,wholeTestSourcesCompiled=True,sourceIdentityCount=935,resourceCount=213,actualRuntimeEntries=97,actualKotlinClasses=11954,invalidJvmMethodNames=0,actualClassDefinitionsProof=proof,productionOverrides=0,fullOriginalMainHostPagerCompiled=True,originalSideBarAndFullDockCompiled=True,typedRootStackHomeProfileCategoryLiveExecutableSources=True,paletteCallbackCapturesImmutableGate=True,soleOriginalChromeRevealProducer=True,fullRootMountedInShell=False,windowFactoryMounted=False,newRuntimeArtifacts=0,newWindowsExeDeployed=False,failedCompiler='Duplicate internal videoCardTransitionChromeReveal was hidden by prospective classpath resolution. Whole compiler rejected duplicate. Remove new emission and consume full original existing Home-card implementation; keep old prepared packet unchanged. Delete only its known stale generated filename through the existing long-path helper.',classProbeFailure='First runner incorrectly equated net61 extra class entries with62 new class definitions. Fixture-only runner corrected; source/classes unchanged.',pending=['Concrete Window/Profile/account factory and actual Shell leaf mount','Full typed VideoDetail parameters and initial offline taskId consumer','Root/Window/native/account acceptance and portable EXE'])
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode());raw=(json.dumps(dict(artifacts=rows,excludedRebuildableOutputs=excluded),indent=2)+'\n').encode();wide(OUT/'artifact-manifest.json').write_bytes(raw)
wide(OUT/'README.md').write_text('''Original MainHost and typed Root navigation source integration
============================================================

Whole classes46 and test-source compilation pass against stable v0.2.3 with935 source identities,213 resources and97 existing runtime entries. Complete original MainHost pager, SideBar/full Dock, navigation host, Home/Profile/Category/Live stack renderers, original chrome/padding/motion and standard/vertical/offline route policies now have compiled executable source bindings. The Home palette callback carries the immutable entry gate. Four new upstream identities are registered once; existing selected source owners are preserved.

Whole compilation caught a duplicate internal chrome-reveal function that prospective classpath compilation had hidden. Root now calls the existing full original Home-card function directly; the new producer omits its duplicate and removes only its exact stale output filename through the long-path helper. The prepared87-artifact source packet remains unchanged. Final installed producer and the failed build are retained explicitly.

Actual46 has11954 Kotlin classes and no invalid JVM method names. Fixture-only Java resolves62 new class definitions/methods/constructors from the immutable product with zero replacements and verified97-entry CP pins. This is class-definition acceptance without initialization, UI rendering or network. The first probe incorrectly expected61 instead of62; its exact invoked runner is retained and corrected separately, without changing product code.

Concrete Window/Profile/account services, physical Shell/typed leaf mounting, complete video/offline entry parameters and real Root/native/account/EXE acceptance remain pending. Compilation and registry counts do not establish functional completion. Raw source/evidence is preserved; rebuildable outputs are excluded.
''',encoding='utf-8',newline='\n');print(json.dumps(dict(rawArtifacts=len(rows),excludedBinaries=len(excluded),manifestSha256Bytes=sha(raw))))

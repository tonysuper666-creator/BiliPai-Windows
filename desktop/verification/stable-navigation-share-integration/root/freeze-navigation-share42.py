from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';OUT=REPO/'desktop/verification/stable-navigation-share-integration'
assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
rows=[];excluded=[]
def put(name,b):
 p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists();p.write_bytes(b)
 rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
def copy_tree(folder,prefix):
 for p in sorted(wide(folder).rglob('*'),key=str):
  if not p.is_file():continue
  name=prefix+'/'+p.relative_to(wide(folder)).as_posix();b=p.read_bytes()
  if p.suffix in ('.jar','.class','.kotlin_module','.dll','.pyc','.obj','.lib','.exp','.pdb'):
   excluded.append(dict(path=name,sha256Bytes=sha(b),reason='Rebuildable compiler/native output'))
  else:put(name,b)
for name,owner,laneName,pin in [('navigation',MAIN,'stable-home-navigation3-host-parity','317a635bc0dd27454f0fcdf351050672fd6f5490a0b4f3195581dc5a9bce625d'),('shareConsent',REPO,'stable-video-share-consent-parity','5f263de98a14de263168a646a0937ba9e3359fa668a7cec1d126d5be489f3fb9')]:
 lane=owner/'desktop/.local'/laneName;raw=wide(lane/'frozen-handoff.json').read_bytes();assert sha(raw)==pin
 put('prepared/'+name+'/frozen-handoff.json',raw);d=json.loads(raw)
 for r in d.get('artifacts',d.get('rawArtifacts',[])):
  relative=r.get('relative',r['path']);p=Path(r['path'])if Path(r['path']).is_absolute()else lane/r['path'];b=wide(p).read_bytes();assert sha(b)==r['sha256Bytes'],p
  if p.suffix in ('.jar','.class','.kotlin_module','.dll','.pyc'):
   excluded.append(dict(lane=name,path=relative,sha256Bytes=sha(b),reason='Rebuildable prepared proof output'))
  else:put('prepared/'+name+'/'+relative,b)
for folder in ['video-share-consent-install','navigation3-host-install','native-media-source-review42','video-share-native-build42','native-media-prepare-actual42','navigation-share-actual42','navigation-share-actual42-02']:
 copy_tree(HERE/folder,'root/'+folder)
for name in ['install-video-share-consent.py','install-navigation3-host.py','review-native-media-build42.py','prove-native-media-prepare42.py','NativeShareConsentFixture.kt','prove-navigation-share42.py','freeze-navigation-share42.py','jvm-method-name-audit-42.json']:
 put('root/'+name,(HERE/name).read_bytes())
snap=MAIN/'desktop/.local/stable-product-snapshot-42'
for name in ['manifest.json','ordered-runtime-cp.json']:put('root/snapshot42/'+name,(snap/name).read_bytes())
put('root/classes-42.log',(REPO/'desktop/.local/stable-build-repair/classes-42.log').read_bytes())
generated=REPO/'desktop/build/generated/native-diagnostic-share'
receipt=json.loads(wide(generated/'producer-receipt.json').read_bytes());assert receipt['resolvedGraphMatchesReviewedBuild']and receipt['freshCompilationOnEveryInvocation']and receipt['stagedDllSha256Bytes']=='89705e2e0b5b146c8fdbccda63dc68bea7e46bfcc7e4aaa50c4cb1efa5331f06'
put('root/gradle-native-producer/producer-receipt.json',wide(generated/'producer-receipt.json').read_bytes())
put('root/gradle-native-producer/producer-input-graph.json',wide(receipt['actualBuildGraph']).read_bytes())
put('root/gradle-native-producer/DesktopNativeDiagnosticShareAssetHash.kt',wide(generated/'kotlin/com/bilipai/desktop/diagnostics/DesktopNativeDiagnosticShareAssetHash.kt').read_bytes())
proof=json.loads((HERE/'navigation-share-actual42-02/result.json').read_bytes());native=json.loads((HERE/'native-media-prepare-actual42/result.json').read_bytes());assert proof['passed']and native['passed']and proof['productionOverrides']==0
report=dict(wholeClassesPhase=42,wholeClassesPassed=True,sourceIdentityCount=926,resourceCount=213,actualRuntimeEntries=97,actualKotlinClasses=11752,invalidJvmMethodNames=0,completeOriginalNavigationHostCompiled=True,completeOriginalShareConsentUiCompiled=True,
 navigationInstalledClassLoadCount=173,navigationClassInitialization=False,navigationGroups=4,shareAssertions=24,shareGroups=4,actorAssertions=proof['actorAssertions'],nativeActorAssertions=proof['nativeActorAssertions'],consentActorAssertions=proof['consentActorAssertions'],actorGroups=3,actualProductClassOrigins=14,productionOverrides=0,
 freshNativeMediaPreparationGroups=7,nativeFormats=3,nativeProbeAvailable=True,verifiedNativeDllSha256Bytes=receipt['stagedDllSha256Bytes'],sameNativeActorAndRegistry=True,
 lifecycleCompileModulesExposed=3,newRuntimeArtifacts=0,fullRootNavigationOrPhysicalWindowAccepted=False,fullHomeRootMounted=False,originalShareConsentRootMounted=False,ShareUI=False,externalReceiver=False,newWindowsExeDeployed=False,
 pending=['Retained original Home/aggregate factory and old planner retirement','Actual Root Window navigation owners, original backstack/geometry and all typed renderers','Same-Store playback authorization and final native/download/cast admission','Wallpaper/theme/file boundaries','Physical Window/real share receiver and final EXE/user acceptance'],
 retainedFailure='First combined fixture failed only because java.util.concurrent and kotlinx.coroutines CancellationException imports were ambiguous; explicit imports repaired fixture, product bytes unchanged')
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode());put('root/compiled-output-exclusions.json',(json.dumps(excluded,indent=2)+'\n').encode())
manifest=(json.dumps(dict(schema='raw-artifact-manifest-v1',artifacts=rows),indent=2)+'\n').encode();wide(OUT/'artifact-manifest.json').write_bytes(manifest)
wide(OUT/'README.md').write_text('''# Original navigation host and Share/Consent — installed source closure

Windows classes42 compiles the original complete776-line navigation host,778-line card morph renderer, entry/backstack/depth/predictive policies, complete share Sheet/friend picker/MoreTargets and127-line Consent UI with explicit Windows ports. Registry926 identities/213 resources preserves existing sole producers and22 DIRECT navigation copies. Three exact Lifecycle2.11.0 modules already present in the immutable runtime97 graph are exposed to the app compile graph; no new runtime artifact, client or persistent store is introduced. Nav121 and Share87 frozen packets are retained, with rebuildable binaries excluded.

Actual immutable42/97 acceptance uses fixture-only compilation and zero production replacements. All173 installed navigation classes load without initialization, with actual product origins checked. The original host private helper preserves real KMP per-entry ViewModelStore/factory/extras and clear isolation. The stack/depth policies and four original settings use the same temporary global store. Original Share4 groups/24 assertions pass QR decode, poster geometry, actual local no-clobber/cancel/cache, existing native Skiko WebP, original private-message error mapping and bounded anonymous owned stream closure. Replies/account values in that fixture are synthetic, without socket or real account access. Historical scope wording is preserved; the outer receipt identifies actual42.

The existing native bridge adds one media preparation export and retains its same bounded Session registry, actual owner dispatcher/callback revoke/drain and token retirement. Fresh controlled MSVC builds,369 header/11 library/64 tool pins and the existing driver/flags agree; Gradle freshly rebuilds and stages DLL89705e2e0b5b146c8fdbccda63dc68bea7e46bfcc7e4aaa50c4cb1efa5331f06 and emits the trusted expected digest. Actual native7 groups validate JPEG/PNG/WebP preparation/state/retirement, rejection and registry8 cap. The real installed native actor and serial diagnostics actor pass25 more assertions: unavailable HWND rejects after actual allocation/retirement, clear allows reuse, old callers and wrong digests reject, same global original consent keys write atomically, basic local error logs remain after disabling, and a canceled queued caller cannot persist consent. Fourteen product-origin observations point to actual product classes. No chooser, receiver delivery, HWND or Root navigation composition is claimed. Successful Windows ShareUI opening alone would still not prove receiver delivery.

The initial combined fixture failed from ambiguous test imports only; its compiler log/source are retained. Explicit imports corrected the fixture while product bytes stayed fixed. Actual Root owners/renderers, original Home factory/old planner retirement, full authorization final publication, wallpaper/theme/file effects and EXE/window/user acceptance remain pending. Source inventory is not feature completion percentage.
''',encoding='utf-8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),excludedBinaries=len(excluded),manifestSha256Bytes=sha(manifest))))

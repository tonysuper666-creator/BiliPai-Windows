from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';OUT=REPO/'desktop/verification/stable-palette-home-window-integration'
assert not OUT.exists();rows=[];excluded=[];sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def put(name,data):
 p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists();p.write_bytes(data);rows.append(dict(path=name,sha256Bytes=sha(data),sizeBytes=len(data)))
def copy_tree(folder,prefix):
 for p in sorted(wide(folder).rglob('*'),key=str):
  if not p.is_file():continue
  name=prefix+'/'+p.relative_to(wide(folder)).as_posix();data=p.read_bytes()
  if p.suffix in ('.jar','.class','.kotlin_module','.dll','.pyc','.obj','.lib','.exp','.pdb'):
   excluded.append(dict(path=name,sha256Bytes=sha(data),reason='Rebuildable compiler/native output'))
  else:put(name,data)
for name,laneName,pin,count in [('palette','stable-wallpaper-palette-parity','9ac7dea50e721b6a445ed2103c15a9c433782a3c92943d4abd0162cb2e19216f',43),('homeRetainer','stable-home-root-mount-parity','4540a111a30feda4e34f5b4d1e598554622db22c52a014c6a5ee0f93ffc6dfc1',47)]:
 lane=MAIN/'desktop/.local'/laneName;raw=wide(lane/'frozen-handoff.json').read_bytes();assert sha(raw)==pin;put('prepared/'+name+'/frozen-handoff.json',raw);records=json.loads(raw)['artifacts'];assert len(records)==count
 for r in records:
  data=wide(lane/r['path']).read_bytes();assert sha(data)==r['sha256Bytes']
  if Path(r['path']).suffix in ('.jar','.class','.kotlin_module','.pyc'):
   excluded.append(dict(path='prepared/'+name+'/'+r['path'],sha256Bytes=sha(data),reason='Rebuildable prepared proof output'))
  else:put('prepared/'+name+'/'+r['path'],data)
for folder in ['palette-home-retainer-install43','root-window-navigation-prepared43','root-window-navigation-prepared43-02','palette-home-window-actual43']:
 copy_tree(HERE/folder,'root/'+folder)
for name in ['install-palette-home-retainer43.py','RootWindowNavigationFixture.kt','prove-root-window-navigation43.py','prove-palette-home-window43.py','freeze-palette-home-window43.py','jvm-method-name-audit-43.json']:
 put('root/'+name,(HERE/name).read_bytes())
for name in ['DesktopRootWindowNavigationOwner.kt','DesktopNavigationHostEnvironment.kt','DesktopOriginalNavigationHost.kt']:
 relative='desktop/src/main/kotlin/com/bilipai/desktop/ui/'+name;put('root/installed-source/'+name,(REPO/relative).read_bytes())
 if name!='DesktopRootWindowNavigationOwner.kt':
  put('root/baseline42/'+name,subprocess.check_output(['git','show','9d743d52:'+relative],cwd=REPO))
first=(HERE/'RootWindowNavigationFixture.kt').read_text(encoding='utf-8').replace('focusableWindowState = false','isFocusableWindow = false',1)
put('root/root-window-navigation-prepared43/reconstructed-exact-invoked-fixture.kt',first.encode())
snap=MAIN/'desktop/.local/stable-product-snapshot-43'
for name in ['manifest.json','ordered-runtime-cp.json']:put('root/snapshot43/'+name,(snap/name).read_bytes())
put('root/classes-43.log',(REPO/'desktop/.local/stable-build-repair/classes-43.log').read_bytes())
proof=json.loads((HERE/'palette-home-window-actual43/result.json').read_bytes());assert proof['passed'] and proof['productionOverrides']==0
report=dict(wholeClassesPhase=43,wholeClassesPassed=True,sourceIdentityCount=927,resourceCount=213,actualRuntimeEntries=97,actualKotlinClasses=11807,invalidJvmMethodNames=0,paletteAssertions=34,paletteGroups=3,homeRetirementGroups=4,homeInstalledClasses=86,physicalUnfocusedWindowAssertions=17,actualProductOrigins=14,productionOverrides=0,oldParallelTodayWatchPlannerRemoved=True,soleOriginalHomePlannerBoundByRequiredRetainer=True,realWindowSavedStateRegistryAndViewModelStore=True,windowOwnerMountedInRoot=False,fullHomeRootMounted=False,originalNavDisplayMounted=False,focusedResumedOrMinimizeAccepted=False,externalHttp=False,personalWallpaperRead=False,accountMutation=False,newWindowsExeDeployed=False,newRuntimeArtifacts=0,
 pending=['Concrete MainHost/Dock and all original typed route renderers in actual Shell','Profile Windows file/theme/window effects','Same-store playback authorization with final native/download/cast publication','Actual complete Window navigation/Home/Profile/player acceptance and portable EXE'],
 fixtureFailure='First prospective fixture used the read-only AWT isFocusableWindow property; corrected to focusableWindowState. Product sources unchanged. Initial compiler log and exact reconstructed invoked fixture retained.',
 runtimeSourceLineEndings='Three semantic Runtime hunks preserve LF content; the original mixed line endings were normalized to LF during exact patch application. The frozen LF source hashes and original raw baseline are retained.')
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode());put('root/compiled-output-exclusions.json',(json.dumps(excluded,indent=2)+'\n').encode())
manifest=(json.dumps(dict(schema='raw-artifact-manifest-v1',artifacts=rows),indent=2)+'\n').encode();wide(OUT/'artifact-manifest.json').write_bytes(manifest)
wide(OUT/'README.md').write_text('''# Original wallpaper palette, retained Home factory and actual window navigation owner

Classes43 passed as one product with927 original source identities,213 resources and the same97 ordered runtime entries. The full original WallpaperPaletteStore is selected through its sole producer, and the existing full VideoCardAdaptiveTintPolicy remains the sole WallpaperPalette schema. The existing Java quantizer is reused; two Java sources retain full AndroidX Target and six original scoring helpers with their source/license receipts. Five original regions, full-image resize before region mapping, color order/fallback, bounded8-entry access-order cache and original defaults are retained. Windows system wallpaper is an explicit real stateless effect, without reading personal wallpaper in the proof. Coil-owned Bitmaps are borrowed and remain open.

The concrete Home factory assembles the already installed original VM, four-page aggregate, CardSession, original request owners, native media lifetime, gallery and return owner. Its required Window/effect/navigation ports remain explicit. DesktopTodayWatchRepository now only serializes original-owner lifetime and projects state; the old parallel history/candidates/refill planner is removed. Three Runtime semantic hunks observe session epochs and retire/drain Home before global storage freeze. Four Shell lifetime hunks prepare an app/window reference and enforce cleanup ordering. The factory is installed but is not yet published or drawn by the actual Shell; its startup bridge waits for the concrete owner rather than inventing fallback recommendations. This intermediate source closure must not be packaged as the final user executable.

DesktopRootWindowNavigationOwner owns a real AWT main-window LifecycleRegistry, ViewModelStore, SavedStateRegistryController, real SavedStateHandle extras/factory and one navigation dispatcher/input. Its actual shown/hidden, active-window/owned-modal and minimized-state reads drive lifecycle on EDT. Closing rejects queued inputs and clears remaining entry stores and dispatchers once. The installed Host now requires and provides the real saved-state owner. Two exact saved-state modules already present in runtime97 are exposed to the compile graph. No fake Android Application or root empty factory is introduced. Full focus/minimize/predictive/Root composition has not been accepted here.

Actual43 fixture-only acceptance has zero production replacements and14 product-origin observations:34 wallpaper assertions in3 groups,4 Home retirement groups and86 installed class loads, and17 checks against a real unfocused AWT window. The window hides/shows through actual events and retains its ViewModel and SavedStateHandle, delivers completed back through the real input, refuses input during retirement, then performs EDT cleanup from an off-EDT close and clears once. Home lifecycle tests use a disclosed scripted consumer to exercise serialized startup/replace/drain/stale projection; they do not claim original VM HTTP, full Root rendering or native playback. Wallpaper uses generated local bands, not personal system files. No account mutation or external HTTP occurred.

Prepared source packets and exact raw receipts are retained. Rebuildable proof JARs/classes are excluded. The first prospective window fixture failed on the AWT read-only property; its log and exact reconstructed invoked fixture are retained, followed by the corrected proof. The Runtime patch normalized mixed line endings to LF; its only behavior changes remain the three documented hunks, with raw baseline and LF hashes preserved. Actual MainHost/Dock/all typed renderers, Profile platform/theme, final playback authorization publication, full Window/player acceptance and desktop portable EXE remain pending. Registry counts are not feature completion percentages.
''',encoding='utf-8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),excludedBinaries=len(excluded),manifestSha256Bytes=sha(manifest))))

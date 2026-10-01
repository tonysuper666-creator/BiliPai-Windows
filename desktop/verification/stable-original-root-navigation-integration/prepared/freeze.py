from pathlib import Path
import hashlib,json,os
H=Path(__file__).resolve().parent
W=H.parents[4]
REPO=W/'work/BiliPai-v023'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
    s=os.path.abspath(p);return Path(s if s.startswith(PREFIX) else PREFIX+s)
def rawsha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def lfsha(p):return hashlib.sha256(safe(p).read_bytes().replace(b'\r\n',b'\n')).hexdigest()
def write(name,obj):safe(H/name).write_text(json.dumps(obj,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
compile=json.loads(safe(H/'compile-11/inputs.json').read_text(encoding='utf-8'))
assert compile['exit']==0
for row in compile['sources']:assert rawsha(row['path'])==row['sha256Bytes'],row['path']
receipt=json.loads(safe(H/'generated/source-receipt.json').read_text(encoding='utf-8'))
registry=json.loads(safe(REPO/'desktop/upstream-sources.json').read_text(encoding='utf-8'))
existing={r['path']:r for r in registry['sources']}
sources={r['path']:r for r in receipt['sources']}
video='app/src/main/java/com/android/purebilibili/data/repository/VideoRepository.kt'
sources[video]={'path':video,'sha256LF':lfsha(REPO/video),'mode':'existing-feature-merge'}
rows=[]
for name,r in sources.items():
    assert lfsha(REPO/name)==r['sha256LF'],name
    row={'path':name,'sha256':r['sha256LF'],'features':['stable-root-home-navigation'],
         'mode':existing[name]['mode'] if name in existing else r['mode'],
         'operation':'merge-feature-preserve-owner-and-mode' if name in existing else 'append-new-identity'}
    rows.append(row)
assert sum(r['operation']=='append-new-identity' for r in rows)==4
write('source-inventory.json',{'target':receipt['target'],'newIdentities':4,'rows':rows,
 'productionDirectCopiedOnlyBySync':['app/src/main/java/com/android/purebilibili/navigation/MainBottomPagerState.kt',
 'app/src/main/java/com/android/purebilibili/feature/download/OfflineVideoRoutingPolicy.kt'],
 'standaloneOnly':'isolated source closure tests explicitly pass --standalone; production omits both DIRECT outputs'})
manuals=sorted((H/'prepared/manual').rglob('*.kt'))
install=[{'source':str(p.relative_to(H)).replace('\\','/'),'target':'desktop/src/main/kotlin/'+p.relative_to(H/'prepared/manual').as_posix(),'sha256LF':lfsha(p)} for p in manuals]
producer=H/'prepared/tools/extract-upstream-root-home-navigation.py'
install.append({'source':producer.relative_to(H).as_posix(),'target':'desktop/tools/'+producer.name,'sha256LF':lfsha(producer)})
write('install-whitelist.json',{'newPayloads':install,'existingTargets':json.loads(safe(H/'existing-hunks-pins.json').read_text(encoding='utf-8')),
 'applyExisting':'existing-hunks.patch only, retain current unrelated Root hunks; never replace Shell/build/registry',
 'excluded':'generated proofs, classes/JARs, duplicate prepared/existing producer copy, stores are not installation payloads'})
safe(H/'GRADLE-SNIPPET.txt').write_text('''// Insert beside extractNavigation3Host. No new dependency.
val extractOriginalRootHomeNavigation by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-root-home-navigation.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-root-home-navigation").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-root-home-navigation.py", "tools/sync-upstream.py",
        "tools/extract-upstream-navigation3-host.py", "tools/extract-upstream-media.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-root-home-navigation" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-root-home-navigation"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-root-home-navigation")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalRootHomeNavigation) }
// Preserve originalHomeProtocols registration; its sole producer now includes the original vertical-video method/cache.
''',encoding='utf-8')
safe(H/'ROOT-INTEGRATION.md').write_text('''# Root Home/navigation source packet A

Target is v0.2.3 commit 3d5d19a2f994daccd0e2f8b5f522b6d82f43d589. This installs executable full original MainHost pager/chrome/stack source, not an already mounted Root. Root must subsequently construct the same retained factory and physical stack in DesktopShell. No Main/Candidate/shared Gradle was edited by this lane.

Install only the seven new payloads in install-whitelist.json, four existing-hunks.patch targets, source-inventory feature merges/four new identities and the Gradle snippet. No entire registry or Shell replacement. The source generator default skips the two DIRECT inputs; Sync retains sole ownership. AppNavigation appears twice in the selection receipt but once in source-inventory. All existing identity modes and other features remain intact.

The six manual interfaces are in prepared/manual/com/bilipai/desktop/ui. DesktopOriginalRootRouteAssembly owns the one SnapshotStateList<BiliPaiNavKey> and exact Return38 metadata; platform.beforeCommit must retire/activate existing native leaf owners without maintaining another stack. DesktopOriginalRootStack mounts the full original Home, MainHost pager, Profile, Category, Live and original Navigation3 display with real Window owners. DesktopOriginalRootChrome mounts the original full side/dock/audio/background, one source capture, real Listen session and the same global preference state. Its required typed ports are deliberately not defaulted to null/noop. RootVideoResolver preserves direct-card versus constructed-route semantics, vertical lookup/cache, all VideoDetail fields and original offline preference. Unknown leaves must be mapped to real existing hosts or declared unavailable, never silently mapped to Home.

DesktopHomeRootWindowBindings.loadWallpaperPalette now takes (DesktopHomeRetainedGate,String,CoroutineScope). Construct per-entry Palette context from the CAPTURED gate; do not read retainer.current for an old page scope. Global imageLoader/appearance/settings/window/consent/locations are app owners, whereas VM/request/Profile/category owners are epoch-bound. Root's current LocalDesktopApplicationImageLoader is required; do not create another Coil client.

Validation: compile-11 is a successful standalone Kotlin/Compose/JVM21 compilation against immutable actual43 strict97. It includes explicit overrides only for original existing HomeVideoProtocol, request interface/facade and captured-palette Root factory. The exact overlap list is compile-11/declared-family-overlap.json (undeclared=[]). This is not zero-product-override nor actual Root mounted acceptance. Earlier compile01/02/04/05/07/09 failures remain as history. No HWND/HTTP/account writes/EXE/package was run. Root current image44/Profile66/Playback45 source must be preserved when applying the next concrete Shell mount.

Known Root consumer gaps to close next: full VideoDetail comment root/target and millisecond resume admission; same physical navigation authority instead of legacy Row/section state; real gallery/current calling Job and native text/image share; real original Home/Profile callbacks; dynamic double-tap real scroll/refresh consumer; favorite/list/audio queue retained coverage. These cannot be satisfied with empty callbacks or flattened page aliases. Firebase transport remains a documented Windows capability gap, independent of real list algorithms.
''',encoding='utf-8')
safe(H/'SCOPE.json').write_text(json.dumps({'prepared':True,'mainIntegration':False,'rootMounted':False,'target':receipt['target'],
 'basis':{'manifestSha256':compile['manifestSha'],'cpSha256':compile['orderedCpSha'],'entries':97},
 'compile':'compile-11','sameOriginalFamilyOverrides':'compile-11/declared-family-overlap.json',
 'noSharedBuild':True,'noNativeWindow':True,'noAccountRequest':True,'noExeAcceptance':True},indent=2)+'\n',encoding='utf-8')
files=[]
for p in H.rglob('*'):
    if not safe(p).is_file() or p.name=='frozen-handoff.json':continue
    if p.suffix.lower() in ('.class','.jar','.dll','.pyc'):continue
    if '__pycache__' in p.parts:continue
    files.append({'path':p.relative_to(H).as_posix(),'sha256Bytes':rawsha(p)})
write('frozen-handoff.json',{'scope':'source-only-root-home-stack-chrome; mounted Shell pending','artifacts':sorted(files,key=lambda r:r['path']),
 'installWhitelist':'install-whitelist.json','compile':'compile-11/inputs.json','sourceInventory':'source-inventory.json'})
print('Frozen',len(files),'raw artifacts;',rawsha(H/'frozen-handoff.json'))

from pathlib import Path
import hashlib,json,os
H=Path(__file__).resolve().parent
MAIN=H.parents[2]
REPO=MAIN.parent/'BiliPai-v023'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def lf(p):return hashlib.sha256(safe(p).read_bytes().replace(b'\r\n',b'\n')).hexdigest()
def write(name,obj):safe(H/name).write_text(json.dumps(obj,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
inputs=json.loads(safe(H/'compile-14/inputs.json').read_text())
assert inputs['exit']==0
for row in inputs['sources']:assert sha(row['path'])==row['sha256Bytes'],row['path']
proof=json.loads(safe(H/'proof-03/proof.json').read_text());assert proof['exit']==0
audit=json.loads(safe(H/'compile-14/class-audit.json').read_text())
assert not audit['undeclared'] and not audit['illegalJvmMethods'] and not audit['crossFacadePublicOrInternalStaticCollisions']
patch_targets=json.loads(safe(H/'existing-hunks-pins.json').read_text())
patch_targets += [json.loads(safe(H/'shell-hunks-pins.json').read_text()),json.loads(safe(H/'main-fullscreen-hunk-pins.json').read_text())]
for row in patch_targets:
 assert lf(REPO/row['path'])==row['baseSha256LF'],row['path']
 assert lf(H/'prepared/existing'/row['path'])==row['desiredSha256LF'],row['path']
new=[]
for p in sorted((H/'prepared/manual').rglob('*.kt')):
 new.append({'source':p.relative_to(H).as_posix(),'target':'desktop/src/main/kotlin/'+p.relative_to(H/'prepared/manual').as_posix(),'sha256LF':lf(p)})
assert len(new)==4
write('install-whitelist.json',{'newManualPayloads':new,'existingExactHunkTargets':patch_targets,
 'patchOrder':['existing-hunks.patch','shell-hunks.patch','main-fullscreen-hunk.patch'],
 'originalSourceDelta':'source-inventory.json','existingFullCopies':'Compilation proof only. Apply exact hunks; do not replace unrelated Root files.',
 'offlineBridge':'Two separately frozen3146 sources already installed by Root; only explicit compile inputs here, not another payload.',
 'noInstall':['classes','JAR','DLL','temporary Store data','prepared/direct (Sync is sole producer)','frozen historical compile source copies']})
registry=json.loads(safe(REPO/'desktop/upstream-sources.json').read_text())
existing={r['path']:r for r in registry['sources']}
paths=[
 ('app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicBackToTopPolicy.kt','append-new-identity','direct'),
 ('app/src/main/java/com/android/purebilibili/navigation/AppNavigation.kt','merge-feature-preserve-mode','reference'),
 ('app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicScreen.kt','merge-feature-preserve-mode','reference'),
 ('app/src/main/java/com/android/purebilibili/data/repository/DynamicRepository.kt','merge-feature-preserve-mode','reference'),
]
source_rows=[]
for path,op,mode in paths:
 if op.startswith('append'):assert path not in existing
 else:assert path in existing
 source_rows.append({'path':path,'sha256':lf(REPO/path),'features':['stable-home-concrete-mount'],
  'mode':existing[path]['mode'] if path in existing else mode,'operation':op})
direct=H/'prepared/direct/com/android/purebilibili/feature/dynamic/DynamicBackToTopPolicy.kt'
assert lf(direct)==source_rows[0]['sha256']
write('source-inventory.json',{'target':'v0.2.3','commit':'3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
 'newIdentities':1,'rows':source_rows,'soleDirectProducer':'existing prepareUpstreamSources Sync; no new extractor/task',
 'reuse':'All complete Home/Profile/Category/Live/Partition/Bangumi/Subscription/Navigation/Dock families come from actual47. No original UI is re-emitted.'})
safe(H/'GRADLE-SNIPPET.txt').write_text('''// No new dependency, producer or task.
// Four manual .kt payloads are in existing desktop/src/main/kotlin.
// Merge source-inventory's one new DIRECT row + three existing feature references.
// prepareUpstreamSources copies DynamicBackToTopPolicy.kt exactly once into existing upstream source set.
// Do NOT add prepared/direct as another sourceDir. Offline3146's two normal manuals are already installed.
''',encoding='utf-8')
safe(H/'ROOT-INTEGRATION.md').write_text('''# Concrete original Root mount packet B

This is an installable source packet against stable v0.2.3/3d5d19a and actual47, not a product runtime or EXE acceptance. Parent Main shared files and Gradle were untouched by this lane. Apply only install-whitelist's four new manuals and twelve exact existing-family hunks. Whole prepared/existing files are compile inputs and desired-byte references, not permission to overwrite newer Root work.

Exact current bases are captured in the three *-pins.json files. Existing-hunks.patch covers RootFactory, Gallery readonly getter, physical RootStack, Return owner, Retainer, retained Gate, actual Timeline, actual UP tab host, registry readonly baseline and Repository. shell-hunks.patch is the concrete ReadyApp replacement; main-fullscreen-hunk.patch only forwards actual WindowState placement getter/setter. Candidate has Root-installed Offline3146 manuals already; do not reinstall another player or use its list as a taskId renderer.

The Shell now physically calls DesktopReadyOriginalRootMount inside its existing actual theme. The one original SnapshotStateList/NavDisplay and full MainHost pager drive Home/Profile/Partition/Category/Live and all installed embedded pages. Old flat discovery Row/sidebar is removed. Native/SMTC section and showVideo are projections, not a second back stack. The full original Dock, sidebar and audio bar remain their existing producers. The old TodayWatch planner was already retired by the installed owner bridge; the actual retained entry is now constructed and installed once per epoch through Runtime recommendations.installOwner. Home remains mounted when a video/settings/favorite/audio leaf covers it.

Window lifecycle, ViewModelStore, SavedState and NavigationEvent use the same actual DesktopRootWindowNavigationOwner, created once for this app Window. Settings/store/theme/Coil/image locations/consent/assets/Window chrome are global owners; Home API/VM/Profile/category children use the captured epoch and same repository Call.Factory. No new HTTP client, cookie jar, list cache, account store, player or native share actor is built. Media wallpaper callback captures DesktopHomeMediaLifetime, not retainer.current. Palette context captures the exact retained gate. Profile uses the existing concrete28 factory and actual14-method same-store Accounts binding. ffprobe comes from the existing bundled/located ffmpeg sibling, not a new resource/runtime.

Shutdown/restore/store-freeze's three existing Root hooks drain DesktopReadyOriginalRootHandle before Runtime/store/global image owner. Compose disposal synchronously closes route admission and calls retainer.retire (no MPV/native join), CAS-clears its exact handle, then drains in a detached NonCancellable task. closeAndJoin joins outside Store/projection locks. Return enter/back now checkpoints before Store/entry/return mutation. The original return geometry/clock/session expressions remain unchanged. The native stop/pause paths enqueue into existing MPV actors. Main fullscreen getter/setter reads actual WindowState; false VideoDetail flag never clears user F11 fullscreen. Only an explicit fullscreen-entry leave preserves the existing restore behavior.

AUTH nav invalidation callback executes only Channel.trySend under the original Store callback. LaunchedEffect consumes it outside that lock. The one new repository helper checks captured epoch, primary MID and Root lifetime under the existing Store admission, applies original sessions.logout there, then calls resetAuthentication after unlock. Retired events are ignored; ordinary user logout is unchanged. This is not another account authority.

Leaf routing preserves typed BVID/CID/resume milliseconds/comment root/target/openId, Story source/seed, favorite collection type/MID/owner, BGM aid/CID/showVideos, Weekly number, Space targetBvid, and actual source ownership. Real existing full Favorites/CommonList retention and queue continuation are used; DownloadList is original and Offline(taskId) actually opens the existing retained native source. Original Dynamic doubletap applies its pinned pure policy to each actual visible Timeline/UP grid, with existing refresh/merge owner. Unread poll uses the existing currentAll's readonly baseline and owner-tagged original API, never advances another cursor.

Evidence: compile14 compiled19 unique inputs (four new manuals, one pinned DIRECT proof, twelve existing source-family overrides, two sibling Offline manuals) against actual47 strict97 SHA-verified before/after; 456classes,236 explicitly declared family overrides, illegal JVM methods0, undeclared0, cross-facade public/internal top-method collisions0. This is intentionally not zero-product-override. proof03 has two Return admission groups (synthetic lock counters over actual prepared owner and product original clock) plus two real in-memory product Store/dispatcher groups with a terminal application interceptor and no socket. The EventListener observes actual cancel outside the Store monitor. proof02's bad fixture main signature, compile13's import/declaration placement failure, earlier narrow failures and class-audit scope mistakes are retained as history. No actual Root HWND, API/account write, process shutdown, package or EXE was exercised here. Parent whole48 and actualWindow fixture are the next acceptance boundary.

Remaining outside this packet: complete original ordinary/vertical VideoDetail and Offline controls are in separate source lanes; current native/comment/source hosts remain useful partial consumers. BangumiPlayer/Live player leaves still existing Windows consumers, including Bangumi preferredAid not yet consumed. History/WatchLater/Following/Liked/Inbox are existing partial Windows pages, not full originals; their list search channels are unavailable/null except the real Favorites receiver. Foreign Following/Liked MID parameters are not yet supported by those legacy self-only consumers. Complete notification/chat/CommentDetail/Aicu/Upower/MemberGuard/open-source declaration/etc leaves remain explicit unavailable routes, not silently aliased to Home. Some settings leaf keys map to existing settings Tree rather than a full original separate stack. Web uses the actual system browser. Firebase original transport and Android physical display corner API remain unavailable; local anonymous consent consumer and original32dp fallback are explicit. Native MPV above Compose, command Popup, PiP, fullscreen and window-focus pointer behavior require product acceptance; compile does not prove them.
''',encoding='utf-8')
write('scope.json',{'prepared':True,'mainIntegration':False,'actualRootMounted':False,'actualWindow':False,'exeAccepted':False,
 'actualBasis':{'snapshot':'stable-product-snapshot-47','manifestSha':inputs['manifestSha'],'cpSha':inputs['orderedCpSha'],'entries':97},
 'finalCompile':'compile-14','sourceInputs':len(inputs['sources']),'classes':audit['candidateClasses'],
 'declaredOverrides':len(audit['exactFamilyOverrides']),'proof':'proof-03','fixtureGroups':4,
 'noSocket':True,'noAccountDisk':True,'noSharedGradle':True})
binary=[];raw=[]
for p in sorted(H.rglob('*')):
 if not safe(p).is_file() or p.name=='frozen-handoff.json' or '__pycache__' in p.parts:continue
 row={'path':p.relative_to(H).as_posix(),'sha256Bytes':sha(p)}
 if p.suffix.lower() in ('.class','.jar','.dll','.pyc'):binary.append(row)
 else:raw.append(row)
write('excluded-runtime-artifacts.json',{'reason':'Private compiled proof artifacts are referenced by hash only, not source installation or Git payload.','artifacts':binary})
raw.append({'path':'excluded-runtime-artifacts.json','sha256Bytes':sha(H/'excluded-runtime-artifacts.json')})
raw=[r for i,r in enumerate(raw) if r['path'] not in {x['path'] for x in raw[:i]}]
write('frozen-handoff.json',{'scope':'prepared concrete original Root/Home/MainHost/NavDisplay mount + same existing leaf owners; actual product Window pending',
 'artifacts':sorted(raw,key=lambda r:r['path']),'installWhitelist':'install-whitelist.json','sourceInventory':'source-inventory.json',
 'finalCompile':'compile-14/inputs.json','classAudit':'compile-14/class-audit.json','narrowProof':'proof-03/proof.json'})
print('FROZEN',len(raw),'raw artifacts;',sha(H/'frozen-handoff.json'))
print('WHITELIST',sha(H/'install-whitelist.json'))

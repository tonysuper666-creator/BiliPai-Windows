from pathlib import Path
import hashlib,json,os,zipfile,subprocess
L=Path(__file__).resolve().parent;R=L.parents[3]/'BiliPai-v023';S=L.parent/'stable-product-snapshot-48'
prefix=chr(92)*2+'?'+chr(92)
def safe(p):
 p=os.path.abspath(p);return Path(p if p.startswith(prefix) else prefix+p)
def read(p):return safe(p).read_text(encoding='utf-8')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def j(p,d):write(p,json.dumps(d,ensure_ascii=False,indent=2)+'\n')
inv=json.loads(read(L/'source-inventory.json'));reg=json.loads(read(R/'desktop/upstream-sources.json'));by={r['path']:r for r in reg['sources']}
for r in inv:
 b=subprocess.check_output(['git','show',r['commit']+':'+r['path']],cwd=R).replace(b'\r\n',b'\n');assert hashlib.sha256(b).hexdigest()==r['sha256LfUtf8']
for p in (L/'producer-replay').rglob('*.kt'):
 expected=L/'prepared/selected'/p.relative_to(L/'producer-replay');assert sha(p)==sha(expected)
cp=json.loads(read(S/'ordered-runtime-cp.json'))
for r in cp:assert sha(r['path'])==r['sha256Bytes']
with zipfile.ZipFile(safe(S/'main-kotlin.jar')) as z:old={n for n in z.namelist() if n.endswith('.class')}
with zipfile.ZipFile(safe(L/'compile-06/candidate.jar')) as z:new={n for n in z.namelist() if n.endswith('.class')}
assert not new&old
patch=subprocess.run(['git','apply','--check',str(L/'consumer.patch')],cwd=R,capture_output=True,text=True)
assert patch.returncode==0,patch.stderr
j(L/'source-audit.json',{'passed':True,'targetCommit':inv[0]['commit'],'originalIdentityPins':len(inv),'sourceSelectionsByteIdenticalToProductionReplay':2,'actual48CpEntries':len(cp),'immutableAllCpShasVerified':True,'newCoreClasses':len(new),'actualProductFqnOverlap':sorted(new&old),'consumerPatchCheck':patch.returncode,'actual48RegistryBaseline':939,'currentObservedRegistry':len(reg['sources']),'currentRegistryShaBytes':sha(R/'desktop/upstream-sources.json'),'originalUiAndVmProducer':'existing extract-upstream-favorites.py; no new CommonList/VM/repository outputs','privateFunctionCollisionScope':'core new namespace and explicit Root-family compile03; no claim newly installed whole-module audit'})
newrow={'path':inv[1]['path'],'sha256':inv[1]['sha256LfUtf8'],'mode':'policy-extract','features':['stable-personal-history-liked']}
assert newrow['path'] not in by
j(L/'registry-delta.json',{'newSources':[newrow],'mergeFeatures':[{'path':inv[0]['path'],'requiredExistingSha256':inv[0]['sha256LfUtf8'],'preserveExistingMode':True,'addFeatures':['stable-personal-history-liked']}],'referenceOnly':[r['path'] for r in inv[2:]],'noWholeManifestReplacement':True})
write(L/'gradle-snippet.kts','''// Insert beside extractOriginalFavorites. No dependency change.
val extractOriginalPersonalLists by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalFavorites)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-personal-lists.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-personal-lists").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-personal-lists.py", "tools/extract-upstream-dynamic-reply-protocol.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-personal-history-liked" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-personal-lists"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-personal-lists")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalPersonalLists) }
''')
wh=[]
for p in sorted((L/'prepared/manual').rglob('*.kt')):
 wh.append({'from':p.relative_to(L).as_posix(),'to':'desktop/src/main/kotlin/'+p.relative_to(L/'prepared/manual').as_posix(),'sha256Bytes':sha(p),'action':'copy sole new manual'})
p=L/'prepared/tools/extract-upstream-personal-lists.py';wh.append({'from':p.relative_to(L).as_posix(),'to':'desktop/tools/'+p.name,'sha256Bytes':sha(p),'action':'copy sole new producer'})
j(L/'install-whitelist.json',{'copy':wh,'patch':{'path':'consumer.patch','sha256Bytes':sha(L/'consumer.patch'),'basePins':'consumer-bases.json','targets':[r['path'] for r in json.loads(read(L/'consumer-bases.json'))],'action':'apply hunks only; prepared/existing compile references never full-copy'},'registry':'registry-delta.json','gradle':'gradle-snippet.kts','generatedReferenceOnly':['prepared/selected','producer-replay'],'noBinaries':True})
j(L/'proof-metadata-correction.json',{'historicalFile':'proof-05/temporary/result.json','historicalBytesSha':sha(L/'proof-05/temporary/result.json'),'correction':'Hardcoded groups=2 was not incremented when the UI group was added. Three GROUP lines are present in run.log. Preserve historical JSON; this corrects reporting only, no new test run.','assertions':19,'groups':3,'pointerPairs':2,'codeSourceOriginalUiAndVm':'actual48 main-kotlin.jar','newCoreProductClassOverrides':0,'RootShellRuntime':False,'HWND':False,'HTTPsocket':False,'userAccountDisk':False})
write(L/'ROOT-INTEGRATION.md','''# Original History / Liked / CoinArchive Root integration

Target stable v0.2.3 commit `3d5d19a2f994daccd0e2f8b5f522b6d82f43d589`. Immutable Main48 manifest `298fd01851352c496f011fef69f9091652c6ba41ea0223a418ee298762cab89d`; CP `2ae45d9e07b65c705807c27ace118cb0f9cdb320a54793b144e80e760f8ed7a1` (97). Prepared handoff, not installed/Main/EXE acceptance.

## Install

Verify all frozen bytes. Copy only four install-whitelist payloads (three manuals and one producer). Apply consumer.patch by exact hunks using consumer-bases.json. Prepared/existing has two explicit compile references, never whole-file install payloads. Keep all physical Root guards, auth-invalid Channel, fullscreen/source publication and later shared changes. Final git apply --check on current Candidate passed.

Registry delta: one NEW SearchArticleNavigationPolicy identity plus existing AppNavigation feature union, preserving existing mode. Existing Favorites owns full CommonListScreen, BaseList/History/Liked ViewModels, repositories, models and protocols; no duplicate output/authority. Reference-only source identities are not registered twice. Current registry may exceed historical Main48 939; never replace it wholesale.

Add gradle-snippet beside Favorites extraction. Producer generates two selections byte-identical to prepared/selected. No new resource/external dependency. Main whole compile and immutable Root runtime acceptance remain Root's next step. Compile03 used seven explicit inputs (three manuals/two selections/two Root family overrides), actual48 friend-module graph, exit0. Core compile06 has 26 new classes with actualProduct overlap0. Runtime proof05 only loads new core + actual48 original UI/VM/protocol, not source-only Root/Shell overrides.

## Concrete seams

DesktopReadyOriginalRootServices gains required trailing library:DesktopLibrary. RootMount leaf gains trailing DesktopPersonalListsRoot, five arguments; Shell already has actual same library. Root remembers personalLists once per retained Home gate using same runtime.store/repository/privacy authority/HazeState. Not a visibility-only or separate user/store owner.

DesktopPersonalListsRoot(gate, repository, globalStore, library, privacyModeEnabled, feedback, globalHazeState) constructs original VMs against installed DesktopFavoriteEnvironment, same Repository captured epoch owned services/cookies/token admission. Cached position comes from actual DesktopLibrary exact CID. HistoryRefreshBus/WatchLaterRefreshBus remain original sole buses.

DesktopOriginalPersonalListHost(entry, bindings, navigation, onBack, onUp, onOpenHistorySearch, revealQueue, onPlayAllAudio, historySearchChannel, historyScrollToTopChannel, globalHazeState, isCurrentPage) calls entire installed CommonListScreen unchanged. Same global Favorite/Home/Navigation/back-to-top preferences, actual category port, original text-share actor and queue actor. History initial load follows original active-page effect; full original filtering/search/cursor/delete/clear/batch/dissolve behaviors remain installed algorithm, no new list implementation.

Shell recognizes History/HistorySearch/LikedVideos typed keys before old PersonalContentScreen. Liked preserves target mid/ownerName/isCoinArchive, including another UP. Full original AppNavigation History callback preserves BVID/CID/resume ms/vertical/source route and dispatches PGC/course/Live/Article through current typed Root commands. Existing FavoriteQueueBridge retained per list entry consumes actual Controller/Listen ownership; queue reveal avoids ordinary reload after queue open. Dock search uses retained actual History channel.

Actual stack prune retires popped HistorySearch/Liked but keeps MainHost History across tab changes and Video/audio coverage. Close joins personal jobs before Home drain, outside Store locks. Same MID new credentials retire old epoch owner; late old search cannot publish or borrow new cookies. App/window/restore lifetime remains current Root lifetime.

## Evidence and limitations

Proof05 passes 19 assertions/3 groups and two actual offscreen pointer pairs. Full original API/DTO/VM/CommonList code sources are actual48 main-kotlin.jar. Fake credentials are only actual in-memory SessionStore; task-owned PluginStore/Library and terminal application interceptor make no sockets/account disk changes. Covers original cursor/type routes, foreign UP/Coin API, CID/resume/vertical, actual refresh bus, covered-entry/pop and delayed same-MID retirement. Material3 History and Liked PNGs retained. No second large theme matrix. Metadata-correction preserves original hardcoded groups=2 result and records three actual console groups. Failed compilation/UI history is preserved: missing Kotlin module packaging and fixture mandatory bottom locals, not product fixes.

Article transport uses full original three selected policy declarations with cancellable same owned Call.Factory HEAD. It inherits Root redirect behavior instead of Android's followRedirects(false) new client. Pinned original pure policy always chooses NativeArticle for positive CV IDs, so navigation behavior is preserved; HEAD redirect transport equivalence is not claimed.

No physical Root route/HWND/live account/HTTP/native playback/packaged EXE or all-personal parity claim. Full WatchLaterScreen, FollowingListScreen, Inbox/Chat/Notifications and standalone CommentDetail remain next slices; legacy WatchLater/Following leaves explicitly unchanged here.
''')
print('source replay/97 CP/new26 overlap0/patch all PASS; install4 + 2 targets + 1 new identity; current registry',len(reg['sources']))

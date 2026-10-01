from pathlib import Path
import hashlib,json,subprocess,re
HERE=Path(__file__).resolve().parent
MAIN=next(p for p in HERE.parents if (p/'.git').exists())
STABLE=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def sha(b):return hashlib.sha256(b).hexdigest()
def write(name,value):
    (HERE/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def original(path):
    data=subprocess.run(['git','show',COMMIT+':'+path],cwd=STABLE,capture_output=True,check=True).stdout.replace(b'\r\n',b'\n')
    blob=subprocess.run(['git','rev-parse',COMMIT+':'+path],cwd=STABLE,capture_output=True,check=True).stdout.decode().strip()
    return dict(path=path,commit=COMMIT,gitBlob=blob,sha256LF=sha(data)),data.decode()
oldpath=MAIN/'desktop/.local/stable-home-page-parity/backend-audit/backend-contract.json'
old=json.loads(oldpath.read_text(encoding='utf-8'))
paths=[s['path'] for s in old['sources']]+[
 'app/src/main/java/com/android/purebilibili/data/repository/DynamicRepository.kt',
 'app/src/main/java/com/android/purebilibili/data/repository/BlockedUpRepository.kt',
 'app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt',
 'app/src/main/java/com/android/purebilibili/feature/home/HomeNotInterestedPolicy.kt',
 'app/src/main/java/com/android/purebilibili/feature/home/TodayWatchCandidatePoolPolicy.kt',
 'app/src/main/java/com/android/purebilibili/feature/home/TodayWatchRefreshPolicy.kt',
 'app/src/main/java/com/android/purebilibili/feature/home/TodayWatchQueuePolicy.kt',
]
sources=[];texts={}
for p in paths:
    pin,text=original(p);sources.append(pin);texts[p]=text
for s in old['sources']:
    assert next(p['sha256LF'] for p in sources if p['path']==s['path'])==s['sha256LF']
vm=texts[paths[0]];lines=vm.splitlines(True)
assert len(lines)==2119,len(lines)
partitions=[
 (1,278,'Pure conversion/filter/refresh helpers','FeedKind, distinct-by-key, recommendation->TodayWatch conversion, valid/blocked/feedback/builtin/JSON filter ordering, recommend idx and undo snapshot policies.'),
 (279,383,'Sole state and jobs','One HomeUiState, category/popular caches and selectors; refreshIdx, livePage/hasMore, undo/initial/user/unread jobs, TodayWatch history/expanded/buildGeneration/consumed/negative feedback sets and feedback Channel.'),
 (384,457,'Startup observers','Collect real incrementalTimelineRefresh/homeRefreshTipVisible, shared blocked-UP list, await both AD and feed plugin readiness; replace per-instance TodayWatch config observer, rebuild only when eligible; start category load.'),
 (458,554,'Refilter and runtime configuration','Refilter category caches and live UID lists, synchronize current legacy projections; real plugin enabled/config->full card config, disabled cancels rebuild and clears plan/cache.'),
 (555,841,'Complete TodayWatch planner','Mode/collapse/only-refresh, generation replacement, current recommendations as base, cursor history, creator profile and eye-care signals, original RecommendationPluginApi request, local then expanded result/cache, cancellation and stale-generation rejection.'),
 (842,1025,'Category/selection/dissolve/consume','Cancel previous initial category job; restore per-category/popular cache, guest LIVE chooses POPULAR, original dissolve/current-category removal and pending refilter, TodayWatch consume and refill, independent live/popular selection.'),
 (1026,1178,'Watch-later and feedback mutations','Original local feedback first, block-local then visual transition, async blocked remote / recommendation server feedback; preserve Channel success/failure copy. BlockCreator result updates one blocked authority.'),
 (1179,1395,'Initial/manual refresh/undo/loadmore/unread','Loading cleanup for category cancellation; manual category sync and recommend snapshot, post-fetch refresh key/boundary/anchor, 5s undo timeout, category loading/hasMore guards; deduplicated user/unread jobs and sum of two original unread responses.'),
 (1396,1692,'Ordinary feeds and state reducers','Distinct RECOMMEND idx/POPULAR four branches/region tid; manual paged-feed policy; after-await cancellation; Default filter stage; incoming duplicate/odd-count handling; append/prepend/replace, keep-old bound, page/hasMore policy, active-category legacy projection.'),
 (1693,1905,'FOLLOW scope/merge and raw mapping','HOME_FOLLOW/video, baseline probe and optional second full replacement; DynamicFeedFetchResult updateNum/usedBaseline/nextOffset/hasMore, raw archive-only mapper preserves dynamicId and original key/duration/stat parsing.'),
 (1906,1991,'LIVE','Initial NAV and authenticated followed rooms plus popular rooms; loadmore only popular, roomid dedup and blocked UID; original error/empty/hasMore outcomes.'),
 (1992,2119,'NAV/following-cache/preview','Original NAV UserState and account context/unread/following flow; one-hour MID-keyed following_cache, unbounded pages ps50 until empty/short/error; owned preview URL result.'),
]
algorithm=[]
for a,b,title,detail in partitions:
    algorithm.append(dict(startLine=a,endLine=b,title=title,contract=detail,partitionSha256LF=sha(''.join(lines[a-1:b]).encode())))
assert partitions[0][0]==1 and partitions[-1][1]==len(lines)
assert all(partitions[i][1]+1==partitions[i+1][0] for i in range(len(partitions)-1))
candidatePaths=[s['path'] for s in old['currentCandidateSources']]+[
 'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopTodayWatchRepository.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginRuntime.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginStore.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopSessionStore.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopRepository.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopCommunityRepository.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopSocialRepository.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopHomeCardMetadataRepository.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DiscoveryScreens.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/PluginProviderScreens.kt',
 'desktop/build/generated/dynamic-settings/com/android/purebilibili/data/repository/DesktopOriginalDynamicTimelineRepository.kt',
]
candidate=[]
for p in candidatePaths:
    data=(STABLE/p).read_bytes();candidate.append(dict(path=p,sha256Bytes=sha(data),sha256LF=sha(data.replace(b'\r\n',b'\n')),scope='Read-only current sibling source; not newly compiled by this audit'))
ports=old['originalReadPorts']+[
 dict(originalRepository=paths[11],member='getDynamicFeed',sourceLine=57,signature='suspend fun getDynamicFeed(refresh:Boolean=false, scope:DynamicFeedScope=DynamicFeedScope.DYNAMIC_SCREEN, type:String="all", incrementalRefresh:Boolean=false):Result<DynamicFeedFetchResult>'),
 dict(originalRepository=paths[11],member='currentUpdateBaseline',sourceLine=199,signature='fun currentUpdateBaseline(scope:DynamicFeedScope=DynamicFeedScope.DYNAMIC_SCREEN,type:String="all"):String'),
 dict(originalRepository=paths[11],member='hasMoreData',sourceLine=426,signature='fun hasMoreData(scope:DynamicFeedScope=DynamicFeedScope.DYNAMIC_SCREEN,type:String="all"):Boolean'),
 dict(originalRepository=paths[11],member='syncPaginationAfterRefresh',sourceLine=433,signature='fun syncPaginationAfterRefresh(scope:DynamicFeedScope,type:String="all",offset:String,updateBaseline:String="",hasMore:Boolean=true)'),
 dict(originalRepository='app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt',member='getFollowings',sourceLine=1417,signature='suspend fun getFollowings(vmid:Long,pn:Int=1,ps:Int=50,orderType:String=""):FollowingsResponse'),
 dict(originalRepository='app/src/main/java/com/android/purebilibili/data/repository/ActionRepository.kt',member='toggleWatchLater',sourceLine=927,signature='suspend fun toggleWatchLater(aid:Long,add:Boolean):Result<Boolean>'),
 dict(originalRepository='app/src/main/java/com/android/purebilibili/data/repository/ActionRepository.kt',member='submitRecommendationFeedback',sourceLine=115,signature='suspend fun submitRecommendationFeedback(metadata:RecommendationFeedbackMetadata,reason:RecommendationFeedbackReason):Result<Unit>'),
 dict(originalRepository='app/src/main/java/com/android/purebilibili/data/repository/BlockedUpRepository.kt',member='getAllBlockedUps',sourceLine=302,signature='fun getAllBlockedUps():Flow<List<BlockedUp>>'),
 dict(originalRepository='app/src/main/java/com/android/purebilibili/data/repository/BlockedUpRepository.kt',member='blockUp',sourceLine=306,signature='suspend fun blockUp(mid:Long,name:String,face:String)'),
 dict(originalRepository='app/src/main/java/com/android/purebilibili/data/repository/BlockedUpRepository.kt',member='blockUpWithBilibiliSync',sourceLine=311,signature='suspend fun blockUpWithBilibiliSync(mid:Long,name:String,face:String,relationSource:BlockedUpRelationSource=BlockedUpRelationSource.PROFILE):BlockedUpWriteResult'),
]
contract=dict(status='READ_ONLY_COMPLETE_VM_BACKEND_CONTRACT_NOT_PRODUCTION',pinnedCommit=COMMIT,originalVmLines=len(lines),priorContract=dict(path=str(oldpath),sha256Bytes=sha(oldpath.read_bytes())),originalSources=sources,currentCandidateSources=candidate,algorithmPartitions=algorithm,exactServicePorts=ports,
 requiredRootPorts=[
  dict(family='Owner',required=['Root existing CoroutineScope tied to Home page/account generation','current MID + same DesktopSessionStore generation, isLoggedIn from actual credentials','stillOwned/assertOwned and atomic withOwnedAdmission for every local persistence and UI receipt'],contract='Capture epoch and request generation before dispatch; ensureActive plus owner check after every await and CPU stage. Same MID replacement is a new epoch. Retire old jobs/observers, never reuse an old following or feedback context.'),
  dict(family='Settings',required=['Flow<Boolean> incrementalTimelineRefresh','Flow<Boolean> homeRefreshTipVisible','actual same Home settings/context'],contract='Use existing canonical settings/global Store. No empty Flow/noop/default false. Context/AndroidViewModel/Application/Toast/Analytics are explicit platform seams; retain algorithms and copy.'),
  dict(family='Feeds',required=['the 13 original Result read signatures above','owned same BilibiliApi + Root WBI and accessToken credentials','same original DynamicTimelineRepository registry referenced by HOME_FOLLOW/video'],contract='Do not turn idx into page without explicit idx+1 adapter, nor collapse all four Popular branches. HOME_FOLLOW is separate original scope and must not reset DYNAMIC_SCREEN all/video cursor.'),
  dict(family='Plugins/planner',required=['real PluginManager pluginsFlow, actual TodayWatchPlugin configState and setters','AD/FILTER awaitPluginReady barrier, builtin then JSON filter','actual RecommendationPluginApi and original request/result types','same feedback/profile context and actual eye-care night signal'],contract='Root runtime.recommendations already owns history/expanded/base/consumed/refill. Choose one planner authority when selected original VM is installed; either move consumers to original VM-owned actor or delegate complete original planner with current-feed candidates. Do not instantiate two rebuilding caches/plans.'),
  dict(family='Mutations',required=['same watch-later original endpoint with captured epoch','canonical local feedback write + local blocked store write','separate owned original recommendation server feedback and remote blocked sync','owned feedback channel/snackbar callback and shared animation manager'],contract='Preserve local feedback -> pending refilter/dissolve -> async remote result. Existing Discovery.notInterested combined await cannot be used as a drop-in before starting original transition, or double-record the same feedback.'),
  dict(family='Following/profile',required=['real NavData Result + SessionStore account projection update','same original two unread Result methods','raw getFollowings page response','following_cache storage read/write primitive StringSet/Long on same canonical Store'],contract='Original one-hour keys following_mids_$mid/following_time_$mid, ps50 unbounded loop until original stop conditions. Preserve actual account isolation and atomic epoch gate. Existing DesktopPluginPreferences lacks StringSet/Long, so a typed value adapter is required; do not create another preferences file or account store.'),
 ],
 directReuse=['HomeUiState full original immutable schema; reference existing VideoItem/LiveRoom/NavData/HistoryResult/FollowingsResponse/Recommendation models','Original 9 pure VM helpers and mode/result/creator conversion functions plus HomeNotInterested/TodayWatch policies, distinct merge helpers and totalMessageUnreadCount; use existing FQN instead of duplicate generation','Existing DesktopOriginalDynamicTimelineRepository getDynamicFeed and syncPaginationAfterRefresh are scope-aware. Original currentUpdateBaseline/hasMoreData getters are not yet exposed on this selected Desktop class and require two complete original member selections on the sole producer, using the same private feedPagination.','Same Root PluginManager/TodayWatchPlugin/RecommendationPluginApi/JSON filter/TodayWatchFeedbackStore/ProfileStore already have Windows context','DesktopHomeCardMetadataRepository.addWatchLater(aid,expectedSessionEpoch) actual owned endpoint can supply result mapping','Existing DesktopDiscoveryRepository HTTP/WBI/app signing and region/popular/weekly transport primitives; original HomeVideo request/filter semantics still required'],
 followRawPageSeam=dict(path='desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunityDynamicScreens.kt',lines='63-67',contract='Existing raw callback wraps DynamicFeedResponse(data=community.dynamicFeed(requestType,offset,baseline).data); CancellationException rethrows, BiliApiException maps code/message. HOME_FOLLOW adapter may reference this transport recipe with captured same owner/epoch, never replace original cursor algorithm with the Community DynamicPage mapper.'),
 plannerDelegation=[
  dict(existingMember='state:StateFlow<DesktopTodayWatchState>',target='Derived StateFlow from the one original HomeUiState.todayWatchPlan/todayWatchLoading/todayWatchError. The facade holds no independently mutable planner list/cache/consumed state.',required='Root exposes actual VM state to the existing PluginProviderScreens consumer; notice/error mapping must preserve source copy, not invent an empty successful plan.'),
  dict(existingMember='suspend fun reload(forceHistory:Boolean=false)',target='One owned VM entry forwarding to original rebuildTodayWatchPlan(forceReloadHistory) under that VM scope, using its existing recommend cache/refreshIdx. Public bridge is required because the original function is private.',required='Do not instantiate old DesktopTodayWatchRepository for this call. Preserve the original generation replacement/cancellation and base/current-feed candidate source; raw mode/collapse/config observer already belongs to the VM.'),
  dict(existingMember='suspend fun consume(bvid:String):Boolean',target='Forward to the original VM consumed-set/plan update/refill actor. Original public markTodayWatchVideoOpened(video) is Unit and requires VideoItem; supply the real plan/current-feed item, or expose an owned BVID bridge around that exact body.',required='If Boolean shouldRefill is still needed by old callers, return the original consumeVideoFromTodayWatchPlan update.shouldRefill from the same actor; do not maintain a facade consumed set.'),
  dict(existingMember='internal suspend fun accountChanged(epoch:Long)',target='Retire old Home VM owner/jobs/config observers/caches and swap facade binding to a fresh VM for actual new epoch, even when MID is unchanged.',required='Keep sole SessionStore and canonical feedback/profile account context; no old epoch feedback/following-cache/plan receipt or revival by facade.'),
  dict(existingMember='internal suspend fun shutdownForRestore()',target='Stop facade admission, retire current VM actor, cancel and await its rebuild/observer/background request jobs before shared Store freeze/restore.',required='Existing runtime shutdown consumer calls this typed lifecycle port; never leave old DesktopTodayWatchRepository refill scope or cache alive as a second authority.'),
 ],
 findings=[
  dict(priority='required',anchor='HomeViewModel.kt:1717-1829',finding='HOME_FOLLOW/video baseline probing and second request are an actual distinct algorithm; generic Community.dynamicFeed is only raw page transport and cannot replace this entire closure.'),
  dict(priority='required',anchor='HomeViewModel.kt:606-824; DesktopTodayWatchRepository.kt:25-152; PluginProviderScreens.kt:171-179',finding='Original Home plan builds from its current recommend cache and refreshIdx+1; existing Desktop service reload fetches a new recommend page and owns consumed/caches. Must converge a single planner actor/state before consumers can claim original Home plan parity.'),
  dict(priority='required',anchor='HomeViewModel.kt:1037-1096; DesktopDiscoveryRepository.kt:143-174',finding='Original visual transition precedes server completion and remote failure retains local action. Existing combined method waits remote before returning, and calling it after original local save also duplicates local mutation.'),
  dict(priority='required',anchor='HomeViewModel.kt:2047-2107; DesktopPluginStore.kt:preferences/update; DesktopPluginPreferences',finding='Following cache requires StringSet + Long; current Android-like plugin preference wrapper only Boolean/Int/String, while canonical Json global Store can support a thin original-key value adapter.'),
  dict(priority='required',anchor='DesktopDiscoveryRepository.kt:45-113; DiscoveryScreens.kt:230',finding='Existing ordinary feed read does not recheck epoch after result or Default filtering; new selected Home commit adapter must deny old-source receipts rather than relying only on ensureSession.'),
  dict(priority='source behavior',anchor='HomeViewModel.kt:1196-1266,1338-1361,1906-1990',finding='Original refresh sets busy inside launch without finally; loadMore admission also occurs before launch, livePage is reset but no advance is visible in this VM. Preserve this as original behavior inventory and flag any Windows cancellation/request adaptation explicitly; do not silently invent paging/success.'),
 ],
 boundary='No VM producer/UI renderer/cache/client/Store production emitted. No Gradle/compiler/HTTP/account/GUI. Root/pinned current source inspection only; Parent uniquely owns eventual original Home VM producer.')
write('backend-contract.json',contract)
write('source-pins.json',dict(original=sources,candidate=candidate))
write('algorithm-partitions.json',algorithm)
write('method-anchors.json',[dict(name=m.group(1),line=vm.count('\n',0,m.start())+1) for m in re.finditer(r'(?m)^\s*(?:private |internal |suspend |override )*fun\s+(?:<[^>]+>\s*)?([\w.]+)\s*\(',vm)])
write('evidence-manifest.json',dict(status=contract['status'],artifacts=[dict(path=p.name,sha256Bytes=sha(p.read_bytes()),bytes=p.stat().st_size) for p in sorted(HERE.iterdir()) if p.is_file() and p.name!='evidence-manifest.json'],boundary=contract['boundary']))
print(json.dumps(dict(originalSources=len(sources),candidateSources=len(candidate),partitions=len(algorithm),servicePorts=len(ports),manifestSha256Bytes=sha((HERE/'evidence-manifest.json').read_bytes())),indent=2))

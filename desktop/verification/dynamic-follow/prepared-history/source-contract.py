from pathlib import Path
import hashlib,importlib.util,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def ext(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def read(p):return ext(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
spec=importlib.util.spec_from_file_location('follow_audit_media',REPO/'desktop/tools/extract-upstream-media.py');media=importlib.util.module_from_spec(spec);spec.loader.exec_module(media);parser=media.parser_for(REPO)
checks=[]
def check(name,result):assert result,name;checks.append(dict(name=name,passed=True))
app=HERE/'original/app/src/main/java/com/android/purebilibili'
generated=HERE/'candidate/generated/com/android/purebilibili'
check('exact original event data class',media.data_class(read(app/'data/repository/ActionRepository.kt'),'FollowStateChange',parser) in read(generated/'data/repository/DesktopOriginalFollowStateChange.kt'))
check('exact original state data class only transient input-output',media.data_class(read(app/'feature/dynamic/DynamicViewModel.kt'),'DynamicUiState',parser) in read(generated/'feature/dynamic/DesktopOriginalDynamicFollowStatePolicy.kt'))
for name in ['resolveDynamicStateAfterAuthorUnfollow','resolveFollowedUsersAfterAuthorUnfollow','timelinePage','updateDynamicTimelinePage','mapDynamicTimelineItems','copyActiveTimelinePage']:
 check('exact original reducer '+name,media.function(read(app/'feature/dynamic/DynamicScreenStatePolicy.kt'),name,parser)==media.function(read(generated/'feature/dynamic/DesktopOriginalDynamicFollowStatePolicy.kt'),name,parser))
for kind,folder,names in [('Timeline','dynamic-settings',['getDynamicFeed','syncPaginationAfterRefresh','fetchDynamicFeedPageWithRetry']),('User','dynamic-tabs',['getUserDynamicFeed','fetchDynamicFeedPageWithRetry','buildSelectedUserDynamicFeedParams'])]:
 filename='DesktopOriginalDynamic'+kind+'Repository.kt';before=read(HERE/'baseline/desktop/build/generated'/folder/'com/android/purebilibili/data/repository'/filename);after=read(generated/'data/repository'/filename)
 for name in names:check('exact existing '+kind+' repository '+name,media.function(before,name,parser)==media.function(after,name,parser))
 extra=after[after.index('    /** One request') if kind=='Timeline' else after.index(' fun checkpointForFollowChange'):after.index('    private suspend fun getPage' if kind=='Timeline' else ' fun hasMore')]
 check(kind+' added checkpoints own no cursor map or items', 'stateBy' not in extra and 'DynamicItem' not in extra and 'restoreAfterFollowChange' in extra)
candidate=HERE/'candidate/desktop/src/main/kotlin/com/bilipai/desktop'
repository=read(candidate/'data/DesktopRepository.kt');before=read(HERE/'baseline/desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopRepository.kt')
check('Repository only adds single event source',repository.replace('    internal val followStateEvents = DesktopFollowStateEvents(sessions)\n','')==before)
social=read(candidate/'data/DesktopSocialRepository.kt');method=media.function(social,'setFollowing',parser)
check('capture owner before queue',method.index('requireOwner()')<method.index('withContext(Dispatchers.IO)'))
check('API check then cancel check then original success event',method.index('check(followingApi(owner).modifyRelation')<method.rindex('currentCoroutineContext().ensureActive()')<method.index('FollowStateChange(mid = mid, isFollowing = follow)'))
check('same owner guarded transport and cookies','cookieJar(ownerJar)' in social and 'events.withCurrent(owner) { originalJar.loadForRequest(url) }' in social and 'addNetworkInterceptor' in social and '.retryOnConnectionFailure(false)' in social)
check('all other existing Social methods unchanged',all(media.function(read(HERE/'baseline/desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopSocialRepository.kt'),name,parser)==media.function(social,name,parser) for name in ['createFavoriteFolder','deleteFavoriteFolder','setFavorite','setWatchLater','removeFavoriteResource','videoRelation','setLike','giveCoins','userProfile','removeBlacklist','commentPage','commentReplies','publishComment']))
registry=read(candidate/'ui/DesktopDynamicCardStateRegistry.kt');apply=media.function(registry,'applyFollowStateChange',parser)
check('event owner and session authority','event.owner != captured' in apply and 'guard.withCurrentDynamicCacheOwner(captured)' in apply and '!stillOwned()' in apply)
check('only concrete original-home owners','filterIsInstance<DesktopDynamicUsersState>' in apply and 'filterIsInstance<DesktopDynamicTimelineState>' in apply and 'mutateDynamicItems(' not in apply)
check('unique cache owner existing currentAll','currentAll?.get()' in apply and 'persistCurrentItems()' in apply and 'DesktopDynamicCache(' not in registry)
for name in ['DesktopDynamicUsersState.kt','DesktopDynamicTimeline.kt']:
 s=read(candidate/'ui'/name)
 check(name+' transient reducer no retained DynamicUiState', 'var state' not in s and 'private var uiState' not in s and 'MutableStateFlow<DynamicUiState>' not in s)
 check(name+' version barrier and sole cursor rollback','@Volatile private var followStateRevision' in s and 'checkpointForFollowChange' in s and 'restoreAfterFollowChange' in s)
events=read(candidate/'data/DesktopFollowStateEvents.kt')
check('original shared flow capacity and replay semantics','MutableSharedFlow<DesktopOwnedFollowStateChange>(extraBufferCapacity = 32)' in events)
check('original tryEmit false does not turn server success into failure','mutableChanges.tryEmit(DesktopOwnedFollowStateChange(owner, change))' in events and 'check(mutableChanges.tryEmit' not in events and '队列已满' not in events)
check('no observer-owned items/cache','DynamicItem' not in events and 'DesktopDynamicCache(' not in events)
session=read(candidate/'ui/DesktopDynamicCardSession.kt')
check('Root session collector filters epoch','isOwned() && event.owner.epoch == expectedEpoch' in session and 'registry.applyFollowStateChange(event)' in session)
output=dict(passed=True,checks=checks,checkCount=len(checks),baselineManifestSha256Bytes=sha(HERE.parent/'dynamic-editor-main-product-snapshot-01/manifest.json'),MainIntegrated=False)
(HERE/'source-contract-result.json').write_text(json.dumps(output,indent=2)+'\n',encoding='utf-8');print('PASS',len(checks),'source contracts')

from pathlib import Path
import hashlib, json, difflib, sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent; REPO=HERE.parents[2]
BASE=HERE.parent/'dynamic-editor-main-product-snapshot-01'
def ext(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def save(p,s):ext(p.parent).mkdir(parents=True,exist_ok=True);ext(p).write_text(s,encoding='utf-8',newline='\n')
def change(s,a,b):assert s.count(a)==1,(a[:90],s.count(a));return s.replace(a,b)
assert sha(BASE/'manifest.json')=='bc6cf9ef064ba68f28f36c3bbf2b59721629405567c7ced7e4799a844ff7b062'
assert sha(BASE/'ordered-runtime-cp.json')=='515bf598f56b27de45aa04d63493e1f8ad38b7e4710ef358fea97b9fc9d620c1'
baselineManifest=json.loads((BASE/'manifest.json').read_bytes())
pins={r['path']:r for r in baselineManifest['sourceFiles']}
sources=['data/DesktopRepository.kt','data/DesktopSocialRepository.kt','ui/DesktopDynamicUsersState.kt',
 'ui/DesktopDynamicTimeline.kt','ui/DesktopDynamicCardSession.kt','ui/DesktopDynamicCardStateRegistry.kt']
receipt=[]
for short in sources:
 p='desktop/src/main/kotlin/com/bilipai/desktop/'+short; assert sha(REPO/p)==pins[p]['sha256Bytes'],p
 raw=(REPO/p).read_bytes(); dest=HERE/'baseline'/p;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(raw)
 receipt.append(dict(path=p,sha256Bytes=sha(REPO/p),sha256Lf=hashlib.sha256(raw.replace(b'\r\n',b'\n')).hexdigest()))
# Exact brace-balanced declarations; full originals remain pinned alongside each extraction.
def decl(s,marker):
 start=s.index(marker);start=s.rfind('\n',0,start)+1;begin=s.index('{',start);depth=0
 # These selected declarations have no braces in strings/comments before their body end.
 for i in range(begin,len(s)):
  depth+=(s[i]=='{')-(s[i]=='}')
  if depth==0:return s[start:i+1]
 raise AssertionError(marker)
app='app/src/main/java/com/android/purebilibili/'
actionPath=app+'data/repository/ActionRepository.kt';vmPath=app+'feature/dynamic/DynamicViewModel.kt';policyPath=app+'feature/dynamic/DynamicScreenStatePolicy.kt'
action=(REPO/actionPath).read_text(encoding='utf-8');vm=(REPO/vmPath).read_text(encoding='utf-8');policy=(REPO/policyPath).read_text(encoding='utf-8')
for p in [actionPath,vmPath,policyPath,app+'feature/dynamic/DynamicIncrementalRefreshPolicy.kt']:
 dest=HERE/'original'/p;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes((REPO/p).read_bytes())
functions=[]
def extract(s,marker,path):
 result=decl(s,marker);functions.append(dict(source=path,marker=marker,sha256Lf=hashlib.sha256(result.encode()).hexdigest()));return result
# A data class has no body; use its exact original range through the closing parenthesis.
follow=action[action.index('data class FollowStateChange('):action.index('\n)',action.index('data class FollowStateChange('))+2]
save(HERE/'candidate/generated/com/android/purebilibili/data/repository/DesktopOriginalFollowStateChange.kt','package com.android.purebilibili.data.repository\n\n'+follow+'\n')
save(HERE/'extracted/observeFollowStateChanges.kt',extract(vm,'private fun observeFollowStateChanges()',vmPath)+'\n')
save(HERE/'extracted/applyAuthorUnfollow.kt',extract(vm,'private fun applyAuthorUnfollow(',vmPath)+'\n')
save(HERE/'extracted/requestFollowingsRefreshIfStale.kt',extract(vm,'private fun requestFollowingsRefreshIfStale()',vmPath)+'\n')
save(HERE/'extracted/followUser.kt',extract(action,'suspend fun followUser(',actionPath)+'\n')
ui=vm[vm.index('data class DynamicUiState('):vm.index('\n)\n',vm.index('data class DynamicUiState('))+2]
markers=['internal fun resolveDynamicStateAfterAuthorUnfollow(', 'internal fun resolveFollowedUsersAfterAuthorUnfollow(',
 'internal fun DynamicUiState.timelinePage(', 'internal fun updateDynamicTimelinePage(',
 'internal fun mapDynamicTimelineItems(', 'private fun DynamicUiState.copyActiveTimelinePage(']
body='package com.android.purebilibili.feature.dynamic\nimport com.android.purebilibili.data.model.response.DynamicItem\nimport kotlinx.collections.immutable.*\n\n'+ui+'\n\n'+'\n\n'.join(extract(policy,m,policyPath) for m in markers)+'\n'
save(HERE/'candidate/generated/com/android/purebilibili/feature/dynamic/DesktopOriginalDynamicFollowStatePolicy.kt',body)
for short in sources:
 path='desktop/src/main/kotlin/com/bilipai/desktop/'+short;s=(HERE/'baseline'/path).read_text(encoding='utf-8')
 if short=='data/DesktopRepository.kt':
  s=change(s,'    private val authMutex = Mutex()','    internal val followStateEvents = DesktopFollowStateEvents(sessions)\n    private val authMutex = Mutex()')
 elif short=='data/DesktopSocialRepository.kt':
  s=change(s,'import kotlinx.coroutines.Dispatchers','import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.ensureActive\nimport com.android.purebilibili.data.repository.FollowStateChange\nimport okhttp3.CookieJar\nimport okhttp3.Cookie\nimport okhttp3.HttpUrl\nimport java.io.IOException')
  old=decl(s,'    suspend fun setFollowing(')
  new='''    suspend fun setFollowing(mid: Long, follow: Boolean) {
        require(mid > 0)
        // Capture before queueing: another login of the same MID is a new authority.
        val owner = repository.followStateEvents.requireOwner()
        require(owner.mid != mid) { "不能关注自己" }
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                repository.followStateEvents.requireCurrent(owner)
                repository.requireCsrf()
                repository.ensureSession()
                currentCoroutineContext().ensureActive()
                repository.followStateEvents.requireCurrent(owner)
                check(followingApi(owner).modifyRelation(mid, if (follow) 1 else 2, repository.requireCsrf()))
                currentCoroutineContext().ensureActive()
                repository.followStateEvents.confirm(owner, FollowStateChange(mid = mid, isFollowing = follow))
            }
        }
    }

    private fun followingApi(owner: DesktopDynamicCacheOwner): BilibiliApi {
        val events=repository.followStateEvents
        val originalJar=repository.httpClient.cookieJar
        val ownerJar=object:CookieJar {
            override fun loadForRequest(url:HttpUrl):List<Cookie> = events.withCurrent(owner) { originalJar.loadForRequest(url) }
            override fun saveFromResponse(url:HttpUrl,cookies:List<Cookie>) {
                // The SessionStore monitor atomically rejects retired-cookie response writes.
                repository.dynamicCacheSessionGuard.withCurrentDynamicCacheOwner(owner) { originalJar.saveFromResponse(url,cookies) }
            }
        }
        val client=repository.httpClient.newBuilder().retryOnConnectionFailure(false).cookieJar(ownerJar).apply {
            interceptors().add(0,okhttp3.Interceptor { chain ->
                if(!events.isCurrent(owner))throw IOException("Follow owner retired")
                val response=chain.proceed(chain.request())
                if(!events.isCurrent(owner)){response.close();throw IOException("Follow owner retired")}
                response
            })
            addNetworkInterceptor { chain ->
                if(!events.isCurrent(owner))throw IOException("Follow owner retired")
                val response=chain.proceed(chain.request())
                if(!events.isCurrent(owner)){response.close();throw IOException("Follow owner retired")}
                response
            }
        }.build()
        return Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java)
    }'''
  s=change(s,old,new)
 elif short=='ui/DesktopDynamicCardSession.kt':
  s=change(s,'import kotlinx.coroutines.flow.update','import kotlinx.coroutines.flow.update\nimport kotlinx.coroutines.flow.collect')
  s=change(s,'    override fun close() { alive.set(false) }','''    /** Root's epoch effect owns collection; this session and registry own no feed copies. */
    suspend fun observeFollowStateChanges(registry: DesktopDynamicCardStateRegistry) {
        repository.followStateEvents.changes.collect { event ->
            if (isOwned() && event.owner.epoch == expectedEpoch) registry.applyFollowStateChange(event)
        }
    }
    override fun close() { alive.set(false) }''')
 elif short=='ui/DesktopDynamicCardStateRegistry.kt':
  s=change(s,'import java.lang.ref.WeakReference','import com.bilipai.desktop.data.DesktopOwnedFollowStateChange\nimport java.lang.ref.WeakReference')
  s=change(s,'    private fun mutate(transform: (List<DynamicItem>) -> List<DynamicItem>) {','''    /** Original home ViewModel scope only; Space/Topic/detail owners are intentionally excluded. */
    fun applyFollowStateChange(event: DesktopOwnedFollowStateChange) {
        val captured = owner ?: return
        if (event.owner != captured || event.change.mid <= 0L) return
        guard.withCurrentDynamicCacheOwner(captured) {
            if (!alive.get() || !stillOwned()) return@withCurrentDynamicCacheOwner
            val active = synchronized(models) {
                models.removeAll { it.get() == null }
                models.mapNotNull { it.get() }
            }
            if (event.change.isFollowing) {
                active.filterIsInstance<DesktopDynamicUsersState>().forEach { it.applyFollowingConfirmed() }
            } else {
                active.filterIsInstance<DesktopDynamicTimelineState>().forEach { it.applyAuthorUnfollow(event.change.mid) }
                active.filterIsInstance<DesktopDynamicUsersState>().forEach { it.applyAuthorUnfollow(event.change.mid) }
                // Original ViewModel saves even an empty All page. The sole Root cache actor still writes it.
                synchronized(models) { currentAll?.get() }?.persistCurrentItems()
            }
        }
    }

    private fun mutate(transform: (List<DynamicItem>) -> List<DynamicItem>) {''')
 elif short=='ui/DesktopDynamicTimeline.kt':
  s=change(s,'    internal var editorRefreshRevision=0L','    internal var editorRefreshRevision=0L\n    @Volatile private var followStateRevision=0L')
  s=change(s,'    suspend fun initialize(incrementalRefresh:Boolean):Boolean=requests.withLock {','''    fun applyAuthorUnfollow(authorMid:Long) {
        if(!stillOwned()||authorMid<=0L||type !in setOf("all","video","pgc","article"))return
        followStateRevision++
        val originalState=DynamicUiState(items=page.items,timelineRequestType=type)
        page=page.copy(items=resolveDynamicStateAfterAuthorUnfollow(originalState,authorMid).items)
    }
    suspend fun initialize(incrementalRefresh:Boolean):Boolean=requests.withLock {''')
  s=change(s,'        val snapshot=page','        val snapshot=page\n        val followRevision=followStateRevision\n        val paginationBefore=original.checkpointForFollowChange(type)')
  s=change(s,'            var successPage=resolveDynamicTimelinePageAfterSuccess','''            if(followRevision!=followStateRevision) {
                // A pre-confirmation response cannot undo the accepted local reducer.
                original.restoreAfterFollowChange(type,paginationBefore)
                page=snapshot.copy(items=page.items)
                return false
            }
            var successPage=resolveDynamicTimelinePageAfterSuccess''')
  s=change(s,'        }finally{busy=false}','        }finally{if(followRevision!=followStateRevision)original.restoreAfterFollowChange(type,paginationBefore);busy=false}')
 elif short=='ui/DesktopDynamicUsersState.kt':
  s=change(s,'import com.bilipai.desktop.settings.DesktopDynamicTabsPreferences','import com.bilipai.desktop.settings.DesktopDynamicTabsPreferences\nimport kotlinx.collections.immutable.toImmutableList')
  s=change(s,'    private var lastFollowingsLoadMs=0L','    private var lastFollowingsLoadMs=0L\n    @Volatile private var followStateRevision=0L\n    private var followingsRefreshRequested=false')
  s=change(s,'    private suspend fun loadLiveUsers(){','''    fun applyFollowingConfirmed() {
        if(!owned())return
        followStateRevision++
        followingsFullyLoaded=false
        lastFollowingsLoadMs=0L
        requestFollowingsRefreshIfStale()
    }
    private fun requestFollowingsRefreshIfStale() {
        if(!owned()||!shouldReloadFollowings(nowMs=nowMs(),lastLoadMs=lastFollowingsLoadMs))return
        if(isFollowingsLoading){followingsRefreshRequested=true;return}
        launchOwned{loadAllFollowings(force=true,pageLimit=resolveDynamicFollowingsPageLimit(false))}
    }
    fun applyAuthorUnfollow(authorMid:Long) {
        if(!owned()||authorMid<=0L)return
        followStateRevision++
        followings=followings.filterNot{it.mid==authorMid}
        live=live.filterNot{it.uid==authorMid}
        val reduced=resolveDynamicStateAfterAuthorUnfollow(DynamicUiState(
            items=dynamics.toImmutableList(),userItems=userItems.toImmutableList()),authorMid)
        dynamics=reduced.items
        userItems=reduced.userItems
        if(selectedUid==authorMid)selectUser(null)
        users=resolveFollowedUsersAfterAuthorUnfollow(users,authorMid)
        rebuild()
    }
    private suspend fun loadLiveUsers(){''')
  s=change(s,'        try{val rows=liveRooms();currentCoroutineContext().ensureActive();if(owned()){live=rows;rebuild()}}','        val revision=followStateRevision\n        try{val rows=liveRooms();currentCoroutineContext().ensureActive();if(owned()&&revision==followStateRevision){live=rows;rebuild()}}')
  s=change(s,'        isFollowingsLoading=true','        isFollowingsLoading=true\n        val revision=followStateRevision')
  s=change(s,'            if(owned()){followings=collected;','            if(owned()&&revision==followStateRevision){followings=collected;')
  s=change(s,'            isFollowingsLoading=false\n            if(owned()&&completeFollowingsLoadRequested&&!followingsFullyLoaded){','''            isFollowingsLoading=false
            if(owned()&&followingsRefreshRequested){
                followingsRefreshRequested=false
                launchOwned{loadAllFollowings(force=true,pageLimit=resolveDynamicFollowingsPageLimit(false))}
            } else if(owned()&&completeFollowingsLoadRequested&&!followingsFullyLoaded){''')
  s=change(s,'        try {\n            val rows=original.getUserDynamicFeed','        val revision=followStateRevision\n        val paginationBefore=original.checkpointForFollowChange(uid)\n        try {\n            val rows=original.getUserDynamicFeed')
  s=change(s,'            // Original ViewModel appends remote rows;','            if(revision!=followStateRevision)return@withLock\n            // Original ViewModel appends remote rows;')
  s=change(s,'if(owned())followingsError=error','if(owned()&&revision==followStateRevision)followingsError=error')
  s=change(s,'            if(owned()&&shouldApplyUserDynamicsResult(selectedUid,uid,requestToken,token))userError=error','            if(owned()&&revision==followStateRevision&&shouldApplyUserDynamicsResult(selectedUid,uid,requestToken,token))userError=error')
  s=change(s,'userError=error\n        }\n    }','userError=error\n        }finally{if(revision!=followStateRevision)original.restoreAfterFollowChange(uid,paginationBefore)}\n    }')
 dest=HERE/'candidate'/path;save(dest,s)
 save(HERE/'patches'/(Path(short).name+'.patch'),''.join(difflib.unified_diff((HERE/'baseline'/path).read_text(encoding='utf-8').splitlines(keepends=True),s.splitlines(keepends=True),fromfile=path,tofile=path)))
generatedPins={r['path']:r for r in baselineManifest['generatedProductFiles']}
for kind in ['Timeline','User']:
 generatedPath='desktop/build/generated/'+('dynamic-settings' if kind=='Timeline' else 'dynamic-tabs')+'/com/android/purebilibili/data/repository/DesktopOriginalDynamic'+kind+'Repository.kt'
 assert sha(REPO/generatedPath)==generatedPins[generatedPath]['sha256Bytes'],generatedPath
 baseline=HERE/'baseline'/generatedPath;ext(baseline.parent).mkdir(parents=True,exist_ok=True);ext(baseline).write_bytes(ext(REPO/generatedPath).read_bytes())
 s=ext(baseline).read_text(encoding='utf-8')
 if kind=='Timeline':
  anchor='    private val feedPagination=DynamicFeedPaginationRegistry()'
  extra='''
    /** One request's transient checkpoint; the original registry remains the only cursor owner. */
    fun checkpointForFollowChange(type:String)=feedPagination.snapshot(DynamicFeedScope.DYNAMIC_SCREEN,type)
    fun restoreAfterFollowChange(type:String,before:DynamicPaginationState) {
        feedPagination.updateState(DynamicFeedScope.DYNAMIC_SCREEN,type,before)
    }'''
 else:
  anchor=' private val userFeedPagination=DynamicUserPaginationRegistry()'
  extra='''
 fun checkpointForFollowChange(uid:Long)=DynamicPaginationState(offset=userFeedPagination.offset(uid),hasMore=userFeedPagination.hasMore(uid))
 fun restoreAfterFollowChange(uid:Long,before:DynamicPaginationState) {
  userFeedPagination.update(uid,before.offset,before.hasMore)
 }'''
 adapted=change(s,anchor,anchor+extra)
 target=HERE/'candidate/generated/com/android/purebilibili/data/repository'/('DesktopOriginalDynamic'+kind+'Repository.kt')
 save(target,adapted)
 save(HERE/'patches'/('DesktopOriginalDynamic'+kind+'Repository.kt.patch'),''.join(difflib.unified_diff(s.splitlines(keepends=True),adapted.splitlines(keepends=True),fromfile=generatedPath,tofile=generatedPath)))
 receipt.append(dict(path=generatedPath,sha256Bytes=sha(REPO/generatedPath),sha256Lf=hashlib.sha256(s.encode()).hexdigest()))
save(HERE/'baseline-pins.json',json.dumps(receipt,indent=2)+'\n')
save(HERE/'extraction-receipt.json',json.dumps(dict(originalFunctions=functions,exactOriginalFollowStateChangeSha256Lf=hashlib.sha256(follow.encode()).hexdigest(),exactOriginalDynamicUiStateSha256Lf=hashlib.sha256(ui.encode()).hexdigest(),productIntegrated=False),indent=2)+'\n')
print('Prepared isolated candidate; no Main or Gradle output changed')

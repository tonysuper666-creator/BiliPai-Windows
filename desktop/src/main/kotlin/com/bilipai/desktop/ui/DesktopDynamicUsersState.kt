package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopOriginalDynamicUserRepository
import com.android.purebilibili.feature.dynamic.*
import com.bilipai.desktop.settings.DesktopDynamicTabsPreferences
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Windows task ownership around the original selected-UP policies, fetch loop and MID cursor. */
internal class DesktopDynamicUsersState(
    private val scope:CoroutineScope,
    val preferences:DesktopDynamicTabsPreferences,
    val selfUid:Long,
    private val followingPage:suspend (page:Int)->FollowingsData,
    private val liveRooms:suspend ()->List<LiveRoom>,
    private val unreadUsers:suspend ()->UplistData?,
    requestPage:suspend (Map<String,String>)->DynamicFeedResponse,
    private val stillOwned:()->Boolean,
    private val selfFace:String="",
    private val nowMs:()->Long=System::currentTimeMillis,
    private val startupDelay:suspend (Long)->Unit={delay(it)},
    private val commitIfCurrent:((()->Unit)->Boolean) = { block -> if(stillOwned()){block();true}else false },
) : DesktopDynamicCardItemsOwner {
    var selectedLogicalTab by mutableIntStateOf(resolveDynamicSelectedTabWithinVisibleTabs(preferences.selectedTab,
        resolveDynamicVisibleTabs(preferences.initialVisibleTabs,preferences.initialTabOrder)));private set
    var selectedUid by mutableStateOf<Long?>(null);private set
    var userItems by mutableStateOf<List<DynamicItem>>(emptyList());private set
    var userLoading by mutableStateOf(false);private set
    var userError by mutableStateOf<Throwable?>(null);private set
    var hasUserMore by mutableStateOf(true);private set
    var users by mutableStateOf<List<SidebarUser>>(emptyList());private set
    var followingsError by mutableStateOf<Throwable?>(null);private set
    var unread by mutableStateOf<Set<Long>>(emptySet());private set
    var showHidden by mutableStateOf(false);private set
    var filter by mutableStateOf(DynamicUserContentFilter.ALL)
    var visibleTabs by mutableStateOf(resolveDynamicVisibleTabs(preferences.initialVisibleTabs,preferences.initialTabOrder));private set
    private var followings:List<FollowingUser> = emptyList()
    private var live:List<LiveRoom> = emptyList()
    private var dynamics:List<DynamicItem> = emptyList()
    private var pinned:Set<Long> = preferences.initialPinned
    private var hidden:Set<Long> = preferences.initialHidden
    @Volatile private var closed=false
    private var requestToken=0L
    private var userJob:Job?=null
    private var startupLoadsActivated=false
    internal var editorRefreshRevision=0L
    private var isFollowingsLoading=false
    private var followingsFullyLoaded=false
    private var completeFollowingsLoadRequested=false
    private var lastFollowingsLoadMs=0L
    @Volatile private var followStateRevision=0L
    private var followingsRefreshRequested=false
    private val ownedJobs=ConcurrentHashMap.newKeySet<Job>()
    private val requests=Mutex()
    private val original=DesktopOriginalDynamicUserRepository(requestPage){owned()}
    val hiddenCount:Int get()=hidden.size
    private fun owned()=!closed&&stillOwned()
    private fun publish(caller:Job?=null,block:()->Unit):Boolean {
        var applied=false
        val admitted=commitIfCurrent { if(owned()&&caller?.isActive!=false){block();applied=true} }
        return admitted&&applied
    }
    override fun mutateDynamicItems(transform: (List<DynamicItem>) -> List<DynamicItem>) {
        if (!owned()) return
        userItems = transform(userItems)
        dynamics = transform(dynamics)
        rebuild()
    }
    private fun launchOwned(inScope:CoroutineScope=scope,block:suspend CoroutineScope.()->Unit):Job {
        val job=inScope.launch(start=CoroutineStart.LAZY){if(owned())block()}
        ownedJobs+=job
        job.invokeOnCompletion{ownedJobs-=job}
        if(owned())job.start()else job.cancel()
        return job
    }
    /** Original primary feed/live barrier, then delayed one-page following hydration. */
    fun activateStartupLoads(refreshFeed:suspend ()->Unit) {
        if(!owned()||startupLoadsActivated)return
        startupLoadsActivated=true
        val plan=resolveDynamicStartupLoadPlan()
        launchOwned{loadUnreadUsers()}
        launchOwned {
            coroutineScope {
                val feed=async{if(plan.refreshFeedImmediately)refreshFeed()}
                val status=async{if(plan.loadLiveStatusImmediately)loadLiveUsers()}
                feed.await();status.await()
            }
            if(!plan.loadFollowingsImmediately)startupDelay(plan.followingsHydrationDelayMs.coerceAtLeast(0L))
            currentCoroutineContext().ensureActive()
            if(owned())loadAllFollowings(force=false,pageLimit=plan.initialFollowingsPageLimit)
        }
        if(selectedLogicalTab==4)requestCompleteFollowingsLoad()
    }
    suspend fun applyTabs(visible:Set<String>,order:List<String>) {
        if(!owned())return
        visibleTabs=resolveDynamicVisibleTabs(visible,order)
        val next=resolveDynamicSelectedTabWithinVisibleTabs(selectedLogicalTab,visibleTabs)
        if(next!=selectedLogicalTab)selectTab(next)
        else if(preferences.selectedTab!=next)preferences.setSelectedTab(next)
    }
    suspend fun selectTab(logical:Int) {
        if(!owned())return
        val next=resolveDynamicSelectedTabWithinVisibleTabs(logical,visibleTabs)
        if(next!=4)clearSelection()
        selectedLogicalTab=next
        preferences.setSelectedTab(next)
        if(next==4)requestCompleteFollowingsLoad()
    }
    suspend fun hydrateUsers()=coroutineScope {
        if(!owned())return@coroutineScope
        val following=launchOwned(this) {
            if(isFollowingsLoading)completeFollowingsLoadRequested=true
            else loadAllFollowings(force=true,pageLimit=null)
        }
        val liveJob=launchOwned(this){loadLiveUsers()}
        val unreadJob=launchOwned(this){loadUnreadUsers()}
        joinAll(following,liveJob,unreadJob)
    }
    fun applyFollowingConfirmed() {
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
    private suspend fun loadLiveUsers(){
        if(!owned())return
        val revision=followStateRevision
        val caller=currentCoroutineContext()[Job]
        try{val rows=liveRooms();currentCoroutineContext().ensureActive();publish(caller){if(revision==followStateRevision){live=rows;rebuild()}}}
            catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){}
    }
    private suspend fun loadUnreadUsers(){
        if(!owned())return
        val caller=currentCoroutineContext()[Job]
        try{val data=unreadUsers();currentCoroutineContext().ensureActive();publish(caller){unread=data?.items
            ?.filter{it.has_update==1}?.mapNotNull{it.user_profile?.info?.uid}?.filter{it>0}.orEmpty().toSet()}}
            catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){}
    }
    private suspend fun loadAllFollowings(force:Boolean,pageLimit:Int?){
        if(!owned()||isFollowingsLoading)return
        val now=nowMs()
        if(!force&&!shouldReloadFollowings(nowMs=now,lastLoadMs=lastFollowingsLoadMs))return
        val caller=currentCoroutineContext()[Job]
        if(!publish(caller){isFollowingsLoading=true})return
        val revision=followStateRevision
        try {
            val collected=mutableListOf<FollowingUser>()
            var reachedEnd=false
            for(page in 1..(pageLimit?.coerceAtLeast(1)?:Int.MAX_VALUE)) {
                currentCoroutineContext().ensureActive();if(!owned())return
                val data=followingPage(page)
                currentCoroutineContext().ensureActive();if(!owned())return
                val rows=data.list?:break
                collected+=rows
                if(hasLoadedAllDynamicFollowings(rows.size,collected.size,data.total)){reachedEnd=true;break}
            }
            publish(caller){if(revision==followStateRevision){followings=collected;followingsFullyLoaded=reachedEnd;lastFollowingsLoadMs=now;followingsError=null;rebuild()}}
        }catch(cancelled:CancellationException){throw cancelled}catch(error:Exception){
            currentCoroutineContext().ensureActive()
            publish(caller){if(revision==followStateRevision)followingsError=error}
        }finally {
            var reload=0
            publish {
                isFollowingsLoading=false
                if(followingsRefreshRequested){followingsRefreshRequested=false;reload=1}
                else if(completeFollowingsLoadRequested&&!followingsFullyLoaded){completeFollowingsLoadRequested=false;reload=2}
            }
            // Launch/cancellation handlers must not run inside Store/entry publication.
            if(reload==1)launchOwned{loadAllFollowings(force=true,pageLimit=resolveDynamicFollowingsPageLimit(false))}
            else if(reload==2)launchOwned{loadAllFollowings(force=true,pageLimit=null)}
        }
    }
    private fun requestCompleteFollowingsLoad(){
        if(!owned()||followingsFullyLoaded)return
        if(isFollowingsLoading){completeFollowingsLoadRequested=true;return}
        completeFollowingsLoadRequested=false
        launchOwned{loadAllFollowings(force=true,pageLimit=null)}
    }
    fun updateTimeline(rows:List<DynamicItem>){publish { dynamics=rows;rebuild() }}
    fun updateUserPreferences(pinned:Set<Long>,hidden:Set<Long>){if(owned()){this.pinned=pinned;this.hidden=hidden;rebuild()}}
    fun toggleShowHidden(){if(owned()){showHidden=!showHidden;rebuild()}}
    private fun rebuild(){users=applyUserPreferences(resolveMergedFollowedUsers(extractUsersFromFollowings(followings),extractUsersFromLive(live),extractUsersFromDynamicItems(dynamics)),pinned,hidden,showHidden)}
    fun panelUsers()=resolveDynamicUpPanelUsers(users,selfUid,selfFace)
    fun visibleItems():List<DynamicItem> = filterSelectedUserDynamicItems(resolveSelectedUserVisibleItems(dynamics,userItems,selectedUid)
        .filter{it.visible}.distinctBy{it.id_str},filter)
    fun selectUser(uid:Long?,onOpenUser:(Long)->Unit={}) {
        if(!owned())return
        if(!isDynamicUserTabVisible(visibleTabs)){uid?.takeIf{it>0}?.let(onOpenUser);return}
        val previous=selectedUid
        val next=resolveDynamicSelectedUserIdAfterClick(previous,uid)
        val tab=resolveDynamicTabAfterUserSelection(previous,uid,selectedLogicalTab)
        selectedUid=next;selectedLogicalTab=tab
        launchOwned{preferences.setSelectedTab(tab);if(tab==4)requestCompleteFollowingsLoad()}
        if(next==null){clearSelection();return}
        unread=unread-next
        if(!shouldReloadSelectedUserDynamics(previous,next,userItems,userError?.message))return
        if(previous!=next){userItems=emptyList();hasUserMore=true;filter=DynamicUserContentFilter.ALL}
        userJob?.cancel();val token=++requestToken;userLoading=true;userError=null
        userJob=launchOwned {
            try{delay(120);loadUser(true,next,token)}
            finally{publish { if(shouldApplyUserDynamicsResult(selectedUid,next,requestToken,token))userLoading=false }}
        }
    }
    fun refreshUser()=requestUser(true)
    /** Original refresh gate selects the current UP or timeline and then refreshes unread markers. */
    fun refreshAfterEditor(timeline:DesktopDynamicTimelineState,incrementalRefresh:Boolean) {
        val refreshUserId=resolveDynamicRefreshUserId(selectedLogicalTab,selectedUid)
        val activeSourceLocked=if(refreshUserId!=null)userLoading else timeline.busy
        if(!owned()||!shouldStartDynamicRefresh(false,activeSourceLocked))return
        launchOwned {
            if(refreshUserId!=null) {
                refreshUser()
                userJob?.join()
            } else timeline.fetch(refresh=true,incrementalRefresh=incrementalRefresh)
            loadUnreadUsers()
        }
    }
    fun loadMoreUser()=requestUser(false)
    private fun requestUser(refresh:Boolean){
        val uid=selectedUid?:return
        if(!owned()||userLoading||(!refresh&&!hasUserMore))return
        val token=++requestToken;userLoading=true;userError=null
        userJob=launchOwned{try{loadUser(refresh,uid,token)}finally{publish { if(shouldApplyUserDynamicsResult(selectedUid,uid,requestToken,token))userLoading=false }}}
    }
    private suspend fun loadUser(refresh:Boolean,uid:Long,token:Long)=requests.withLock {
        if(!owned()||!shouldApplyUserDynamicsResult(selectedUid,uid,requestToken,token))return@withLock
        val revision=followStateRevision
        val paginationBefore=original.checkpointForFollowChange(uid)
        val caller=currentCoroutineContext()[Job]
        try {
            val rows=original.getUserDynamicFeed(uid,refresh).getOrThrow();currentCoroutineContext().ensureActive()
            publish(caller) {
                if(revision==followStateRevision&&shouldApplyUserDynamicsResult(selectedUid,uid,requestToken,token)) {
                    // Original ViewModel appends remote rows; local/remote deduplication belongs to its visible-items policy.
                    userItems=if(refresh)rows else userItems+rows;hasUserMore=original.hasMore(uid);userError=null
                }
            }
        }catch(cancelled:CancellationException){throw cancelled}catch(error:Exception){
            currentCoroutineContext().ensureActive()
            publish(caller){if(revision==followStateRevision&&shouldApplyUserDynamicsResult(selectedUid,uid,requestToken,token))userError=error}
        }finally{publish { if(revision!=followStateRevision)original.restoreAfterFollowChange(uid,paginationBefore) }}
    }
    private fun clearSelection(){userJob?.cancel();requestToken++;selectedUid=null;userItems=emptyList();userLoading=false;userError=null;hasUserMore=true}
    fun close(){if(closed)return;closed=true;requestToken++;ownedJobs.toList().forEach{it.cancel()}}
}

package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopOriginalDynamicUserRepository
import com.android.purebilibili.feature.dynamic.*
import com.bilipai.desktop.settings.DesktopDynamicTabsPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
) {
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
    private var closed=false
    private var requestToken=0L
    private var userJob:Job?=null
    private val requests=Mutex()
    private val original=DesktopOriginalDynamicUserRepository(requestPage){owned()}
    val hiddenCount:Int get()=hidden.size
    private fun owned()=!closed&&stillOwned()
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
    }
    suspend fun hydrateUsers()=coroutineScope {
        if(!owned())return@coroutineScope
        val following=launch {
            try {
                val collected=mutableListOf<FollowingUser>();var page=1
                while(true){
                    val data=followingPage(page);currentCoroutineContext().ensureActive();if(!owned())return@launch
                    val rows=data.list.orEmpty();collected+=rows
                    if(hasLoadedAllDynamicFollowings(rows.size,collected.size,data.total))break
                    page++
                }
                followings=collected;followingsError=null;rebuild()
            }catch(cancelled:CancellationException){throw cancelled}catch(error:Exception){if(owned())followingsError=error}
        }
        val liveJob=launch {try{val rows=liveRooms();currentCoroutineContext().ensureActive();if(owned()){live=rows;rebuild()}}
            catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){}}
        val unreadJob=launch {try{val data=unreadUsers();currentCoroutineContext().ensureActive();if(owned())unread=data?.items
            ?.filter{it.has_update==1}?.mapNotNull{it.user_profile?.info?.uid}?.filter{it>0}.orEmpty().toSet()}
            catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){}}
        joinAll(following,liveJob,unreadJob)
    }
    fun updateTimeline(rows:List<DynamicItem>){if(owned()){dynamics=rows;rebuild()}}
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
        scope.launch{if(owned())preferences.setSelectedTab(tab)}
        if(next==null){clearSelection();return}
        unread=unread-next
        if(!shouldReloadSelectedUserDynamics(previous,next,userItems,userError?.message))return
        if(previous!=next){userItems=emptyList();hasUserMore=true;filter=DynamicUserContentFilter.ALL}
        userJob?.cancel();val token=++requestToken;userLoading=true;userError=null
        userJob=scope.launch {
            try{delay(120);loadUser(true,next,token)}
            finally{if(owned()&&shouldApplyUserDynamicsResult(selectedUid,next,requestToken,token))userLoading=false}
        }
    }
    fun refreshUser()=requestUser(true)
    fun loadMoreUser()=requestUser(false)
    private fun requestUser(refresh:Boolean){
        val uid=selectedUid?:return
        if(!owned()||userLoading||(!refresh&&!hasUserMore))return
        val token=++requestToken;userLoading=true;userError=null
        userJob=scope.launch{try{loadUser(refresh,uid,token)}finally{if(owned()&&shouldApplyUserDynamicsResult(selectedUid,uid,requestToken,token))userLoading=false}}
    }
    private suspend fun loadUser(refresh:Boolean,uid:Long,token:Long)=requests.withLock {
        if(!owned()||!shouldApplyUserDynamicsResult(selectedUid,uid,requestToken,token))return@withLock
        try {
            val rows=original.getUserDynamicFeed(uid,refresh).getOrThrow();currentCoroutineContext().ensureActive()
            if(!owned()||!shouldApplyUserDynamicsResult(selectedUid,uid,requestToken,token))return@withLock
            // Original ViewModel appends remote rows; local/remote deduplication belongs to its visible-items policy.
            userItems=if(refresh)rows else userItems+rows;hasUserMore=original.hasMore(uid);userError=null
        }catch(cancelled:CancellationException){throw cancelled}catch(error:Exception){
            if(owned()&&shouldApplyUserDynamicsResult(selectedUid,uid,requestToken,token))userError=error
        }
    }
    private fun clearSelection(){userJob?.cancel();requestToken++;selectedUid=null;userItems=emptyList();userLoading=false;userError=null;hasUserMore=true}
    fun close(){if(closed)return;closed=true;requestToken++;userJob?.cancel()}
}

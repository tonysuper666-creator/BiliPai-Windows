package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.DesktopDynamicSettings.DynamicFeedLayoutMode
import com.android.purebilibili.core.ui.components.FeedVerticalStaggeredGrid
import com.android.purebilibili.core.ui.components.AppText as Text
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.dynamic.*
import com.bilipai.desktop.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.collections.immutable.toImmutableList

/** Native page envelope; original source owns every timeline merge, baseline and pagination decision. */
internal class DesktopDynamicTimelineState(
    private val type:String,
    fetchPage:suspend (type:String,offset:String,updateBaseline:String)->DynamicFeedResponse,
    private val stillOwned:()->Boolean={true},
    initialCachedItems:List<DynamicItem> = emptyList(),
    private val onAllTimelineChanged:(List<DynamicItem>)->Unit = {},
) : DesktopDynamicCardItemsOwner {
    private val original=DesktopOriginalDynamicTimelineRepository(fetchPage,stillOwned)
    private val requests=Mutex()
    val scroll=LazyStaggeredGridState()
    var page by mutableStateOf(if(type=="all"&&initialCachedItems.isNotEmpty())
        DynamicTimelinePageState(items=initialCachedItems.toImmutableList(),isCachePlaceholder=true)
        else DynamicTimelinePageState());private set
    var initialized by mutableStateOf(false);private set
    internal var editorRefreshRevision=0L
    @Volatile private var followStateRevision=0L
    var busy by mutableStateOf(false);private set
    var error by mutableStateOf<Throwable?>(null);private set
    val isAllTimeline: Boolean get() = type == "all"
    internal fun currentUpdateBaseline(): String = original.currentUpdateBaseline(type = type)
    fun persistCurrentItems() { if (isAllTimeline && stillOwned()) onAllTimelineChanged(page.items) }
    override fun mutateDynamicItems(transform: (List<DynamicItem>) -> List<DynamicItem>) {
        if (!stillOwned()) return
        val updated = transform(page.items)
        if (updated == page.items) return
        page = page.copy(items = updated.toImmutableList())
    }
    fun applyAuthorUnfollow(authorMid:Long) {
        if(!stillOwned()||authorMid<=0L||type !in setOf("all","video","pgc","article"))return
        followStateRevision++
        val originalState=DynamicUiState(items=page.items,timelineRequestType=type)
        page=page.copy(items=resolveDynamicStateAfterAuthorUnfollow(originalState,authorMid).items)
    }
    suspend fun initialize(incrementalRefresh:Boolean):Boolean=requests.withLock {
        if(initialized||error!=null)return@withLock false
        fetchLocked(refresh=true,incrementalRefresh=incrementalRefresh)
    }
    suspend fun fetch(refresh:Boolean,incrementalRefresh:Boolean):Boolean=requests.withLock {
        fetchLocked(refresh,incrementalRefresh)
    }
    suspend fun loadMore(incrementalRefresh:Boolean):Boolean {
        if(!page.hasMore||page.isLoading||busy)return false
        return fetch(refresh=false,incrementalRefresh=incrementalRefresh)
    }
    private suspend fun fetchLocked(refresh:Boolean,incrementalRefresh:Boolean):Boolean {
        if(!stillOwned())return false
        val snapshot=page
        val followRevision=followStateRevision
        val paginationBefore=original.checkpointForFollowChange(type)
        busy=true;error=null
        page=resolveDynamicTimelinePageForLoadStart(snapshot,refresh,true)
        try {
            val result=original.getDynamicFeed(refresh=refresh,type=type,incrementalRefresh=incrementalRefresh).getOrThrow()
            currentCoroutineContext().ensureActive()
            if(!stillOwned())return false
            if(followRevision!=followStateRevision) {
                // A pre-confirmation response cannot undo the accepted local reducer.
                original.restoreAfterFollowChange(type,paginationBefore)
                page=snapshot.copy(items=page.items)
                return false
            }
            var successPage=resolveDynamicTimelinePageAfterSuccess(page,result.items,refresh,incrementalRefresh,result.hasMore)
            // Same post-refresh synchronization as original DynamicViewModel; a replacement
            // must not continue paging through the retired tail of the old list.
            if(refresh&&successPage.incrementalPrependedCount==0) {
                if(result.nextOffset.isNotBlank())original.syncPaginationAfterRefresh(DynamicFeedScope.DYNAMIC_SCREEN,
                    type,offset=result.nextOffset,hasMore=result.hasMore)
                successPage=successPage.copy(hasMore=result.hasMore)
            }
            page=successPage
            initialized=true
            if(type=="all")onAllTimelineChanged(page.items)
            return true
        }catch(cancelled:CancellationException){
            if(stillOwned())page=snapshot.copy(items=page.items)
            throw cancelled
        }catch(failure:Exception){
            if(stillOwned()){error=failure;page=resolveDynamicTimelinePageAfterFailure(snapshot.copy(items=page.items),failure.message.orEmpty(),refresh)}
            return false
        }finally{if(followRevision!=followStateRevision)original.restoreAfterFollowChange(type,paginationBefore);busy=false}
    }
}

@Composable
internal fun DesktopDynamicTimelineFeed(
    state:DesktopDynamicTimelineState,
    preferences:DesktopDynamicTimelinePreferences,
    onLogin:()->Unit,
    transform:(List<DynamicItem>)->List<DynamicItem> = {it},
    oldContentDividerLabel:String="以下是之前的动态",
    active:Boolean=true,
    row:@Composable (DynamicItem)->Unit,
) {
    val scope=rememberCoroutineScope()
    val latestActive=rememberUpdatedState(active)
    val rootScroll = LocalDesktopRootDynamicScroll.current
    val layout by preferences.layoutMode.collectAsState(DynamicFeedLayoutMode.WATERFALL)
    val incremental by preferences.incrementalRefresh.collectAsState(false)
    val displayed=remember(state.page.items,transform){transform(state.page.items)}
    val keys=remember(displayed){displayed.map {"dynamic_${dynamicFeedItemKey(it)}"}}
    val divider=resolveOldContentDividerIndex(displayed.map(::dynamicFeedItemKey),state.page.incrementalRefreshBoundaryKey,true)
    LaunchedEffect(state){snapshotFlow { latestActive.value }.first { it };state.initialize(incrementalRefresh=incremental)}
    val allowAutomaticLoadMore=shouldAutoLoadMoreForUserContentFilter(
        isSelectedUserFeed=false,filter=DynamicUserContentFilter.ALL,visibleItemCount=displayed.size)
    val shouldLoadMore by remember(state.scroll,state.busy,state.page.hasMore,allowAutomaticLoadMore) {
        derivedStateOf {
            val layoutInfo=state.scroll.layoutInfo
            shouldLoadMoreDynamicFeed(
                furthestVisibleItemIndex=layoutInfo.visibleItemsInfo.maxOfOrNull{it.index},
                totalItemsCount=layoutInfo.totalItemsCount,allowAutomaticLoadMore=allowAutomaticLoadMore,
                isLoading=state.busy||state.page.isLoading,hasMore=state.page.hasMore)
        }
    }
    // Original ViewModel starts a separate owned request. Loading-state recomposition
    // must not cancel that request by retiring this threshold-observation effect.
    LaunchedEffect(shouldLoadMore,state,active){if(active&&shouldLoadMore)scope.launch{if(latestActive.value)state.loadMore(incremental)}}
    fun fetch(refresh:Boolean) {if(!state.busy)scope.launch{state.fetch(refresh,incremental)}}
    LaunchedEffect(rootScroll, state, active) {
        if(!active)return@LaunchedEffect
        rootScroll?.receiveAsFlow()?.collectLatest { request ->
            applyDesktopRootDynamicScroll(request, state.scroll) { scope.launch{if(latestActive.value)state.fetch(true, incremental)};Unit }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Text("${displayed.size} 项",style=MaterialTheme.typography.labelLarge)
            TextButton(enabled=!state.busy,onClick={fetch(true)}){Text("刷新")}
        }
    Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.TopCenter) {
        FeedVerticalStaggeredGrid(columns=if(layout==DynamicFeedLayoutMode.LIST)StaggeredGridCells.Fixed(1)
            else StaggeredGridCells.Adaptive(resolveDynamicTimelineMinColumnWidth()),state=state.scroll,
            modifier=Modifier.widthIn(max=resolveDynamicTimelineMaxWidth()).fillMaxSize(),
            contentPadding=PaddingValues(20.dp),
            horizontalArrangement=Arrangement.spacedBy(resolveDynamicTimelineHorizontalSpacing()),
            verticalItemSpacing=resolveDynamicTimelineVerticalSpacing(),
            prependItemKeys=if(shouldUseDynamicManualPrependAnchor(layout))keys else emptyList(),
            prependDividerIndex=if(shouldUseDynamicManualPrependAnchor(layout))divider else -1) {
            displayed.forEachIndexed {index,item->
                if(index==divider)item(key="old_content_divider",span=StaggeredGridItemSpan.FullLine){
                    OldContentDivider(oldContentDividerLabel)}
                item(key=keys[index]){row(item)}
            }
            state.error?.let {failure->item(span=StaggeredGridItemSpan.FullLine){CommunityFailure(failure,onLogin){fetch(!state.initialized||state.page.errorSource!=DynamicFeedErrorSource.APPEND)}}}
            if(state.busy)item(span=StaggeredGridItemSpan.FullLine){DesktopLoadingIndicator(Modifier.fillMaxWidth())}
            if(!state.busy&&displayed.isEmpty()&&state.error==null)item(span=StaggeredGridItemSpan.FullLine){
                Text(if(state.page.items.isEmpty())"暂无内容"else"当前筛选隐藏了这一批内容",color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(!state.busy&&state.initialized&&state.page.hasMore)item(span=StaggeredGridItemSpan.FullLine){Button(onClick={scope.launch{state.loadMore(incremental)}}){Text("加载更多")}}
        }
    }
    }
}

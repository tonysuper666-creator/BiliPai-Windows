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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native page envelope; original source owns every timeline merge, baseline and pagination decision. */
internal class DesktopDynamicTimelineState(
    private val type:String,
    fetchPage:suspend (type:String,offset:String,updateBaseline:String)->DynamicFeedResponse,
    private val stillOwned:()->Boolean={true},
) {
    private val original=DesktopOriginalDynamicTimelineRepository(fetchPage,stillOwned)
    private val requests=Mutex()
    val scroll=LazyStaggeredGridState()
    var page by mutableStateOf(DynamicTimelinePageState());private set
    var initialized by mutableStateOf(false);private set
    var busy by mutableStateOf(false);private set
    var error by mutableStateOf<Throwable?>(null);private set
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
        busy=true;error=null
        page=resolveDynamicTimelinePageForLoadStart(snapshot,refresh,true)
        try {
            val result=original.getDynamicFeed(refresh=refresh,type=type,incrementalRefresh=incrementalRefresh).getOrThrow()
            currentCoroutineContext().ensureActive()
            if(!stillOwned())return false
            var successPage=resolveDynamicTimelinePageAfterSuccess(snapshot,result.items,refresh,incrementalRefresh,result.hasMore)
            // Same post-refresh synchronization as original DynamicViewModel; a replacement
            // must not continue paging through the retired tail of the old list.
            if(refresh&&successPage.incrementalPrependedCount==0) {
                if(result.nextOffset.isNotBlank())original.syncPaginationAfterRefresh(DynamicFeedScope.DYNAMIC_SCREEN,
                    type,offset=result.nextOffset,hasMore=result.hasMore)
                successPage=successPage.copy(hasMore=result.hasMore)
            }
            page=successPage
            initialized=true
            return true
        }catch(cancelled:CancellationException){
            if(stillOwned())page=snapshot
            throw cancelled
        }catch(failure:Exception){
            if(stillOwned()){error=failure;page=resolveDynamicTimelinePageAfterFailure(snapshot,failure.message.orEmpty(),refresh)}
            return false
        }finally{busy=false}
    }
}

@Composable
internal fun DesktopDynamicTimelineFeed(
    state:DesktopDynamicTimelineState,
    preferences:DesktopDynamicTimelinePreferences,
    onLogin:()->Unit,
    transform:(List<DynamicItem>)->List<DynamicItem> = {it},
    oldContentDividerLabel:String="以下是之前的动态",
    row:@Composable (DynamicItem)->Unit,
) {
    val scope=rememberCoroutineScope()
    val layout by preferences.layoutMode.collectAsState(DynamicFeedLayoutMode.WATERFALL)
    val incremental by preferences.incrementalRefresh.collectAsState(false)
    val displayed=remember(state.page.items,transform){transform(state.page.items)}
    val keys=remember(displayed){displayed.map {"dynamic_${dynamicFeedItemKey(it)}"}}
    val divider=resolveOldContentDividerIndex(displayed.map(::dynamicFeedItemKey),state.page.incrementalRefreshBoundaryKey,true)
    LaunchedEffect(state){state.initialize(incrementalRefresh=incremental)}
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
    LaunchedEffect(shouldLoadMore,state){if(shouldLoadMore)scope.launch{state.loadMore(incremental)}}
    fun fetch(refresh:Boolean) {if(!state.busy)scope.launch{state.fetch(refresh,incremental)}}
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

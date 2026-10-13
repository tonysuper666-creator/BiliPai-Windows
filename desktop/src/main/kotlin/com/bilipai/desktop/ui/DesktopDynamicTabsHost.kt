package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.DesktopDynamicCardSettings
import com.android.purebilibili.core.store.DesktopDynamicSettings.DynamicFeedLayoutMode
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.*
import com.bilipai.desktop.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Original five dynamic hosts. Liquid tabs follow the shell glass flag; the native row remains the off path. */
@Composable internal fun DesktopDynamicTabsHost(
    users:DesktopDynamicUsersState,
    timelinePreferences:DesktopDynamicTimelinePreferences,
    onOpenUser:(Long)->Unit,
    onLogin:()->Unit,
    transform:(List<DynamicItem>)->List<DynamicItem>,
    timeline:(String)->DesktopDynamicTimelineState,
    active:Boolean=true,
    awaitPageActive:suspend ()->Unit={},
    trailing:@Composable ()->Unit={},
    row:@Composable (DynamicItem)->Unit,
) {
    val scope=rememberCoroutineScope()
    val preferences=users.preferences
    val visible by preferences.visibleTabs.collectAsState(preferences.initialVisibleTabs)
    val order by preferences.tabOrder.collectAsState(preferences.initialTabOrder)
    val showAllUsers by preferences.allTabUsers.collectAsState(preferences.initialAllTabUsers)
    val pinned by preferences.pinned.collectAsState(preferences.initialPinned)
    val hidden by preferences.hidden.collectAsState(preferences.initialHidden)
    var settingError by remember{mutableStateOf<Throwable?>(null)}
    val writer=remember(users){Mutex()}
    fun write(block:suspend()->Unit){scope.launch{try{writer.withLock{block()}}catch(cancelled:CancellationException){throw cancelled}catch(error:Exception){settingError=error}}}
    LaunchedEffect(users,visible,order){users.applyTabs(visible,order)}
    LaunchedEffect(users,pinned,hidden){users.updateUserPreferences(pinned,hidden)}
    LaunchedEffect(users,active){if(active)users.activateStartupLoads {
        awaitPageActive()
        timeline(resolveDynamicFeedRequestType(users.selectedLogicalTab))
            .initialize(timelinePreferences.incrementalRefresh.first())
    }}
    val selectedType=resolveDynamicFeedRequestType(users.selectedLogicalTab)
    val activeTimeline=if(users.selectedLogicalTab==4)null else timeline(selectedType)
    val cardSession=LocalDesktopDynamicCardSession.current
    val contentRevision by (cardSession?.contentRevision ?: remember { kotlinx.coroutines.flow.MutableStateFlow(0L) }).collectAsState()
    LaunchedEffect(users,selectedType,users.selectedUid,contentRevision,active) {
        if(!active||contentRevision<=0L)return@LaunchedEffect
        val current=timeline(selectedType)
        val refreshUserId=resolveDynamicRefreshUserId(users.selectedLogicalTab,users.selectedUid)
        val seen=if(refreshUserId!=null)users.editorRefreshRevision else current.editorRefreshRevision
        if(contentRevision<=seen)return@LaunchedEffect
        if(refreshUserId!=null)users.editorRefreshRevision=contentRevision else current.editorRefreshRevision=contentRevision
        users.refreshAfterEditor(current,timelinePreferences.incrementalRefresh.first())
    }
    val allItems=timeline("all").page.items
    LaunchedEffect(users,allItems){users.updateTimeline(allItems)}
    val initialDisplayMode=remember(preferences){preferences.initialDisplayMode}
    val displayMode by preferences.displayMode.collectAsState(initialDisplayMode)
    val chrome=remember(timelinePreferences){DesktopDynamicPageChromePreferences(timelinePreferences.context)}
    val previewTextVisible by chrome.imagePreviewTextVisible.collectAsState(chrome.peekImagePreviewTextVisible())
    val detailImageLayout by chrome.detailImageLayout.collectAsState(chrome.peekDetailImageLayout())
    val collapseOnScroll by chrome.topBarCollapseOnScroll.collectAsState(chrome.peekTopBarCollapseOnScroll())
    val actionsCollapsed by chrome.topActionsCollapsed.collectAsState(chrome.peekTopActionsCollapsed())
    val pageDirection by chrome.pageLayoutDirection.collectAsState(chrome.peekPageLayoutDirection())
    val chromeScroll=remember{DesktopDynamicFeedChromeScroll()}
    val listState=rememberLazyListState()
    val showHorizontalUsers=shouldShowDynamicHorizontalUserList(displayMode.isHorizontalUserList(),users.selectedLogicalTab,
        users.selectedLogicalTab==0&&showAllUsers)
    val topBarOverlays=collapseOnScroll
    val userListOverlays=showHorizontalUsers
    val topPaddingExtra=resolveWindowsDynamicListTopPaddingExtraDp(topBarOverlays, userListOverlays).dp
    val tabStrip: @Composable () -> Unit = {
        DesktopDynamicTabStrip(users.visibleTabs,users.selectedLogicalTab,displayMode,
            onSelectLogicalTab={write{users.selectTab(it)}},
            onDisplayModeChange={write{preferences.setDisplayMode(it)}},
            trailing=trailing,
            actionDockCollapsed=actionsCollapsed,
            onActionDockCollapsedChange={write{chrome.setTopActionsCollapsed(it)}},
            previewTextVisible=previewTextVisible,
            onPreviewTextVisibleChange={write{chrome.setImagePreviewTextVisible(it)}},
            detailImageLayoutValue=detailImageLayout.value,
            onDetailImageLayoutChange={value->
                write{chrome.setDetailImageLayout(DesktopDynamicCardSettings.DynamicDetailImageLayout.fromValue(value))}
            },
            topBarCollapseOnScroll=collapseOnScroll,
            onTopBarCollapseOnScrollChange={write{chrome.setTopBarCollapseOnScroll(it)}},
            pageLayoutDirection=pageDirection,
            onPageLayoutDirectionChange={write{chrome.setPageLayoutDirection(it)}},
        )
    }
    val userRail: @Composable () -> Unit = {
        HorizontalUserList(users.panelUsers(),users.selectedUid,selfUid=users.selfUid,listState=listState,
            showHiddenUsers=users.showHidden,hiddenCount=users.hiddenCount,uplistUpdateMids=users.unread,
            onUserClick={users.selectUser(it,onOpenUser)},onToggleShowHidden=users::toggleShowHidden,
            onTogglePin={write{preferences.toggleUserPreference(DesktopOriginalDynamicUserPreferenceKeys.KEY_PINNED_USERS,it)}},
            onToggleHidden={write{preferences.toggleUserPreference(DesktopOriginalDynamicUserPreferenceKeys.KEY_HIDDEN_USERS,it)}},
            modifier=Modifier.largeScreenFocusTraversal(DesktopLargeScreenFocusAxis.HORIZONTAL))
    }
    val gridOverlay: @Composable BoxScope.() -> Unit = {
        if (topBarOverlays || userListOverlays) {
            DesktopDynamicCollapsingChrome(
                scroll=chromeScroll,
                showTopBar=topBarOverlays,
                showUserList=userListOverlays,
                topBar=tabStrip,
                userList=userRail,
                modifier=Modifier.align(Alignment.TopCenter),
            )
        }
    }
    DesktopDynamicModeFrame(
        mode=displayMode,
        sidebarExpanded=users.sidebarExpanded,
        onToggleSidebar=users::toggleSidebar,
        showHorizontalUsers=showHorizontalUsers,
        panelUsers=users.panelUsers(),
        selectedUid=users.selectedUid,
        selfUid=users.selfUid,
        showHidden=users.showHidden,
        hiddenCount=users.hiddenCount,
        unread=users.unread,
        onUserClick={users.selectUser(it,onOpenUser)},
        onToggleShowHidden=users::toggleShowHidden,
        onTogglePin={write{preferences.toggleUserPreference(DesktopOriginalDynamicUserPreferenceKeys.KEY_PINNED_USERS,it)}},
        onToggleHidden={write{preferences.toggleUserPreference(DesktopOriginalDynamicUserPreferenceKeys.KEY_HIDDEN_USERS,it)}},
        horizontalUsers=userRail,
        header=tabStrip,
        notices={
            settingError?.let{CommunityFailure(it,onLogin){settingError=null}}
            users.followingsError?.let{CommunityFailure(it,onLogin){scope.launch{users.hydrateUsers()}}}
        },
        pageLayoutDirection=pageDirection,
        topBarOverlaysFeed=topBarOverlays,
        userListOverlaysFeed=userListOverlays,
        escapeArmed=active,
        feed={
            val hasGrid=activeTimeline!=null||users.selectedUid!=null
            Box(Modifier.fillMaxSize()) {
                if(activeTimeline!=null) {
                    val title=allDynamicTabSpecs.first{it.logicalIndex==users.selectedLogicalTab}.title
                    DesktopDynamicTimelineFeed(activeTimeline,timelinePreferences,onLogin,transform,
                        oldContentDividerLabel=if(selectedType=="all")"以下是之前的动态"else"以下是之前的${title}",active=active,
                        topPaddingExtra=topPaddingExtra,chromeScroll=chromeScroll,chromeOverlay=gridOverlay,row=row)
                } else if(users.selectedUid!=null) {
                    DesktopDynamicSelectedUserFeed(users,timelinePreferences,onOpenUser,onLogin,transform,active,
                        topPaddingExtra=topPaddingExtra,chromeScroll=chromeScroll,chromeOverlay=gridOverlay,row=row)
                } else AppText("选择关注用户查看动态",Modifier.padding(20.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(!hasGrid) gridOverlay()
            }
        },
    )
}

@Composable private fun DesktopDynamicSelectedUserFeed(
    state:DesktopDynamicUsersState,preferences:DesktopDynamicTimelinePreferences,
    onOpenUser:(Long)->Unit,onLogin:()->Unit,transform:(List<DynamicItem>)->List<DynamicItem>,active:Boolean,
    topPaddingExtra: Dp = 0.dp,
    chromeScroll: DesktopDynamicFeedChromeScroll? = null,
    chromeOverlay: @Composable BoxScope.() -> Unit = {},
    row:@Composable (DynamicItem)->Unit,
) {
    val layout by preferences.layoutMode.collectAsState(DynamicFeedLayoutMode.WATERFALL)
    val rows=transform(state.visibleItems())
    val grid=remember(state.selectedUid){LazyStaggeredGridState()}
    val rootScroll = LocalDesktopRootDynamicScroll.current
    LaunchedEffect(rootScroll, state, state.selectedUid, active) {
        if(!active)return@LaunchedEffect
        rootScroll?.receiveAsFlow()?.collectLatest { request ->
            applyDesktopRootDynamicScroll(request, grid) { state.refreshUser() }
        }
    }
    val allowAutomaticLoadMore=shouldAutoLoadMoreForUserContentFilter(
        isSelectedUserFeed=true,filter=state.filter,visibleItemCount=rows.size)
    val shouldLoadMore by remember(grid,state.userLoading,state.hasUserMore,allowAutomaticLoadMore) {
        derivedStateOf {
            val layoutInfo=grid.layoutInfo
            shouldLoadMoreDynamicFeed(
                furthestVisibleItemIndex=layoutInfo.visibleItemsInfo.maxOfOrNull{it.index},
                totalItemsCount=layoutInfo.totalItemsCount,allowAutomaticLoadMore=allowAutomaticLoadMore,
                isLoading=state.userLoading,hasMore=state.hasUserMore)
        }
    }
    LaunchedEffect(shouldLoadMore,state.selectedUid,state.filter,active){if(active&&shouldLoadMore)state.loadMoreUser()}
    LaunchedEffect(grid, chromeScroll) {
        val target = chromeScroll ?: return@LaunchedEffect
        snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> target.publish(index, offset) }
    }
    Column(Modifier.fillMaxSize()) {
        DynamicSelectedUserFeedHeader(state.panelUsers().firstOrNull{it.uid==state.selectedUid}?.name.orEmpty(),
            state.filter,{state.filter=it},{state.selectedUid?.let(onOpenUser)})
        AppTextButton(enabled=!state.userLoading,onClick=state::refreshUser){AppText(desktopHomeStringResource(DesktopShellRefreshStringKey))}
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        FeedVerticalStaggeredGrid(columns=if(layout==DynamicFeedLayoutMode.LIST)StaggeredGridCells.Fixed(1)
            else StaggeredGridCells.Adaptive(resolveDynamicTimelineMinColumnWidth()),state=grid,
            modifier=Modifier.widthIn(max=resolveDynamicTimelineMaxWidth()).fillMaxSize(),
            contentPadding=PaddingValues(start=20.dp, end=20.dp, bottom=20.dp, top=20.dp + topPaddingExtra),
            horizontalArrangement=Arrangement.spacedBy(resolveDynamicTimelineHorizontalSpacing()),verticalItemSpacing=resolveDynamicTimelineVerticalSpacing()) {
            rows.forEach {item->item(key="dynamic_${dynamicFeedItemKey(item)}"){row(item)}}
            state.userError?.let{error->item(span=StaggeredGridItemSpan.FullLine){CommunityFailure(error,onLogin,state::refreshUser)}}
            if(state.userLoading)item(span=StaggeredGridItemSpan.FullLine){DesktopLoadingIndicator(Modifier.fillMaxWidth())}
            if(!state.userLoading&&rows.isEmpty()&&state.userError==null)item(span=StaggeredGridItemSpan.FullLine){AppText("暂无内容")}
            if(!state.userLoading&&state.hasUserMore)item(span=StaggeredGridItemSpan.FullLine){AppPrimaryButton(text="加载更多",onClick=state::loadMoreUser)}
        }
        chromeOverlay()
        }
    }
}

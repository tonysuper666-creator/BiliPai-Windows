"""v0.4.6 Space content pager: ContentTabPager with its PagerState rebuild guard, the pager mount on the Space screen,
the four second banner rotation and the pinned card without the overflow icon.

Roadmap slice space-profile-content-pager (#32), SOURCE only; nothing here was compiled or run on a JVM.

Which Space screen is mounted. DesktopOriginalSpacePageRootHost (DesktopOriginalSpacePagesRoot.kt) is the leaf the Root
dispatcher mounts for BiliPaiNavKey.Space and calls SpaceScreen, the original SpaceViewModel based screen that
extract-upstream-space-pages.py generates from the v0.2.5 pin. DesktopCompleteSpaceScreen has no caller outside its own
file. The roadmap marked this "unknown"; it is the generated original screen, so the pager is mounted there.

What is carried.
- ContentTabPager.kt (rememberContentTabPager, stationaryPagerHeader, contentTabPagerGestures) is carried WHOLE from the
  v046-space-content-pager archive (verbatim v0.4.6 bytes, introduced by 35e2fac8, key(pageCount) guard added by f00cb206)
  and emitted as a new file by the Space pages producer. It is a new declaration group: nothing else in the compile
  universe declares any of the three names. rememberContentTabPager replaces the PagerState whenever the page count
  changes, so an asynchronously loaded tab (the Space cheese tab) can never leave the pager counting more pages than the
  layout's key list holds (the out of bounds crash of the 0.4.4 pager). One adaptation, counted and hash pinned: the
  upstream call passes forceVerticalPriority = true to verticalPriorityHorizontalPagerSwipe. The Windows tree carries that
  modifier from the v0.2.5 pin (extract-upstream-home-page.py selects the declaration), which has no such parameter and no
  Miuix override branch: it already is the forced vertical-priority path. The argument line becomes a comment, so the
  behaviour is identical to upstream's forced call and no Home gesture changes (the Miuix branch that v0.4.6 adds for the
  non forced callers is not carried here).
- The Space screen text is advanced by counted, hash pinned hunks that are the upstream hunks of 35e2fac8 and f00cb206
  rebased onto the pinned text: one LazyGridState per primary tab, the AppHorizontalPager of pages (userScrollEnabled false,
  gestures by contentTabPagerGestures), the header and the sticky tab rail kept stationary while the pages move, load-more
  effects that only fire on the active page, and the single fixed glass primary rail of f00cb206 (liquid glass owns one drag
  lifecycle outside the pager). SpaceContentTabs and SpaceMainTabRow gain the pager parameters; both have exactly one
  caller (plus the glass rail call of SpaceMainTabRow) and the new parameters are forwarded from each.
- b3ace0a55 (0.2.8): the banner of an UP with several header images rotates every 4 s, paused while dragged; the pinned
  video card drops its overflow icon (showTrailingMore). The followActionAnchor hunk of the same commit is not carried: it
  positions the unfollow confirmation popup of the Android shell and the Windows confirmation is an owned dialog.

Two Windows differences from the upstream hunks, both counted in ADAPTATIONS: the content pager is AppHorizontalPager (the
upstream file imports it under the alias HorizontalPager; the pinned file imports the Foundation HorizontalPager for its
banner, which stays), and contentTabPagerGestures is enabled always because the pinned SpaceContent is only composed for a
loaded SpaceUiState.Success (the in-list skeleton of 0.3.6, isInitialLoading, is not part of the pinned screen).

Not carried (follow-up slices, listed in the report): the ProfileScreen pager (its generated owner is a separate recipe),
the LiveListScreen indicator argument (needs the AppLiquidAwareTabRow parameter of 35e2fac8), and the back-to-top backdrop
(AppLiquidGlassBackToTopButton / FloatingChromeBackdrop): upstream stops the button from sampling the app-shell backdrop to
avoid an Android RenderNode cycle; nothing on Windows provides LocalFloatingChromeBackdrop, and the v0.4.6 button would
then lose its glass on Windows unless the Space screen passed a page-local source.
"""
from pathlib import Path
import hashlib
import json
import os
import re

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
INTRODUCED_BY = '35e2fac8a3870a2227a323e19f29f1e93d7e591c'
AMENDED_BY = 'f00cb206f3fa1a5e8f55ebfc78e9c6030ea77f8d'
BANNER_COMMIT = 'b3ace0a55df682af62c1062fa7d9e2c6062b0633'
ARCHIVE = 'desktop/upstream-slices/v046-space-content-pager'
MANIFEST_SHA256 = 'c07e26f1866e11fbeb44fe886448977afe13b6a16bb60a44cc7e3562d8edc47c'
LEAF = 'ContentTabPager.kt'
LEAF_PATH = 'app/src/main/java/com/android/purebilibili/core/ui/common/ContentTabPager.kt'
LEAF_BYTES = 4639
LEAF_SHA256 = '5392c163e1ac16caeea0f332a82edc2b052db4e47cc420870b30ec5b33dd05dc'
LEAF_BLOB = 'd659a5d40f554e475ff0558fb6eebe276b7381c2'
LICENSE_SHA256 = '3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986'
OUTPUT_RELATIVE = 'com/android/purebilibili/core/ui/common/ContentTabPager.kt'
PROOF_DIRECTORY = 'v046-space-content-pager-proofs'
OUTPUT_MODE = 'adapted-complete-original-v046-space-content-pager'
TOP_LEVEL_NAMES = ('rememberContentTabPager', 'stationaryPagerHeader', 'contentTabPagerGestures')
# Last upstream change to SpaceScreen.kt at v0.4.6 is c4124a062; the v0.4.6 blob is what the hunks are rebased from.
UPSTREAM_SPACE = ('app/src/main/java/com/android/purebilibili/feature/space/SpaceScreen.kt',
                  '384e8345c35a264bae6f15631eea1153a4b8b98d')

# The one adaptation of the carried file (see the module text).
ADAPT_BEFORE = '        forceVerticalPriority = true,\n'
ADAPT_AFTER = ('        // Windows: the carried gesture modifier has no Miuix override branch, so it already runs the forced\n'
               '        // vertical-priority path that the upstream call selects with its forced flag.\n')
EMITTED_BYTES = 4797
EMITTED_SHA256 = 'affa60c94d5416a3e9a3f7814dbd2742ac9a6b2f6b991046a1f350ef75b2abcc'

PKG = 'com/android/purebilibili/'
SPACE = PKG + 'feature/space/SpaceScreen.kt'
PAGER_MARK = 'rememberContentTabPager('


def L(*lines):
    """Kotlin text of the given lines, each ended by a line feed."""
    return ''.join(line + '\n' for line in lines)


# (label, before, after, origin). Applied in this order to the generated Space screen text, each exactly once.
# origin: 'pager' = hunk of 35e2fac8 / f00cb206, 'banner' = hunk of b3ace0a55.
EDITS = [
    dict(label='Imports of the pager helpers, the pager state and the banner timer', origin='pager',
         before='import androidx.compose.foundation.pager.rememberPagerState\n',
         after=L('import androidx.compose.foundation.pager.rememberPagerState',
                 'import androidx.compose.foundation.pager.PagerState',
                 'import androidx.compose.foundation.interaction.collectIsDraggedAsState',
                 'import androidx.compose.ui.layout.positionInRoot',
                 'import com.android.purebilibili.core.ui.common.contentTabPagerGestures',
                 'import com.android.purebilibili.core.ui.common.rememberContentTabPager',
                 'import com.android.purebilibili.core.ui.common.stationaryPagerHeader',
                 'import com.android.purebilibili.core.ui.components.AppHorizontalPager',
                 'import kotlinx.coroutines.delay')),
    dict(label='Banner rotation interval constant', origin='banner',
         before='import kotlinx.coroutines.launch\n\n@OptIn(',
         after=L('import kotlinx.coroutines.launch',
                 '',
                 '// UP 空间多张头图自动轮播间隔',
                 'private const val SPACE_BANNER_AUTO_ROTATE_INTERVAL_MS = 4_000L',
                 '') + '@OptIn('),
    dict(label='One LazyGridState per primary tab; the active one drives the screen chrome', origin='pager',
         before=L('    val gridState = rememberLazyGridState()',
                  '    val isSpaceScrolling by remember {',
                  '        derivedStateOf { gridState.isScrollInProgress }',
                  '    }',
                  '    val pinnedTopChromeScrim by remember {',
                  '        derivedStateOf {'),
         after=L('    val pageGridStates = SpaceMainTab.entries.associateWith { rememberLazyGridState() }',
                 '    val activePrimaryTab = resolveSpacePrimaryTab(',
                 '        (uiState as? SpaceUiState.Success)?.tabShellState?.selectedTab ?: SpaceMainTab.HOME',
                 '    )',
                 '    val gridState = pageGridStates.getValue(activePrimaryTab)',
                 '    val isSpaceScrolling by remember(gridState) {',
                 '        derivedStateOf { gridState.isScrollInProgress }',
                 '    }',
                 '    val pinnedTopChromeScrim by remember(gridState) {',
                 '        derivedStateOf {')),
    dict(label='SpaceContent receives the per tab grid states', origin='pager',
         before=L('                            state = state,',
                  '                            gridState = gridState,'),
         after=L('                            state = state,',
                 '                            gridState = gridState,',
                 '                            pageGridStates = pageGridStates,')),
    dict(label='SpaceContent declares the per tab grid states', origin='pager',
         before='    gridState: LazyGridState,\n',
         after=L('    gridState: LazyGridState,',
                 '    pageGridStates: Map<SpaceMainTab, LazyGridState>,')),
    dict(label='The content pager state, built by the rebuild guard of ContentTabPager', origin='pager',
         before=L('    val selectedMainTab = state.tabShellState.selectedTab',
                  '    val selectedContributionTab = remember('),
         after=L('    val selectedMainTab = state.tabShellState.selectedTab',
                 '    val singleGlassPrimaryRail = LocalAppThemeConfig.current.liquidGlassEnabled',
                 '    // Glass owns one drag/press lifecycle outside the pager. Per-page native rails keep',
                 '    // their existing sticky layout and animation; only glass pages reserve an empty slot.',
                 '    var glassPrimaryRailHeightPx by remember { mutableStateOf(0) }',
                 '    var glassPrimaryRailBoundsInRoot by remember { mutableStateOf<Rect?>(null) }',
                 '    val displayedMainTabs = resolveSpaceDisplayedMainTabs(',
                 '        tabs = state.mainTabs,',
                 '        selectedTab = selectedMainTab,',
                 '        hasCheese = state.hasCheeseTab || state.cheeseItems.isNotEmpty(),',
                 '    )',
                 '    val selectedPage = displayedMainTabs.indexOfFirst {',
                 '        it.tab == resolveSpacePrimaryTab(selectedMainTab)',
                 '    }.coerceAtLeast(0)',
                 '    val pagerState = rememberContentTabPager(',
                 '        pageCount = displayedMainTabs.size,',
                 '        selectedPage = selectedPage,',
                 '        onPageSelected = { page ->',
                 '            displayedMainTabs.getOrNull(page)?.tab?.let { tab ->',
                 '                if (tab != resolveSpacePrimaryTab(selectedMainTab)) onMainTabSelected(tab)',
                 '            }',
                 '        },',
                 '        prepareNeighbors = { sourcePage ->',
                 '            val source = pageGridStates.getValue(displayedMainTabs[sourcePage].tab)',
                 '            // All pages share the hero and primary rail. Match their vertical anchor before',
                 '            // exposing a neighbor; content history is kept when the hero is already collapsed.',
                 '            displayedMainTabs.forEachIndexed { index, tab ->',
                 '                if (index != sourcePage) {',
                 '                    val target = pageGridStates.getValue(tab.tab)',
                 '                    if (source.firstVisibleItemIndex == 0) {',
                 '                        target.requestScrollToItem(0, source.firstVisibleItemScrollOffset)',
                 '                    } else if (target.firstVisibleItemIndex == 0) {',
                 '                        target.requestScrollToItem(1)',
                 '                    }',
                 '                }',
                 '            }',
                 '        },',
                 '    )',
                 '    val selectedContributionTab = remember(')),
    dict(label='The grid and its floating chrome become the content of one pager page', origin='pager',
         before=L('            .onGloballyPositioned { gridContainerRootTopPx = it.boundsInRoot().top }',
                  '    ) {',
                  '        val density = LocalDensity.current'),
         after=L('            .onGloballyPositioned { gridContainerRootTopPx = it.boundsInRoot().top }',
                 '    ) {',
                 '        AppHorizontalPager(',
                 '            state = pagerState,',
                 '            userScrollEnabled = false,',
                 '            key = { displayedMainTabs[it].tab },',
                 '            modifier = Modifier.fillMaxSize().contentTabPagerGestures(',
                 '                pager = pagerState,',
                 '                enabled = true,',
                 '                contentTopPx = {',
                 '                    if (pinnedTabsRootBottomPx > 0f) pinnedTabsRootBottomPx - gridContainerRootTopPx',
                 '                    else Float.POSITIVE_INFINITY',
                 '                },',
                 '            ),',
                 '        ) { page ->',
                 '        val primaryTab = displayedMainTabs[page].tab',
                 '        val isPageActive = page == selectedPage',
                 '        val pageTab = if (isPageActive) selectedMainTab else primaryTab',
                 '        val state = state.copy(',
                 '            tabShellState = state.tabShellState.withSelectedTab(pageTab),',
                 '            isSearchMode = state.isSearchMode && isPageActive,',
                 '        )',
                 '        val selectedMainTab = pageTab',
                 '        val gridState = pageGridStates.getValue(primaryTab)',
                 '        val lazyGridSharedTransitionScope = lazyGridSharedTransitionScope.takeIf { isPageActive }',
                 '        val lazyGridAnimatedVisibilityScope = lazyGridAnimatedVisibilityScope.takeIf { isPageActive }',
                 '        val density = LocalDensity.current')),
    dict(label='The hero header stays where it is while sibling pages move', origin='pager',
         before=L('            item(span = { GridItemSpan(maxLineSpan) }) {',
                  '                SpaceHeader('),
         after=L('            item(span = { GridItemSpan(maxLineSpan) }) {',
                 '                Box(Modifier.stationaryPagerHeader(pagerState, page)) {',
                 '                SpaceHeader(')),
    dict(label='End of the stationary hero header box', origin='pager',
         before=L('                    useExpandedLayout = adaptiveLayoutSpec.useExpandedHeader,',
                  '                )',
                  '            }'),
         after=L('                    useExpandedLayout = adaptiveLayoutSpec.useExpandedHeader,',
                 '                )',
                 '                }',
                 '            }')),
    dict(label='The sticky tab rail reports its bounds from the active page and takes the pager parameters', origin='pager',
         before=L('                        .onGloballyPositioned { pinnedTabsRootBottomPx = it.boundsInRoot().bottom }',
                  '                ) {',
                  '                    SpaceContentTabs(',
                  '                        state = state,',
                  '                        onMainTabSelected = onMainTabSelected,',
                  '                        onContributionTabSelected = onContributionTabSelected,',
                  '                        modifier = Modifier.fillMaxWidth(),',
                  '                    )'),
         after=L('                        .onGloballyPositioned {',
                 '                            if (isPageActive) pinnedTabsRootBottomPx = it.boundsInRoot().bottom',
                 '                            if (singleGlassPrimaryRail && page == pagerState.currentPage) {',
                 '                                glassPrimaryRailBoundsInRoot = Rect(',
                 '                                    it.positionInRoot(),',
                 '                                    androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()),',
                 '                                )',
                 '                            }',
                 '                        }',
                 '                ) {',
                 '                    SpaceContentTabs(',
                 '                        state = state,',
                 '                        onMainTabSelected = onMainTabSelected,',
                 '                        pagerState = pagerState,',
                 '                        page = page,',
                 '                        primaryTabHeightProvider = if (singleGlassPrimaryRail) {',
                 '                            { glassPrimaryRailHeightPx }',
                 '                        } else null,',
                 '                        onContributionTabSelected = onContributionTabSelected,',
                 '                        modifier = Modifier.fillMaxWidth(),',
                 '                    )')),
    dict(label='Dynamic load-more only fires on the active page', origin='pager',
         before=L('                            LaunchedEffect(dynamicCardItems.size) {',
                  '                                onLoadMoreDynamic()'),
         after=L('                            LaunchedEffect(dynamicCardItems.size, isPageActive) {',
                 '                                if (isPageActive) onLoadMoreDynamic()')),
    dict(label='Audio load-more only fires on the active page', origin='pager',
         before='LaunchedEffect(state.audios.size) { onLoadMoreAudios() }',
         after='LaunchedEffect(state.audios.size, isPageActive) { if (isPageActive) onLoadMoreAudios() }'),
    dict(label='Article load-more only fires on the active page', origin='pager',
         before='LaunchedEffect(state.articles.size) { onLoadMoreArticles() }',
         after='LaunchedEffect(state.articles.size, isPageActive) { if (isPageActive) onLoadMoreArticles() }'),
    dict(label='Bangumi load-more only fires on the active page', origin='pager',
         before='LaunchedEffect(state.bangumiItems.size) { onLoadMoreBangumi() }',
         after='LaunchedEffect(state.bangumiItems.size, isPageActive) { if (isPageActive) onLoadMoreBangumi() }'),
    dict(label='Cheese load-more only fires on the active page', origin='pager',
         before='LaunchedEffect(state.cheeseItems.size) { onLoadMoreCheese() }',
         after='LaunchedEffect(state.cheeseItems.size, isPageActive) { if (isPageActive) onLoadMoreCheese() }'),
    dict(label='The pager closes after the floating chrome; glass pages get one fixed primary rail', origin='pager',
         before=L('        GridPinchColumnHudPill(',
                  '            visible = isPinchPillVisible,',
                  '            columns = gridColumns,',
                  '            modifier = Modifier',
                  '                .align(Alignment.TopCenter)',
                  '                .padding(top = chromeTopInset + 16.dp)',
                  '        )',
                  '    }',
                  '    }',
                  '}'),
         after=L('        GridPinchColumnHudPill(',
                 '            visible = isPinchPillVisible,',
                 '            columns = gridColumns,',
                 '            modifier = Modifier',
                 '                .align(Alignment.TopCenter)',
                 '                .padding(top = chromeTopInset + 16.dp)',
                 '        )',
                 '        } // HorizontalPager',
                 '',
                 '        if (singleGlassPrimaryRail) {',
                 '            // A single fixed owner survives page changes, so the incoming page cannot',
                 '            // start a second pager-driven bloom while the dragged capsule is releasing.',
                 '            // Read geometry in measurement/placement rather than recomposing on scroll.',
                 '            SpaceMainTabRow(',
                 '                tabs = displayedMainTabs,',
                 '                selectedTab = resolveSpacePrimaryTab(selectedMainTab),',
                 '                onSelect = onMainTabSelected,',
                 '                indicatorPositionProvider = { pagerState.currentPage + pagerState.currentPageOffsetFraction },',
                 '                isScrollInProgressProvider = { pagerState.isScrollInProgress },',
                 '                modifier = Modifier',
                 '                    .offset {',
                 '                        IntOffset(',
                 '                            0,',
                 '                            ((glassPrimaryRailBoundsInRoot?.top ?: gridContainerRootTopPx) -',
                 '                                gridContainerRootTopPx).roundToInt(),',
                 '                        )',
                 '                    }',
                 '                    .layout { measurable, constraints ->',
                 '                        val bounds = glassPrimaryRailBoundsInRoot',
                 '                        val width = (bounds?.width?.roundToInt() ?: constraints.maxWidth)',
                 '                            .coerceIn(constraints.minWidth, constraints.maxWidth)',
                 '                        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))',
                 '                        layout(constraints.maxWidth, placeable.height) {',
                 '                            if (bounds != null) {',
                 '                                // Ignore the pager\'s horizontal translation; the grid\'s',
                 '                                // symmetric content padding only determines the rail width.',
                 '                                placeable.placeRelative((constraints.maxWidth - placeable.width) / 2, 0)',
                 '                            }',
                 '                        }',
                 '                    }',
                 '                    .onSizeChanged { glassPrimaryRailHeightPx = it.height }',
                 '                    .background(',
                 '                        com.android.purebilibili.core.ui.globalWallpaperAwareChromeColor(',
                 '                            MaterialTheme.colorScheme.surface',
                 '                        ).let { if (it.alpha < 0.97f) it.copy(alpha = 0.97f) else it }',
                 '                    ),',
                 '            )',
                 '        }',
                 '',
                 '    }',
                 '    }',
                 '}')),
    dict(label='SpaceContentTabs declares the pager parameters', origin='pager',
         before=L('private fun SpaceContentTabs(',
                  '    state: SpaceUiState.Success,',
                  '    onMainTabSelected: (SpaceMainTab) -> Unit,',
                  '    onContributionTabSelected: (String) -> Unit,',
                  '    modifier: Modifier = Modifier,',
                  ') {'),
         after=L('private fun SpaceContentTabs(',
                 '    state: SpaceUiState.Success,',
                 '    onMainTabSelected: (SpaceMainTab) -> Unit,',
                 '    onContributionTabSelected: (String) -> Unit,',
                 '    modifier: Modifier = Modifier,',
                 '    pagerState: PagerState,',
                 '    page: Int,',
                 '    primaryTabHeightProvider: (() -> Int)? = null,',
                 ') {')),
    dict(label='The primary rail is a reserved slot on glass pages and a stationary rail otherwise', origin='pager',
         before=L('        SpaceMainTabRow(',
                  '            tabs = displayedMainTabs,',
                  '            selectedTab = resolveSpacePrimaryTab(selectedMainTab),',
                  '            onSelect = onMainTabSelected,',
                  '        )'),
         after=L('        if (primaryTabHeightProvider != null) {',
                 '            Spacer(',
                 '                Modifier.fillMaxWidth().layout { measurable, constraints ->',
                 '                    val height = primaryTabHeightProvider().coerceIn(constraints.minHeight, constraints.maxHeight)',
                 '                    val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))',
                 '                    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }',
                 '                }',
                 '            )',
                 '        } else {',
                 '            SpaceMainTabRow(',
                 '                tabs = displayedMainTabs,',
                 '                selectedTab = resolveSpacePrimaryTab(selectedMainTab),',
                 '                onSelect = onMainTabSelected,',
                 '                modifier = Modifier.stationaryPagerHeader(pagerState, page),',
                 '                indicatorPositionProvider = { pagerState.currentPage + pagerState.currentPageOffsetFraction },',
                 '                isScrollInProgressProvider = { pagerState.isScrollInProgress },',
                 '            )',
                 '        }')),
    dict(label='SpaceMainTabRow declares the modifier and the two pager providers', origin='pager',
         before=L('private fun SpaceMainTabRow(',
                  '    tabs: List<SpaceMainTabItem>,',
                  '    selectedTab: SpaceMainTab,',
                  '    onSelect: (SpaceMainTab) -> Unit,',
                  ') {'),
         after=L('private fun SpaceMainTabRow(',
                 '    tabs: List<SpaceMainTabItem>,',
                 '    selectedTab: SpaceMainTab,',
                 '    onSelect: (SpaceMainTab) -> Unit,',
                 '    modifier: Modifier = Modifier,',
                 '    indicatorPositionProvider: () -> Float,',
                 '    isScrollInProgressProvider: () -> Boolean,',
                 ') {')),
    dict(label='SpaceMainTabRow applies the caller modifier and forwards the providers', origin='pager',
         before=L('    Column(',
                  '        modifier = Modifier',
                  '            .fillMaxWidth()',
                  '            .padding(top = 6.dp, bottom = 2.dp)',
                  '    ) {',
                  '        AppThemeAdaptiveTabRow('),
         after=L('    Column(',
                 '        modifier = modifier',
                 '            .fillMaxWidth()',
                 '            .padding(top = 6.dp, bottom = 2.dp)',
                 '    ) {',
                 '        AppThemeAdaptiveTabRow(')),
    dict(label='SpaceMainTabRow passes the providers to the shared tab row', origin='pager',
         before=L('            selectedValue = tabs[selectedIndex].tab,',
                  '            onSelectionChange = onSelect,'),
         after=L('            selectedValue = tabs[selectedIndex].tab,',
                 '            onSelectionChange = onSelect,',
                 '            indicatorPositionProvider = indicatorPositionProvider,',
                 '            isScrollInProgressProvider = isScrollInProgressProvider,')),
    dict(label='Banner with several header images rotates every four seconds, paused while dragged', origin='banner',
         before=L('        LaunchedEffect(pagerState.currentPage, topImages) {',
                  '            onCurrentBannerUrlChange(topImages.getOrNull(pagerState.currentPage)?.header)',
                  '        }'),
         after=L('        LaunchedEffect(pagerState.currentPage, topImages) {',
                 '            onCurrentBannerUrlChange(topImages.getOrNull(pagerState.currentPage)?.header)',
                 '        }',
                 '        // 多张头图自动轮播：用户拖动期间暂停，松手后重新计时',
                 '        val bannerDragging by pagerState.interactionSource.collectIsDraggedAsState()',
                 '        LaunchedEffect(pagerState, bannerDragging, topImages.size) {',
                 '            if (bannerDragging) return@LaunchedEffect',
                 '            while (true) {',
                 '                delay(SPACE_BANNER_AUTO_ROTATE_INTERVAL_MS)',
                 '                val nextPage = (pagerState.currentPage + 1) % topImages.size',
                 '                pagerState.animateScrollToPage(nextPage)',
                 '            }',
                 '        }')),
    dict(label='The pinned video card hides the overflow icon', origin='banner',
         before=L('        badgeLabel = "置顶",',
                  '        onClick = onClick,'),
         after=L('        badgeLabel = "置顶",',
                 '        showTrailingMore = false,',
                 '        onClick = onClick,')),
    dict(label='The archive row declares showTrailingMore', origin='banner',
         before=L('    statusLabels: List<String> = emptyList(),',
                  '    isLocateHighlight: Boolean = false,',
                  '    onClick: () -> Unit,'),
         after=L('    statusLabels: List<String> = emptyList(),',
                 '    isLocateHighlight: Boolean = false,',
                 '    showTrailingMore: Boolean = true,',
                 '    onClick: () -> Unit,')),
    dict(label='The archive row shows the overflow icon only when asked', origin='banner',
         before=L('        trailingContent = {',
                  '            AppIcon(',
                  '                imageVector = Icons.Outlined.MoreVert,',
                  '                contentDescription = null,',
                  '                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),',
                  '                modifier = Modifier',
                  '                    .align(Alignment.BottomEnd)',
                  '                    .padding(8.dp)',
                  '                    .size(18.dp),',
                  '            )',
                  '        },',
                  '    )',
                  '}'),
         after=L('        trailingContent = if (showTrailingMore) {',
                 '            {',
                 '                AppIcon(',
                 '                    imageVector = Icons.Outlined.MoreVert,',
                 '                    contentDescription = null,',
                 '                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),',
                 '                    modifier = Modifier',
                 '                        .align(Alignment.BottomEnd)',
                 '                        .padding(8.dp)',
                 '                        .size(18.dp),',
                 '                )',
                 '            }',
                 '        } else {',
                 '            null',
                 '        },',
                 '    )',
                 '}')),
]

# Added or changed text that has no upstream counterpart (everything else in EDITS is a line of the v0.4.6 SpaceScreen).
# Each entry is a line of an EDITS `after` that is not a line of the v0.4.6 file, kept as a pin so a test can prove the
# list is complete and no other line departs from upstream.
ADAPTATIONS = [
    ('the content pager is AppHorizontalPager (upstream aliases it as HorizontalPager)',
     '        AppHorizontalPager('),
    ('the pager gestures are always enabled: the pinned screen has no in-list skeleton state',
     '                enabled = true,'),
    ('AppHorizontalPager is imported under its own name',
     'import com.android.purebilibili.core.ui.components.AppHorizontalPager'),
]


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def wide(path):
    """Extended-length form on Windows, so a deep Gradle output path cannot reach MAX_PATH; unchanged elsewhere."""
    path = Path(path).absolute()
    if os.name != 'nt':
        return path
    text = str(path)
    prefix = chr(92) * 2 + '?' + chr(92)
    return Path(text if text.startswith(prefix) else prefix + text)


def fixed(repo):
    """Verified bytes of the carried upstream file. Raises when the archive differs from its pins in any way."""
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing v046 space content pager archive')
    need({p.name for p in root.iterdir()} == {LEAF, 'manifest.json', 'LICENSE'}, 'v046 space content pager leaves changed')
    raw = (root / 'manifest.json').read_bytes()
    need(sha(raw) == MANIFEST_SHA256, 'v046 space content pager manifest changed')
    manifest = json.loads(raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'v046 space content pager source identity changed')
    need(len(manifest['files']) == 1, 'v046 space content pager source set changed')
    row = manifest['files'][0]
    need((row['path'], row['filename'], row['bytes'], row['sha256'], row['gitBlob']) ==
         (LEAF_PATH, LEAF, LEAF_BYTES, LEAF_SHA256, LEAF_BLOB), 'v046 space content pager manifest row changed')
    leaf = root / LEAF
    need(leaf.is_file() and not leaf.is_symlink(), 'Linked v046 space content pager source')
    body = leaf.read_bytes()
    need(len(body) == LEAF_BYTES and sha(body) == LEAF_SHA256, 'v046 space content pager bytes changed')
    need(hashlib.sha1(b'blob ' + str(len(body)).encode() + b'\0' + body).hexdigest() == LEAF_BLOB,
         'v046 space content pager Git blob changed')
    need(b'\r' not in body, 'v046 space content pager source is not LF only')
    licence = root / 'LICENSE'
    need(licence.is_file() and not licence.is_symlink() and sha(licence.read_bytes()) == LICENSE_SHA256 ==
         manifest['license']['sha256'], 'v046 space content pager license changed')
    return body


def validate_outputs(repo, *outputs):
    """Call first in every producer that hooks this slice: archive intact and no output inside or around it."""
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps v046 space content pager inputs')


def adapt_pager(body):
    """The one counted, hash-pinned adaptation of the carried file. Returns (adapted bytes, edit record)."""
    text = body.decode('utf-8')
    need(text.count(ADAPT_BEFORE) == 1, 'Content tab pager forced priority anchor changed')
    need(text.count('forceVerticalPriority') == 1, 'Content tab pager no longer has exactly one forced priority use')
    need(text.count('verticalPriorityHorizontalPagerSwipe(') == 1, 'Content tab pager gesture call changed')
    at = text.index(ADAPT_BEFORE)
    adapted = text[:at] + ADAPT_AFTER + text[at + len(ADAPT_BEFORE):]
    data = adapted.encode('utf-8')
    need(len(data) == EMITTED_BYTES and sha(data) == EMITTED_SHA256, 'Content tab pager adapted bytes changed')
    need(adapted[:at] + ADAPT_BEFORE + adapted[at + len(ADAPT_AFTER):] == text, 'Content tab pager adaptation is not recoverable')
    edit = dict(offset=at, before=ADAPT_BEFORE, after=ADAPT_AFTER, count=1,
                label='The pinned gesture modifier is already the forced vertical-priority path')
    return data, edit


def emit_pager(repo, output):
    """Write the upstream file, with its one comment adaptation, under the producer output and return its row."""
    body = fixed(repo)
    data, edit = adapt_pager(body)
    target = wide(Path(output) / OUTPUT_RELATIVE)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    proof = dict(upstreamCommit=COMMIT, introducedBy=INTRODUCED_BY, amendedBy=AMENDED_BY, archive=ARCHIVE,
                 sourceManifestSHA256=MANIFEST_SHA256, sourcePath=LEAF_PATH, upstreamBlob=LEAF_BLOB,
                 inputBytes=LEAF_BYTES, inputSHA256=LEAF_SHA256, outputBytes=EMITTED_BYTES, outputSHA256=EMITTED_SHA256,
                 wholeOriginalEmittedByteForByte=False, topLevelNames=list(TOP_LEVEL_NAMES), edits=[edit],
                 fullInputInverseRecovered=True, emittedSHA256=sha(target.read_bytes()), mainCompiled=False,
                 applicationExecuted=False)
    proof_path = wide(Path(output) / PROOF_DIRECTORY / (LEAF + '.json'))
    proof_path.parent.mkdir(parents=True, exist_ok=True)
    proof_path.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    return dict(path=OUTPUT_RELATIVE, origin=LEAF_PATH, mode=OUTPUT_MODE, sha256LF=EMITTED_SHA256, generated=True)


def site_edits(relative, text):
    """Counted before/after replacements for the generated Space screen; [] for every other output.

    Fails closed when the text already carries the pager, when an anchor is missing or repeated, and when an earlier
    edit would change the number of times a later anchor occurs."""
    if relative != SPACE:
        return []
    need(PAGER_MARK not in text and 'SPACE_BANNER_AUTO_ROTATE_INTERVAL_MS' not in text and 'showTrailingMore' not in text,
         'The Space content pager is already applied or foreign: ' + relative)
    working = text
    for edit in EDITS:
        need(edit['before'] != edit['after'], 'Space content pager edit is empty: ' + edit['label'])
        need(text.count(edit['before']) == 1, 'Space content pager anchor changed: ' + edit['label'])
        need(working.count(edit['before']) == 1, 'Space content pager anchor moved: ' + edit['label'])
        working = working.replace(edit['before'], edit['after'], 1)
    need(working.count(PAGER_MARK) == 1 and working.count('SpaceMainTabRow(') == 3,
         'Space content pager call count changed')
    return [dict(label=edit['label'], before=edit['before'], after=edit['after']) for edit in EDITS]

package com.bilipai.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.feature.dynamic.*
import com.android.purebilibili.feature.dynamic.components.DynamicUserLiveBadge
import com.android.purebilibili.feature.home.components.BottomBarLiquidSegmentedControl
import com.android.purebilibili.feature.home.components.FloatingBottomBarGeometryMode

/** Five original dynamic hosts. Horizontal reuses HorizontalUserList; the other four use the sidebar geometry. */
@Composable
internal fun DesktopDynamicModeFrame(
    mode: DesktopDynamicDisplayMode,
    sidebarExpanded: Boolean,
    onToggleSidebar: () -> Unit,
    showHorizontalUsers: Boolean,
    panelUsers: List<SidebarUser>,
    selectedUid: Long?,
    selfUid: Long,
    showHidden: Boolean,
    hiddenCount: Int,
    unread: Set<Long>,
    onUserClick: (Long?) -> Unit,
    onToggleShowHidden: () -> Unit,
    onTogglePin: (Long) -> Unit,
    onToggleHidden: (Long) -> Unit,
    horizontalUsers: @Composable () -> Unit,
    header: @Composable () -> Unit,
    notices: @Composable () -> Unit,
    feed: @Composable () -> Unit,
    pageLayoutDirection: DesktopDynamicPageLayoutDirection = DesktopDynamicPageLayoutDirection.LEFT,
    topBarOverlaysFeed: Boolean = false,
    userListOverlaysFeed: Boolean = false,
    escapeArmed: Boolean = true,
) {
    val sidebarOnRight = resolveDynamicSidebarOnRight(mode, pageLayoutDirection)
    BindDesktopDynamicDrawerEscape(
        active = escapeArmed,
        expanded = mode.isDrawer() && sidebarExpanded,
        onClose = onToggleSidebar,
    )
    if (mode.isHorizontalUserList()) {
        Column(Modifier.fillMaxSize()) {
            if (!topBarOverlaysFeed) header()
            if (showHorizontalUsers && !userListOverlaysFeed) horizontalUsers()
            notices()
            Box(Modifier.weight(1f).fillMaxWidth()) { feed() }
        }
        return
    }
    val showPanel = !mode.isDrawer() || sidebarExpanded
    Row(Modifier.fillMaxSize()) {
        if (!sidebarOnRight && showPanel) {
            DesktopDynamicSidebarPanel(
                users = panelUsers,
                selectedUserId = selectedUid,
                selfUid = selfUid,
                isExpanded = sidebarExpanded,
                showHiddenUsers = showHidden,
                hiddenCount = hiddenCount,
                uplistUpdateMids = unread,
                onUserClick = { uid ->
                    onUserClick(uid)
                    if (mode.isDrawer() && sidebarExpanded) onToggleSidebar()
                },
                onToggleShowHidden = onToggleShowHidden,
                onTogglePin = onTogglePin,
                onToggleHidden = onToggleHidden,
                onHeaderClick = onToggleSidebar,
                alignDividerToStart = false,
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize()) {
                if (!topBarOverlaysFeed) header()
                notices()
                Box(Modifier.weight(1f).fillMaxWidth()) { feed() }
            }
            if (mode.isDrawer() && !sidebarExpanded) {
                Box(
                    Modifier
                        .align(if (sidebarOnRight) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(8.dp)
                        .largeScreenControlTarget(
                            contentDescription = resolveDynamicDrawerHandleDescription(sidebarOnRight),
                            onClick = onToggleSidebar,
                            shape = CircleShape,
                        )
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText(
                        text = if (sidebarOnRight) "‹" else "›",
                        modifier = Modifier.clearAndSetSemantics { },
                        style = MaterialTheme.typography.headlineMedium,
                        tapToCopyEnabled = false,
                    )
                }
            }
        }
        if (sidebarOnRight && showPanel) {
            DesktopDynamicSidebarPanel(
                users = panelUsers,
                selectedUserId = selectedUid,
                selfUid = selfUid,
                isExpanded = sidebarExpanded,
                showHiddenUsers = showHidden,
                hiddenCount = hiddenCount,
                uplistUpdateMids = unread,
                onUserClick = { uid ->
                    onUserClick(uid)
                    if (mode.isDrawer() && sidebarExpanded) onToggleSidebar()
                },
                onToggleShowHidden = onToggleShowHidden,
                onTogglePin = onTogglePin,
                onToggleHidden = onToggleHidden,
                onHeaderClick = onToggleSidebar,
                alignDividerToStart = true,
            )
        }
    }
}

@Composable
internal fun DesktopDynamicTabStrip(
    tabs: List<DynamicTabSpec>,
    selectedLogicalTab: Int,
    displayMode: DesktopDynamicDisplayMode,
    onSelectLogicalTab: (Int) -> Unit,
    onDisplayModeChange: (DesktopDynamicDisplayMode) -> Unit,
    trailing: @Composable () -> Unit,
    actionDockCollapsed: Boolean = false,
    onActionDockCollapsedChange: (Boolean) -> Unit = {},
    previewTextVisible: Boolean = true,
    onPreviewTextVisibleChange: (Boolean) -> Unit = {},
    detailImageLayoutValue: Int = 0,
    onDetailImageLayoutChange: (Int) -> Unit = {},
    topBarCollapseOnScroll: Boolean = false,
    onTopBarCollapseOnScrollChange: (Boolean) -> Unit = {},
    pageLayoutDirection: DesktopDynamicPageLayoutDirection = DesktopDynamicPageLayoutDirection.LEFT,
    onPageLayoutDirectionChange: (DesktopDynamicPageLayoutDirection) -> Unit = {},
) {
    val liquid = LocalAppThemeConfig.current.liquidGlassEnabled
    val glass = LocalDesktopWindowsGlassMaterial.current
    val backdrop = glass?.backdrop?.takeIf {
        liquid && glass.renderer.supported && glass.sourceReady && glass.owns()
    }
    Row(
        Modifier.fillMaxWidth()
            .largeScreenFocusTraversal(DesktopLargeScreenFocusAxis.HORIZONTAL)
            .padding(horizontal = resolveDynamicTopBarHorizontalPadding()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (liquid && tabs.isNotEmpty()) {
                val spec = resolveDynamicTopBarLiquidTabSpec()
                val selectedIndex = tabs.indexOfFirst { it.logicalIndex == selectedLogicalTab }.coerceAtLeast(0)
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    val tabCount = tabs.size.coerceAtLeast(1)
                    val tabItemWidth = ((maxWidth - AppSpacingTokens.ExtraSmall * 2) / tabCount).coerceIn(
                        AppChromeSizeTokens.MinimumTouchTarget,
                        resolveDynamicTopBarTabItemWidthDp().dp,
                    )
                    BottomBarLiquidSegmentedControl(
                        items = tabs.map { it.title },
                        selectedIndex = selectedIndex,
                        onSelected = { index ->
                            tabs.getOrNull(index)?.logicalIndex?.let(onSelectLogicalTab)
                        },
                        itemWidth = tabItemWidth,
                        height = spec.heightDp.dp,
                        indicatorHeight = spec.indicatorHeightDp.dp,
                        labelFontSize = spec.labelFontSizeSp.sp,
                        geometryMode = FloatingBottomBarGeometryMode.TopNavigation,
                        liquidGlassEffectsEnabled = true,
                        dragSelectionEnabled = tabs.size > 1,
                        tapPressRefractionEnabled = true,
                        miuixBackdrop = backdrop,
                        modifier = Modifier.height(spec.heightDp.dp),
                    )
                }
            } else {
                AppNativeTabRow(
                    options = tabs.map { AppSegmentOption(it.logicalIndex, it.title) },
                    selectedValue = selectedLogicalTab,
                    modifier = Modifier.fillMaxWidth(),
                    onSelectionChange = onSelectLogicalTab,
                )
            }
        }
        AnimatedVisibility(
            visible = !actionDockCollapsed,
            enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppWindowActionMenu(
                    groups = buildDynamicPageOverflowGroups(
                        displayMode = displayMode,
                        previewTextVisible = previewTextVisible,
                        detailImageLayoutValue = detailImageLayoutValue,
                        topBarCollapseOnScroll = topBarCollapseOnScroll,
                        pageLayoutDirection = pageLayoutDirection,
                        onDisplayMode = onDisplayModeChange,
                        onPreviewText = onPreviewTextVisibleChange,
                        onDetailLayout = onDetailImageLayoutChange,
                        onTopBarCollapse = onTopBarCollapseOnScrollChange,
                        onDirection = onPageLayoutDirectionChange,
                    ),
                ) {
                    Box(
                        Modifier.size(desktopLargeScreenMinimumTarget()),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppIcon(
                            imageVector = if (displayMode.isHorizontalUserList()) rememberAppGridLayoutIcon() else rememberAppListLayoutIcon(),
                            contentDescription = resolveDynamicDisplayModeDescription(),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(AppSpacingTokens.ExtraLarge - AppSpacingTokens.Micro),
                        )
                    }
                }
                trailing()
            }
        }
        AppIconButton(
            onClick = { onActionDockCollapsedChange(!actionDockCollapsed) },
            modifier = Modifier.defaultMinSize(
                minWidth = desktopLargeScreenMinimumTarget(),
                minHeight = desktopLargeScreenMinimumTarget(),
            ),
        ) {
            AppIcon(
                imageVector = if (actionDockCollapsed) rememberAppChevronDownIcon() else rememberAppChevronUpIcon(),
                contentDescription = resolveDynamicActionDockDescription(actionDockCollapsed),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(AppSpacingTokens.ExtraLarge - AppSpacingTokens.Micro),
            )
        }
    }
}

/** Overlay chrome. Reads the scroll snapshot here so the card grid does not recompose per pixel. */
@Composable
internal fun DesktopDynamicCollapsingChrome(
    scroll: DesktopDynamicFeedChromeScroll,
    showTopBar: Boolean,
    showUserList: Boolean,
    topBar: @Composable () -> Unit,
    userList: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = scroll.firstVisibleItemIndex
    val offset = scroll.firstVisibleItemScrollOffset
    val topCollapsed = shouldCollapseDynamicTopBar(
        collapseOnScrollEnabled = showTopBar,
        firstVisibleItemIndex = index,
        firstVisibleItemScrollOffset = offset,
        topTolerancePx = DynamicHeaderCollapseTriggerPx,
    )
    val usersCollapsed = shouldCollapseDynamicHorizontalUserList(
        firstVisibleItemIndex = index,
        firstVisibleItemScrollOffset = offset,
        topTolerancePx = DynamicHeaderCollapseTriggerPx,
    )
    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = showTopBar && !topCollapsed,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
        ) { topBar() }
        AnimatedVisibility(
            visible = showUserList && !usersCollapsed,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
        ) { userList() }
    }
}

@Composable
internal fun DesktopDynamicSidebarPanel(
    users: List<SidebarUser>,
    selectedUserId: Long?,
    selfUid: Long,
    isExpanded: Boolean,
    showHiddenUsers: Boolean,
    hiddenCount: Int,
    uplistUpdateMids: Set<Long>,
    onUserClick: (Long?) -> Unit,
    onToggleShowHidden: () -> Unit,
    onTogglePin: (Long) -> Unit,
    onToggleHidden: (Long) -> Unit,
    onHeaderClick: () -> Unit,
    alignDividerToStart: Boolean,
) {
    val width by animateDpAsState(resolveDynamicSidebarWidth(isExpanded), label = "sidebarWidth")
    val wallpaperVisible = LocalGlobalWallpaperBackdropVisible.current
    val surface = AppSurfaceTokens.surface()
    val containerColor = resolveDynamicSidebarContainerColor(surface, wallpaperVisible)
    val headerColor = resolveDynamicSidebarReturnHeaderColor(surface, backgroundAlpha = 0.92f, wallpaperVisible)
    val headerHeight = resolveDynamicSidebarReturnHeaderHeightDp().dp
    val listState = rememberLazyListState()
    val fling = rememberDesktopDynamicSidebarFlingBehavior()
    val liveUsers = remember(users, selfUid) {
        users.filter { it.isLive && !isDynamicUpPanelShortcut(it.uid, selfUid) }
    }
    val shortcutUsers = remember(users, selfUid) { users.filter { isDynamicUpPanelShortcut(it.uid, selfUid) } }
    val restUsers = remember(users, selfUid) {
        users.filter { !it.isLive && !isDynamicUpPanelShortcut(it.uid, selfUid) }
    }
    var showLiveUsers by remember { mutableStateOf(true) }
    val visibleUsers = remember(liveUsers, shortcutUsers, restUsers, showLiveUsers) {
        buildList {
            if (showLiveUsers) addAll(liveUsers)
            addAll(shortcutUsers)
            addAll(restUsers)
        }
    }
    var initialEntranceActive by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(400)
        initialEntranceActive = false
    }
    val hasScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    val glass = desktopWindowsGlassSurfaceMaterial(
        Modifier.width(width).fillMaxHeight(),
        RectangleShape,
        containerColor,
    )
    Box(
        glass.modifier.largeScreenFocusTraversal(DesktopLargeScreenFocusAxis.VERTICAL).clip(RectangleShape).then(
            if (glass.enabled) Modifier else Modifier.background(containerColor),
        ),
    ) {
        LazyColumn(
            state = listState,
            flingBehavior = fling,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = headerHeight, bottom = AppSpacingTokens.Large),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (hiddenCount > 0 || showHiddenUsers) {
                item(key = "hidden_toggle") {
                    Box(
                        Modifier.padding(bottom = AppSpacingTokens.Medium)
                            .largeScreenControlTarget(
                                contentDescription = resolveDynamicHiddenUsersDescription(showHiddenUsers),
                                onClick = onToggleShowHidden,
                                shape = CircleShape,
                            )
                            .clip(CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppIcon(
                            imageVector = if (showHiddenUsers) rememberAppVisibilityOnIcon() else rememberAppVisibilityOffIcon(),
                            contentDescription = null,
                            tint = if (showHiddenUsers) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(AppSpacingTokens.Large + AppSpacingTokens.ExtraSmall),
                        )
                    }
                }
            }
            if (liveUsers.isNotEmpty()) {
                item(key = "live_fold") {
                    AppTextButton(
                        onClick = { showLiveUsers = !showLiveUsers },
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = desktopLargeScreenMinimumTarget())
                            .semantics {
                                contentDescription = resolveDynamicLiveFoldDescription(showLiveUsers, liveUsers.size)
                            },
                        contentPadding = PaddingValues(horizontal = AppSpacingTokens.None, vertical = AppSpacingTokens.ExtraSmall),
                    ) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            AppText(
                                "Live(${liveUsers.size})",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                tapToCopyEnabled = false,
                            )
                            AppIcon(
                                imageVector = if (showLiveUsers) rememberAppChevronUpIcon() else rememberAppChevronDownIcon(),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(AppSpacingTokens.Medium),
                            )
                        }
                    }
                }
            }
            itemsIndexed(visibleUsers, key = { _, user -> "sidebar_${user.uid}" }) { index, user ->
                DesktopDynamicSidebarCascadeItem(
                    index = index,
                    enabled = shouldAnimateSidebarItemCascade(index, hasScrolled, initialEntranceActive),
                ) {
                    DesktopDynamicSidebarUserItem(
                        user = user,
                        isSelected = isDynamicUpPanelItemSelected(selectedUserId, user.uid),
                        showLabel = isExpanded,
                        showUnreadBadge = user.uid in uplistUpdateMids,
                        allowManageMenu = !isDynamicUpPanelShortcut(user.uid, selfUid),
                        onClick = { onUserClick(user.uid) },
                        onTogglePin = { onTogglePin(user.uid) },
                        onToggleHidden = { onToggleHidden(user.uid) },
                    )
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(headerHeight).align(Alignment.TopCenter)
                .largeScreenControlTarget(
                    contentDescription = resolveDynamicSidebarHeaderDescription(isExpanded),
                    onClick = onHeaderClick,
                    shape = RectangleShape,
                )
                .background(headerColor),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(
                imageVector = rememberAppBackIcon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(AppSpacingTokens.ExtraLarge),
            )
        }
        Box(
            Modifier.align(if (alignDividerToStart) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(top = resolveDynamicSidebarDividerTopOffset(0.dp))
                .fillMaxHeight()
                .width(AppSpacingTokens.Micro / 4)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
        )
    }
}

@Composable
private fun DesktopDynamicSidebarUserItem(
    user: SidebarUser,
    isSelected: Boolean,
    showLabel: Boolean,
    showUnreadBadge: Boolean,
    allowManageMenu: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleHidden: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val face = resolveDynamicSidebarUserAvatarUrl(user.face)
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .largeScreenControlTarget(
                    contentDescription = resolveDynamicUserContentDescription(user.name, user.isHidden, user.isPinned),
                    onClick = onClick,
                    isSelected = isSelected,
                    onLongClick = if (allowManageMenu) ({ showMenu = true }) else null,
                )
                .padding(AppSpacingTokens.ExtraSmall)
                .alpha(if (user.isHidden) 0.5f else 1f),
        ) {
            Box {
                Box(
                    Modifier.size(AppSpacingTokens.DoubleExtraLarge + AppSpacingTokens.Small)
                        .clip(CircleShape)
                        .then(
                            when {
                                isSelected -> Modifier.border(AppSpacingTokens.Micro, MaterialTheme.colorScheme.primary, CircleShape)
                                showUnreadBadge -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                else -> Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (face.isNotEmpty()) {
                        AsyncImage(
                            model = face,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                if (showUnreadBadge) {
                    Box(
                        Modifier.align(Alignment.TopEnd).size(AppSpacingTokens.Small)
                            .clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            if (shouldShowDynamicUserLiveBadge(user.isLive)) {
                DynamicUserLiveBadge(Modifier.padding(top = AppSpacingTokens.Micro))
            }
            if (showLabel) {
                AppText(
                    if (user.isHidden) "${user.name}(隐)" else user.name,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    fontSize = MaterialTheme.typography.labelSmall.fontSize,
                    tapToCopyEnabled = false,
                )
            }
        }
        AppDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            AppDropdownMenuItem(
                text = { AppText(if (user.isPinned) "取消置顶" else "置顶") },
                onClick = { showMenu = false; onTogglePin() },
            )
            AppDropdownMenuItem(
                text = { AppText(if (user.isHidden) "取消隐藏" else "隐藏") },
                onClick = { showMenu = false; onToggleHidden() },
            )
        }
    }
}

@Composable
private fun DesktopDynamicSidebarCascadeItem(
    index: Int,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(30L * index)
        visible = true
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "sidebarCascade")
    Box(Modifier.graphicsLayer { this.alpha = alpha }) { content() }
}

@Composable
private fun rememberDesktopDynamicSidebarFlingBehavior(): FlingBehavior {
    val base = ScrollableDefaults.flingBehavior()
    val factor = resolveDynamicSidebarFlingDampingFactor()
    return remember(base, factor) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                val leftover = with(base) { performFling(initialVelocity * factor) }
                return if (factor > 0f) leftover / factor else 0f
            }
        }
    }
}

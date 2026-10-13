package com.bilipai.desktop.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
) {
    if (mode.isHorizontalUserList()) {
        Column(Modifier.fillMaxSize()) {
            header()
            if (showHorizontalUsers) horizontalUsers()
            notices()
            Box(Modifier.weight(1f).fillMaxWidth()) { feed() }
        }
        return
    }
    val sidebarOnRight = mode.isRightAligned()
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
                header()
                notices()
                Box(Modifier.weight(1f).fillMaxWidth()) { feed() }
            }
            if (mode.isDrawer() && !sidebarExpanded) {
                AppSmallFloatingActionButton(
                    onClick = onToggleSidebar,
                    modifier = Modifier
                        .align(if (sidebarOnRight) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(8.dp),
                ) {
                    AppText(
                        text = if (sidebarOnRight) "‹" else "›",
                        style = MaterialTheme.typography.headlineMedium,
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
) {
    val liquid = LocalAppThemeConfig.current.liquidGlassEnabled
    val glass = LocalDesktopWindowsGlassMaterial.current
    val backdrop = glass?.backdrop?.takeIf {
        liquid && glass.renderer.supported && glass.sourceReady && glass.owns()
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = resolveDynamicTopBarHorizontalPadding()),
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
        AppWindowActionMenu(
            groups = listOf(
                DesktopDynamicDisplayMode.entries.map { mode ->
                    AppWindowAction(
                        label = resolveDynamicDisplayModeLabel(mode),
                        selected = displayMode == mode,
                        onClick = { onDisplayModeChange(mode) },
                    )
                },
            ),
        ) {
            AppIcon(
                imageVector = if (displayMode.isHorizontalUserList()) rememberAppGridLayoutIcon() else rememberAppListLayoutIcon(),
                contentDescription = "关注列表位置",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(AppSpacingTokens.ExtraLarge - AppSpacingTokens.Micro),
            )
        }
        trailing()
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
        glass.modifier.clip(RectangleShape).then(
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
                            .size(AppChromeSizeTokens.MinimumTouchTarget)
                            .clip(CircleShape)
                            .clickable(onClick = onToggleShowHidden),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppIcon(
                            imageVector = if (showHiddenUsers) rememberAppVisibilityOnIcon() else rememberAppVisibilityOffIcon(),
                            contentDescription = if (showHiddenUsers) "隐藏已隐藏用户" else "显示隐藏用户",
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
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = AppSpacingTokens.None, vertical = AppSpacingTokens.ExtraSmall),
                    ) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            AppText(
                                "Live(${liveUsers.size})",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                            )
                            AppIcon(
                                imageVector = if (showLiveUsers) rememberAppChevronUpIcon() else rememberAppChevronDownIcon(),
                                contentDescription = if (showLiveUsers) "收起直播" else "展开直播",
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
                .background(headerColor).clickable(onClick = onHeaderClick),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(
                imageVector = rememberAppBackIcon(),
                contentDescription = if (isExpanded) "收起关注列表" else "展开关注列表",
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

@OptIn(ExperimentalFoundationApi::class)
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
                .combinedClickable(onClick = onClick, onLongClick = { if (allowManageMenu) showMenu = true })
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
                            contentDescription = user.name,
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

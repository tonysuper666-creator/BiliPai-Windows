package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.android.purebilibili.core.store.DesktopOriginalLinkedDockSettings
import com.android.purebilibili.core.util.BilibiliNavigationTarget
import com.android.purebilibili.feature.home.LocalHomeFeedScrollInProgress
import com.android.purebilibili.feature.home.LocalHomeScrollOffset
import com.android.purebilibili.feature.home.createContinuousScrollOffsetConnection
import com.android.purebilibili.feature.home.components.BottomNavItem
import com.android.purebilibili.feature.home.components.LinkedBottomDock
import com.android.purebilibili.feature.home.components.LinkedDockNowPlayingSlot
import com.android.purebilibili.feature.home.components.LinkedDockPhase
import com.android.purebilibili.feature.home.components.LiquidGlassTuning
import com.android.purebilibili.feature.home.components.SharedFloatingBottomBarIconStyle
import com.android.purebilibili.feature.home.components.resolveLinkedDockPhaseOnAudioChange
import com.android.purebilibili.navigation.submitDesktopOriginalBottomBarSearchKeyword
import com.bilipai.desktop.plugins.DesktopPluginContext
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.combine
import top.yukonga.miuix.kmp.blur.Backdrop

/** Root passes the actual page state and retained phase. No competing state authority. */
internal class DesktopOriginalLinkedDockOwner(
    val scrollOffset: MutableFloatState,
    val feedScrollInProgress: MutableState<Boolean>,
    val phase: State<LinkedDockPhase>,
    val onPhaseChange: (LinkedDockPhase) -> Unit,
    val isOwned: () -> Boolean,
) {
    private val sourceConnection = createContinuousScrollOffsetConnection(scrollOffset)
    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (isOwned()) sourceConnection.onPreScroll(available, source) else Offset.Zero
    }
}

private val LocalDesktopOriginalLinkedDockOwner = staticCompositionLocalOf<DesktopOriginalLinkedDockOwner> {
    error("Original linked dock requires the Root page scroll and phase owner")
}

/** Apply pageScrollModifier once to the actual page nested-scroll ancestor, outside the dock. */
@Composable
internal fun DesktopOriginalLinkedDockOwnerProvider(
    owner: DesktopOriginalLinkedDockOwner,
    content: @Composable (pageScrollModifier: Modifier) -> Unit,
) {
    if (!owner.isOwned()) return
    CompositionLocalProvider(
        LocalDesktopOriginalLinkedDockOwner provides owner,
        LocalHomeScrollOffset provides owner.scrollOffset,
        LocalHomeFeedScrollInProgress provides owner.feedScrollInProgress,
    ) {
        content(Modifier.nestedScroll(owner.nestedScrollConnection))
    }
}

private data class DesktopLinkedDockPreferences(
    val labelMode: Int,
    val searchEnabled: Boolean,
    val mergeOnScrollEnabled: Boolean,
    val listScopedSearchEnabled: Boolean,
)

/** Only platform/owner binding. The sole original LinkedBottomDock owns all rendering and motion. */
@Composable
internal fun DesktopOriginalLinkedDock(
    preferenceContext: DesktopPluginContext,
    currentItem: BottomNavItem,
    firstItem: BottomNavItem,
    firstLabel: String,
    collapseRequested: Boolean,
    isTopLevelDestination: Boolean,
    isMainHost: Boolean,
    hasActiveAudioPlayback: Boolean,
    onSearchClick: () -> Unit,
    onOpenSearch: (String) -> Unit,
    onOpenNativeTarget: (BilibiliNavigationTarget) -> Unit,
    historyListScopedSearchChannel: SendChannel<String>?,
    favoriteListScopedSearchChannel: SendChannel<String>?,
    watchLaterListScopedSearchChannel: SendChannel<String>?,
    containerColor: Color,
    backdrop: Backdrop?,
    glassEnabled: Boolean,
    liquidGlassTuning: LiquidGlassTuning,
    iconStyle: SharedFloatingBottomBarIconStyle,
    navigationItemCount: Int,
    navigationMinEdgePadding: Dp,
    nowPlayingContent: LinkedDockNowPlayingSlot?,
    animateNowPlayingPresence: Boolean,
    blurEnabled: Boolean,
    hazeState: HazeState?,
    modifier: Modifier,
    navigationContent: @Composable () -> Unit,
) {
    val owner = LocalDesktopOriginalLinkedDockOwner.current
    if (!owner.isOwned()) return
    checkNotNull(LocalNavigationEventDispatcherOwner.current) {
        "Original linked dock requires Root's existing navigation back dispatcher"
    }
    // Original AppNavigation reconciles its retained phase from the active session,
    // selected item, and now-playing preference; Root supplies that same resolved value.
    LaunchedEffect(hasActiveAudioPlayback) {
        if (!owner.isOwned()) return@LaunchedEffect
        val reconciled = resolveLinkedDockPhaseOnAudioChange(owner.phase.value, hasActiveAudioPlayback)
        if (reconciled != owner.phase.value) {
            owner.onPhaseChange(reconciled)
        }
    }
    val preferences by remember(preferenceContext) {
        combine(
            DesktopOriginalLinkedDockSettings.getBottomBarLabelMode(preferenceContext),
            DesktopOriginalLinkedDockSettings.getBottomBarSearchEnabled(preferenceContext),
            DesktopOriginalLinkedDockSettings.getLinkedDockMergeOnScrollEnabled(preferenceContext),
            DesktopOriginalLinkedDockSettings.getListScopedSearchEnabled(preferenceContext),
        ) { labels, search, merge, listScoped -> DesktopLinkedDockPreferences(labels, search, merge, listScoped) }
    }.collectAsState<DesktopLinkedDockPreferences, DesktopLinkedDockPreferences?>(initial = null)
    val resolved = preferences ?: return
    LinkedBottomDock(
        currentItem = currentItem,
        firstItem = firstItem,
        firstLabel = firstLabel,
        searchEnabled = resolved.searchEnabled,
        isFeedScrollInProgress = owner.feedScrollInProgress.value,
        collapseRequested = collapseRequested,
        onSearchClick = { if (owner.isOwned()) onSearchClick() },
        onSearchKeywordSubmit = { keyword ->
            submitDesktopOriginalBottomBarSearchKeyword(
                keyword = keyword,
                bottomBarSearchEnabled = resolved.searchEnabled,
                listScopedSearchEnabled = resolved.listScopedSearchEnabled,
                isMainHost = isMainHost,
                currentBottomNavItem = currentItem,
                historyListScopedSearchChannel = historyListScopedSearchChannel,
                favoriteListScopedSearchChannel = favoriteListScopedSearchChannel,
                watchLaterListScopedSearchChannel = watchLaterListScopedSearchChannel,
                onOpenSearch = onOpenSearch,
                onOpenNativeTarget = onOpenNativeTarget,
                isOwned = owner.isOwned,
            )
        },
        containerColor = containerColor,
        backdrop = backdrop,
        glassEnabled = glassEnabled,
        liquidGlassTuning = liquidGlassTuning,
        iconStyle = iconStyle,
        navigationItemCount = navigationItemCount,
        navigationLabelMode = resolved.labelMode,
        navigationMinEdgePadding = navigationMinEdgePadding,
        nowPlayingContent = nowPlayingContent,
        dockPhase = owner.phase.value,
        onDockPhaseChange = { if (owner.isOwned()) owner.onPhaseChange(it) },
        isTopLevelDestination = isTopLevelDestination,
        mergeOnScrollDownEnabled = resolved.mergeOnScrollEnabled,
        animateNowPlayingPresence = animateNowPlayingPresence,
        modifier = modifier.excludeFromLiquidBackground(),
        blurEnabled = blurEnabled,
        hazeState = hazeState,
        navigationContent = navigationContent,
    )
}

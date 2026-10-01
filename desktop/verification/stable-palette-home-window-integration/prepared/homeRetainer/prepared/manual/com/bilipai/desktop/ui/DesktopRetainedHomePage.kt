package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.unit.Dp
import com.android.purebilibili.feature.home.HomeScrollRequest
import com.android.purebilibili.core.ui.transition.VideoCardTransitionBackgroundState
import com.android.purebilibili.core.ui.transition.VideoCardTransitionClock
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.channels.Channel

/** Actual full original Home renderer. Root supplies its one real dock/scroll/clock state;
 * no new data owner, fake return state, navigation fallback, settings or native lease lives here.
 * MainHost/Dock/Profile/other keys are distinct Root destinations, never aliased to Home.
 */
@Composable internal fun DesktopRetainedHomePage(
    root: DesktopHomeRetainedRoot,
    window: DesktopHomeRootWindowBindings,
    navigation: DesktopOriginalHomeNavigation,
    scrollOffset: MutableFloatState,
    feedScrollInProgress: MutableState<Boolean>,
    scrollChannel: Channel<HomeScrollRequest>,
    bottomBarVisible: Boolean,
    bottomBarContentPadding: Dp,
    setBottomBarVisible: (Boolean) -> Unit,
    transitionBackground: VideoCardTransitionBackgroundState,
    transitionClock: VideoCardTransitionClock,
    globalHazeState: HazeState?,
    isTopLevelActive: Boolean,
    homeGraphicsLayerCaptureReady: Boolean,
) {
    if (!root.isCurrentOwner()) return
    check(window.context === root.environment.pluginContext && window.settings === root.environment.settings)
    val returning by root.returns.session.collectAsState()
    DesktopOriginalHomeRoot(root.entry.ui, root.environment, root.media,
        desktopHomeActualPlatform(window.resources.background, window.resources.metrics, homeGraphicsLayerCaptureReady),
        window.resources.metrics.holder, { url, size, count -> root.ErrorAnimation(url, size, count) },
        navigation, scrollOffset, feedScrollInProgress, scrollChannel, bottomBarVisible,
        bottomBarContentPadding, setBottomBarVisible, transitionBackground, transitionClock,
        globalHazeState, isTopLevelActive,
        returning.isReturningFromDetail, returning.isQuickReturnFromDetail,
        { root.returns.consumeReturning() })
}

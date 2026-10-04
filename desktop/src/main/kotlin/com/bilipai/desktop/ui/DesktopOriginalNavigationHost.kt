package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import com.android.purebilibili.core.store.AppNavigationSettings
import com.android.purebilibili.core.store.HomeSettings
import com.android.purebilibili.core.ui.transition.VideoCardTransitionClock
import com.android.purebilibili.navigation3.BiliPaiNavDisplayHost
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.navigation3.BiliPaiNavSourceMetadata
import com.android.purebilibili.navigation3.BiliPaiProgrammaticBackDispatcher
import com.android.purebilibili.navigation3.resolveVideoCardTransitionEnabledForSource
import com.android.purebilibili.navigation.resolveAppNavigationAppearance
import com.android.purebilibili.navigation3.predictiveback.BiliPaiPredictiveBackAnimationStyle
import com.android.purebilibili.navigation3.predictiveback.BiliPaiPredictiveBackExitDirection

/** Whole original host. Root must supply actual stack, settings, clock and navigation actors. */
@Composable
internal fun DesktopOriginalNavigationHost(
    environment: DesktopNavigationHostEnvironment,
    backStack: SnapshotStateList<BiliPaiNavKey>,
    homeSettings: HomeSettings,
    navigationSettings: AppNavigationSettings,
    isLightBackground: Boolean,
    reduceMotion: Boolean,
    videoSharedTransitionDurationMillis: Int,
    videoCardClock: VideoCardTransitionClock,
    sourceMetadata: BiliPaiNavSourceMetadata,
    programmaticBackDispatcher: BiliPaiProgrammaticBackDispatcher,
    preferWholeCardReturn: Boolean,
    onBack: () -> Unit,
    onPrepareVideoCardSharedReturn: () -> Boolean,
    onRelatedVideoDetailReturned: () -> Unit,
    restorePreviousVideoSourceOnDetailReturn: Boolean,
    modifier: Modifier,
    content: @Composable (BiliPaiNavKey) -> Unit,
) {
    if (!environment.isCurrent()) return
    CompositionLocalProvider(
        LocalDesktopNavigationHostEnvironment provides environment,
        LocalLifecycleOwner provides environment.rootLifecycleOwner,
        LocalViewModelStoreOwner provides environment.rootViewModelStoreOwner,
        LocalSavedStateRegistryOwner provides environment.rootSavedStateRegistryOwner,
        LocalNavigationEventDispatcherOwner provides environment.rootNavigationEventOwner,
    ) {
        DesktopWindowsNavigationDisplay(
            environment = environment,
            backStack = backStack,
            videoCardClock = videoCardClock,
            programmaticBackDispatcher = programmaticBackDispatcher,
            onBack = onBack,
            onPrepareVideoCardSharedReturn = onPrepareVideoCardSharedReturn,
            onRelatedVideoDetailReturned = onRelatedVideoDetailReturned,
            restorePreviousVideoSourceOnDetailReturn = restorePreviousVideoSourceOnDetailReturn,
            modifier = modifier,
            content = content,
        )
    }
}

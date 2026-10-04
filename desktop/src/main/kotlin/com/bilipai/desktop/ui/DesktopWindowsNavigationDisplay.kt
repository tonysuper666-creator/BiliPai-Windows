package com.bilipai.desktop.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.android.purebilibili.core.ui.LocalAnimatedVisibilityScope
import com.android.purebilibili.core.ui.LocalGlobalWallpaperBackdropVisible
import com.android.purebilibili.core.ui.LocalSharedTransitionEnabled
import com.android.purebilibili.core.ui.LocalSharedTransitionScope
import com.android.purebilibili.core.ui.transition.LocalClickToPlayEnabled
import com.android.purebilibili.core.ui.transition.LocalDynamicImagePreviewTextVisible
import com.android.purebilibili.core.ui.transition.LocalVideoCardSharedElementSourceRoute
import com.android.purebilibili.core.ui.transition.LocalVideoCardTransitionClock
import com.android.purebilibili.core.ui.transition.VideoCardTransitionClock
import com.android.purebilibili.navigation3.*
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition

/** Identity geometry and zero-duration driver; NONE in the Android host still uses a slide. */
internal val DesktopWindowsInstantNavigationTransition = navGraphicsTransition(
    opaqueDepth = 0f,
    motion = NavMotion(
        commit = NavSettleSpec.Tween(0, LinearEasing),
        cancel = NavSettleSpec.Tween(0, LinearEasing),
        programmatic = NavSettleSpec.Tween(0, LinearEasing),
    ),
    dismissDirection = NavSwipeDirection.None,
    scrim = { 0f },
) { /* Native desktop surfaces keep their actual client coordinates. */ }

/** Original nonanimated back sequence: the real Root owns every mutation and restoration. */
internal fun completeDesktopWindowsNavigationBack(
    leavingKey: BiliPaiNavKey?,
    restorePreviousVideoSource: Boolean,
    prepareVideoReturn: () -> Boolean,
    back: () -> Unit,
    relatedVideoReturned: () -> Unit,
) {
    if (leavingKey is BiliPaiNavKey.VideoDetail) prepareVideoReturn()
    val returningFromRelated = (leavingKey as? BiliPaiNavKey.VideoDetail)?.sourceRoute
        ?.substringBefore('?')?.startsWith("video/") == true || restorePreviousVideoSource
    back()
    if (returningFromRelated) relatedVideoReturned()
}

/** Same live typed stack and pinned per-entry lifecycle/VM/saveable/back owners.
 * Only the Windows presentation changes: no phone morph, captured depth layer,
 * blur, gesture slide, SharedTransitionLayout or LookaheadScope is introduced.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun DesktopWindowsNavigationDisplay(
    environment: DesktopNavigationHostEnvironment,
    backStack: SnapshotStateList<BiliPaiNavKey>,
    videoCardClock: VideoCardTransitionClock,
    programmaticBackDispatcher: BiliPaiProgrammaticBackDispatcher,
    onBack: () -> Unit,
    onPrepareVideoCardSharedReturn: () -> Boolean,
    onRelatedVideoDetailReturned: () -> Unit,
    restorePreviousVideoSourceOnDetailReturn: Boolean,
    modifier: Modifier,
    content: @Composable (BiliPaiNavKey) -> Unit,
) {
    val latestBack by rememberUpdatedState(onBack)
    val latestPrepare by rememberUpdatedState(onPrepareVideoCardSharedReturn)
    val latestRelatedReturn by rememberUpdatedState(onRelatedVideoDetailReturned)
    val latestRestorePrevious by rememberUpdatedState(restorePreviousVideoSourceOnDetailReturn)
    val performBack = remember(environment, backStack) {
        {
            if (environment.isCurrent()) completeDesktopWindowsNavigationBack(
                backStack.lastOrNull(), latestRestorePrevious,
                latestPrepare, latestBack, latestRelatedReturn,
            )
        }
    }
    DisposableEffect(programmaticBackDispatcher, performBack) {
        programmaticBackDispatcher.register(performBack)
        onDispose { programmaticBackDispatcher.unregister(performBack) }
    }
    val clickToPlayEnabled by DesktopOriginalNavigationHostSettings.getClickToPlay(environment.context)
        .collectAsStateWithLifecycle(initialValue = DesktopOriginalNavigationHostSettings.getClickToPlaySync(environment.context))
    val dynamicImagePreviewTextVisible by com.android.purebilibili.core.store.DesktopDynamicCardSettings
        .getDynamicImagePreviewTextVisible(environment.context).collectAsStateWithLifecycle(initialValue = true)
    val globalWallpaperVisible = LocalGlobalWallpaperBackdropVisible.current
    val stackSnapshot = backStack.toList()
    CompositionLocalProvider(
        LocalSharedTransitionScope provides null,
        LocalAnimatedVisibilityScope provides null,
        LocalSharedTransitionEnabled provides false,
    ) {
        @Suppress("UNCHECKED_CAST")
        NavDisplay(
            backStack = backStack as NavBackStack,
            modifier = modifier,
            onBack = performBack,
            transition = DesktopWindowsInstantNavigationTransition,
            effects = NavDisplayEffects.None,
            backCompletionPolicy = null,
        ) {
            biliPaiNavEntries(
                swipeBackDirection = NavSwipeDirection.None,
                predictiveBackExcludedTransition = DesktopWindowsInstantNavigationTransition,
                videoCardTransition = DesktopWindowsInstantNavigationTransition,
                fullscreenVideoCardTransition = DesktopWindowsInstantNavigationTransition,
            ) { destination ->
                val opaqueVideoChild = remember(destination) { shouldUseOpaqueVideoChildBackground(destination, stackSnapshot) }
                val backState = rememberNavigationEventState(NavigationEventInfo.None)
                NavigationBackHandler(state = backState, isBackEnabled = backStack.size > 1, onBackCompleted = performBack)
                CompositionLocalProvider(
                    LocalGlobalWallpaperBackdropVisible provides (globalWallpaperVisible && !opaqueVideoChild),
                    LocalVideoCardSharedElementSourceRoute provides destination.toLegacyRoute(),
                    LocalVideoCardTransitionClock provides videoCardClock,
                    LocalClickToPlayEnabled provides clickToPlayEnabled,
                    LocalDynamicImagePreviewTextVisible provides dynamicImagePreviewTextVisible,
                ) {
                    Box(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
                        ProvideDesktopWindowsNavigationEntryExtras(environment.entryViewModels) { content(destination) }
                    }
                }
            }
        }
    }
}

/** Patch only extras/factory on the actual NavDisplay entry; never allocate another store. */
@Composable
private fun ProvideDesktopWindowsNavigationEntryExtras(
    platform: DesktopNavigationEntryViewModelPlatform,
    content: @Composable () -> Unit,
) {
    val originalOwner = LocalViewModelStoreOwner.current
    if (originalOwner == null) { content(); return }
    val owner = remember(originalOwner, platform) {
        val defaults = originalOwner as? HasDefaultViewModelProviderFactory
        val extras = platform.patchCreationExtras(originalOwner, defaults?.defaultViewModelCreationExtras ?: CreationExtras.Empty)
        object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore = originalOwner.viewModelStore
            override val defaultViewModelProviderFactory = defaults?.defaultViewModelProviderFactory ?: platform.defaultFactory(originalOwner)
            override val defaultViewModelCreationExtras = extras
        }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
}

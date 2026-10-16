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
import com.android.purebilibili.core.ui.transition.LocalDynamicCommentPreviewVisible
import com.android.purebilibili.core.ui.transition.LocalVideoCardSharedElementSourceRoute
import com.android.purebilibili.core.ui.transition.LocalVideoCardTransitionClock
import com.android.purebilibili.core.ui.transition.VideoCardTransitionClock
import com.android.purebilibili.core.ui.transition.VideoCardTransitionSettleState
import com.android.purebilibili.core.ui.transition.resolveVideoCardTransitionExposure
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

/** The current Windows host completes related pops without a morph driver.
 * A saved user preference is not evidence that an animation is running. Supply
 * its actual clock exposure while letting the original nonanimated policy
 * restore the previous source in this one genuine back-completion callback.
 */
internal fun completeDesktopWindowsRelatedVideoReturn(
    owner: DesktopHomeReturnNavigationOwner,
    clock: VideoCardTransitionClock,
): Boolean = owner.onRelatedReturnExposure(
    animated = false,
    exposure = resolveVideoCardTransitionExposure(clock.phase,
        clock.settleState == VideoCardTransitionSettleState.InteractiveSeek,
        clock.settleState == VideoCardTransitionSettleState.CancelRestore || clock.gestureRestoreInProgress),
)

/** Settle the original clock only from the actual committed Windows stack.
 * This host has zero-duration navigation and no shared morph driver. Preserve an
 * armed source while its video (or a child above it) is present, and release it
 * when the real pop exposes Home. No timer or fabricated back target is used.
 */
internal fun settleDesktopWindowsCommittedNavigationClock(
    clock: VideoCardTransitionClock,
    committedStack: List<BiliPaiNavKey>,
) {
    if (committedStack.any { it is BiliPaiNavKey.VideoDetail }) {
        clock.endGesture()
        clock.endGestureRestore()
        clock.clearSharedMorphProgress()
        clock.markHeld()
    } else {
        clock.markIdle()
    }
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
    val performBack = remember(environment, backStack, videoCardClock) {
        {
            if (environment.isCurrent()) {
                completeDesktopWindowsNavigationBack(
                    backStack.lastOrNull(), latestRestorePrevious,
                    latestPrepare, latestBack, latestRelatedReturn,
                )
                if (environment.isCurrent()) {
                    settleDesktopWindowsCommittedNavigationClock(videoCardClock, backStack.toList())
                }
            }
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
    val dynamicCommentPreviewVisible by com.android.purebilibili.core.store.home.DynamicSettingsStore
        .observeCommentPreviewVisible(environment.context).collectAsStateWithLifecycle(initialValue = false)
    val globalWallpaperVisible = LocalGlobalWallpaperBackdropVisible.current
    val stackSnapshot = backStack.toList()
    val clockPhase = videoCardClock.phase
    SideEffect {
        // The click may pre-arm this same clock even if navigation is rejected.
        // Observe its phase, and never settle an obsolete composition's stack.
        if (environment.isCurrent() && backStack.toList() == stackSnapshot &&
            videoCardClock.phase == clockPhase) {
            settleDesktopWindowsCommittedNavigationClock(videoCardClock, stackSnapshot)
        }
    }
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
                    LocalDynamicCommentPreviewVisible provides dynamicCommentPreviewVisible,
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

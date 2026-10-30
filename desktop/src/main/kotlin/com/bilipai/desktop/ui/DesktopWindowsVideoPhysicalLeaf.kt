package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import kotlinx.coroutines.delay
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.Role
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.feature.video.viewmodel.CommentSortMode
import com.android.purebilibili.feature.video.screen.VideoDetailDomainEffects
import com.android.purebilibili.feature.video.ui.components.CommentEmoteTextField
import com.android.purebilibili.feature.video.ui.components.RichCommentText
import com.android.purebilibili.feature.video.subtitle.*
import com.android.purebilibili.feature.video.ui.section.resolveSubtitleLanguageLabel
import com.android.purebilibili.feature.video.ui.section.VideoSubtitleOverlayHost
import com.android.purebilibili.feature.video.ui.overlay.NextWatchOverlay
import com.android.purebilibili.feature.video.ui.overlay.normalizeViewPointSegments
import kotlinx.coroutines.ensureActive
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.core.ui.LocalDetailedCommentTimeEnabled
import com.android.purebilibili.core.ui.components.rememberFollowActionWithConfirmation
import com.android.purebilibili.core.store.DesktopOriginalReplySettings
import com.android.purebilibili.core.store.DesktopOriginalVideoControlSettings
import com.android.purebilibili.core.store.DesktopOriginalPlayerSectionSettings
import com.android.purebilibili.core.store.PlayerInteractionSettings
import com.android.purebilibili.feature.video.ui.components.VideoAspectRatio
import com.android.purebilibili.feature.video.ui.components.SleepTimerOptions
import com.android.purebilibili.feature.video.progress.buildPbpRidgeSamples
import com.android.purebilibili.feature.video.ui.components.resolveSafeVideoAspectRatio
import com.android.purebilibili.feature.video.ui.components.resolveVideoViewportLayout
import com.android.purebilibili.feature.video.ui.components.toVideoAspectRatio
import com.android.purebilibili.feature.video.ui.components.toFullscreenAspectRatio
import com.bilipai.desktop.player.DesktopNativeVideoViewportTransform
import com.android.purebilibili.feature.video.viewmodel.resolvePlaybackCompletionRepeatMode
import com.bilipai.desktop.data.VideoCard
import com.bilipai.desktop.player.PlayerPreferences
import com.bilipai.desktop.appearance.DesktopWindowsUxPlayerLayout
import com.bilipai.desktop.appearance.LocalDesktopWindowsUx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/** Required Root actions. Each callback belongs to the same physical Window/source; no new business authority. */
internal class DesktopWindowsVideoActions(
    val back: () -> Unit, val fullscreen: () -> Unit, val pictureInPicture: () -> Unit,
    val user: (Long) -> Unit, val video: (VideoCard) -> Unit,
    val download: (DesktopOriginalVideoOwnerAssembly, VideoPlaybackUiState.Success) -> Unit,
    val favorite: @Composable (DesktopOriginalVideoOwnerAssembly, VideoPlaybackUiState.Success, () -> Boolean) -> Unit,
    val overlay: @Composable () -> Unit,
    val enhancement: @Composable () -> Unit,
    val openLink: (String) -> Unit,
    val honorLink: (DesktopOriginalVideoOwnerAssembly, DesktopOriginalVideoAcceptedPublication, String, () -> Boolean) -> Unit,
    val descriptionLink: (DesktopOriginalVideoOwnerAssembly, DesktopOriginalVideoAcceptedPublication, String, () -> Boolean) -> Unit,
    val relatedNavigation: (DesktopOriginalVideoOwnerAssembly, DesktopOriginalVideoAcceptedPublication, BiliPaiNavKey, () -> Boolean) -> Unit,
    val metadataNavigation: (DesktopOriginalVideoOwnerAssembly, DesktopOriginalVideoAcceptedPublication, BiliPaiNavKey, () -> Boolean) -> Unit,
    val login: () -> Unit, val danmakuSettings: () -> Unit, val toggleDanmaku: () -> Unit,
    val notice: (String) -> Unit,
    val focusChanged: (Boolean) -> Unit,
    val nativeKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean,
    val registerScreenshot: (() -> Boolean) -> AutoCloseable,
    val collectionQueue: @Composable (DesktopWindowsVideoCollectionQueuePresentation) -> Unit,
    val bgm: @Composable (DesktopWindowsVideoBgmPresentation) -> Unit,
    val interaction: @Composable (DesktopWindowsVideoInteractionPresentation) -> Unit,
)

/** Windows renderer over the installed original VM/owner/MPV. Native peer stays in Root; this leaf reports its viewport only. */
@Composable internal fun DesktopWindowsVideoPhysicalLeaf(
    route: BiliPaiNavKey.VideoDetail,
    shell: DesktopOriginalVideoShellOwner,
    active: Boolean,
    presentationAlive: Boolean,
    fullscreen: Boolean,
    pipActive: Boolean,
    preferences: PlayerPreferences,
    preferencesChanged: (PlayerPreferences) -> Unit,
    actions: DesktopWindowsVideoActions,
) {
    val ready by shell.slot.factoryReady.collectAsState()
    LaunchedEffect(shell, ready, route) { if (ready && active) shell.slot.requireAssembly() }
    val owner by shell.slot.assemblies.collectAsState()
    val assembly = owner?.takeIf { it.owns() }
    val platforms = LocalDesktopOriginalVideoRootPlatforms.current
    if (assembly == null || platforms == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("正在准备播放器")
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = actions.back) { Text("返回") }
        }
        return
    }
    val routeState = LocalDesktopOriginalRootVideoRouteState.current
    SideEffect { shell.publishVideoRouteState(assembly, routeState) }
    val native = assembly.section.nativePlayer
    val nativeSurface = checkNotNull(LocalDesktopWindowsNativeVideoSurface.current) {
        "Windows video requires its retained Root native surface"
    }
    check(nativeSurface.player === native) { "Windows video native surface belongs to another player" }
    val rootEnvironment = LocalDesktopOriginalVideoRootWindowEnvironment.current
    val partScope = rememberCoroutineScope()
    val viewportLease = remember(nativeSurface) { Any() }
    DisposableEffect(nativeSurface, route, viewportLease) { onDispose {
        nativeSurface.releaseViewport(route, viewportLease)
    } }
    val currentActive by rememberUpdatedState(active)
    val currentPresentationAlive by rememberUpdatedState(presentationAlive)
    val latestPip by rememberUpdatedState(pipActive)
    fun current(): Boolean = currentActive && shell.slot.currentAssembly() === assembly && assembly.owns()
    fun navigateFromThisClick(forward: Boolean) {
        if (!current() || !rootEnvironment.owns() || rootEnvironment.currentKey() !== route) return
        val click = shell.playback.captureManualNavigation() ?: return
        partScope.launch {
            val caller = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
            if (!caller.isActive) throw CancellationException("Manual video navigation cancelled")
            shell.playback.navigateManual(click, forward, caller) {
                current() && rootEnvironment.owns() && rootEnvironment.currentKey() === route
            }
        }
    }
    fun navigateNextWatchFromThisClick(displayed: DesktopOriginalVideoNextWatchHint): Boolean {
        if (!current() || !rootEnvironment.owns() || rootEnvironment.currentKey() !== route) return false
        // Capture THIS displayed offer synchronously, before the actual click task.
        val click = shell.playback.captureNextWatchNavigation(displayed) ?: return false
        if (click.assembly !== assembly) return false
        partScope.launch {
            val caller = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
            caller.ensureActive()
            shell.playback.navigateManual(click, true, caller) {
                current() && rootEnvironment.owns() && rootEnvironment.currentKey() === route
            }
        }
        return true
    }
    fun feedbackPresentationCurrent(): Boolean = currentPresentationAlive && !latestPip &&
        rootEnvironment.owns() && rootEnvironment.currentKey() === route &&
        shell.slot.currentAssembly() === assembly && assembly.owns()
    val latestActions by rememberUpdatedState(actions)
    val viewportFocus = remember(assembly) { androidx.compose.ui.focus.FocusRequester() }
    // UI state only: the original Root/entry/source owns every operation.
    val chromeSource = assembly.native.current()
    val chrome = remember(assembly, DesktopWindowsFullscreenChromeEntryKey(route), chromeSource) { DesktopWindowsFullscreenChromeState() }
    val latestChrome by rememberUpdatedState(chrome)
    val latestChromeSource by rememberUpdatedState(chromeSource)
    val latestChromeRoute by rememberUpdatedState(route)
    val latestFullscreen by rememberUpdatedState(fullscreen)
    fun chromeCurrent(): Boolean = current() && !latestPip && rootEnvironment.owns() &&
        rootEnvironment.currentKey() === latestChromeRoute && latestChromeSource?.let(assembly.native::isCurrent) == true
    fun chromeActivity(reason: DesktopWindowsFullscreenChromeActivity) {
        if (latestFullscreen && chromeCurrent()) latestChrome.reveal(reason)
    }
    val chromeWindow = LocalDesktopWindowsPlayerWindow.current
    var chromeWindowFocused by remember(chromeWindow) { mutableStateOf(chromeWindow?.isFocused == true) }
    var barInteraction by remember(chrome) { mutableStateOf(DesktopWindowsFullscreenChromeInteraction()) }
    // These claims come from this actual native host's AWT input and pointer readback.
    // Keep them separate: moving over video does not steal keyboard focus from a control.
    var nativePointerOnVideo by remember(assembly, native.surface) { mutableStateOf(false) }
    var nativeKeyboardOnVideo by remember(assembly, native.surface) { mutableStateOf(false) }
    var viewportKeyboardFocused by remember(assembly, native.surface) { mutableStateOf(false) }
    val nativePointerMotion = remember(assembly, native.surface) { DesktopWindowsNativePointerMotion() }
    fun observeChromePointer() {
        if (!current() || latestPip) return
        desktopWindowsObserveNativePointer(native.surface) { point ->
            nativePointerOnVideo = point != null
            // Remember a real departure without turning a Compose notification
            // into native activity or consuming a subsequent native Move.
            if (point == null) nativePointerMotion.observe(desktopWindowsNativePointerScreenPosition())
        }
    }
    var topFocused by remember(chrome) { mutableStateOf(false) }
    val topInteractions = remember(chrome) { MutableInteractionSource() }
    val topHovered by topInteractions.collectIsHoveredAsState()
    val windowKeyFallback = LocalDesktopWindowKeyFallback.current
    // V6 (spec 6.5 and 6.6): the layout, the flyout flag and the effects of the shortcut router arrive here from a SideEffect below,
    // once the page state they read exists. The router itself is built with the key input, because that is where it is registered.
    val shortcutBinding = remember(assembly) { DesktopWindowsVideoShortcutBinding() }
    val latestKeyInputFocus by rememberUpdatedState(
        topFocused || barInteraction.focused || viewportKeyboardFocused || nativeKeyboardOnVideo)
    // Only the exact existing Main window; no global input/window observer.
    DisposableEffect(chromeWindow, assembly, native.surface, windowKeyFallback, rootEnvironment,
        route, active, presentationAlive, pipActive) {
        // V6 (spec 6.6): the LEGACY layout keeps its gate (focus on the strip, the bar, the viewport or the native surface); the DESKTOP
        // layout has none of those controls, so the binding decides by layout. Both keyboard inputs below share this one rule.
        val keyboardOwned = { shortcutBinding.ownsFocus(latestKeyInputFocus, desktopWindowsTextEditorFocused()) &&
            native.surface.isShowing && chromeWindow != null &&
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow === chromeWindow }
        val input = DesktopWindowsVideoKeyInput(
            currentSource = { latestChromeSource?.takeIf { chromeCurrent() } },
            ownsFocus = keyboardOwned,
            isFullscreen = { latestFullscreen },
            onKey = { event ->
                chromeActivity(DesktopWindowsFullscreenChromeActivity.NATIVE_KEY)
                latestActions.nativeKey(event)
            },
        )
        // V6 (spec 6.5): the window-level player shortcuts. Every effect goes through the admitted lambdas bound by the SideEffect below.
        val shortcuts = DesktopWindowsVideoShortcutInput(
            currentSource = { latestChromeSource?.takeIf { chromeCurrent() } },
            ownsFocus = keyboardOwned,
            controlHoldsFocus = { topFocused || barInteraction.focused },
            textEditorFocused = ::desktopWindowsTextEditorFocused,
            isFullscreen = { latestFullscreen },
            isPaused = { native.state.value.paused },
            layout = { shortcutBinding.layout },
            perform = { action ->
                shortcutBinding.perform(action).also { handled ->
                    if (handled) chromeActivity(DesktopWindowsFullscreenChromeActivity.NATIVE_KEY)
                }
            },
        )
        // Clicking a Compose control retains its focus. Offer Space/Escape before
        // that button can consume them, using this exact page/source admission.
        val keyRegistration = if (active && presentationAlive && !pipActive)
            windowKeyFallback?.registerLeaf { event -> input.onKey(event) || shortcuts.onKey(event) } else null
        val listener = object : java.awt.event.WindowFocusListener {
            override fun windowGainedFocus(event: java.awt.event.WindowEvent) {
                chromeWindowFocused = true
                chromeActivity(DesktopWindowsFullscreenChromeActivity.WINDOW_FOCUS_GAINED)
            }
            override fun windowLostFocus(event: java.awt.event.WindowEvent) {
                input.releaseHeldInput()
                shortcuts.releaseHeldInput()
                chromeWindowFocused = false
                nativePointerOnVideo = false
                nativeKeyboardOnVideo = false
                chromeActivity(DesktopWindowsFullscreenChromeActivity.WINDOW_FOCUS_LOST)
            }
        }
        chromeWindow?.addWindowFocusListener(listener)
        chromeWindowFocused = chromeWindow?.isFocused == true
        onDispose {
            chromeWindow?.removeWindowFocusListener(listener)
            keyRegistration?.close()
            input.close()
            shortcuts.close()
        }
    }
    // Listen only to this actual native host and its one non-focusable MPV Canvas.
    // A comment editor is outside this component tree and retains its native input.
    DisposableEffect(assembly, native.surface) {
        val surface = native.surface
        val oldFocusable = surface.isFocusable
        surface.isFocusable = true
        val focus = object : java.awt.event.FocusAdapter() {
            override fun focusGained(event: java.awt.event.FocusEvent) {
                nativeKeyboardOnVideo = current() && !latestPip && surface.isFocusOwner
                chromeActivity(DesktopWindowsFullscreenChromeActivity.NATIVE_FOCUS_GAINED)
                if(current() && !latestPip) latestActions.focusChanged(true)
            }
            override fun focusLost(event: java.awt.event.FocusEvent) {
                nativeKeyboardOnVideo = false
                chromeActivity(DesktopWindowsFullscreenChromeActivity.NATIVE_FOCUS_LOST)
                if(current()) latestActions.focusChanged(false)
            }
        }
        fun observeNativePointer(reason: DesktopWindowsFullscreenChromeActivity?, departureOnly: Boolean = false) {
            if (!current() || latestPip || !surface.isShowing) return
            desktopWindowsObserveNativePointer(surface) { point ->
                nativePointerOnVideo = point != null
                val moved = nativePointerMotion.observe(desktopWindowsNativePointerScreenPosition(),
                    withinSurface = nativePointerOnVideo, departureOnly = departureOnly)
                if (reason != null && moved && nativePointerOnVideo) chromeActivity(reason)
            }
        }
        val mouse = object : java.awt.event.MouseAdapter() {
            // Peer geometry can change event-local coordinates or emit Enter/Move.
            // Only actual screen movement restarts idle; ownership is still read back.
            override fun mouseEntered(event: java.awt.event.MouseEvent) {
                observeNativePointer(DesktopWindowsFullscreenChromeActivity.NATIVE_ENTER)
            }
            override fun mouseMoved(event: java.awt.event.MouseEvent) {
                observeNativePointer(DesktopWindowsFullscreenChromeActivity.NATIVE_MOVE)
            }
            override fun mouseDragged(event: java.awt.event.MouseEvent) {
                observeNativePointer(DesktopWindowsFullscreenChromeActivity.NATIVE_DRAG)
            }
            override fun mouseExited(event: java.awt.event.MouseEvent) { observeNativePointer(null, departureOnly = true) }
            override fun mousePressed(event: java.awt.event.MouseEvent) {
                if(current() && !latestPip && surface.isShowing) {
                    observeNativePointer(null)
                    chromeActivity(DesktopWindowsFullscreenChromeActivity.NATIVE_PRESS)
                    viewportFocus.requestFocus()
                    surface.requestFocusInWindow()
                }
            }
        }
        val key = object : java.awt.event.KeyAdapter() {
            override fun keyPressed(event: java.awt.event.KeyEvent) {
                if(!event.isConsumed && current() && !latestPip && surface.isFocusOwner && surface.isShowing) {
                    chromeActivity(DesktopWindowsFullscreenChromeActivity.NATIVE_KEY)
                    if (latestActions.nativeKey(desktopWindowsNativeVideoKey(event))) event.consume()
                }
            }
        }
        surface.addFocusListener(focus);surface.addMouseListener(mouse);surface.addKeyListener(key)
        // Mounting an already focused host need not emit another FocusGained event.
        nativeKeyboardOnVideo = current() && !latestPip && surface.isFocusOwner
        val canvas = surface.components.filterIsInstance<java.awt.Canvas>().single()
        canvas.addMouseListener(mouse)
        canvas.addMouseMotionListener(mouse); surface.addMouseMotionListener(mouse)
        onDispose {
            canvas.removeMouseMotionListener(mouse); surface.removeMouseMotionListener(mouse)
            canvas.removeMouseListener(mouse);surface.removeMouseListener(mouse)
            surface.removeFocusListener(focus);surface.removeKeyListener(key);surface.isFocusable=oldFocusable
            nativePointerOnVideo = false; nativeKeyboardOnVideo = false
            latestActions.focusChanged(false)
        }
    }
    val state by native.state.collectAsState()
    val nextWatchHint by assembly.nextWatch.state.collectAsState()
    val playback by shell.playback.state.collectAsState()
    val sponsorPresentation by assembly.playback.desktopSponsorPresentation.collectAsState()
    val sleepTimerMinutes by assembly.playback.sleepTimerMinutes.collectAsState()
    val pbpProgressData by assembly.playback.pbpProgressData.collectAsState()
    val progressPeakEnabled by remember(platforms.holder.settingsContext) {
        DesktopOriginalVideoControlSettings.getProgressPeakDanmakuEnabled(platforms.holder.settingsContext)
    }.collectAsState(false)
    val original by assembly.playback.uiState.collectAsState()
    val latestOriginal by rememberUpdatedState(original)
    val subject by assembly.playback.subjectSnapshot.collectAsState()
    val favoriteEvent by assembly.playback.favoriteFolderSaveEvent.collectAsState()
    val success = original as? VideoPlaybackUiState.Success
    val detail by assembly.playback.detailState.collectAsState()
    val metadataAdmission by assembly.playback.desktopMetadataAdmission.collectAsState()
    val requestState = assembly.playback.captureDesktopLoadState()
    // This separate raw ViewInfo channel never becomes a playback Success.
    // All existing native/action/download variables below still use real success.
    val readonlyInfo = detail.info?.takeIf {
        current() && metadataAdmission?.isCurrent() == true && detail.requestToken == requestState.currentLoadRequestToken &&
            detail.requestBvid == route.bvid && detail.requestCid == route.cid &&
            requestState.currentRequest?.bvid == detail.requestBvid &&
            requestState.currentRequest?.cid == detail.requestCid
    }
    val interactiveChoicePanel by assembly.playback.interactiveChoicePanel.collectAsState()
    // Bind outside the original visible choice UI and the viewport carrier.
    // Owner minimization/choice hiding does not revoke an accepted child ACK.
    val interactiveChoiceSource = rememberDesktopWindowsVideoInteractiveChoiceSource(
        assembly, success, assembly.native.current(), interactiveChoicePanel,
        stillOwned = ::feedbackPresentationCurrent,
        sourceAdmission = { source, action ->
            var applied = false
            shell.factoryFor(assembly).withPresentationAdmission(assembly, source) {
                if (feedbackPresentationCurrent()) assembly.native.admitPlaybackDispatch(source) {
                    if (feedbackPresentationCurrent()) { action(); applied = true }
                }
            } && applied
        },
    )
    val playlistItems by assembly.environment.playlist.playlist.collectAsState()
    val collectionQueueSource = assembly.native.current()?.takeIf { accepted ->
        current() && success?.info?.let { it.bvid == accepted.request.bvid && it.cid == accepted.request.cid } == true
    }
    var showCollection by remember(assembly, collectionQueueSource) { mutableStateOf(false) }
    var showPlaybackQueue by remember(assembly, collectionQueueSource) { mutableStateOf(false) }
    var audioLanguageMenu by remember(assembly, collectionQueueSource) { mutableStateOf<DesktopWindowsVideoAudioSelection?>(null) }
    var audioTrackMenu by remember(assembly, collectionQueueSource) { mutableStateOf<DesktopWindowsVideoAudioSelection?>(null) }
    var interactionMode by remember(assembly, collectionQueueSource) { mutableStateOf<DesktopWindowsVideoInteraction?>(null) }
    var downloadSelection by remember(assembly, route) { mutableStateOf<DesktopWindowsVideoDownloadSelection?>(null) }
    var batchDownloadSelection by remember(assembly, route) { mutableStateOf<DesktopWindowsVideoBatchDownloadSelection?>(null) }
    fun openDownloadSelection(value: VideoPlaybackUiState.Success, batch: Boolean = false) {
        val expected = assembly.native.current() ?: return
        val pageJob = partScope.coroutineContext[kotlinx.coroutines.Job] ?: return
        fun stillCaptured(): Boolean = current() && rootEnvironment.owns() &&
            rootEnvironment.currentKey() === route && assembly.native.isCurrent(expected) &&
            shell.factoryFor(assembly).isPresentationCurrent(assembly, expected) &&
            assembly.playback.captureDesktopPlaybackState().let {
                it is VideoPlaybackUiState.Success && it.info.bvid == expected.request.bvid &&
                    it.info.cid == expected.request.cid && !it.isQualitySwitching
            }
        if (value.info.bvid != expected.request.bvid || value.info.cid != expected.request.cid) return
        downloadSelection?.dismiss()
        batchDownloadSelection?.dismiss()
        downloadSelection = null
        batchDownloadSelection = null
        // The original VM owns the actual request Job, account receipt and task capture.
        // No request or persistence is performed under native/Store admission locks.
        if (batch) {
            batchDownloadSelection = DesktopWindowsVideoBatchDownloadSelection.capture(value, pageJob, ::stillCaptured) { quality, options, candidates, owns ->
                assembly.playback.downloadBatchWithQuality(quality, options, candidates, owns)
            }
        } else {
            downloadSelection = DesktopWindowsVideoDownloadSelection.capture(value, pageJob, ::stillCaptured) { quality, options, owns ->
                assembly.playback.downloadWithQuality(quality, options, owns)
            }
        }
    }
    fun saveVideoResource(audioOnly: Boolean) {
        val value = assembly.playback.captureDesktopPlaybackState() as? VideoPlaybackUiState.Success ?: return
        val expected = assembly.native.current() ?: return
        val pageJob = partScope.coroutineContext[kotlinx.coroutines.Job] ?: return
        fun stillCaptured(): Boolean = pageJob.isActive && current() && !latestPip &&
            rootEnvironment.owns() && rootEnvironment.currentKey() === route &&
            assembly.native.isCurrent(expected) && shell.factoryFor(assembly).isPresentationCurrent(assembly, expected) &&
            assembly.playback.captureDesktopPlaybackState().let {
                it is VideoPlaybackUiState.Success && it.info.bvid == expected.request.bvid &&
                    it.info.cid == expected.request.cid && !it.isQualitySwitching
            }
        if (value.info.bvid != expected.request.bvid || value.info.cid != expected.request.cid || !stillCaptured()) return
        // Same original VM, immutable task/cover bytes and Root storage actor.
        if (audioOnly) {
            if (value.audioUrl.isNullOrBlank()) return
            assembly.playback.downloadAudio(platforms.holder.settingsContext, ::stillCaptured,
                com.android.purebilibili.feature.download.DownloadOptions(includeDanmaku = false))
        } else {
            if (value.info.pic.isBlank()) return
            assembly.playback.saveCover(platforms.holder.settingsContext, ::stillCaptured) { action ->
                // Same file gate as screenshots: native verification returns before disk IO.
                shell.factoryFor(assembly).withPresentationAdmission(assembly, expected) {
                    if (!stillCaptured()) throw CancellationException("Original cover page retired")
                    action()
                }
            }
        }
    }
    // SHARE alone retains its original draft tree across temporary owner hiding.
    // The same Root, route, account authorization and accepted full source remain required.
    val retainedShareSource = assembly.native.current()?.takeIf { accepted ->
        feedbackPresentationCurrent() && shell.factoryFor(assembly).isPresentationCurrent(assembly, accepted) &&
            success?.info?.let { it.bvid == accepted.request.bvid && it.cid == accepted.request.cid } == true
    }
    var showRetainedShare by remember(assembly, retainedShareSource) { mutableStateOf(false) }
    fun retainedShareCurrent(): Boolean = feedbackPresentationCurrent() && retainedShareSource != null &&
        shell.factoryFor(assembly).isPresentationCurrent(assembly, retainedShareSource) &&
        assembly.native.isCurrent(retainedShareSource) && assembly.playback.captureDesktopPlaybackState().let {
            it is VideoPlaybackUiState.Success && it.info.bvid == retainedShareSource.request.bvid &&
                it.info.cid == retainedShareSource.request.cid
        }
    fun collectionQueueCurrent(): Boolean = current() && collectionQueueSource != null &&
        assembly.native.isCurrent(collectionQueueSource) && assembly.playback.captureDesktopPlaybackState().let {
            it is VideoPlaybackUiState.Success && it.info.bvid == collectionQueueSource.request.bvid &&
                it.info.cid == collectionQueueSource.request.cid
        }
    fun interactionCurrent(): Boolean = collectionQueueCurrent() && rootEnvironment.currentKey() === route
    fun openInteraction(mode: DesktopWindowsVideoInteraction) {
        if (interactionCurrent()) {
            if (mode == DesktopWindowsVideoInteraction.SHARE) {
                if (retainedShareCurrent()) {
                    interactionMode = null
                    showRetainedShare = true
                }
            } else {
                showRetainedShare = false
                interactionMode = mode
            }
        }
    }
    val aiSummaryEntryEnabled by com.android.purebilibili.core.store.DesktopOriginalVideoContentSettings
        .getVideoAiSummaryEntryEnabled(platforms.holder.settingsContext).collectAsState(true)
    val videoNoteEnabled by com.android.purebilibili.core.store.DesktopOriginalVideoContentSettings
        .getVideoNoteEnabled(platforms.holder.settingsContext).collectAsState(true)
    val bgmResult by assembly.playback.desktopBgmResult.collectAsState(null)
    val canOpenCollection = success?.info?.ugc_season != null && collectionQueueSource != null
    val canOpenPlaybackQueue = collectionQueueSource != null && playlistItems.isNotEmpty()
    val chapterResult by assembly.playback.desktopChapterResult.collectAsState(null)
    // The list is stamped by its accepted player-info result, never by the current screen.
    val chaptersSource = assembly.native.current()
    val chapters = chapterResult?.takeIf { result ->
        val accepted = chaptersSource
        current() && assembly.playback.captureDesktopChapterResult() === result && accepted != null &&
            accepted.request.bvid == result.bvid && accepted.request.cid == result.cid &&
            success != null && success.info.bvid == result.bvid && success.info.cid == result.cid
    }
    val resumeSuggestion by assembly.playback.resumePlaybackSuggestion.collectAsState()
    val screenshotSection = checkNotNull(platforms.holder.section as? DesktopOriginalVideoSectionWindowsPlatform) {
        "Windows screenshots require the existing Root Windows Section"
    }
    var screenshotBusy by remember(assembly, route) { mutableStateOf(false) }
    var savedScreenshot by remember(assembly, route) { mutableStateOf<DesktopOriginalSavedVideoScreenshot?>(null) }
    fun takeScreenshot(): Boolean {
        val value = assembly.playback.captureDesktopPlaybackState() as? VideoPlaybackUiState.Success ?: return false
        val expected = assembly.native.current() ?: return false
        val pageJob = partScope.coroutineContext[kotlinx.coroutines.Job] ?: return false
        val factory = shell.factoryFor(assembly)
        fun stillCaptured(): Boolean = pageJob.isActive && current() && !latestPip &&
            rootEnvironment.owns() && rootEnvironment.currentKey() === route &&
            factory.isPresentationCurrent(assembly, expected) && assembly.native.isCurrent(expected)
        val actual = native.state.value
        if (!stillCaptured() || value.info.bvid != expected.request.bvid || value.info.cid != expected.request.cid ||
            value.isQualitySwitching || !actual.ready || actual.loading || actual.ended || actual.audioOnly || actual.videoCodec == null) return false
        if (screenshotBusy || savedScreenshot?.isCurrent() == true) return true
        savedScreenshot = null
        screenshotBusy = true
        partScope.launch {
            try {
                // Explicit accepted publication: a delayed launch can never capture a later source.
                val image = screenshotSection.captureAndSaveScreenshotForShare(expected, value.info.title, ::stillCaptured)
                if (image == null) {
                    if (stillCaptured()) latestActions.notice("截图失败，请稍后重试")
                } else {
                    var published = false
                    if (!factory.withPresentationAdmission(assembly, expected) {
                            if (stillCaptured() && image.isCurrent()) { savedScreenshot = image; published = true }
                        } || !published) throw CancellationException("Screenshot page retired before saved prompt")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (stillCaptured()) latestActions.notice(failure.message ?: "截图失败，请稍后重试")
            } finally { screenshotBusy = false }
        }
        return true
    }
    LaunchedEffect(savedScreenshot, collectionQueueSource, active, pipActive) {
        if (savedScreenshot?.isCurrent() == false) savedScreenshot = null
    }
    val latestScreenshot by rememberUpdatedState<() -> Boolean>(::takeScreenshot)
    DisposableEffect(assembly, route, screenshotSection, active, presentationAlive, pipActive) {
        val registration = if (current() && !latestPip && rootEnvironment.owns() && rootEnvironment.currentKey() === route)
            latestActions.registerScreenshot { latestScreenshot() } else null
        onDispose { registration?.close() }
    }
    val engagement by assembly.domains.engagement.uiState.collectAsState()
    val engagementSubject = engagement.subject
    // The original interaction menus keep collectionQueueSource's foreground
    // permission. Confirmed/in-flight feedback borrows the same actual accepted
    // source with its exact Root lifetime; minimization must not null its key.
    val feedbackSource = assembly.native.current()?.takeIf { accepted ->
        feedbackPresentationCurrent() && success?.info?.let {
            it.bvid == accepted.request.bvid && it.cid == accepted.request.cid
        } == true
    }
    val brandEvents = LocalDesktopBrandSuccessEvents.current
    val engagementBinding = remember(assembly, feedbackSource, engagementSubject, presentationAlive, pipActive, brandEvents) {
        val expected = feedbackSource
        if (expected == null || engagementSubject == null) null
        else {
            val factory = shell.factoryFor(assembly)
            DesktopWindowsVideoEngagementBinding(expected, assembly.domains.engagement, engagementSubject,
                stillOwned = { current() && rootEnvironment.currentKey() === route && assembly.native.isCurrent(expected) },
                stillFeedbackOwned = { feedbackPresentationCurrent() && factory.isPresentationCurrent(assembly, expected) &&
                    assembly.native.isCurrent(expected) },
                admission = { action -> factory.withPresentationAdmission(assembly, expected, action) }).also { it.mountBrandFeedback(brandEvents) }
        }
    }
    DisposableEffect(engagementBinding) { onDispose { engagementBinding?.close() } }
    var bootstrapError by remember(assembly, route) { mutableStateOf<String?>(null) }
    var bootstrapRetry by remember(assembly, route) { mutableIntStateOf(0) }
    val reportPageError = LocalDesktopPageErrorReporter.current
    val originalLoadError = (original as? VideoPlaybackUiState.Error)?.error
    LaunchedEffect(originalLoadError) {
        if (current() && originalLoadError != null)
            reportPageError?.invoke(originalLoadError.javaClass.simpleName,
                (originalLoadError as? com.android.purebilibili.data.model.VideoLoadError.UnknownError)?.throwable)
    }
    val latestBootstrapError by rememberUpdatedState(bootstrapError)
    val failureLogin = DesktopVideoFailureLoginIntent.capture(rootEnvironment, shell, assembly, route,
        original as? VideoPlaybackUiState.Error) {
        latestBootstrapError == null && current() && feedbackPresentationCurrent() && latestOriginal ===
            assembly.playback.captureDesktopPlaybackState()
    }
    // Reuse the same real failure/login/retry content in the finite details pane.
    val failureContent: @Composable () -> Unit = {
        DesktopWindowsPageErrorCard(
            message = bootstrapError ?: desktopWindowsVideoErrorMessage(originalLoadError),
            onRetry = {
                if (current() && rootEnvironment.currentKey() === route) {
                    if (bootstrapError != null) { bootstrapError = null; bootstrapRetry++ }
                    else shell.playback.retry()
                }
            },
            onLogin = failureLogin?.let { intent ->
                {
                    (rootEnvironment.commands as? DesktopOriginalRootRouteAssembly)
                        ?.loginFromReadFailure(intent)
                    Unit
                }
            },
        )
    }
    val speedOptions by remember(platforms.holder.settingsContext) {
        DesktopOriginalVideoControlSettings.getPlaybackSpeedOptions(platforms.holder.settingsContext)
    }.collectAsState(emptyList())
    val completion by remember(platforms.holder.settingsContext) {
        DesktopOriginalVideoControlSettings.getPlaybackCompletionBehavior(platforms.holder.settingsContext)
    }.collectAsState(DesktopOriginalVideoControlSettings.getPlaybackCompletionBehaviorSync(platforms.holder.settingsContext))
    LaunchedEffect(assembly, completion, state.sourceTitle, active) {
        if (current() && assembly.section.isOwned()) assembly.section.repeatMode = resolvePlaybackCompletionRepeatMode(completion)
    }
    // Reuse the original Holder's session policy: a part/language/preference change
    // resets its manual override; native publication changes alone are not a new key.
    val subtitleBelongsToCurrentVideo = success != null && success.info.cid > 0L &&
        success.subtitleOwnerBvid == success.info.bvid && success.subtitleOwnerCid == success.info.cid
    val subtitlePreferenceSession = resolveSubtitlePreferenceSession(
        bvid = success?.info?.bvid ?: assembly.playback.captureDesktopLoadState().currentBvid,
        cid = success?.info?.cid ?: 0L,
        primaryLanguage = success?.subtitlePrimaryLanguage,
        secondaryLanguage = success?.subtitleSecondaryLanguage,
        primaryTrackLikelyAi = subtitleBelongsToCurrentVideo && success?.subtitlePrimaryLikelyAi == true,
        secondaryTrackLikelyAi = subtitleBelongsToCurrentVideo && success?.subtitleSecondaryLikelyAi == true,
        hasPrimaryTrack = subtitleBelongsToCurrentVideo &&
            (!success?.subtitlePrimaryTrackKey.isNullOrBlank() || !success?.subtitlePrimaryLanguage.isNullOrBlank()),
        hasSecondaryTrack = subtitleBelongsToCurrentVideo &&
            (!success?.subtitleSecondaryTrackKey.isNullOrBlank() || !success?.subtitleSecondaryLanguage.isNullOrBlank()),
        preference = preferences.subtitleAutoPreference,
        isMuted = state.muted,
    )
    var subtitleOverride by rememberDesktopWindowsSubtitleMode(
        assembly.subtitleSaveableOwnerKey, subtitlePreferenceSession,
    )
    var subtitleLargeTextByUser by rememberDesktopWindowsSubtitleLargeText(
        assembly.subtitleSaveableOwnerKey, subtitlePreferenceSession,
    )
    // Match the original Section: controls retain bound tracks while cues load,
    // but the overlay falls back to cues that can actually be displayed.
    val subtitlePrimaryAvailable = subtitleBelongsToCurrentVideo && success?.subtitlePrimaryCues?.isNotEmpty() == true
    val subtitleSecondaryAvailable = subtitleBelongsToCurrentVideo && success?.subtitleSecondaryCues?.isNotEmpty() == true
    val subtitleControlAvailability = resolveSubtitleControlAvailability(
        primaryTrackBound = subtitleBelongsToCurrentVideo &&
            (!success?.subtitlePrimaryTrackKey.isNullOrBlank() || !success?.subtitlePrimaryLanguage.isNullOrBlank()),
        secondaryTrackBound = subtitleBelongsToCurrentVideo &&
            (!success?.subtitleSecondaryTrackKey.isNullOrBlank() || !success?.subtitleSecondaryLanguage.isNullOrBlank()),
        primaryCueAvailable = subtitlePrimaryAvailable,
        secondaryCueAvailable = subtitleSecondaryAvailable,
    )
    val subtitleMode = normalizeSubtitleDisplayMode(
        preferredMode = subtitleOverride,
        hasPrimaryTrack = subtitlePrimaryAvailable,
        hasSecondaryTrack = subtitleSecondaryAvailable,
    )
    val ownedSubtitleState = success?.takeIf { subtitleBelongsToCurrentVideo }
    val subtitlePrimaryLabel = ownedSubtitleState?.subtitleTracks?.firstOrNull {
        it.trackKey == ownedSubtitleState.subtitlePrimaryTrackKey
    }?.let(::resolveSubtitleTrackDisplayLabel) ?: resolveSubtitleLanguageLabel(
        languageCode = ownedSubtitleState?.subtitlePrimaryLanguage, fallbackLabel = "中文",
    )
    val subtitleSecondaryLabel = ownedSubtitleState?.subtitleTracks?.firstOrNull {
        it.trackKey == ownedSubtitleState.subtitleSecondaryTrackKey
    }?.let(::resolveSubtitleTrackDisplayLabel) ?: resolveSubtitleLanguageLabel(
        languageCode = ownedSubtitleState?.subtitleSecondaryLanguage, fallbackLabel = "英文",
    )
    val subtitleDisplayOptions = resolveSubtitleDisplayOptions(
        primaryLabel = subtitlePrimaryLabel,
        secondaryLabel = subtitleSecondaryLabel,
        hasPrimaryTrack = subtitleControlAvailability.primarySelectable,
        hasSecondaryTrack = subtitleControlAvailability.secondarySelectable,
    )
    // This bridge still reads/writes the saved preference, never a transient fallback.
    val latestSubtitleMode by rememberUpdatedState(subtitleOverride)
    DisposableEffect(assembly, platforms.subtitleMode) {
        val registration = platforms.subtitleMode.register(assembly.playback, { latestSubtitleMode }, { subtitleOverride = it })
        onDispose { registration.close() }
    }

    // Root keeps the Canvas peer while an opaque detail route covers this leaf.
    // A returning actual entry may retain only its exact admitted publication;
    // a new same-value/BV-only entry still follows the original load protocol.
    fun samePhysicalEntry(): Boolean = current() && rootEnvironment.currentKey() === route
    // Mount-local only: on Back, verify the saved exact receipt BEFORE any
    // current-source projection may replace it. This flag never grants ownership.
    var presentationVerified by remember(assembly, nativeSurface, route.openId, route.bvid, route.cid) { mutableStateOf(false) }
    SideEffect {
        if (presentationVerified && samePhysicalEntry())
            nativeSurface.recordPresentedSource(route, assembly, ::samePhysicalEntry)
    }
    LaunchedEffect(assembly, route.openId, route.bvid, route.cid, route.resumePositionMs, active, bootstrapRetry) {
        if (!active) return@LaunchedEffect
        try {
            native.state.first { actual ->
                if (!samePhysicalEntry()) throw CancellationException("Windows video entry retired")
                if (!actual.ready && actual.error != null) error(actual.error!!)
                actual.ready
            }
            if (!samePhysicalEntry()) throw CancellationException("Windows video entry retired")
            if (nativeSurface.retainPresentedEntry(route, assembly, ::samePhysicalEntry)) {
                presentationVerified = true
                return@LaunchedEffect
            }
            val prior = assembly.native.current()
            val bootstrapSource = DesktopVideoBootstrapSeed.capture(rootEnvironment, assembly, route)
            val retained = shell.playback.openVideoDetail(VideoCard(route.bvid, "", route.coverUrl, "", 0, 0,
                preferredCid = route.cid), route.resumePositionMs, keepMatchingSource = true, bootstrapSource = bootstrapSource)
            if (samePhysicalEntry()) {
                nativeSurface.recordBootstrap(route, assembly, prior, retained)
                nativeSurface.recordPresentedSource(route, assembly, ::samePhysicalEntry)
                presentationVerified = true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (current()) {
                reportPageError?.invoke(failure.javaClass.simpleName, failure)
                bootstrapError = "播放器初始化失败，请重试。"
            }
        }
    }
    val viewportSource = assembly.native.current()
    SideEffect {
        nativeSurface.bindViewportSource(route, viewportLease, assembly, viewportSource) {
            samePhysicalEntry() && !latestPip
        }
    }
    var seekPreview by remember(assembly, native, route) { mutableStateOf<DesktopWindowsVideoSeekPreview?>(null) }
    // Capture this already-admitted UI receipt on the actual composition lane.
    // Deferred preference predicates retain only its immutable source reference.
    val presentedPictureSource = nativeSurface.capturePresentedSource(route, assembly)
    val pictureFactory = shell.factoryFor(assembly)
    val pictureSettings by remember(platforms.holder.settingsContext) {
        DesktopOriginalPlayerSectionSettings.getPlayerInteractionSettings(platforms.holder.settingsContext)
    }.collectAsState(PlayerInteractionSettings())
    val isVerticalPicture = state.videoWidth > 0 && state.videoHeight > state.videoWidth
    val pictureAspectRatio = resolveSafeVideoAspectRatio(
        pictureSettings.fixedFullscreenAspectRatio.toVideoAspectRatio(), isVerticalPicture)
    // Original flip intent is local to this mounted video owner, not a new global setting.
    var pictureFlipHorizontal by remember(assembly) { mutableStateOf(false) }
    var pictureFlipVertical by remember(assembly) { mutableStateOf(false) }
    var picturePreferenceJob by remember(assembly) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Returning from PiP/coverage must reapply even if the value itself is unchanged.
    var submittedPictureViewport by remember(assembly, native, active, pipActive) {
        mutableStateOf<Pair<DesktopOriginalVideoAcceptedPublication, DesktopNativeVideoViewportTransform>?>(null)
    }
    fun playbackToolsCurrent(expected: DesktopOriginalVideoAcceptedPublication?, requirePicture: Boolean = false): Boolean {
        if (expected == null) return false
        val value = assembly.playback.captureDesktopPlaybackState() as? VideoPlaybackUiState.Success ?: return false
        val origin = expected.bootstrapOrigin
        // Original part/next-video requests retain the SAME typed route while
        // changing CID/BV. A presentation receipt never replaces their request proof.
        val sameEntrySource = try {
            when {
                origin != null -> origin.seed.assembly === assembly &&
                    origin.matchesResolvedRequest(expected.request) &&
                    ((origin.seed.route === route && origin.seed.ownsEntry()) || expected === presentedPictureSource)
                expected !== presentedPictureSource -> false
                else -> {
                    val load = assembly.captureLoadState()
                    val page = expected.pageSubject
                    if (page != null) page.sameOriginalRequestIdentity(load) && load.currentCid == expected.request.cid &&
                        page.matchesResolvedRequest(expected.request)
                    else {
                        val request = load.currentRequest
                        request != null && captureDesktopOriginalResolvedMediaRequest(load, request,
                            load.currentLoadRequestToken).let { resolved ->
                            resolved.bvid == expected.request.bvid && resolved.cid == expected.request.cid
                        }
                    }
                }
            }
        } catch (_: CancellationException) { false }
        val actual = native.state.value
        return sameEntrySource && value.info.bvid == expected.request.bvid &&
            value.info.cid == expected.request.cid && !value.isQualitySwitching &&
            presentationVerified && current() && !latestPip &&
            rootEnvironment.owns() && rootEnvironment.currentKey() === route &&
            pictureFactory.isPresentationCurrent(assembly, expected) && assembly.native.isCurrent(expected) &&
            actual.ready && !actual.loading && !actual.ended && actual.error == null &&
            (!requirePicture || (!actual.audioOnly && actual.videoCodec != null &&
                actual.videoWidth > 0 && actual.videoHeight > 0))
    }
    // Cover inputs never borrow the previous video's loading CID or native frame.
    val coverBootstrapVerified = presentationVerified
    val coverLoadState = assembly.playback.captureDesktopLoadState()
    val coverRequest = coverLoadState.currentRequest.takeIf { coverBootstrapVerified }
    val coverRequestToken = if (coverBootstrapVerified) coverLoadState.currentLoadRequestToken else 0L
    val coverBvid = coverRequest?.bvid ?: route.bvid
    fun coverEntryCurrent(): Boolean = samePhysicalEntry() && !latestPip && rootEnvironment.owns() &&
        presentationVerified == coverBootstrapVerified && currentPresentationAlive
    fun coverRequestCurrent(): Boolean = try {
        if (!coverEntryCurrent()) false
        else if (!coverBootstrapVerified) true
        else {
            val load = assembly.captureLoadState()
            load.currentRequest === coverRequest && load.currentLoadRequestToken == coverRequestToken &&
                (coverRequest == null || load.currentBvid == coverRequest.bvid)
        }
    } catch (_: CancellationException) { false }
    fun coverAcceptedCurrentOrThrow(expected: DesktopOriginalVideoAcceptedPublication): Boolean {
        if (!coverBootstrapVerified || !coverRequestCurrent() ||
            !pictureFactory.isPresentationCurrent(assembly, expected) || !assembly.native.isCurrent(expected)) return false
        val value = assembly.playback.captureDesktopPlaybackState() as? VideoPlaybackUiState.Success ?: return false
        if (value.info.bvid != expected.request.bvid || value.info.cid != expected.request.cid ||
            value.info.bvid != coverBvid) return false
        val load = assembly.captureLoadState()
        val origin = expected.bootstrapOrigin
        val page = expected.pageSubject
        val full = expected.fullLoadSubject
        return when {
            origin != null -> origin.seed.assembly === assembly && origin.matchesResolvedRequest(expected.request) &&
                ((origin.seed.route === route && origin.seed.ownsEntry()) || expected === presentedPictureSource)
            page != null -> page.sameOriginalRequestIdentity(load) && load.currentCid == expected.request.cid &&
                page.matchesResolvedRequest(expected.request)
            full != null -> full.sameOriginalRequestIdentity(load)
            expected === presentedPictureSource -> load.currentRequest?.let { request ->
                captureDesktopOriginalResolvedMediaRequest(load, request, load.currentLoadRequestToken).let {
                    it.bvid == expected.request.bvid && it.cid == expected.request.cid
                }
            } == true
            else -> false
        }
    }
    fun coverAcceptedCurrent(expected: DesktopOriginalVideoAcceptedPublication): Boolean = try {
        coverAcceptedCurrentOrThrow(expected)
    } catch (_: CancellationException) { false }
    fun captureCoverRuntime(expected: DesktopOriginalVideoAcceptedPublication): DesktopWindowsVideoEntryCoverRuntime? {
        var captured: DesktopWindowsVideoEntryCoverRuntime? = null
        if (coverAcceptedCurrent(expected)) assembly.native.admitPlaybackDispatch(expected) {
            if (coverAcceptedCurrent(expected)) {
                val lease = native.captureNativeVideoCanvasLease(viewportLease, expected.nativeSource)
                if (lease != null) captured = DesktopWindowsVideoEntryCoverRuntime(expected, lease,
                    assembly.section.capturePresentationState(), assembly.section.playWhenReady,
                    assembly.section.isPlaying, assembly.section.currentPosition)
            }
        }
        return captured
    }
    val coverRuntime = viewportSource?.let(::captureCoverRuntime)
    fun currentCoverRuntime(captured: DesktopWindowsVideoEntryCoverRuntime?): DesktopWindowsVideoEntryCoverRuntime? {
        if (captured == null) return null
        return captureCoverRuntime(captured.source)?.takeIf {
            it.lease.playbackRevision == captured.lease.playbackRevision &&
                it.lease.peer === captured.lease.peer && it.lease.hostWindowHandle == captured.lease.hostWindowHandle
        }
    }
    fun coverStillCurrent(): Boolean = coverRequestCurrent() &&
        (coverRuntime == null || currentCoverRuntime(coverRuntime) != null)
    fun coverCanReveal(): Boolean = currentCoverRuntime(coverRuntime)?.state?.firstVideoFrameReady == true
    fun playFromEntryCover(): Boolean {
        val captured = coverRuntime ?: return false
        var played = false
        return try {
            pictureFactory.withPresentationAdmission(assembly, captured.source) {
                // Native readback admission has returned; original control writes
                // retain Store -> entry -> control/native lock order.
                if (currentCoverRuntime(captured) != null) {
                    com.android.purebilibili.feature.video.usecase.playPlayerFromUserAction(assembly.section)
                    played = true
                }
            } && played
        } catch (_: CancellationException) { false }
    }
    var coverDetailUrl = ""
    var coverMetadataVertical = false
    if (coverRequest != null && coverRequestCurrent()) {
        metadataAdmission?.admit {
            val currentDetail = assembly.playback.detailState.value
            if (coverRequestCurrent() && currentDetail.requestToken == coverRequestToken &&
                currentDetail.requestBvid == coverRequest.bvid && currentDetail.requestCid == coverRequest.cid) {
                val info = currentDetail.info
                if (info?.bvid == coverBvid) {
                    coverDetailUrl = info.pic
                    coverMetadataVertical = info.dimension?.let { it.width > 0 && it.height > it.width } == true
                }
            }
        }
    }
    if (coverRuntime != null) {
        val acceptedInfo = success?.info?.takeIf {
            it.bvid == coverRuntime.source.request.bvid && it.cid == coverRuntime.source.request.cid
        }
        if (acceptedInfo != null) {
            coverDetailUrl = acceptedInfo.pic
            coverMetadataVertical = acceptedInfo.dimension?.let { it.width > 0 && it.height > it.width } == true
        }
    }
    val coverContentKey = DesktopWindowsVideoEntryCoverIdentity(assembly, route, coverRequest, coverRequestToken,
        coverRuntime?.source, coverRuntime?.lease?.playbackRevision, coverRuntime?.lease?.peer)
    val coverAutoPlay by remember(platforms.holder.settingsContext) {
        DesktopOriginalPlayerSectionSettings.getClickToPlay(platforms.holder.settingsContext)
    }.collectAsState(DesktopOriginalPlayerSectionSettings.getClickToPlaySync(platforms.holder.settingsContext))
    var showSponsorSegment by remember(assembly, viewportSource) { mutableStateOf(false) }
    fun sponsorCurrent(expected: DesktopOriginalVideoAcceptedPublication?): Boolean =
        playbackToolsCurrent(expected) && expected != null &&
            assembly.environment.plugins.isPlaybackDispatchCurrent(expected)
    fun admitSponsorAction(expected: DesktopOriginalVideoAcceptedPublication, action: () -> Unit): Boolean {
        var applied = false
        return pictureFactory.withPresentationAdmission(assembly, expected) {
            if (sponsorCurrent(expected)) assembly.native.admitPlaybackDispatch(expected) {
                if (sponsorCurrent(expected)) { action(); applied = true }
            }
        } && applied
    }
    fun pictureCurrent(expected: DesktopOriginalVideoAcceptedPublication?): Boolean =
        playbackToolsCurrent(expected, requirePicture = true)
    fun admitPlaybackTool(expected: DesktopOriginalVideoAcceptedPublication, action: () -> Unit): Boolean {
        var applied = false
        return pictureFactory.withPresentationAdmission(assembly, expected) {
            if (playbackToolsCurrent(expected)) { action(); applied = true }
        } && applied
    }
    val subtitlePositionWriter: suspend (Float) -> Unit = remember(assembly, viewportSource, route, platforms.holder.settingsContext) {
        val expected = viewportSource
        val write: suspend (Float) -> Unit = { offset ->
            try {
                DesktopOriginalPlaybackPreferenceOperation.run(platforms.holder.settingsContext, { pictureCurrent(expected) }) {
                    DesktopOriginalPlayerSectionSettings.setSubtitleVerticalOffsetFraction(
                        platforms.holder.settingsContext, offset)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (pictureCurrent(expected)) latestActions.notice(failure.message ?: "字幕位置未能保存")
            }
        }
        write
    }
    fun changeSubtitlePositionLocked(locked: Boolean) {
        val expected = viewportSource ?: return
        if (!pictureCurrent(expected)) return
        partScope.launch {
            try {
                DesktopOriginalPlaybackPreferenceOperation.run(platforms.holder.settingsContext, { pictureCurrent(expected) }) {
                    DesktopOriginalPlayerSectionSettings.setSubtitlePositionLocked(
                        platforms.holder.settingsContext, locked)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (pictureCurrent(expected)) latestActions.notice(failure.message ?: "字幕锁定设置未能保存")
            }
        }
    }
    val progressDurationMs = state.durationSeconds.takeIf { it.isFinite() && it > 0.0 }?.let { (it * 1000.0).toLong() } ?: 0L
    val progressSamples = remember(pbpProgressData, progressDurationMs) {
        pbpProgressData?.let { buildPbpRidgeSamples(it, progressDurationMs) }.orEmpty()
    }
    fun changePictureAspectRatio(ratio: VideoAspectRatio) {
        val expected = viewportSource ?: return
        if (!pictureCurrent(expected)) return
        val actual = native.state.value
        val safeRatio = resolveSafeVideoAspectRatio(ratio, actual.videoHeight > actual.videoWidth)
        picturePreferenceJob?.cancel()
        picturePreferenceJob = partScope.launch {
            try {
                DesktopOriginalPlaybackPreferenceOperation.run(platforms.holder.settingsContext, { pictureCurrent(expected) }) {
                    DesktopOriginalPlayerSectionSettings.setFullscreenAspectRatio(
                        platforms.holder.settingsContext, safeRatio.toFullscreenAspectRatio())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (pictureCurrent(expected)) latestActions.notice(failure.message ?: "画面比例未能保存")
            }
        }
    }
    fun flipPicture(horizontal: Boolean) {
        val expected = viewportSource ?: return
        if (!pictureCurrent(expected)) return
        assembly.native.admitPlaybackDispatch(expected) {
            if (pictureCurrent(expected)) {
                if (horizontal) pictureFlipHorizontal = !pictureFlipHorizontal
                else pictureFlipVertical = !pictureFlipVertical
            }
        }
    }
    SideEffect {
        val expected = viewportSource
        val transform = DesktopNativeVideoViewportTransform(pictureAspectRatio.playerResizeMode,
            1f, 0f, 0f, pictureFlipHorizontal, pictureFlipVertical)
        if (expected != null && pictureCurrent(expected) &&
            (submittedPictureViewport?.first !== expected || submittedPictureViewport?.second != transform)) {
            assembly.native.admitPlaybackDispatch(expected) {
                if (pictureCurrent(expected) && native.setOriginalVideoViewport(expected.nativeSource, transform))
                    submittedPictureViewport = expected to transform
            }
        }
    }
    CompositionLocalProvider(LocalDesktopOriginalVideoHolderPlatform provides platforms.holder,
        LocalDesktopCommentBindings provides platforms.holder.commentsPlatform) {
        // These are state-domain effects only. They bind the existing four VMs without mounting the phone screen.
        VideoDetailDomainEffects(platforms.holder.settingsContext, active, original, subject, favoriteEvent,
            assembly.playback, assembly.domains.engagement, assembly.domains.composer, assembly.domains.supplement)
        DesktopWindowsVideoFollowGroupSection(assembly, engagementBinding)
    }
    val feedbackBounds = remember(engagementBinding) { DesktopWindowsVideoFeedbackBounds() }
    val routedCommentRequest = rememberSaveable(assembly, route.commentRootRpid, route.commentTargetRpid,
        saver = DesktopWindowsVideoRoutedCommentRequest.Saver) {
        DesktopWindowsVideoRoutedCommentRequest(route.commentRootRpid, route.commentTargetRpid)
    }
    var commentsInitializedAid by remember(assembly, success?.info?.aid) { mutableStateOf(0L) }
    val preferredSort = DesktopOriginalReplySettings.getCommentDefaultSortModeSync(platforms.holder.settingsContext.pluginContext)
    LaunchedEffect(assembly, success?.info?.aid, success?.info?.owner?.mid, active, preferredSort) {
        if (active && current() && success != null) {
            assembly.domains.comments.init(success.info.aid,
                success.info.owner.mid, preferredSortMode=CommentSortMode.fromApiMode(preferredSort), expectedReplyCount = success.info.stat.reply)
            commentsInitializedAid = success.info.aid
        }
    }
    LaunchedEffect(assembly, success?.info?.bvid, success?.info?.cid, route.startAudio, active) {
        if (route.startAudio && current() && success?.info?.bvid == route.bvid) {
            assembly.native.current()?.takeIf { it.request.bvid == success.info.bvid && it.request.cid == success.info.cid }?.let { expected ->
                assembly.native.admitPlaybackDispatch(expected) { if(current()) native.setAudioOnly(true) }
            }
        }
    }

    // The original Nav entry may leave composition while a detail route covers
    // it. Preserve its panel intent through the entry's existing saveable owner.
    var detailsOpen by rememberSaveable(assembly) { mutableStateOf(false) }
    val feedbackPlacementDiagnostic = System.getProperty("bilipai.validation.brandFeedbackPlacementInput") == "true"
    // Validation-only plain counters: no Compose state, listener, action or normal-run allocation.
    val detailsCallbackCounts = if (feedbackPlacementDiagnostic) remember(assembly, native.surface) { LongArray(2) } else null
    var detailsTab by rememberSaveable(assembly, stateSaver = Saver<DesktopWindowsVideoDetailsTab, String>(
        save = { it.name }, restore = { DesktopWindowsVideoDetailsTab.valueOf(it) },
    )) { mutableStateOf(DesktopWindowsVideoDetailsTab.INTRODUCTION) }
    LaunchedEffect(assembly, route.commentRootRpid, route.commentTargetRpid) {
        if (current() && route.commentRootRpid > 0L) {
            detailsTab = DesktopWindowsVideoDetailsTab.COMMENTS
            detailsOpen = true
        }
    }
    val chromeHeld = DesktopWindowsFullscreenChromeInteraction(topHovered, topFocused)
        .held(nativePointerOnVideo, nativeKeyboardOnVideo) ||
        barInteraction.held(nativePointerOnVideo, nativeKeyboardOnVideo) || detailsOpen ||
        showCollection || showPlaybackQueue || audioLanguageMenu != null || audioTrackMenu != null || interactionMode != null ||
        screenshotBusy || savedScreenshot?.isCurrent() == true
    val chromeCanAutoHide = desktopWindowsFullscreenChromeCanAutoHide(fullscreen, active && !pipActive,
        chromeWindowFocused, chromeHeld, state, bootstrapError != null || playback.error != null || playback.recovering)
    val latestChromeCanAutoHide by rememberUpdatedState(chromeCanAutoHide)
    if (System.getProperty("bilipai.validation.fullscreenIdleInput") == "true" || feedbackPlacementDiagnostic) {
        // Fixture-only readback on this exact retained surface; no controller or
        // globally registered owner. Returned values never retain live objects.
        val latestChromeDiagnostic by rememberUpdatedState<() -> Map<String, Any>>({
            check(java.awt.EventQueue.isDispatchThread())
            var pointerReadAvailable = false
            var pointerPosition: java.awt.Point? = null
            desktopWindowsObserveNativePointer(native.surface) { point ->
                pointerReadAvailable = true
                pointerPosition = point
            }
            val screenPointer = desktopWindowsNativePointerScreenPosition()
            val lastScreenPointer = nativePointerMotion.lastScreenPoint
            linkedMapOf(
                "lastRevealReasonCode" to chrome.lastRevealReasonCode,
                "nativePointerScreenReadAvailable" to (screenPointer != null),
                "nativePointerScreenX" to (screenPointer?.x ?: 0),
                "nativePointerScreenY" to (screenPointer?.y ?: 0),
                "nativeLastPointerScreenKnown" to (lastScreenPointer != null),
                "nativeLastPointerScreenX" to (lastScreenPointer?.x ?: 0),
                "nativeLastPointerScreenY" to (lastScreenPointer?.y ?: 0),
                "chromeIdentity" to System.identityHashCode(chrome),
                "sourceIdentity" to System.identityHashCode(chromeSource),
                "routeIdentity" to System.identityHashCode(route),
                "windowIdentity" to System.identityHashCode(chromeWindow),
                "surfaceIdentity" to System.identityHashCode(native.surface),
                "visible" to chrome.visible, "activityRevision" to chrome.activityRevision,
                "remainingIdleMillis" to chrome.remainingIdleMillis(),
                "fullscreen" to fullscreen, "active" to active, "pipActive" to pipActive,
                "windowFocusedCached" to chromeWindowFocused,
                "windowFocusedActual" to (chromeWindow?.isFocused == true),
                "windowActiveActual" to (chromeWindow?.isActive == true),
                "nativeKeyboardFocusActual" to native.surface.isFocusOwner,
                "nativePointerClaim" to nativePointerOnVideo, "nativeKeyboardClaim" to nativeKeyboardOnVideo,
                "nativePointerReadAvailable" to pointerReadAvailable,
                "nativePointerWithinSurface" to (pointerPosition != null),
                "nativePointerLocalX" to (pointerPosition?.x ?: 0), "nativePointerLocalY" to (pointerPosition?.y ?: 0),
                "nativeSurfaceWidth" to native.surface.width, "nativeSurfaceHeight" to native.surface.height,
                "topHovered" to topHovered, "topFocused" to topFocused,
                "barHovered" to barInteraction.hovered, "barFocused" to barInteraction.focused,
                "barOperationHeld" to barInteraction.operationHeld, "detailsOpen" to detailsOpen,
                "collectionOpen" to showCollection, "queueOpen" to showPlaybackQueue,
                "audioLanguageMenuOpen" to (audioLanguageMenu != null), "audioTrackMenuOpen" to (audioTrackMenu != null),
                "interactionOpen" to (interactionMode != null), "chromeHeldComposed" to chromeHeld,
                "canAutoHideComposed" to chromeCanAutoHide,
                "bootstrapErrorPresent" to (bootstrapError != null), "playbackErrorPresent" to (playback.error != null),
                "playbackRecovering" to playback.recovering, "qualitySwitching" to (success?.isQualitySwitching == true),
                "ready" to state.ready, "firstVideoFrameReady" to state.firstVideoFrameReady,
                "nativeUnpaused" to (state.nativePaused == false), "loading" to state.loading,
                "paused" to state.paused, "ended" to state.ended, "audioOnly" to state.audioOnly,
                "pausedForCache" to state.pausedForCache, "nativeErrorPresent" to (state.error != null),
                "assemblyCurrent" to (shell.slot.currentAssembly() === assembly), "assemblyOwned" to assembly.owns(),
                "rootOwned" to rootEnvironment.owns(), "routeIdentityCurrent" to (rootEnvironment.currentKey() === latestChromeRoute),
                "sourceCaptured" to (latestChromeSource != null),
                "sourceCurrent" to (latestChromeSource?.let(assembly.native::isCurrent) == true),
                "chromeCurrent" to chromeCurrent(), "latestChromeIdentityMatches" to (latestChrome === chrome),
            ).apply {
                detailsCallbackCounts?.let { counts ->
                    put("detailsCallbackAttempts", counts[0]); put("detailsCallbackAccepted", counts[1])
                }
            }
        })
        DisposableEffect(assembly, native.surface, chromeWindow) {
            val surface = native.surface
            val boundWindow = chromeWindow
            val getter = java.util.function.Supplier {
                check(java.awt.EventQueue.isDispatchThread())
                check(boundWindow != null && boundWindow === rootEnvironment.window &&
                    javax.swing.SwingUtilities.getWindowAncestor(surface) === boundWindow)
                latestChromeDiagnostic()
            }
            val property = "bilipai.validation.fullscreenChromeSnapshot"
            surface.putClientProperty(property, getter)
            onDispose { if (surface.getClientProperty(property) === getter) surface.putClientProperty(property, null) }
        }
    }
    // Gate changes reset the idle period; a new accepted source gets new UI state.
    LaunchedEffect(chrome, fullscreen, active, pipActive, chromeCanAutoHide) {
        chrome.reveal(DesktopWindowsFullscreenChromeActivity.GATE_CHANGED)
    }
    LaunchedEffect(chrome, chromeCanAutoHide, chrome.visible, chrome.activityRevision) {
        if (chromeCanAutoHide && chrome.visible) {
            val revision = chrome.activityRevision
            delay(chrome.remainingIdleMillis())
            chrome.hideIfIdle(revision, latestChrome === chrome && latestChromeCanAutoHide && chromeCurrent())
        }
    }
    val chromeVisible = !fullscreen || chrome.visible
    fun command(block: () -> Unit): Boolean {
        val accepted = assembly.native.current() ?: return false
        if (!current()) return false
        var dispatched = false
        val admitted = assembly.native.admitPlaybackDispatch(accepted) { if (current()) { block(); dispatched = true } }
        return admitted && dispatched
    }
    fun seekChapter(expected: DesktopOriginalVideoChapterResult, accepted: DesktopOriginalVideoAcceptedPublication, positionMs: Long) {
        if (!current() || !assembly.native.isCurrent(accepted)) return
        assembly.native.admitPlaybackDispatch(accepted) {
            if (!current()) return@admitPlaybackDispatch
            val session = assembly.playback.captureDesktopLoadState()
            val duration = native.state.value.durationSeconds
            val durationMs = if (duration.isFinite() && duration > 0.0) (duration * 1000.0).toLong() else 0L
            if (accepted.request.bvid == expected.bvid && accepted.request.cid == expected.cid &&
                isDesktopOriginalVideoChapterSeekCurrent(expected, assembly.playback.captureDesktopChapterResult(),
                    session.currentBvid, session.currentCid, session.currentLoadRequestToken, positionMs, durationMs)) {
                assembly.playback.seekTo(positionMs)
            }
        }
    }
    fun setSpeed(speed: Double) {
        if (current()) {
            assembly.playback.applyPlaybackSpeedFromUi(speed.toFloat())
            preferencesChanged(preferences.copy(speed = speed))
        }
    }
    // V2 (spec 4.13 and 4.15): the player_layout flag is read here. DESKTOP has no overlay yet, so it resolves to LEGACY and the
    // three pieces below render exactly as before. The controls binding holds the admitted actions of the bar and the strip.
    val windowsUxSettings by LocalDesktopWindowsUx.current.settings.collectAsState()
    val legacyChrome = desktopPlayerEffectiveLayout(windowsUxSettings.playerLayout, DESKTOP_PLAYER_OVERLAY_AVAILABLE) ==
        DesktopWindowsUxPlayerLayout.LEGACY
    val controls = DesktopPlayerControlsBinding(
        current = ::current, command = ::command,
        togglePause = { native.togglePause() }, setMuted = { native.setMuted(it) }, setVolume = { native.setVolume(it) },
        isMuted = { state.muted }, preferences = preferences, preferencesChanged = preferencesChanged,
        navigate = ::navigateFromThisClick,
        switchQuality = { shell.playback.switchQuality(it) }, seekToSeconds = { shell.playback.seekTo(it) },
        goBack = { actions.back() }, enterPictureInPicture = { actions.pictureInPicture() },
        toggleFullscreen = { actions.fullscreen() }, speed = ::setSpeed,
    )
    BoxWithConstraints(Modifier.fillMaxSize().padding(if (fullscreen) 0.dp else 8.dp)) {
        // Keep one Row and one native slot through every width/fullscreen/panel change.
        // A narrow window still reserves real sibling space: a heavyweight Canvas cannot be covered by a Compose sheet.
        val detailsWidth = minOf(360.dp, maxWidth * .43f)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(if (chromeVisible) 6.dp else 0.dp)) {
                val legacyTopStrip: @Composable () -> Unit = { DesktopWindowsPlayerSurface(Modifier.fillMaxWidth()
                    .desktopWindowsChromePointerInput(::observeChromePointer)
                    .onFocusChanged { topFocused = it.hasFocus }.focusGroup().hoverable(topInteractions)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = controls.back, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                        Text(success?.info?.title ?: readonlyInfo?.title ?: "视频", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } }
                if (chromeVisible && legacyChrome) legacyTopStrip()
                // One retained native slot; original fixed ratios letterbox the same viewport.
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                    val showPageError = bootstrapError != null || playback.error != null || originalLoadError != null
                    val viewportLayout = resolveVideoViewportLayout(constraints.maxWidth, constraints.maxHeight, pictureAspectRatio)
                    val density = LocalDensity.current
                    val viewportWidth = with(density) { viewportLayout.width.toDp() }
                    val viewportHeight = with(density) { viewportLayout.height.toDp() }
                    var viewportSize by remember(native) { mutableStateOf(IntSize.Zero) }
                    Box(Modifier.align(Alignment.Center).size(viewportWidth, viewportHeight)
                        .background(Color.Black).onSizeChanged { viewportSize = it }
                        .onGloballyPositioned { coordinates ->
                            // Store pixels only, never a LayoutCoordinates object.
                            // Host-hidden still keeps the peer; outgoing entries may
                            // not overwrite the current physical route's viewport.
                            if (rootEnvironment.currentKey() === route && rootEnvironment.owns() &&
                                shell.slot.currentAssembly() === assembly && assembly.owns() && !pipActive) {
                                val origin = coordinates.positionInWindow()
                                val rect = Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
                                feedbackBounds.reportVideo(rect)
                                nativeSurface.reportViewport(route, viewportLease, rect)
                            }
                        }
                        .focusRequester(viewportFocus).onFocusChanged {
                            viewportKeyboardFocused = it.hasFocus
                            if(current()) actions.focusChanged(it.hasFocus)
                        }.focusable()) {
                        // V6 (spec 6.6): on page entry the viewport takes the keyboard focus unless a text editor owns it.
                        LaunchedEffect(assembly, route, active, presentationAlive, pipActive) {
                            if (active && presentationAlive && !pipActive && current())
                                desktopWindowsRequestPageEntryFocus(desktopWindowsTextEditorFocused()) { viewportFocus.requestFocus() }
                        }
                        if (active && !pipActive) {
                            DesktopVideoCommandPopup(viewportSize, {
                                // Use the existing viewport overlay carrier so the retained heavyweight Canvas
                                // cannot cover the page card. No native surface or playback owner is replaced.
                                if (showPageError) DesktopWindowsPageErrorOverlay { failureContent() }
                                else Box(Modifier.fillMaxSize()) {
                                    DesktopWindowsVideoEntryCover(
                                        contentKey = coverContentKey, bvid = coverBvid,
                                        entryCoverUrl = if (coverBvid == route.bvid) route.coverUrl else "",
                                        detailCoverUrl = coverDetailUrl,
                                        autoPlayOnOpenEnabled = coverAutoPlay,
                                        firstVideoFrameReady = coverRuntime?.state?.firstVideoFrameReady == true,
                                        observedPlayWhenReady = coverRuntime?.playWhenReady == true,
                                        observedIsPlaying = coverRuntime?.isPlaying == true,
                                        currentPositionMs = coverRuntime?.currentPositionMs ?: 0L,
                                        hasMediaItem = coverRuntime != null,
                                        isVerticalVideo = coverRuntime?.state?.let {
                                            if (it.videoWidth > 0 && it.videoHeight > 0) it.videoHeight > it.videoWidth
                                            else coverMetadataVertical
                                        } ?: coverMetadataVertical,
                                        isFullscreen = fullscreen,
                                        stillCurrent = ::coverStillCurrent, canReveal = ::coverCanReveal,
                                        onPlayFromCover = ::playFromEntryCover,
                                    )
                                    actions.overlay()
                                    val subtitleSizes = resolveSubtitleTextSizeSpec(
                                        playerWidthDp = viewportWidth.value.toInt(), largeTextEnabled = subtitleLargeTextByUser)
                                    CompositionLocalProvider(LocalDesktopOriginalVideoSectionPlatform provides platforms.holder.section) {
                                        key(assembly.subtitleSaveableOwnerKey, success?.info?.bvid, success?.info?.cid) {
                                            VideoSubtitleOverlayHost(
                                                player = assembly.section, uiState = original,
                                                bvid = success?.info?.bvid ?: route.bvid,
                                                subtitleFeatureEnabled = isSubtitleFeatureEnabledForUser(),
                                                subtitleOverlayEnabled = pictureCurrent(viewportSource) && subtitleMode != SubtitleDisplayMode.OFF,
                                                subtitleDisplayMode = subtitleMode,
                                                primaryTextSizeSp = subtitleSizes.primarySp, secondaryTextSizeSp = subtitleSizes.secondarySp,
                                                initialVerticalOffsetFraction = pictureSettings.subtitleVerticalOffsetFraction,
                                                positionLocked = pictureSettings.subtitlePositionLocked,
                                                isInPipMode = pipActive, isAudioOnly = state.audioOnly,
                                                suppressSubtitleOverlay = !current(), isFullscreen = fullscreen,
                                                // Windows chrome is a sibling outside this measured video viewport.
                                                controlsVisible = false, endDrawerReservedWidth = 0.dp,
                                                playerViewportSize = viewportSize, bottomControlsHeightPx = 0,
                                                onVerticalOffsetCommitted = subtitlePositionWriter,
                                            )
                                        }
                                    }
                                    DesktopWindowsVideoInteractiveChoiceOverlay(assembly, interactiveChoicePanel, interactiveChoiceSource)
                                    DesktopWindowsSponsorCommunityOverlay(shell.playback, assembly, viewportSource,
                                        sponsorPresentation, showSponsorSegment, ::sponsorCurrent, ::admitSponsorAction,
                                        onCloseSegment = { showSponsorSegment = false })
                                    NextWatchOverlay(
                                        settingsContext = platforms.holder.settingsContext,
                                        mediaKey = "${success?.info?.bvid ?: route.bvid}:${success?.info?.cid ?: 0L}",
                                        eligible = feedbackPresentationCurrent() && chromeWindowFocused && pictureCurrent(viewportSource) &&
                                            success != null && !state.audioOnly && (!fullscreen || !chromeVisible) &&
                                            !interactiveChoicePanel.visible && !showCollection && !showPlaybackQueue &&
                                            !showSponsorSegment && seekPreview == null && !barInteraction.operationHeld,
                                        playing = !(state.nativePaused ?: state.paused) && !state.loading && !state.pausedForCache &&
                                            !state.ended && state.error == null && state.failure == null,
                                        hint = nextWatchHint,
                                        resolve = assembly.nextWatch::capture,
                                        play = ::navigateNextWatchFromThisClick,
                                    )
                                    DesktopWindowsVideoSeekPreviewHost(
                                        preview = seekPreview, source = viewportSource, success = success,
                                        current = chromeVisible && pictureCurrent(viewportSource), viewportSize = viewportSize,
                                        currentPositionMs = (native.state.value.positionSeconds * 1000.0).toLong(),
                                        durationMs = (native.state.value.durationSeconds * 1000.0).toLong(),
                                        modifier = Modifier.align(Alignment.BottomStart),
                                    )
                                }
                            }, native.surface)
                        } else Text("正在浮窗播放", color=Color.White, modifier=Modifier.align(Alignment.Center))
                        if (presentationAlive && !pipActive && !showPageError) engagementBinding?.let { binding ->
                            DesktopWindowsConfirmedVideoFeedback(binding, viewportSize, native.surface, feedbackBounds)
                        }
                    }
                }
                if (playback.recovering && playback.recoveryMessage != null)
                    Text(playback.recoveryMessage.orEmpty(), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                val legacyControlBar: @Composable () -> Unit = { DesktopWindowsVideoControlBar(
                    state = state, sourceVersion = native.currentSourceSnapshot()?.sourceVersion ?: 0L,
                    enabled = current() && success != null, fullscreen = fullscreen, detailsOpen = detailsOpen,
                    onInteractionHoldChanged = { interaction ->
                        if (barInteraction != interaction) {
                            val previouslyHeld = barInteraction.held(nativePointerOnVideo, nativeKeyboardOnVideo)
                            barInteraction = interaction
                            if (previouslyHeld != interaction.held(nativePointerOnVideo, nativeKeyboardOnVideo))
                                chromeActivity(DesktopWindowsFullscreenChromeActivity.CONTROL_HOLD_CHANGED)
                        }
                    },
                    onChromePointerInput = ::observeChromePointer,
                    hasPrevious = shell.playback.hasPrevious, hasNext = shell.playback.hasNext,
                    canPictureInPicture = !pipActive && success != null && state.videoCodec != null && !state.audioOnly,
                    speedOptions = speedOptions,
                    pbpRidgeSamples = if (progressPeakEnabled && playbackToolsCurrent(collectionQueueSource)) progressSamples else emptyList(),
                    seekPreviewSource = viewportSource,
                    seekPreviewEnabled = pictureCurrent(viewportSource),
                    onSeekPreviewChanged = { expected, preview ->
                        if (preview == null) {
                            if (seekPreview?.source === expected) seekPreview = null
                        } else if (expected === viewportSource && preview.source === expected && pictureCurrent(expected)) {
                            seekPreview = preview
                        }
                    },
                    qualities = success?.let { value -> value.qualityIds.mapIndexed { index, id -> id to (value.qualityLabels.getOrNull(index) ?: id.toString()) } }.orEmpty(),
                    selectedQuality = success?.currentQuality,
                    canOpenCollection = canOpenCollection, canOpenPlaybackQueue = canOpenPlaybackQueue,
                    onOpenCollection = { if (collectionQueueCurrent()) { showPlaybackQueue = false; showCollection = true } },
                    onOpenPlaybackQueue = { if (collectionQueueCurrent()) { showCollection = false; showPlaybackQueue = true } },
                    canOpenInteraction = interactionCurrent(),
                    onSendDanmaku = { openInteraction(DesktopWindowsVideoInteraction.DANMAKU) },
                    onShareVideo = { openInteraction(DesktopWindowsVideoInteraction.SHARE) },
                    canDownloadAudio = interactionCurrent() && success?.let { !it.isQualitySwitching && !it.audioUrl.isNullOrBlank() } == true,
                    canSaveCover = interactionCurrent() && success?.let { !it.isQualitySwitching && it.info.pic.isNotBlank() } == true,
                    onDownloadAudio = { saveVideoResource(audioOnly = true) },
                    onSaveCover = { saveVideoResource(audioOnly = false) },
                    canScreenshot = interactionCurrent() && success?.isQualitySwitching == false &&
                        state.ready && !state.loading && !state.ended && !state.audioOnly && state.videoCodec != null &&
                        savedScreenshot?.isCurrent() != true,
                    screenshotBusy = screenshotBusy, onScreenshot = { takeScreenshot() },
                    chapters = chapters, chaptersSource = chaptersSource, onChapterSeek = ::seekChapter,
                    onPlayPause = controls.playPause,
                    onPrevious = controls.previous, onNext = controls.next,
                    onMute = controls.mute,
                    onVolume = controls.volume,
                    onSpeed = controls.speed,
                    onQuality = controls.quality,
                    onSeek = controls.seek,
                    onPictureInPicture = controls.pictureInPicture,
                    onFullscreen = controls.fullscreen,
                    onDetails = {
                        detailsCallbackCounts?.let { if (it[0] < Long.MAX_VALUE) it[0]++ }
                        if (current()) {
                            detailsCallbackCounts?.let { if (it[1] < Long.MAX_VALUE) it[1]++ }
                            detailsOpen = !detailsOpen
                        }
                    },
                    onOpenIntroduction = { if (current()) {
                        detailsTab = DesktopWindowsVideoDetailsTab.INTRODUCTION
                        detailsOpen = true
                    } },
                    sponsorMoreActions = { dismiss ->
                        DesktopWindowsSponsorMoreActions(assembly, viewportSource, sponsorPresentation,
                            ::sponsorCurrent, ::admitSponsorAction,
                            onOpenSegment = { showSponsorSegment = true }, dismissMenu = dismiss)
                    },
                    sponsorSkip = {
                        val skip = sponsorPresentation.skip?.takeIf { it.source === collectionQueueSource }
                        val action = skip?.let { captured ->
                            val segment = captured.segment ?: return@let null
                            val state = captured.state
                            val segmentId = state.segmentId ?: return@let null
                            if (!state.visible || segmentId.isBlank() || segmentId != segment.UUID ||
                                state.skipToMs <= 0L || state.skipToMs != segment.endTimeMs) return@let null
                            com.android.purebilibili.plugin.sdk.SkipAction.ShowButton(
                                state.skipToMs, state.label.orEmpty(), segmentId)
                        }
                        DesktopWindowsSponsorSkipSection(shell.playback, assembly, collectionQueueSource,
                            action, skip?.segment, { interactionCurrent() &&
                                assembly.playback.desktopSponsorPresentation.value.skip === skip })
                    },
                    enhancement = actions.enhancement,
                ) }
                if (chromeVisible && legacyChrome) legacyControlBar()
            }
            val detailsPanel: @Composable () -> Unit = { DesktopWindowsVideoDetailsPanel(
                modifier = Modifier.width(detailsWidth).fillMaxHeight(), selectedTab = detailsTab,
                onTabChange = { detailsTab = it }, onClose = { if (current()) detailsOpen = false },
                current = ::current,
                loading = readonlyInfo == null && original is VideoPlaybackUiState.Loading && bootstrapError == null && playback.error == null,
                failureContent = if (readonlyInfo == null && success == null &&
                    (original is VideoPlaybackUiState.Error || bootstrapError != null || playback.error != null)) failureContent else null,
                related = {
                    if (collectionQueueSource != null && success != null) collectionQueueSource.let { source ->
                        DesktopWindowsVideoRelatedSection(assembly, success, source,
                            platforms.content, ::interactionCurrent,
                            sourceAdmission = { action ->
                                var applied = false
                                shell.factoryFor(assembly).withPresentationAdmission(assembly, source) {
                                    if (interactionCurrent()) assembly.native.admitPlaybackDispatch(source) {
                                        if (interactionCurrent()) { action(); applied = true }
                                    }
                                } && applied
                            }) { target, cardOwned ->
                            latestActions.relatedNavigation(assembly, source, target, cardOwned)
                        }
                    } else if (readonlyInfo != null) metadataAdmission?.let { permission ->
                        fun readonlyCurrent(): Boolean = current() && rootEnvironment.owns() &&
                            rootEnvironment.currentKey() === route && rootEnvironment.commands.containsEntry(route) &&
                            assembly.playback.desktopMetadataAdmission.value === permission && permission.isCurrent()
                        DesktopWindowsVideoReadonlyRelatedSection(assembly, detail, permission, ::readonlyCurrent) { target, cardOwned ->
                            val stillCaptured = { readonlyCurrent() && cardOwned() }
                            val admit: ((() -> Unit) -> Boolean) = { action ->
                                var applied = false
                                permission.admit {
                                    if (stillCaptured()) { action(); applied = true }
                                } && applied
                            }
                            // Existing Root resolver/return controller/physical stack
                            // captures the destination afresh. No native success is forged.
                            val commands = rootEnvironment.commands as? DesktopOriginalRootRouteAssembly
                                ?: error("Actual Root route assembly is required for readonly related navigation")
                            if (stillCaptured()) {
                                if (target is BiliPaiNavKey.VideoDetail)
                                    commands.videoFromSource(target, stillCaptured, admit)
                                else commands.pushFromSource(target, stillCaptured, admit)
                            }
                        }
                    }
                },
                introduction = {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (success != null) {
                            Text(success.info.title, style = MaterialTheme.typography.titleMedium)
                            collectionQueueSource?.let { source ->
                                DesktopWindowsVideoStatisticsSection(assembly, success, source,
                                    platforms.holder.settingsContext, ::interactionCurrent,
                                    sourceAdmission = { action ->
                                        var applied = false
                                        shell.factoryFor(assembly).withPresentationAdmission(assembly, source) {
                                            if (interactionCurrent()) assembly.native.admitPlaybackDispatch(source) {
                                                if (interactionCurrent()) { action(); applied = true }
                                            }
                                        } && applied
                                    }, onNotice = { latestActions.notice(it) })
                            }
                            TextButton(onClick = { if (current()) actions.user(success.info.owner.mid) }) { Text(success.info.owner.name) }
                            collectionQueueSource?.let { source ->
                                DesktopWindowsVideoMetadataSection(assembly, success.info, source,
                                    platforms.holder.settingsContext, platforms.portrait.creatorTeam,
                                    ::current, actions.user) { url, honorOwned ->
                                    latestActions.honorLink(assembly, source, url, honorOwned)
                                }
                            }
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { engagementBinding?.like() }, enabled = engagementBinding?.isOwned() == true,
                                    modifier = Modifier.desktopWindowsFeedbackLikeAnchor(feedbackBounds)) { Text(if(engagement.isLiked) "已点赞" else "点赞") }
                                actions.favorite(assembly, success, ::current)
                                TextButton(onClick = rememberFollowActionWithConfirmation(
                                    targetKey = success.info.owner.mid, userName = success.info.owner.name,
                                    isFollowing = engagement.isFollowing, onFollowClick = { engagementBinding?.toggleFollow() }),
                                    enabled = engagementBinding?.isOwned() == true) { Text(if(engagement.isFollowing) "已关注" else "关注") }
                                TextButton(onClick = { engagementBinding?.triple() }, enabled = engagementBinding?.isOwned() == true) { Text("三连") }
                            }
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { if(current()) assembly.domains.engagement.toggleWatchLater() }) { Text("稍后再看") }
                                TextButton(onClick = { engagementBinding?.openCoinDialog() },
                                    enabled = engagementBinding?.isOwned() == true && engagement.coinCount < engagement.coinLimit) { Text("投币") }
                                TextButton(onClick = {
                                    if (desktopWindowsVideoCanChooseDownloadQuality(success)) openDownloadSelection(success)
                                    else if (current()) actions.download(assembly, success)
                                }) { Text(if (desktopWindowsVideoCanChooseDownloadQuality(success)) "下载" else "下载当前画质") }
                                if (com.android.purebilibili.feature.download.resolveBatchDownloadCandidates(success.info).size > 1) {
                                    TextButton(onClick = { openDownloadSelection(success, batch = true) }) { Text("批量缓存") }
                                }
                                TextButton(onClick = { saveVideoResource(audioOnly = true) },
                                    enabled = interactionCurrent() && !success.isQualitySwitching && !success.audioUrl.isNullOrBlank()) { Text("仅下载音频") }
                                TextButton(onClick = { saveVideoResource(audioOnly = false) },
                                    enabled = interactionCurrent() && !success.isQualitySwitching && success.info.pic.isNotBlank()) { Text("保存封面") }
                                TextButton(onClick = { takeScreenshot() },
                                    enabled = interactionCurrent() && !success.isQualitySwitching && state.ready && !state.loading &&
                                        !state.ended && !state.audioOnly && state.videoCodec != null && !screenshotBusy && savedScreenshot?.isCurrent() != true) {
                                    Text(if (screenshotBusy) "截图中…" else "视频截图（S）")
                                }
                                TextButton(onClick = { openInteraction(DesktopWindowsVideoInteraction.SHARE) }, enabled = interactionCurrent()) { Text("分享视频") }
                            }
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { openInteraction(DesktopWindowsVideoInteraction.DANMAKU) }, enabled = interactionCurrent()) { Text("发送弹幕") }
                                if (aiSummaryEntryEnabled) TextButton(onClick = { openInteraction(DesktopWindowsVideoInteraction.AI_SUMMARY) }, enabled = interactionCurrent()) { Text("AI 总结") }
                                if (videoNoteEnabled) TextButton(onClick = { openInteraction(DesktopWindowsVideoInteraction.NOTES) }, enabled = interactionCurrent()) { Text("视频笔记") }
                            }
                            collectionQueueSource?.let { source ->
                                bgmResult?.takeIf { desktopWindowsVideoBgmMatchesSource(it,
                                    assembly.playback.captureDesktopBgmResult(), source.request) }?.let { music ->
                                    actions.bgm(DesktopWindowsVideoBgmPresentation(assembly, source, music, ::current))
                                }
                            }
                            collectionQueueSource?.let { source ->
                                DesktopWindowsVideoDescriptionSection(assembly, success.info, source,
                                    success.videoTags, platforms.holder.settingsContext.pluginContext,
                                    ::interactionCurrent,
                                    onMetadataNavigation = { target, metadataOwned ->
                                        latestActions.metadataNavigation(assembly, source, target, metadataOwned)
                                    }) { url, descriptionOwned ->
                                    latestActions.descriptionLink(assembly, source, url, descriptionOwned)
                                }
                            }
                            if (success.info.pages.size > 1) {
                                Text("分P", style = MaterialTheme.typography.titleSmall)
                                success.info.pages.forEachIndexed { index, part ->
                                    TextButton(onClick = {
                                        if (!current() || rootEnvironment.currentKey() !== route) return@TextButton
                                        val selectedState = assembly.playback.captureDesktopPlaybackState() as? VideoPlaybackUiState.Success
                                            ?: return@TextButton
                                        if (selectedState.info.bvid != success.info.bvid || selectedState.info.cid != success.info.cid ||
                                            selectedState.info.pages.getOrNull(index)?.cid != part.cid) return@TextButton
                                        partScope.launch {
                                            try {
                                                if (!current() || rootEnvironment.currentKey() !== route ||
                                                    assembly.playback.captureDesktopPlaybackState() !== selectedState) return@launch
                                                val source = DesktopVideoBootstrapSeed.capture(rootEnvironment, assembly, route)
                                                if (!current() || rootEnvironment.currentKey() !== route ||
                                                    assembly.playback.captureDesktopPlaybackState() !== selectedState) return@launch
                                                shell.playback.playPart(index, bootstrapSource = source, expectedPartState = selectedState)
                                            } catch (cancelled: CancellationException) { throw cancelled }
                                            catch (failure: Exception) {
                                                if (current() && rootEnvironment.currentKey() === route &&
                                                    assembly.playback.captureDesktopPlaybackState() === selectedState)
                                                    latestActions.notice(failure.message ?: "分 P 加载未能启动")
                                            }
                                        }
                                    }, modifier = Modifier.fillMaxWidth()) {
                                        Text("P${index + 1} · ${part.part}")
                                    }
                                }
                            }
                        } else if (readonlyInfo != null) {
                            Text(readonlyInfo.title, style = MaterialTheme.typography.titleMedium)
                            Text(readonlyInfo.owner.name, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (readonlyInfo.desc.isNotBlank()) Text(readonlyInfo.desc,
                                style = MaterialTheme.typography.bodyMedium)
                            // Playback failure/retry remains visible beside admitted
                            // readonly metadata; it no longer replaces all detail tabs.
                            if (original is VideoPlaybackUiState.Error || bootstrapError != null || playback.error != null)
                                failureContent()
                        } else Text("正在读取视频信息", style = MaterialTheme.typography.bodyMedium)
                        HorizontalDivider()
                        Text("播放设置", style = MaterialTheme.typography.titleSmall)
                        collectionQueueSource?.let { source ->
                            if (success != null) DesktopWindowsVideoTransportSection(
                                success = success, sourceOwner = source, enabled = playbackToolsCurrent(source),
                                stillOwned = { playbackToolsCurrent(source) },
                                admit = { action -> admitPlaybackTool(source, action) },
                                onSwitchCdnTo = { index ->
                                    if (playbackToolsCurrent(source)) assembly.playback.switchCdnTo(index)
                                },
                                onProbeCdnCandidates = {
                                    if (playbackToolsCurrent(source)) assembly.playback.probeCurrentCdnCandidates()
                                }, modifier = Modifier.fillMaxWidth(),
                            )
                            if (playbackToolsCurrent(source)) {
                                Text("定时暂停", style = MaterialTheme.typography.labelLarge)
                                SleepTimerOptions(sleepTimerMinutes) { minutes ->
                                    if (playbackToolsCurrent(source)) assembly.playback.setSleepTimer(minutes)
                                }
                            }
                        }
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(state.audioOnly, onClick = { if(command { native.setAudioOnly(!state.audioOnly) }) preferencesChanged(preferences.copy(audioOnly=!state.audioOnly)) }, label = { Text("仅音频") })
                            FilterChip(preferences.danmaku.enabled, onClick = { if (current()) actions.toggleDanmaku() }, label = { Text("弹幕") })
                            TextButton(onClick = { if (current()) actions.danmakuSettings() }) { Text("弹幕设置") }
                        }
                        DesktopWindowsVideoViewportControls(
                            ratio = pictureAspectRatio, flipHorizontal = pictureFlipHorizontal,
                            flipVertical = pictureFlipVertical, enabled = pictureCurrent(viewportSource),
                            isVerticalVideo = isVerticalPicture,
                            onRatioSelected = ::changePictureAspectRatio,
                            onFlipHorizontal = { flipPicture(horizontal = true) },
                            onFlipVertical = { flipPicture(horizontal = false) },
                        )
                        Text("视频编码", style = MaterialTheme.typography.labelLarge)
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("hev1" to "HEVC", "av01" to "AV1", "avc1" to "H.264").forEach { (codec, label) ->
                                FilterChip(preferences.videoCodecPreference == codec,
                                    onClick = { if(current()) preferencesChanged(preferences.copy(videoCodecPreference=codec)) }, label = { Text(label) })
                            }
                        }
                        Text("字幕显示", style = MaterialTheme.typography.labelLarge)
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            subtitleDisplayOptions.forEach { option ->
                                FilterChip(selected = subtitleMode == option.mode,
                                    onClick = { if (current() && option.enabled) subtitleOverride = option.mode },
                                    enabled = option.enabled, label = { Text(option.label) })
                            }
                            FilterChip(selected = subtitleLargeTextByUser, enabled = pictureCurrent(viewportSource),
                                onClick = { viewportSource?.let { expected ->
                                    admitPlaybackTool(expected) { subtitleLargeTextByUser = !subtitleLargeTextByUser }
                                } }, label = { Text("放大字幕") })
                            FilterChip(selected = pictureSettings.subtitlePositionLocked, enabled = pictureCurrent(viewportSource),
                                onClick = { changeSubtitlePositionLocked(!pictureSettings.subtitlePositionLocked) },
                                label = { Text("锁定位置") })
                        }
                        if (!pictureSettings.subtitlePositionLocked && subtitleMode != SubtitleDisplayMode.OFF)
                            Text("拖动视频上的字幕可调整位置", style = MaterialTheme.typography.bodySmall)
                        success?.let { value ->
                            val languages = desktopWindowsAudioLanguageOptions(value)
                            val audioTracks = desktopWindowsNativeAudioTracks(state)
                            if (languages.isNotEmpty() || audioTracks.size > 1) {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (languages.isNotEmpty()) TextButton(onClick = {
                                        collectionQueueSource?.let { accepted ->
                                            audioLanguageMenu = shell.playback.captureAudioSelection(assembly, accepted, ::collectionQueueCurrent)
                                        }
                                    }, enabled = collectionQueueCurrent() && !value.isQualitySwitching, modifier = Modifier.fillMaxWidth()) {
                                        val language = desktopWindowsCurrentAudioLanguage(value)
                                        Text("音频语言：${languages.firstOrNull { it.language == language }?.label ?: language ?: "原声"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (audioTracks.size > 1) TextButton(onClick = {
                                        collectionQueueSource?.let { accepted ->
                                            audioTrackMenu = shell.playback.captureAudioSelection(assembly, accepted, ::collectionQueueCurrent)
                                        }
                                    }, enabled = collectionQueueCurrent() && !value.isQualitySwitching && state.ready && !state.loading, modifier = Modifier.fillMaxWidth()) {
                                        Text("音轨：${audioTracks.firstOrNull { it.selected }?.let(::desktopWindowsNativeAudioTrackLabel) ?: "未选择"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (value.availableAudioQualities.isNotEmpty()) {
                                Text("音质", style = MaterialTheme.typography.labelLarge)
                                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    value.availableAudioQualities.forEach { audio ->
                                        FilterChip(value.requestedAudioQuality == audio.preferenceId,
                                            onClick = { if (current()) shell.playback.selectAudioQuality(audio.preferenceId) }, label = { Text(audio.label) })
                                    }
                                }
                            }
                            if (value.subtitleTracks.isNotEmpty()) {
                                Text("字幕轨道", style = MaterialTheme.typography.labelLarge)
                                value.subtitleTracks.forEach { track -> TextButton(onClick={if(current()) assembly.playback.selectSubtitleTrack(track.trackKey)}) {Text(track.lanDoc)} }
                            }
                        }
                        Text("当前视频：${state.videoCodec ?: "—"} · ${state.videoWidth}×${state.videoHeight}\n音频：${state.audioCodec ?: "—"}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                comments = {
                    if (success != null) CompositionLocalProvider(LocalDesktopCommentBindings provides platforms.holder.commentsPlatform) {
                        collectionQueueSource?.let { commentSource ->
                        val commentFactory = shell.factoryFor(assembly)
                        DesktopWindowsVideoCommentsSection(assembly, success, commentSource,
                            routedComment = routedCommentRequest.takeIf { commentsInitializedAid == success.info.aid &&
                                it.rootReplyId > 0L && !it.handled },
                            current = { interactionCurrent() && commentFactory.isPresentationCurrent(assembly, commentSource) && assembly.native.isCurrent(commentSource) },
                            admission = { action -> commentFactory.withPresentationAdmission(assembly, commentSource, action) },
                            onUser = actions.user, login = actions.login,
                            openLink=actions.openLink, seek=shell.playback::seekTo,
                            search = { openComment -> collectionQueueSource?.let { captured ->
                                val factory = shell.factoryFor(assembly)
                                DesktopWindowsCommentSearchSection(assembly.domains.comments, captured, success.info.owner.mid,
                                    stillOwned = { interactionCurrent() && factory.isPresentationCurrent(assembly, captured) &&
                                        assembly.native.isCurrent(captured) },
                                    admission = { action -> factory.withPresentationAdmission(assembly, captured, action) },
                                    onComment = openComment)
                            } })
                        }
                    } else Text("正在读取评论", style = MaterialTheme.typography.bodyMedium)
                },
            ) }
            if (detailsOpen) detailsPanel()
        }
    }

    // V6 (spec 6.5 and 6.6): bind the shortcut router to this composition. Each effect is an admitted lambda of the leaf: the controls binding,
    // current(), shell.playback.* and actions.*; the router reaches neither the player nor the assembly.
    SideEffect {
        shortcutBinding.legacyLayout = legacyChrome
        shortcutBinding.flyoutOpen = showCollection || showPlaybackQueue || audioLanguageMenu != null || audioTrackMenu != null ||
            interactionMode != null
        shortcutBinding.effects = DesktopPlayerShortcutEffects(
            controls = controls,
            snapshot = { native.state.value.let { DesktopPlayerShortcutSnapshot(it.positionSeconds, it.durationSeconds, it.volume) } },
            seekBy = { seconds -> if (current()) shell.playback.seekBy(seconds) },
            hasPart = { forward -> if (forward) shell.playback.hasNext else shell.playback.hasPrevious },
            canMiniPlayer = { !pipActive && success != null && state.videoCodec != null && !state.audioOnly },
            togglePanel = { if (current()) detailsOpen = !detailsOpen },
            toggleSubtitles = {
                val target = if (subtitleMode != SubtitleDisplayMode.OFF) SubtitleDisplayMode.OFF
                else subtitleDisplayOptions.firstOrNull { it.enabled && it.mode != SubtitleDisplayMode.OFF }?.mode
                if (current() && target != null) { subtitleOverride = target; true } else false
            },
            toggleDanmaku = { if (current()) actions.toggleDanmaku() },
            takeScreenshot = ::takeScreenshot,
            chapterStartsMs = {
                val result = chapters
                if (result == null) emptyList()
                else normalizeViewPointSegments(result.points, (native.state.value.durationSeconds * 1000.0).toLong()).map { it.fromMs }
            },
            seekToChapter = { positionMs ->
                val result = chapters
                val accepted = chaptersSource
                if (result != null && accepted != null) seekChapter(result, accepted, positionMs)
            },
            speedChoices = { resolveDesktopWindowsVideoSpeedOptions(speedOptions, state.speed.toFloat()).map { it.toDouble() } },
            currentSpeed = { state.speed },
        )
    }

    audioLanguageMenu?.takeIf { shell.playback.isAudioSelectionCurrent(it) }?.let { selection ->
        val options = desktopWindowsAudioLanguageOptions(selection.success)
        DesktopWindowsPlayerDialog("音频语言", { audioLanguageMenu = null }, preferredHeightDp = (180 + 48 * options.size).coerceIn(220, 420)) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("音频语言", style = MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.weight(1f)) {
                    items(options) { option ->
                        val selected = option.language == desktopWindowsCurrentAudioLanguage(selection.success)
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = {
                                audioLanguageMenu = null
                                if (!shell.playback.selectAudioLanguage(selection, option.language) &&
                                    option.language != desktopWindowsCurrentAudioLanguage(selection.success)) actions.notice("音频选项已变化，请重新打开")
                            }), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected, onClick = null)
                            Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                TextButton(onClick = { audioLanguageMenu = null }) { Text("完成") }
            }
        }
    }
    LaunchedEffect(state.nativeTrackIdentity, audioTrackMenu) {
        if (audioTrackMenu?.let { !shell.playback.isNativeAudioSelectionCurrent(it) } == true) audioTrackMenu = null
    }
    audioTrackMenu?.takeIf { shell.playback.isNativeAudioSelectionCurrent(it) }?.let { selection ->
        val tracks = desktopWindowsNativeAudioTracks(state)
        DesktopWindowsPlayerDialog("音轨", { audioTrackMenu = null }, preferredHeightDp = (180 + 48 * tracks.size).coerceIn(220, 420)) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("音轨", style = MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.weight(1f)) {
                    items(tracks) { track ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(track.selected, role = Role.RadioButton, onClick = {
                                audioTrackMenu = null
                                if (!shell.playback.selectNativeAudioTrack(selection, track.id) && !track.selected)
                                    actions.notice("音轨选项已变化，请重新打开")
                            }), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = track.selected, onClick = null)
                            Text(desktopWindowsNativeAudioTrackLabel(track), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                TextButton(onClick = { audioTrackMenu = null }) { Text("完成") }
            }
        }
    }

    interactionMode?.takeIf { interactionCurrent() }?.let { mode ->
        actions.interaction(DesktopWindowsVideoInteractionPresentation(assembly,
            checkNotNull(collectionQueueSource), mode, ::interactionCurrent, { interactionMode = null }))
    }
    if (showRetainedShare && retainedShareCurrent()) {
        actions.interaction(DesktopWindowsVideoInteractionPresentation(assembly,
            checkNotNull(retainedShareSource), DesktopWindowsVideoInteraction.SHARE,
            ::retainedShareCurrent, { showRetainedShare = false },
            stillPresented = ::interactionCurrent, visible = interactionCurrent()))
    }

    if (success != null && collectionQueueSource != null && collectionQueueCurrent() &&
        (showCollection || showPlaybackQueue)) {
        CompositionLocalProvider(LocalDesktopOriginalVideoHolderPlatform provides platforms.holder) {
            actions.collectionQueue(DesktopWindowsVideoCollectionQueuePresentation(assembly, success,
                collectionQueueSource, showCollection && canOpenCollection, showPlaybackQueue && canOpenPlaybackQueue,
                ::collectionQueueCurrent, { showCollection = false }, { showPlaybackQueue = false }))
        }
    }

    downloadSelection?.takeIf { it.isCurrent() }?.let { selected ->
        com.android.purebilibili.feature.download.DownloadQualityDialog(
            title = selected.title,
            qualityOptions = selected.qualityOptions,
            currentQuality = selected.currentQuality,
            onQualitySelected = { quality, options ->
                selected.select(quality, options)
                if (downloadSelection === selected) downloadSelection = null
            },
            onDismiss = {
                selected.dismiss()
                if (downloadSelection === selected) downloadSelection = null
            },
        )
    }

    batchDownloadSelection?.takeIf { it.isCurrent() }?.let { selected ->
        val downloadTasks by platforms.holder.downloads.tasks.collectAsState()
        val downloadedIds = downloadTasks.values.filter { !it.isFailed && !it.isAudioOnly }
            .map { "${it.bvid}#${it.cid}" }.toSet()
        com.android.purebilibili.feature.download.BatchDownloadDialog(
            title = selected.title,
            candidates = selected.candidates,
            qualityOptions = selected.qualityOptions,
            currentQuality = selected.currentQuality,
            downloadedIds = downloadedIds,
            onConfirm = { quality, options, candidates ->
                selected.select(quality, options, candidates)
                if (batchDownloadSelection === selected) batchDownloadSelection = null
            },
            onDismiss = {
                selected.dismiss()
                if (batchDownloadSelection === selected) batchDownloadSelection = null
            },
        )
    }

    savedScreenshot?.takeIf { it.isCurrent() }?.let { image ->
        fun dismissSavedScreenshot() { if (!screenshotBusy && savedScreenshot === image) savedScreenshot = null }
        DesktopWindowsPlayerDialog("截图已保存", ::dismissSavedScreenshot, preferredHeightDp = 280) {
            AlertDialog(onDismissRequest = ::dismissSavedScreenshot,
                title = { Text("截图已保存") },
                text = { Text("已保存到图片目录（PNG），是否分享这张截图？") },
                confirmButton = { TextButton(enabled = !screenshotBusy, onClick = {
                    if (!image.isCurrent() || savedScreenshot !== image) return@TextButton
                    screenshotBusy = true
                    partScope.launch {
                        try {
                            if (screenshotSection.shareSavedScreenshot(image)) {
                                if (image.isCurrent() && savedScreenshot === image) savedScreenshot = null
                            } else if (image.isCurrent()) latestActions.notice("无法打开分享，请稍后重试")
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            if (image.isCurrent()) latestActions.notice(failure.message ?: "无法打开分享，请稍后重试")
                        } finally { screenshotBusy = false }
                    }
                }) { Text(if (screenshotBusy) "分享中…" else "分享") } },
                dismissButton = { TextButton(enabled = !screenshotBusy, onClick = ::dismissSavedScreenshot) { Text("完成") } })
        }
    }

    // Consume only the original VM suggestion; its coordinator owns the threshold,
    // saved-history lookup, preference and once-per-target acknowledgement.
    val resumeAnchor = assembly.native.current()?.takeIf { expected ->
        success?.info?.let { it.bvid == route.bvid && it.bvid == expected.request.bvid && it.cid == expected.request.cid } == true
    }
    resumeSuggestion?.takeIf { current() && resumeAnchor != null }?.let { suggestion ->
        fun stillSuggested(): Boolean = current() &&
            assembly.playback.resumePlaybackSuggestion.value === suggestion &&
            assembly.native.isCurrent(checkNotNull(resumeAnchor))
        DesktopWindowsPlayerDialog(
            title = "继续播放",
            onDismissRequest = { if(stillSuggested()) assembly.playback.dismissResumePlaybackSuggestion() },
            preferredHeightDp = 320,
        ) {
            AlertDialog(
                onDismissRequest = { if(stillSuggested()) assembly.playback.dismissResumePlaybackSuggestion() },
                title = { Text("继续播放") },
                text = { Text("检测到上次播放到 ${suggestion.targetLabel}（${FormatUtils.formatDuration(suggestion.positionMs)}），是否跳转继续播放？") },
                confirmButton = { TextButton(onClick = {
                    // The original method can save history before launching its owned load;
                    // do not execute that IO while holding the native/source admission locks.
                    if(stillSuggested()) assembly.playback.continueResumePlaybackSuggestion()
                }) { Text("跳转") } },
                dismissButton = { TextButton(onClick = {
                    if(stillSuggested()) assembly.playback.dismissResumePlaybackSuggestion()
                }) { Text("稍后") } })
        }
    }
    DesktopWindowsVideoCoinSection(engagementBinding)
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
private fun desktopWindowsNativeVideoKey(event:java.awt.event.KeyEvent):androidx.compose.ui.input.key.KeyEvent =
    androidx.compose.ui.input.key.KeyEvent(
        androidx.compose.ui.input.key.Key(event.keyCode, event.keyLocation.takeUnless {
            it == java.awt.event.KeyEvent.KEY_LOCATION_UNKNOWN
        } ?: java.awt.event.KeyEvent.KEY_LOCATION_STANDARD),
        androidx.compose.ui.input.key.KeyEventType.KeyDown, event.keyChar.code,
        isAltPressed = event.isAltDown, isCtrlPressed = event.isControlDown,
        isMetaPressed = event.isMetaDown, isShiftPressed = event.isShiftDown, nativeEvent = event,
    )

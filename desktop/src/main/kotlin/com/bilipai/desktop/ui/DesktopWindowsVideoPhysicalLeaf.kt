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
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.core.ui.LocalDetailedCommentTimeEnabled
import com.android.purebilibili.core.store.DesktopOriginalReplySettings
import com.android.purebilibili.core.store.DesktopOriginalVideoControlSettings
import com.android.purebilibili.feature.video.viewmodel.resolvePlaybackCompletionRepeatMode
import com.bilipai.desktop.data.VideoCard
import com.bilipai.desktop.player.PlayerPreferences
import kotlinx.coroutines.CancellationException
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
    val honorLink: (DesktopOriginalVideoOwnerAssembly, DesktopOriginalVideoAcceptedPublication, String) -> Unit,
    val login: () -> Unit, val danmakuSettings: () -> Unit, val toggleDanmaku: () -> Unit,
    val notice: (String) -> Unit,
    val focusChanged: (Boolean) -> Unit,
    val nativeKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean,
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
    val viewportLease = remember(nativeSurface) { Any() }
    DisposableEffect(nativeSurface, route, viewportLease) { onDispose {
        nativeSurface.releaseViewport(route, viewportLease)
    } }
    val currentActive by rememberUpdatedState(active)
    val currentPresentationAlive by rememberUpdatedState(presentationAlive)
    val latestPip by rememberUpdatedState(pipActive)
    fun current(): Boolean = currentActive && shell.slot.currentAssembly() === assembly && assembly.owns()
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
    fun chromeActivity() { if (latestFullscreen && chromeCurrent()) latestChrome.reveal() }
    val chromeWindow = LocalDesktopWindowsPlayerWindow.current
    var chromeWindowFocused by remember(chromeWindow) { mutableStateOf(chromeWindow?.isFocused == true) }
    var barInteraction by remember(chrome) { mutableStateOf(DesktopWindowsFullscreenChromeInteraction()) }
    // These claims come only from this actual native host's AWT input events.
    // Keep them separate: moving over video does not steal keyboard focus from a control.
    var nativePointerOnVideo by remember(assembly, native.surface) { mutableStateOf(false) }
    var nativeKeyboardOnVideo by remember(assembly, native.surface) { mutableStateOf(false) }
    var topFocused by remember(chrome) { mutableStateOf(false) }
    val topInteractions = remember(chrome) { MutableInteractionSource() }
    val topHovered by topInteractions.collectIsHoveredAsState()
    // Only the exact existing Main window; no global input/window observer.
    DisposableEffect(chromeWindow, assembly, native.surface) {
        val listener = object : java.awt.event.WindowFocusListener {
            override fun windowGainedFocus(event: java.awt.event.WindowEvent) {
                chromeWindowFocused = true
                chromeActivity()
            }
            override fun windowLostFocus(event: java.awt.event.WindowEvent) {
                chromeWindowFocused = false
                nativePointerOnVideo = false
                nativeKeyboardOnVideo = false
                chromeActivity()
            }
        }
        chromeWindow?.addWindowFocusListener(listener)
        onDispose { chromeWindow?.removeWindowFocusListener(listener) }
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
                chromeActivity()
                if(current() && !latestPip) latestActions.focusChanged(true)
            }
            override fun focusLost(event: java.awt.event.FocusEvent) {
                nativeKeyboardOnVideo = false
                chromeActivity()
                if(current()) latestActions.focusChanged(false)
            }
        }
        fun observeNativePointer(event: java.awt.event.MouseEvent, activity: Boolean) {
            if (!current() || latestPip || !surface.isShowing) return
            val point = javax.swing.SwingUtilities.convertPoint(event.component, event.point, surface)
            nativePointerOnVideo = surface.contains(point)
            if (activity && nativePointerOnVideo) chromeActivity()
        }
        val mouse = object : java.awt.event.MouseAdapter() {
            // A layout expansion may re-enter the same heavyweight peer without
            // a new user movement. An already owned pointer must not undo hiding.
            override fun mouseEntered(event: java.awt.event.MouseEvent) { observeNativePointer(event, !nativePointerOnVideo) }
            override fun mouseMoved(event: java.awt.event.MouseEvent) { observeNativePointer(event, true) }
            override fun mouseDragged(event: java.awt.event.MouseEvent) { observeNativePointer(event, true) }
            override fun mouseExited(event: java.awt.event.MouseEvent) { observeNativePointer(event, false) }
            override fun mousePressed(event: java.awt.event.MouseEvent) {
                if(current() && !latestPip && surface.isShowing) {
                    observeNativePointer(event, true)
                    viewportFocus.requestFocus()
                    surface.requestFocusInWindow()
                }
            }
        }
        val key = object : java.awt.event.KeyAdapter() {
            override fun keyPressed(event: java.awt.event.KeyEvent) {
                if(!event.isConsumed && current() && !latestPip && surface.isFocusOwner && surface.isShowing) {
                    chromeActivity()
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
    val playback by shell.playback.state.collectAsState()
    val manualSponsorSegment by assembly.playback.currentSponsorSegment.collectAsState()
    val original by assembly.playback.uiState.collectAsState()
    val subject by assembly.playback.subjectSnapshot.collectAsState()
    val favoriteEvent by assembly.playback.favoriteFolderSaveEvent.collectAsState()
    val success = original as? VideoPlaybackUiState.Success
    val playlistItems by assembly.environment.playlist.playlist.collectAsState()
    val collectionQueueSource = assembly.native.current()?.takeIf { accepted ->
        current() && success?.info?.let { it.bvid == accepted.request.bvid && it.cid == accepted.request.cid } == true
    }
    var showCollection by remember(assembly, collectionQueueSource) { mutableStateOf(false) }
    var showPlaybackQueue by remember(assembly, collectionQueueSource) { mutableStateOf(false) }
    var audioLanguageMenu by remember(assembly, collectionQueueSource) { mutableStateOf<DesktopWindowsVideoAudioSelection?>(null) }
    var audioTrackMenu by remember(assembly, collectionQueueSource) { mutableStateOf<DesktopWindowsVideoAudioSelection?>(null) }
    var interactionMode by remember(assembly, collectionQueueSource) { mutableStateOf<DesktopWindowsVideoInteraction?>(null) }
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
    val completion by remember(platforms.holder.settingsContext) {
        DesktopOriginalVideoControlSettings.getPlaybackCompletionBehavior(platforms.holder.settingsContext)
    }.collectAsState(DesktopOriginalVideoControlSettings.getPlaybackCompletionBehaviorSync(platforms.holder.settingsContext))
    LaunchedEffect(assembly, completion, state.sourceTitle, active) {
        if (current() && assembly.section.isOwned()) assembly.section.repeatMode = resolvePlaybackCompletionRepeatMode(completion)
    }
    var subtitleOverride by remember(assembly) { mutableStateOf<SubtitleDisplayMode?>(null) }
    val subtitleMode = subtitleOverride ?: resolveSubtitleDisplayModeByAutoPreference(preferences.subtitleAutoPreference,
        success?.subtitlePrimaryCues?.isNotEmpty() == true, success?.subtitleSecondaryCues?.isNotEmpty() == true,
        success?.subtitlePrimaryLikelyAi == true, success?.subtitleSecondaryLikelyAi == true, state.muted)
    val latestSubtitleMode by rememberUpdatedState(subtitleMode)
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
    LaunchedEffect(assembly, route.openId, route.bvid, route.cid, route.resumePositionMs, active) {
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
            val retained = shell.playback.openVideoDetail(VideoCard(route.bvid, "", route.coverUrl, "", 0, 0,
                preferredCid = route.cid), route.resumePositionMs, keepMatchingSource = true)
            if (samePhysicalEntry()) {
                nativeSurface.recordBootstrap(route, assembly, prior, retained)
                nativeSurface.recordPresentedSource(route, assembly, ::samePhysicalEntry)
                presentationVerified = true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (current()) bootstrapError = failure.message ?: "播放器初始化失败" }
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
        showCollection || showPlaybackQueue || audioLanguageMenu != null || audioTrackMenu != null || interactionMode != null
    val chromeCanAutoHide = desktopWindowsFullscreenChromeCanAutoHide(fullscreen, active && !pipActive,
        chromeWindowFocused, chromeHeld, state, bootstrapError != null || playback.error != null || playback.recovering)
    val latestChromeCanAutoHide by rememberUpdatedState(chromeCanAutoHide)
    // Gate changes reset the idle period; a new accepted source gets new UI state.
    LaunchedEffect(chrome, fullscreen, active, pipActive, chromeCanAutoHide) { chrome.reveal() }
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
    BoxWithConstraints(Modifier.fillMaxSize().padding(if (fullscreen) 0.dp else 8.dp)) {
        // Keep one Row and one native slot through every width/fullscreen/panel change.
        // A narrow window still reserves real sibling space: a heavyweight Canvas cannot be covered by a Compose sheet.
        val detailsWidth = minOf(360.dp, maxWidth * .43f)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(if (chromeVisible) 6.dp else 0.dp)) {
                if (chromeVisible) DesktopWindowsPlayerSurface(Modifier.fillMaxWidth()
                    .desktopWindowsChromePointerInput { nativePointerOnVideo = false }
                    .onFocusChanged { topFocused = it.hasFocus }.focusGroup().hoverable(topInteractions)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (current()) actions.back() }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                        Text(success?.info?.title ?: "视频", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // Fill all remaining height; there is no phone-derived fraction or maximum 380dp video height.
                var viewportSize by remember(native) { mutableStateOf(IntSize.Zero) }
                Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black).onSizeChanged { viewportSize = it }
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
                    .focusRequester(viewportFocus).onFocusChanged { if(current()) actions.focusChanged(it.hasFocus) }.focusable()) {
                    if (active && !pipActive) {
                        DesktopVideoCommandPopup(viewportSize, {
                            Box(Modifier.fillMaxSize()) {
                                actions.overlay()
                                val positionMs = (state.positionSeconds * 1000.0).toLong()
                                val primary = if (subtitleMode == SubtitleDisplayMode.PRIMARY_ONLY || subtitleMode == SubtitleDisplayMode.BILINGUAL)
                                    success?.subtitlePrimaryCues?.let { resolveSubtitleTextAt(it, positionMs) } else null
                                val secondary = if (subtitleMode == SubtitleDisplayMode.SECONDARY_ONLY || subtitleMode == SubtitleDisplayMode.BILINGUAL)
                                    success?.subtitleSecondaryCues?.let { resolveSubtitleTextAt(it, positionMs) } else null
                                if (current() && success?.subtitleOwnerBvid == success?.info?.bvid &&
                                    success?.subtitleOwnerCid == success?.info?.cid && (primary != null || secondary != null))
                                    Column(Modifier.align(Alignment.BottomCenter).padding(14.dp).background(Color.Black.copy(alpha=.65f)).padding(6.dp)) {
                                        primary?.let { Text(it, color=Color.White, fontSize=20.sp) }
                                        secondary?.let { Text(it, color=Color.White, fontSize=16.sp) }
                                    }
                            }
                        }, native.surface)
                    } else Text("正在浮窗播放", color=Color.White, modifier=Modifier.align(Alignment.Center))
                    if (presentationAlive && !pipActive) engagementBinding?.let { binding ->
                        DesktopWindowsConfirmedVideoFeedback(binding, viewportSize, native.surface, feedbackBounds)
                    }
                }
                if (playback.recovering && playback.recoveryMessage != null)
                    Text(playback.recoveryMessage.orEmpty(), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                if (bootstrapError != null || playback.error != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(bootstrapError ?: playback.error.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { if (current()) shell.playback.retry() }) { Text("重试") }
                }
                if (chromeVisible) DesktopWindowsVideoControlBar(
                    state = state, sourceVersion = native.currentSourceSnapshot()?.sourceVersion ?: 0L,
                    enabled = current() && success != null, fullscreen = fullscreen, detailsOpen = detailsOpen,
                    onInteractionHoldChanged = { interaction ->
                        if (barInteraction != interaction) {
                            val previouslyHeld = barInteraction.held(nativePointerOnVideo, nativeKeyboardOnVideo)
                            barInteraction = interaction
                            if (previouslyHeld != interaction.held(nativePointerOnVideo, nativeKeyboardOnVideo)) chromeActivity()
                        }
                    },
                    onChromePointerInput = { nativePointerOnVideo = false },
                    hasPrevious = shell.playback.hasPrevious, hasNext = shell.playback.hasNext,
                    canPictureInPicture = !pipActive && success != null && state.videoCodec != null && !state.audioOnly,
                    qualities = success?.let { value -> value.qualityIds.mapIndexed { index, id -> id to (value.qualityLabels.getOrNull(index) ?: id.toString()) } }.orEmpty(),
                    selectedQuality = success?.currentQuality,
                    canOpenCollection = canOpenCollection, canOpenPlaybackQueue = canOpenPlaybackQueue,
                    onOpenCollection = { if (collectionQueueCurrent()) { showPlaybackQueue = false; showCollection = true } },
                    onOpenPlaybackQueue = { if (collectionQueueCurrent()) { showCollection = false; showPlaybackQueue = true } },
                    canOpenInteraction = interactionCurrent(),
                    onSendDanmaku = { openInteraction(DesktopWindowsVideoInteraction.DANMAKU) },
                    onShareVideo = { openInteraction(DesktopWindowsVideoInteraction.SHARE) },
                    chapters = chapters, chaptersSource = chaptersSource, onChapterSeek = ::seekChapter,
                    onPlayPause = { command { native.togglePause() } },
                    onPrevious = { if (current()) shell.playback.previous() }, onNext = { if (current()) shell.playback.next() },
                    onMute = { if(command { native.setMuted(!state.muted) }) preferencesChanged(preferences.copy(muted=!state.muted)) },
                    onVolume = { value -> if(command { native.setVolume(value) }) preferencesChanged(preferences.copy(volume=value)) },
                    onSpeed = ::setSpeed,
                    onQuality = { quality -> if (current()) shell.playback.switchQuality(quality) },
                    onSeek = { seconds -> if (current()) shell.playback.seekTo(seconds) },
                    onPictureInPicture = { if (current()) actions.pictureInPicture() },
                    onFullscreen = { if (current()) actions.fullscreen() },
                    onDetails = { if (current()) detailsOpen = !detailsOpen },
                    onOpenIntroduction = { if (current()) {
                        detailsTab = DesktopWindowsVideoDetailsTab.INTRODUCTION
                        detailsOpen = true
                    } },
                    sponsorSkip = {
                        DesktopWindowsSponsorSkipSection(shell.playback, assembly, collectionQueueSource,
                            playback.manualSkip, manualSponsorSegment, ::interactionCurrent)
                    },
                    enhancement = actions.enhancement,
                )
            }
            if (detailsOpen) DesktopWindowsVideoDetailsPanel(
                modifier = Modifier.width(detailsWidth).fillMaxHeight(), selectedTab = detailsTab,
                onTabChange = { detailsTab = it }, onClose = { if (current()) detailsOpen = false },
                current = ::current, related = playback.related, onVideo = actions.video,
                introduction = {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (success != null) {
                            Text(success.info.title, style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { if (current()) actions.user(success.info.owner.mid) }) { Text(success.info.owner.name) }
                            collectionQueueSource?.let { source ->
                                DesktopWindowsVideoMetadataSection(assembly, success.info, source,
                                    platforms.holder.settingsContext, platforms.portrait.creatorTeam,
                                    ::current, actions.user) { url -> latestActions.honorLink(assembly, source, url) }
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { engagementBinding?.like() }, enabled = engagementBinding?.isOwned() == true,
                                    modifier = Modifier.desktopWindowsFeedbackLikeAnchor(feedbackBounds)) { Text(if(engagement.isLiked) "已点赞" else "点赞") }
                                actions.favorite(assembly, success, ::current)
                                TextButton(onClick = { engagementBinding?.toggleFollow() }, enabled = engagementBinding?.isOwned() == true) { Text(if(engagement.isFollowing) "已关注" else "关注") }
                                TextButton(onClick = { engagementBinding?.triple() }, enabled = engagementBinding?.isOwned() == true) { Text("三连") }
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { if(current()) assembly.domains.engagement.toggleWatchLater() }) { Text("稍后再看") }
                                TextButton(onClick = { if(current()) assembly.domains.engagement.openCoinDialog() }) { Text("投币") }
                                TextButton(onClick = { if (current()) actions.download(assembly, success) }) { Text("下载当前画质") }
                                TextButton(onClick = { openInteraction(DesktopWindowsVideoInteraction.SHARE) }, enabled = interactionCurrent()) { Text("分享视频") }
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            Text(success.info.desc, style = MaterialTheme.typography.bodyMedium)
                            if (success.info.pages.size > 1) {
                                Text("分P", style = MaterialTheme.typography.titleSmall)
                                success.info.pages.forEachIndexed { index, part ->
                                    TextButton(onClick = { if (current()) shell.playback.playPart(index) }, modifier = Modifier.fillMaxWidth()) {
                                        Text("P${index + 1} · ${part.part}")
                                    }
                                }
                            }
                        } else Text("正在读取视频信息", style = MaterialTheme.typography.bodyMedium)
                        HorizontalDivider()
                        Text("播放设置", style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(state.audioOnly, onClick = { if(command { native.setAudioOnly(!state.audioOnly) }) preferencesChanged(preferences.copy(audioOnly=!state.audioOnly)) }, label = { Text("仅音频") })
                            FilterChip(preferences.danmaku.enabled, onClick = { if (current()) actions.toggleDanmaku() }, label = { Text("弹幕") })
                            TextButton(onClick = { if (current()) actions.danmakuSettings() }) { Text("弹幕设置") }
                        }
                        Text("视频编码", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("hev1" to "HEVC", "av01" to "AV1", "avc1" to "H.264").forEach { (codec, label) ->
                                FilterChip(preferences.videoCodecPreference == codec,
                                    onClick = { if(current()) preferencesChanged(preferences.copy(videoCodecPreference=codec)) }, label = { Text(label) })
                            }
                        }
                        Text("字幕显示", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SubtitleDisplayMode.entries.forEach { mode -> FilterChip(subtitleMode==mode,
                                onClick={if(current()) subtitleOverride=mode}, label={Text(when(mode) {SubtitleDisplayMode.OFF->"字幕关闭";SubtitleDisplayMode.PRIMARY_ONLY->"主字幕";SubtitleDisplayMode.SECONDARY_ONLY->"副字幕";SubtitleDisplayMode.BILINGUAL->"双语字幕"})}) }
                        }
                        success?.let { value ->
                            val languages = desktopWindowsAudioLanguageOptions(value)
                            val audioTracks = desktopWindowsNativeAudioTracks(state)
                            if (languages.isNotEmpty() || audioTracks.size > 1) {
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (languages.isNotEmpty()) TextButton(onClick = {
                                        collectionQueueSource?.let { accepted ->
                                            audioLanguageMenu = shell.playback.captureAudioSelection(assembly, accepted, ::collectionQueueCurrent)
                                        }
                                    }, enabled = collectionQueueCurrent() && !value.isQualitySwitching) {
                                        val language = desktopWindowsCurrentAudioLanguage(value)
                                        Text("音频语言：${languages.firstOrNull { it.language == language }?.label ?: language ?: "原声"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (audioTracks.size > 1) TextButton(onClick = {
                                        collectionQueueSource?.let { accepted ->
                                            audioTrackMenu = shell.playback.captureAudioSelection(assembly, accepted, ::collectionQueueCurrent)
                                        }
                                    }, enabled = collectionQueueCurrent() && !value.isQualitySwitching && state.ready && !state.loading) {
                                        Text("音轨：${audioTracks.firstOrNull { it.selected }?.let(::desktopWindowsNativeAudioTrackLabel) ?: "未选择"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (value.availableAudioQualities.isNotEmpty()) {
                                Text("音质", style = MaterialTheme.typography.labelLarge)
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            )
        }
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
    audioTrackMenu?.takeIf { shell.playback.isAudioSelectionCurrent(it) }?.let { selection ->
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
    if(engagement.coinDialogVisible) {
        DesktopWindowsPlayerDialog(
            title = "投币",
            onDismissRequest = { if(current()) assembly.domains.engagement.setCoinDialogVisible(false) },
            preferredHeightDp = 280,
        ) {
            AlertDialog(
            onDismissRequest={if(current()) assembly.domains.engagement.setCoinDialogVisible(false)},
            title={Text("投币")}, text={Text("选择投币数量")},
            confirmButton={Row {listOf(1,2).forEach {count-> TextButton(onClick={if(current()) {
                if (engagementBinding?.coin(count, false) == true) assembly.domains.engagement.setCoinDialogVisible(false)
            }}) {Text("${count}枚")} }}},
            dismissButton={TextButton(onClick={if(current()) assembly.domains.engagement.setCoinDialogVisible(false)}) {Text("取消")}})
        }
    }
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

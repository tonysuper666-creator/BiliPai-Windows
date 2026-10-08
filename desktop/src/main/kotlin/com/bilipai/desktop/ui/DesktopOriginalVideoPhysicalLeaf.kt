package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.android.purebilibili.feature.video.screen.AudioModeScreen
import com.android.purebilibili.navigation3.BiliPaiNavKey
import kotlinx.coroutines.CancellationException

/** Physical typed leaves of the SAME original retained owner. Neither switching
 * to BV audio nor covering the video creates a ListenAudioSession/second player.
 * Root keeps this owner and every original domain when another leaf is on top.
 */
@Composable internal fun DesktopOriginalVideoPhysicalLeaf(
    key: BiliPaiNavKey,
    shell: DesktopOriginalVideoShellOwner,
    commands: DesktopOriginalRootRouteCommands,
    active: Boolean,
    windowBack: () -> Unit,
    openBilibiliLink: (String) -> Unit,
    immersivePlaybackChanged: (Boolean) -> Unit,
    pendingOwner: @Composable () -> Unit,
) {
    require(key is BiliPaiNavKey.VideoDetail || key is BiliPaiNavKey.AudioMode || key is BiliPaiNavKey.NativeMusic)
    val factoryReady by shell.slot.factoryReady.collectAsState()
    LaunchedEffect(shell, factoryReady, key) {
        if (factoryReady) shell.slot.requireAssembly()
    }
    val owner by shell.slot.assemblies.collectAsState()
    val platforms = LocalDesktopOriginalVideoRootPlatforms.current
    val current = owner?.takeIf { it.owns() }
    if (current == null || platforms == null) {
        // Genuine factory/owner installation in progress; no playback Success or
        // fallback controller is manufactured while Root drains the predecessor.
        pendingOwner()
        return
    }
    CompositionLocalProvider(LocalDesktopOriginalVideoNativeCarrierActive provides active) {
        DesktopOriginalVideoPhysicalLeafContent(key, current, platforms, shell, commands, active,
            windowBack, openBilibiliLink, immersivePlaybackChanged)
    }
}

@Composable private fun DesktopOriginalVideoPhysicalLeafContent(
    key: BiliPaiNavKey,
    current: DesktopOriginalVideoOwnerAssembly,
    platforms: DesktopOriginalVideoRootWindowPlatforms,
    shell: DesktopOriginalVideoShellOwner,
    commands: DesktopOriginalRootRouteCommands,
    active: Boolean,
    windowBack: () -> Unit,
    openBilibiliLink: (String) -> Unit,
    immersivePlaybackChanged: (Boolean) -> Unit,
) {
    if (key is BiliPaiNavKey.VideoDetail) {
        val routeState = LocalDesktopOriginalRootVideoRouteState.current
        SideEffect { shell.publishVideoRouteState(current, routeState) }
        DesktopOriginalVideoHolderRoute(key, current, platforms,
            routeState,
            desktopOriginalRootVideoActions(key, commands, windowBack,
                openBilibiliLink, immersivePlaybackChanged))
        return
    }
    val audioBvid = when (key) {
        is BiliPaiNavKey.AudioMode -> key.sourceBvid
        is BiliPaiNavKey.NativeMusic -> key.bvid
        else -> error("Expected an original audio route")
    }
    val audioCid = when (key) {
        is BiliPaiNavKey.AudioMode -> key.sourceCid
        is BiliPaiNavKey.NativeMusic -> key.cid
        else -> error("Expected an original audio route")
    }
    val audioResume = (key as? BiliPaiNavKey.AudioMode)?.sourceResumePositionMs ?: 0L
    val musicTitle = (key as? BiliPaiNavKey.NativeMusic)?.title?.ifEmpty { "背景音乐" }
    val windowEnvironment = LocalDesktopOriginalVideoRootWindowEnvironment.current
    val latestActive by rememberUpdatedState(active)
    var audioSubjectRequested by remember(current, key) { mutableStateOf(false) }
    var initialized by remember(current, platforms) { mutableStateOf(false) }
    LaunchedEffect(current, platforms) {
        platforms.awaitNativeInitialization()
        if (!current.owns()) throw CancellationException("Original BV audio entry retired")
        initialized = true
    }
    if (!initialized) {
        platforms.InitialNativeSurface(Modifier.fillMaxSize())
        return
    }
    // Android leaves have a route-scoped VM. Windows retains the SAME original
    // VM. Seed explicit NativeMusic and different AudioMode subjects; matching
    // AudioMode keeps the original paused/resume/reuse semantics unchanged.
    LaunchedEffect(current, platforms, key, initialized, active) {
        if (!initialized || !active || audioSubjectRequested) return@LaunchedEffect
        if (!latestActive || !windowEnvironment.owns() || windowEnvironment.currentKey() != key || !current.owns())
            throw CancellationException("Original audio route retired")
        // Both raw read ports already belong to this same sole full original VM.
        // beginLoadRequest keeps the prior accepted currentCid, so Loading must
        // compare its currentRequest; a Ready page switch instead uses currentCid.
        val loading = current.playback.captureDesktopPlaybackState() is
            com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState.Loading
        val session = current.playback.captureDesktopLoadState()
        val targetBvid = if (loading) session.currentRequest?.bvid else session.currentBvid
        val targetCid = if (loading) session.currentRequest?.cid else session.currentCid
        val sameSubject = targetBvid == audioBvid && (audioCid <= 0L || targetCid == audioCid)
        val seed = key is BiliPaiNavKey.NativeMusic ||
            (key is BiliPaiNavKey.AudioMode && audioBvid.isNotBlank() && !sameSubject)
        if (seed) {
            current.playback.attachPlayer(current.section)
            current.playback.loadVideo(bvid = audioBvid, cid = audioCid, autoPlay = true,
                fallbackResumePositionMs = audioResume,
                force = loading)
        }
        audioSubjectRequested = true
    }
    if ((key is BiliPaiNavKey.NativeMusic || key is BiliPaiNavKey.AudioMode) && !audioSubjectRequested) {
        platforms.InitialNativeSurface(Modifier.fillMaxSize())
        return
    }
    CompositionLocalProvider(
        LocalDesktopOriginalAudioModePlatform provides platforms.audio,
        LocalDesktopOriginalMusicUiPlatform provides platforms.music,
        LocalDesktopOriginalPlayerSettingsContext provides platforms.holder.settingsContext,
        LocalDesktopOriginalVideoContentBindings provides platforms.content,
        LocalDesktopCommentBindings provides platforms.holder.commentsPlatform,
        LocalDesktopOriginalSubtitleModeBinding provides platforms.subtitleMode,
    ) {
        AudioModeScreen(viewModel = remember(current, key, windowEnvironment) {
            DesktopOriginalVideoConsumedViews(current,
                manualAudioCurrent = { latestActive && windowEnvironment.owns() &&
                    windowEnvironment.currentKey() === key && current.owns() },
                manualAudioRetained = { windowEnvironment.owns() &&
                    windowEnvironment.commands.containsEntry(key) && current.owns() })
        },
            engagementViewModel = current.domains.engagement,
            composerViewModel = platforms.audio.composer,
            supplementViewModel = current.domains.supplement,
            onBack = windowBack,
            onVideoModeClick = { bvid, cid ->
                // Immutable original fields and the actual readback. This is a
                // typed route switch; no ordinary open/load precedes the Holder.
                val accepted = current.native.current()
                var position: Long? = null
                if (active && accepted != null && accepted.request.bvid == bvid && accepted.request.cid == cid &&
                    current.native.admitPlaybackDispatch(accepted) {
                        position = current.section.currentPosition.coerceAtLeast(0L)
                    }) {
                    val destination = BiliPaiNavKey.VideoDetail(bvid, cid,
                        resumePositionMs = checkNotNull(position), sourceRoute = "audio_mode")
                    if (key is BiliPaiNavKey.NativeMusic) {
                        if (latestActive && windowEnvironment.owns() && windowEnvironment.currentKey() == key &&
                            current.owns() && commands.back()) commands.video(destination)
                    } else commands.video(destination)
                }
            },
            isInPipMode = LocalDesktopOriginalVideoRootWindowEnvironment.current.let {
                // This is the same physical Root's PiP state, projected through
                // its actual navigation/window bindings below.
                it.inPictureInPicture()
            },
            initialBvid = audioBvid, initialCid = audioCid,
            initialResumePositionMs = audioResume, titleOverride = musicTitle)
    }
}

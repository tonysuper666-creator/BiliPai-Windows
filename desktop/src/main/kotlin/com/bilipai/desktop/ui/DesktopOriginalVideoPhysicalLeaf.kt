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
    require(key is BiliPaiNavKey.VideoDetail || key is BiliPaiNavKey.AudioMode)
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
    val audio = key as BiliPaiNavKey.AudioMode
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
    CompositionLocalProvider(
        LocalDesktopOriginalAudioModePlatform provides platforms.audio,
        LocalDesktopOriginalMusicUiPlatform provides platforms.music,
        LocalDesktopOriginalPlayerSettingsContext provides platforms.holder.settingsContext,
        LocalDesktopOriginalVideoContentBindings provides platforms.content,
        LocalDesktopCommentBindings provides platforms.holder.commentsPlatform,
        LocalDesktopOriginalSubtitleModeBinding provides platforms.subtitleMode,
    ) {
        AudioModeScreen(viewModel = remember(current) { DesktopOriginalVideoConsumedViews(current) },
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
                    commands.video(BiliPaiNavKey.VideoDetail(bvid, cid,
                        resumePositionMs = checkNotNull(position), sourceRoute = "audio_mode"))
                }
            },
            isInPipMode = LocalDesktopOriginalVideoRootWindowEnvironment.current.let {
                // This is the same physical Root's PiP state, projected through
                // its actual navigation/window bindings below.
                it.inPictureInPicture()
            },
            initialBvid = audio.sourceBvid, initialCid = audio.sourceCid,
            initialResumePositionMs = audio.sourceResumePositionMs)
    }
}

package com.android.purebilibili.feature.video.screen

import com.bilipai.desktop.ui.DesktopOriginalVideoHolderWindowPort as Window
import com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform
import androidx.compose.ui.unit.IntRect as Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player
import com.bilipai.desktop.ui.DesktopOriginalMpvSectionControl
import com.android.purebilibili.feature.video.ui.section.shouldKeepVideoPlaybackAwake

@Composable
internal fun VideoDetailPipParamsEffect(
    window: Window,
    playerBounds: Rect?,
    pipModeEnabled: Boolean,
    player: DesktopOriginalMpvSectionControl?,
) {
    val holderPlatform = com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform.current
    var lastPipBounds by remember { mutableStateOf<Rect?>(null) }
    var lastPipModeEnabled by remember { mutableStateOf<Boolean?>(null) }
    var lastPipUpdateElapsedMs by remember { mutableLongStateOf(0L) }
    val latestPlayer by rememberUpdatedState(player)
    val platform = LocalDesktopOriginalVideoHolderPlatform.current

    LaunchedEffect(window, playerBounds, pipModeEnabled) {
        if (!window.supportsPictureInPicture) return@LaunchedEffect
        val modeChanged = lastPipModeEnabled == null || lastPipModeEnabled != pipModeEnabled
        val boundsChanged = hasMeaningfulVideoPlayerBoundsChange(lastPipBounds, playerBounds)
        val now = platform.elapsedRealtimeMillis()
        if (!shouldApplyPipParamsUpdate(
                pipModeEnabled = pipModeEnabled,
                modeChanged = modeChanged,
                boundsChanged = boundsChanged,
                elapsedSinceLastUpdateMs = now - lastPipUpdateElapsedMs,
            )
        ) return@LaunchedEffect

        lastPipBounds = playerBounds
        lastPipModeEnabled = pipModeEnabled
        lastPipUpdateElapsedMs = now
        val videoWidth = latestPlayer?.videoSize?.width ?: 0
        val videoHeight = latestPlayer?.videoSize?.height ?: 0
        window.updatePictureInPicture(
            player = latestPlayer, sourceBounds = playerBounds,
            autoEnterEnabled = pipModeEnabled, seamlessResizeEnabled = pipModeEnabled,
        )
    }
}

@Composable
internal fun VideoDetailKeepScreenOnEffect(
    window: Window?,
    player: Player,
) {
    val holderPlatform = com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform.current
    val shouldKeepAwake by produceState(
        initialValue = shouldKeepVideoPlaybackAwake(
            playWhenReady = player.playWhenReady,
            isPlaying = player.isPlaying,
            playbackState = player.playbackState,
        ),
        key1 = player,
    ) {
        fun update() {
            value = shouldKeepVideoPlaybackAwake(
                playWhenReady = player.playWhenReady,
                isPlaying = player.isPlaying,
                playbackState = player.playbackState,
            )
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = update()
            override fun onPlaybackStateChanged(playbackState: Int) = update()
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = update()
        }
        player.addListener(listener)
        awaitDispose { player.removeListener(listener) }
    }
    DisposableEffect(window, shouldKeepAwake) {
        val lease = window?.acquireKeepAwake(shouldKeepAwake)
        onDispose { lease?.close() }
    }
}

@Composable
internal fun VideoDetailSystemBarsEffect(
    window: Window?,
    isScreenActive: Boolean,
    spec: VideoDetailSystemBarsApplySpec,
    reapplyGeneration: Int = 0,
) {
    val holderPlatform = com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform.current
    // The generation re-commits the same policy after a cancelled return or a retained route
    // becomes topmost again.
    val effectiveSpec = remember(spec, reapplyGeneration) { spec }
    SideEffect {
        if (!isScreenActive || window == null) return@SideEffect
        window.applySystemBars(effectiveSpec)
    }
}

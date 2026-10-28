package com.android.purebilibili.feature.video.ui.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Keeps the polling snapshot unread until a progress consumer needs it. */
@Composable
internal fun rememberVideoPlayerOverlayProgress(
    player: Player,
    bvid: String,
    cid: Long,
    videoDuration: Long,
    controlsVisible: Boolean,
    hostLifecycleStarted: Boolean,
    hasPendingSeekResume: Boolean,
    highFrequencyProgressActive: Boolean,
    onPlayingChanged: (Boolean) -> Unit,
): State<PlayerProgress> {
    val currentOnPlayingChanged = rememberUpdatedState(onPlayingChanged)
    return produceState(
        initialValue = PlayerProgress(),
        player,
        bvid,
        cid,
        videoDuration,
        controlsVisible,
        hostLifecycleStarted,
        hasPendingSeekResume,
        highFrequencyProgressActive,
    ) {
        // Take one snapshot while stopped, and keep updating paused playback while started.
        // This also preserves progress immediately after rotating or seeking.
        do {
            value = PlayerProgress(
                current = player.currentPosition,
                duration = resolveSeekableDurationMs(
                    playbackDurationMs = player.duration,
                    fallbackDurationMs = videoDuration,
                ),
                buffered = player.bufferedPosition,
            )
            currentOnPlayingChanged.value(
                resolveOverlayPlaybackButtonPlayingState(
                    isPlaying = player.isPlaying,
                    playWhenReady = player.playWhenReady,
                    playbackState = player.playbackState,
                    hasPendingSeekResume = hasPendingSeekResume,
                )
            )
            if (!shouldPollInlineVideoOverlayProgress(
                    playerExists = true,
                    hostLifecycleStarted = hostLifecycleStarted,
                )
            ) {
                return@produceState
            }
            delay(
                resolveInlineVideoOverlayProgressPollingIntervalMs(
                    controlsVisible = controlsVisible,
                    isPlaying = player.isPlaying,
                    highFrequencyProgressActive = highFrequencyProgressActive,
                )
            )
        } while (isActive)
    }
}

package com.android.purebilibili.feature.video.usecase
import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player
import com.android.purebilibili.core.util.Logger
import com.android.purebilibili.feature.video.playback.session.PlaybackUserActionTracker
import com.android.purebilibili.feature.video.ui.overlay.PlaybackUserActionType
internal fun shouldPreparePlayerBeforeExplicitPlay(
    playbackState: Int,
    hasMediaItems: Boolean
): Boolean {
    return hasMediaItems && playbackState == Player.STATE_IDLE
}

internal fun playPlayerFromUserAction(player: Player) {
    playPlayerForUserIntent(player, trackUserAction = true)
}

internal fun pausePlayerFromUserAction(player: Player) {
    PlaybackUserActionTracker.recordAction(
        player = player,
        type = PlaybackUserActionType.PAUSE
    )
    player.pause()
}

internal fun applyPlaybackButtonUserAction(
    player: Player,
    isShowingPauseIcon: Boolean
) {
    if (isShowingPauseIcon && player.playbackState != Player.STATE_ENDED) {
        pausePlayerFromUserAction(player)
    } else {
        playPlayerFromUserAction(player)
    }
}

private fun playPlayerForUserIntent(
    player: Player,
    trackUserAction: Boolean,
    ensurePlayWhenReady: Boolean = true
) {
    if (trackUserAction) {
        PlaybackUserActionTracker.recordAction(
            player = player,
            type = PlaybackUserActionType.PLAY
        )
    }
    Logger.d(
        "VideoPlaybackUseCase",
        "USER_DBG playPlayerFromUserAction before: " +
            "state=${player.playbackState}, isPlaying=${player.isPlaying}, " +
            "playWhenReady=${player.playWhenReady}, mediaItemCount=${player.mediaItemCount}, pos=${player.currentPosition}"
    )
    val hasMediaItems = player.mediaItemCount > 0
    if (shouldPreparePlayerBeforeExplicitPlay(player.playbackState, hasMediaItems)) {
        player.prepare()
    }
    if (ensurePlayWhenReady && !player.playWhenReady) {
        player.playWhenReady = true
    }
    player.play()
    Logger.d(
        "VideoPlaybackUseCase",
        "USER_DBG playPlayerFromUserAction after: " +
            "state=${player.playbackState}, isPlaying=${player.isPlaying}, " +
            "playWhenReady=${player.playWhenReady}, pos=${player.currentPosition}"
    )
}

internal fun shouldResumePlaybackAfterUserSeek(
    playWhenReadyBeforeSeek: Boolean,
    playbackStateBeforeSeek: Int
): Boolean {
    return playWhenReadyBeforeSeek || playbackStateBeforeSeek == Player.STATE_ENDED
}

internal fun seekPlayerFromUserAction(
    player: Player,
    positionMs: Long,
    shouldResumePlaybackOverride: Boolean? = null
) {
    val shouldResume = shouldResumePlaybackOverride ?: shouldResumePlaybackAfterUserSeek(
        playWhenReadyBeforeSeek = player.playWhenReady,
        playbackStateBeforeSeek = player.playbackState
    )
    Logger.d(
        "VideoPlaybackUseCase",
        "USER_DBG seekPlayerFromUserAction: target=$positionMs, shouldResume=$shouldResume, " +
            "beforeState=${player.playbackState}, beforePlaying=${player.isPlaying}, beforePwr=${player.playWhenReady}"
    )
    player.logSeek(
        targetPositionMs = positionMs,
        currentPositionMs = player.currentPosition,
        bufferedPositionMs = player.bufferedPosition,
        durationMs = player.duration.coerceAtLeast(0L)
    )
    if (shouldResume) {
        player.playWhenReady = true
    }
    player.seekTo(positionMs)
    if (shouldResume) {
        playPlayerForUserIntent(
            player = player,
            trackUserAction = false,
            ensurePlayWhenReady = false
        )
    }
}

internal fun togglePlayerPlaybackFromUserAction(player: Player) {
    Logger.d(
        "VideoPlaybackUseCase",
        "USER_DBG togglePlayerPlaybackFromUserAction before: " +
            "state=${player.playbackState}, isPlaying=${player.isPlaying}, playWhenReady=${player.playWhenReady}, pos=${player.currentPosition}"
    )
    if (player.playbackState == Player.STATE_ENDED) {
        Logger.d(
            "VideoPlaybackUseCase",
            "USER_DBG togglePlayerPlaybackFromUserAction restart from beginning"
        )
        player.seekTo(0L)
        playPlayerFromUserAction(player)
        return
    }
    if (
        shouldPauseForPlaybackToggle(
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady,
            playbackState = player.playbackState
        )
    ) {
        pausePlayerFromUserAction(player)
        Logger.d(
            "VideoPlaybackUseCase",
            "USER_DBG togglePlayerPlaybackFromUserAction paused: " +
                "state=${player.playbackState}, isPlaying=${player.isPlaying}, playWhenReady=${player.playWhenReady}, pos=${player.currentPosition}"
        )
        return
    }
    playPlayerFromUserAction(player)
}

internal fun shouldPauseForPlaybackToggle(
    isPlaying: Boolean,
    playWhenReady: Boolean,
    playbackState: Int
): Boolean {
    return playWhenReady && (isPlaying || playbackState == Player.STATE_BUFFERING)
}

package com.android.purebilibili.feature.download
import com.bilipai.desktop.ui.DesktopOfflinePlaybackState

internal fun resolveOfflineVideoStartFullscreen(
    isAudioOnly: Boolean,
    isVerticalVideo: Boolean
): Boolean = !isAudioOnly && !isVerticalVideo

internal fun shouldResumePlaybackAfterOfflineSeek(
    playbackState: Int,
    wasPlayingBeforeSeek: Boolean,
    targetPositionMs: Long,
    durationMs: Long
): Boolean {
    if (targetPositionMs < 0L) return false
    val cappedDurationMs = durationMs.coerceAtLeast(0L)
    return wasPlayingBeforeSeek || (
        playbackState == DesktopOfflinePlaybackState.ENDED &&
            (cappedDurationMs <= 0L || targetPositionMs < cappedDurationMs)
        )
}

internal fun shouldShowOfflineDanmakuControl(
    localSegmentCount: Int,
    isAudioOnly: Boolean
): Boolean = localSegmentCount > 0 && !isAudioOnly

internal fun shouldShowOfflineDanmakuLayer(
    localSegmentCount: Int,
    isAudioOnly: Boolean,
    danmakuEnabled: Boolean
): Boolean {
    return danmakuEnabled && shouldShowOfflineDanmakuControl(
        localSegmentCount = localSegmentCount,
        isAudioOnly = isAudioOnly
    )
}

internal fun resolveOfflineSeekProgressFromTouch(
    touchX: Float,
    containerWidthPx: Float
): Float {
    if (!touchX.isFinite() || !containerWidthPx.isFinite() || containerWidthPx <= 0f) {
        return 0f
    }
    return (touchX / containerWidthPx).coerceIn(0f, 1f)
}

internal fun resolveOfflineSeekPositionFromTouch(
    touchX: Float,
    containerWidthPx: Float,
    durationMs: Long
): Long {
    val safeDuration = durationMs.coerceAtLeast(0L)
    return (resolveOfflineSeekProgressFromTouch(touchX, containerWidthPx) * safeDuration).toLong()
}

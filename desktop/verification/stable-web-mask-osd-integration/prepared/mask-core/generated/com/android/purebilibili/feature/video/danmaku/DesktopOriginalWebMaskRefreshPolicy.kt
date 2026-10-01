package com.android.purebilibili.feature.video.danmaku
private const val MIN_ENGINE_PLAYBACK_SPEED = 0.1f
private const val MAX_ENGINE_PLAYBACK_SPEED = 4.0f
private const val NORMAL_SYNC_INTERVAL_MS = 3200L

internal fun normalizeDanmakuPlaybackSpeed(videoSpeed: Float): Float {
    if (videoSpeed.isNaN()) return 1.0f
    return videoSpeed.coerceIn(MIN_ENGINE_PLAYBACK_SPEED, MAX_ENGINE_PLAYBACK_SPEED)
}

internal fun resolveDanmakuDriftSyncIntervalMs(videoSpeed: Float): Long {
    val normalizedSpeed = normalizeDanmakuPlaybackSpeed(videoSpeed)
    return when {
        normalizedSpeed >= 1.75f -> 900L
        normalizedSpeed >= 1.25f -> 1200L
        normalizedSpeed > 1.02f -> 2000L
        normalizedSpeed <= 0.75f -> 3000L
        normalizedSpeed < 0.98f -> 3500L
        else -> NORMAL_SYNC_INTERVAL_MS
    }
}

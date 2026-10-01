package com.android.purebilibili.core.lifecycle

internal const val BACKGROUND_IMAGE_TRIM_DELAY_MS = 45_000L
private const val BACKGROUND_IMAGE_CACHE_LIGHT_MAX_BYTES = 24L * 1024 * 1024
private const val BACKGROUND_IMAGE_CACHE_MAX_BYTES = 8L * 1024 * 1024

internal fun resolveBackgroundImageCacheTrimTargetBytes(
    cacheSizeBytes: Long,
    backgroundElapsedMs: Long,
): Long {
    val budget = if (backgroundElapsedMs >= BACKGROUND_IMAGE_TRIM_DELAY_MS) {
        BACKGROUND_IMAGE_CACHE_MAX_BYTES
    } else {
        BACKGROUND_IMAGE_CACHE_LIGHT_MAX_BYTES
    }
    return cacheSizeBytes.coerceIn(0L, budget)
}

internal fun shouldTrimImageCacheAfterBackgroundDelay(
    isInBackground: Boolean,
    isPipActiveOrPending: Boolean,
    backgroundElapsedMs: Long,
): Boolean = isInBackground && !isPipActiveOrPending &&
    backgroundElapsedMs >= BACKGROUND_IMAGE_TRIM_DELAY_MS

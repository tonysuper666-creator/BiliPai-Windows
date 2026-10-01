package com.android.purebilibili.core.lifecycle

import android.content.ComponentCallbacks2

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

/**
 * 应用级后台内存编排计划。
 * 由 Application.onTrimMemory / onLowMemory 解析后分发给图片缓存与播放器。
 */
internal data class BackgroundMemoryTrimPlan(
    val imageCacheTrimLevel: Int?,
    val clearImageMemoryCache: Boolean,
    val notifyPlayerHeavyOptimization: Boolean,
    val requestIdlePlaybackRelease: Boolean
)

internal fun resolveBackgroundMemoryTrimPlan(level: Int): BackgroundMemoryTrimPlan {
    val imageCacheTrimLevel = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
        ComponentCallbacks2.TRIM_MEMORY_MODERATE,
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> level
        else -> null
    }
    val clearImageMemoryCache = level == ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
        level == ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
        level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
    val notifyPlayerHeavyOptimization = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
        level == ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
        level == ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
        level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
    val requestIdlePlaybackRelease = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
        level == ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
        level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE

    return BackgroundMemoryTrimPlan(
        imageCacheTrimLevel = imageCacheTrimLevel,
        clearImageMemoryCache = clearImageMemoryCache,
        notifyPlayerHeavyOptimization = notifyPlayerHeavyOptimization,
        requestIdlePlaybackRelease = requestIdlePlaybackRelease
    )
}

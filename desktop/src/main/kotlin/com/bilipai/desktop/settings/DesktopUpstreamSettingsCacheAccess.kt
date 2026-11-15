package com.android.purebilibili.feature.settings

import android.content.Context
import com.android.purebilibili.core.util.CacheClearTarget
import com.android.purebilibili.core.util.CacheUtils
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import com.bilipai.desktop.settings.DesktopSettingsApplication
import kotlinx.coroutines.CancellationException

/** Original VM calls keep their spelling; all bytes and clear admission remain with the real owner. */
private fun Context.settingsApplication(): DesktopSettingsApplication {
    val application = applicationContext as? DesktopSettingsApplication
        ?: throw UnsupportedDesktopCapability("Settings cache owner", "the mounted Root must supply its actual storage owner")
    if (!application.owns()) throw CancellationException("Settings page retired")
    return application
}

internal suspend fun CacheUtils.getCacheBreakdown(context: Context): CacheUtils.CacheBreakdown =
    context.settingsApplication().cacheOwner.breakdown()

internal suspend fun CacheUtils.clearCache(context: Context, targets: Set<CacheClearTarget>): Result<Unit> = try {
    context.settingsApplication().cacheOwner.clear(targets)
    Result.success(Unit)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}

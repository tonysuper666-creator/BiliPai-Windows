package com.android.purebilibili.core.util
import com.android.purebilibili.data.model.VideoQuality
internal fun resolvePlaybackDefaultQualityId(
    storedQuality: Int,
    autoHighestEnabled: Boolean,
    isLoggedIn: Boolean,
    isVip: Boolean
): Int {
    if (autoHighestEnabled) {
        return VideoQuality.SUPER_8K.code
    }

    return resolvePlayableDefaultQualityId(
        storedQuality = storedQuality,
        isLoggedIn = isLoggedIn,
        isVip = isVip
    )
}

internal fun shouldRefreshVipStatusBeforeResolvingDefaultQuality(
    storedQuality: Int,
    autoHighestEnabled: Boolean,
    isLoggedIn: Boolean,
    cachedIsVip: Boolean
): Boolean {
    if (!isLoggedIn || cachedIsVip) return false
    return autoHighestEnabled || storedQuality > 80
}

internal fun resolvePlayableDefaultQualityId(
    storedQuality: Int,
    isLoggedIn: Boolean,
    isVip: Boolean
): Int {
    if (isVip) return storedQuality

    return when {
        isLoggedIn && storedQuality > 80 -> 80
        !isLoggedIn && storedQuality > 64 -> 64
        else -> storedQuality
    }
}

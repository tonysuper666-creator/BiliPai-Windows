package com.android.purebilibili.feature.video.ui.section
import com.android.purebilibili.core.store.*

internal fun resolveVideoPlayerDanmakuSettingsScope(
    isFullscreen: Boolean,
    isPortraitFullscreen: Boolean,
): DanmakuSettingsScope {
    return resolveDanmakuSettingsScope(
        isLandscape = isFullscreen && !isPortraitFullscreen
    )
}

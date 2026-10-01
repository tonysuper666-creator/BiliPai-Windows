package com.android.purebilibili.feature.video.screen
internal fun shouldApplyStatusBarPaddingToVideoPlayerChrome(
    statusBarVisible: Boolean,
): Boolean = statusBarVisible

internal data class VideoDetailSystemBarsVisibilityPolicy(
    val hideStatusBars: Boolean,
    val hideNavigationBars: Boolean
)

@Suppress("UNUSED_PARAMETER")
internal fun resolveVideoDetailSystemBarsVisibilityPolicy(
    isFullscreenMode: Boolean,
    hideVideoPageStatusBar: Boolean,
    isInPipMode: Boolean,
    isScreenActive: Boolean,
    isPortraitFullscreen: Boolean = false,
    forceShowSystemBarsInPortrait: Boolean = false
): VideoDetailSystemBarsVisibilityPolicy {
    if (!isScreenActive || isInPipMode) {
        return VideoDetailSystemBarsVisibilityPolicy(
            hideStatusBars = false,
            hideNavigationBars = false
        )
    }
    // Portrait immersive pager: hide status + nav bars for full-bleed playback.
    // forceShow allows the portrait chrome toggle to temporarily restore bars.
    if (isPortraitFullscreen) {
        if (forceShowSystemBarsInPortrait) {
            return VideoDetailSystemBarsVisibilityPolicy(
                hideStatusBars = false,
                hideNavigationBars = false
            )
        }
        return VideoDetailSystemBarsVisibilityPolicy(
            hideStatusBars = true,
            hideNavigationBars = true
        )
    }
    if (isFullscreenMode) {
        return VideoDetailSystemBarsVisibilityPolicy(
            hideStatusBars = true,
            hideNavigationBars = true
        )
    }
    // This preference used to hide the status bar. It now controls the Compose Haze backdrop
    // above the inline player, so the system icons remain visible and readable.
    return VideoDetailSystemBarsVisibilityPolicy(
        hideStatusBars = false,
        hideNavigationBars = false
    )
}


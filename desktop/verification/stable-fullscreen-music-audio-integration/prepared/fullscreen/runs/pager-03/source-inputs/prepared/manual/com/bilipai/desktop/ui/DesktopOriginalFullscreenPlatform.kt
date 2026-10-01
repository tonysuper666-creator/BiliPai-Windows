package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** Read/command view of the one already accepted MPV subject. Root must derive all
 * six fields from the same entry/sourceVersion; this interface owns no player/state.
 */
internal interface DesktopOriginalFullscreenMiniOwner {
    val player: DesktopOriginalMpvSectionControl?
    val currentTitle: String
    val currentBvid: String?
    val currentCid: Long
    val currentAid: Long
    val duration: Long
}

/** Required platform capabilities of the existing Window/Section/Overlay owner.
 * Leases must restore only their own unchanged Window/source token. No Android
 * orientation/system-bar implementation or physical-fullscreen proof is implied.
 */
internal interface DesktopOriginalFullscreenPlatform {
    val section: DesktopOriginalVideoSectionPlatform
    fun acquireFullscreen(player: DesktopOriginalMpvSectionControl?): AutoCloseable
    fun acquireKeepAwake(enabled: Boolean): AutoCloseable
    fun recoverSurface(player: DesktopOriginalMpvSectionControl)
    fun acquireDanmakuPlayer(player: DesktopOriginalMpvSectionControl): AutoCloseable
    /** Must delegate the sole DesktopOriginalPlayerSurface shaped foreground owner,
     * with the actual native sourceVersion and PiP exclusion. It cannot create a
     * second Canvas/Popup/renderer or attach the same source twice.
     */
    @Composable fun Surface(player: DesktopOriginalMpvSectionControl?, foreground: @Composable () -> Unit)
}

internal val LocalDesktopOriginalFullscreenPlatform = staticCompositionLocalOf<DesktopOriginalFullscreenPlatform> {
    error("Fullscreen overlay requires the existing MPV/Window/Overlay owner")
}

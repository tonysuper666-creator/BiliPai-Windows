package com.bilipai.desktop.danmaku

import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot

/** Windows mapping of the original renderingPaused frame-update gate. Values
 * come from the existing native media clock; this owns no clock, job or document.
 * A source that has never painted cannot borrow a predecessor's retained frame.
 */
internal fun resolveDesktopDanmakuPresentationTime(
    currentSource: OwnedPlaybackSourceSnapshot?,
    previousSource: OwnedPlaybackSourceSnapshot?,
    previousTimeSeconds: Double,
    actualTimeSeconds: Double,
    renderingPaused: Boolean,
): Double? {
    val selected = if (renderingPaused) {
        if (currentSource == null || previousSource == null ||
            currentSource.sourceVersion != previousSource.sourceVersion ||
            currentSource.source != previousSource.source) return null
        previousTimeSeconds
    } else actualTimeSeconds
    return selected.takeIf { it.isFinite() && it >= 0.0 }
}

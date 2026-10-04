package com.bilipai.desktop.danmaku

import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot

/** Presentation changes remeasure retained items; filtering/merging changes still rebuild. */
internal fun DanmakuSettings.hasSameTimelinePolicy(next: DanmakuSettings): Boolean = copy(
    opacity=next.opacity, fontScale=next.fontScale, fontWeight=next.fontWeight,
    speedFactor=next.speedFactor, displayAreaRatio=next.displayAreaRatio,
    scrollDurationSeconds=next.scrollDurationSeconds, staticDurationSeconds=next.staticDurationSeconds,
    scrollFixedVelocity=next.scrollFixedVelocity, massiveMode=next.massiveMode,
    smartOcclusionEnabled=next.smartOcclusionEnabled, lineHeight=next.lineHeight,
    strokeEnabled=next.strokeEnabled, strokeWidth=next.strokeWidth,
) == next

/** Ephemeral actual Root presentation, never inferred from video geometry or saved as a preference. */
internal class DesktopDanmakuSourcePresentation(private val player: MpvPlayer) {
    private data class Stamp(val source: OwnedPlaybackSourceSnapshot, val fullscreen: Boolean)
    @Volatile private var stamp: Stamp? = null

    fun update(expected: OwnedPlaybackSourceSnapshot, fullscreen: Boolean): Boolean =
        player.admitSourceSnapshot(expected) { stamp=Stamp(expected,fullscreen) }

    fun isFullscreen(): Boolean = stamp?.let {
        it.fullscreen && player.ownsSourceSnapshot(it.source)
    } ?: false
}

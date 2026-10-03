package com.bilipai.desktop.player

/** Actual native source and explicit pause-command intent, not another player/session. */
internal class DesktopBackgroundPlaybackPauseToken internal constructor(
    internal val player: MpvPlayer,
    internal val source: OwnedPlaybackSourceSnapshot,
    internal val pauseIntentSerial: Long,
) {
    override fun toString() = "DesktopBackgroundPlaybackPauseToken(sourceVersion=${source.sourceVersion})"
}

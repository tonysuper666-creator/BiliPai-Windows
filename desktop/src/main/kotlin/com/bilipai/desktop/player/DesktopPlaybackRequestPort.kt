package com.bilipai.desktop.player

import com.bilipai.desktop.data.VideoCard

/** Requests run on the existing desktop UI lane. The implementation owns loading,
 * source versions, recovery and heartbeat; a caller never loads MPV directly.
 */
interface DesktopPlaybackRequestPort {
    fun requestPlayback(card: VideoCard, resumePositionMs: Long? = null): DesktopPlaybackRequestLease
}

/** A screen's request ownership, not ownership of the shared decoder or surface.
 * A replaced/account-retired lease cannot retry, change quality, or stop playback.
 * Close releases only this request; the root still owns the native player lifetime.
 */
interface DesktopPlaybackRequestLease : AutoCloseable {
    val isCurrent: Boolean
    fun retry(): Boolean
    fun selectQuality(quality: Int): Boolean
    fun selectAudioQuality(preferenceId: Int): Boolean
}

package com.bilipai.desktop.danmaku

import com.android.purebilibili.feature.video.danmaku.DanmakuCloudRuleSyncPolicy
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import java.util.concurrent.atomic.AtomicReference

/** Source display metadata over the existing MPV/Root admission; no sender/account authority. */
internal class DesktopDanmakuAuthorPresentation(private val player: MpvPlayer) {
    private data class Stamp(val source: OwnedPlaybackSourceSnapshot?, val token: Any?, val hash: String?,
        val revision: Long, val stillOwned: () -> Boolean)
    internal data class Paint(val userHash: String?, val measurementRevision: Long) {
        fun matches(userHash: String): Boolean = this.userHash != null && this.userHash == userHash
    }
    private val stamp = AtomicReference(Stamp(null, null, null, 0L, { false }))

    /** Caller already holds its existing source/account/entry permit. No Overlay monitor acquired. */
    fun bind(source: OwnedPlaybackSourceSnapshot, token: Any, mid: Long, stillOwned: () -> Boolean): Boolean {
        val hash = if (mid > 0L) DanmakuCloudRuleSyncPolicy.crc32Hex(mid.toString()) else null
        if (!stillOwned()) return false
        var applied = false
        player.admitSourceSnapshot(source) {
            if (stillOwned()) {
                stamp.updateAndGet { old -> Stamp(source, token, hash, old.revision + 1L, stillOwned) }
                applied = true
            }
        }
        return applied
    }
    fun release(token: Any): Boolean {
        while (true) {
            val old = stamp.get()
            if (old.token !== token) return false
            if (stamp.compareAndSet(old, Stamp(null, null, null, old.revision + 1L, { false }))) return true
        }
    }
    fun capture(): Paint {
        val current = stamp.get()
        val owned = current.source?.let { current.stillOwned() && player.ownsSourceSnapshot(it) } == true
        // A retired same-version recovery also changes the effective measurement key.
        return if (owned && stamp.get() === current) Paint(current.hash, current.revision)
            else Paint(null, -current.revision)
    }
}

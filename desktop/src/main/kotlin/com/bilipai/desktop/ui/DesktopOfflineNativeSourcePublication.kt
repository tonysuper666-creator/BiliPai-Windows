package com.bilipai.desktop.ui

import com.bilipai.desktop.player.DesktopNativePlaybackPublication
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.immutableSnapshot

/** Admission for the local file actually loaded by the existing offline entry.
 * Lock order: existing SessionStore/Root entry -> this receipt -> actual MPV.
 * No disk IO, actor drain or entry admission is performed while retiring it.
 */
internal class DesktopOfflineNativeSourcePublication(
    private val player: MpvPlayer,
    private val stillOwned: () -> Boolean,
    private val withOwnedAdmission: ((() -> Unit) -> Boolean),
) : DesktopNativePlaybackPublication, AutoCloseable {
    private val lock = Any()
    private var retired = false
    private var loaded: OwnedPlaybackSourceSnapshot? = null

    /** Called inside the original entry admission, after that same MPV load.
     * This is a read-only receipt; it never publishes or modifies a source.
     */
    fun bind(expected: OwnedPlaybackSourceSnapshot) = synchronized(lock) {
        check(!retired && loaded == null)
        require(expected.source.nativePublication === this)
        require(expected.source.authorizationReceipt == null && expected.source.primaryAccountEpoch == null)
        check(player.ownsSourceSnapshot(expected))
        loaded = expected
    }

    // MPV's existing same-file recovery/replay may change these two startup
    // controls. The file, transport, metadata and publication identity stay exact.
    private fun mediaIdentity(source: PlaybackSource) = source
        .copy(startPositionSeconds = 0.0, startPaused = false).immutableSnapshot()

    override fun admit(command: () -> Unit): Boolean {
        var applied = false
        val accepted = withOwnedAdmission {
            synchronized(lock) {
                val original = loaded
                if (!retired && original != null && stillOwned()) {
                    val current = player.currentSourceSnapshot()
                    if (current != null && current.sourceVersion == original.sourceVersion &&
                        current.source.nativePublication === this &&
                        mediaIdentity(current.source) == mediaIdentity(original.source)) {
                        // Protect the complete *current* immutable source at the
                        // final native boundary, including recovery/start controls.
                        player.admitSourceSnapshot(current) {
                            if (!retired && stillOwned()) { command(); applied = true }
                        }
                    }
                }
            }
        }
        return accepted && applied
    }

    override fun close() = synchronized(lock) {
        retired = true
        loaded = null
    }
}

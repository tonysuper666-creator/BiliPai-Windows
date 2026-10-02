package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.player.PlaylistItem
import com.bilipai.desktop.audio.ListenAudioSession
import com.bilipai.desktop.data.VideoCard

/** Only an admission/reveal adapter. The Controller/Listen session retain the actual
 * native source, queue, shuffle policy and ownership. No Root queue is copied into a store. */
internal class DesktopFavoriteQueueBridge(
    private val playback: DesktopUnifiedPlaybackFacade,
    private val listen: ListenAudioSession?,
    private val stillOwned: () -> Boolean,
    private val isSelectedTarget: (Boolean) -> Boolean,
    private val beforeOpen: (Boolean) -> Boolean,
    private val revealVideo: () -> Unit,
    private val revealAudio: () -> Unit,
) : AutoCloseable {
    private class Lease(val audio: Boolean)
    private data class Reveal(val lease: Lease, val bvid: String, val cid: Long)
    private var active: Lease? = null
    private var reveal: Reveal? = null

    fun openQueue(items: List<PlaylistItem>, index: Int, audio: Boolean,
        resumePositionMs: Long? = null): DesktopFavoriteQueueToken? {
        if (!stillOwned() || index !in items.indices || !beforeOpen(audio) || !stillOwned()) return null
        val selected = items[index]
        val lease = Lease(audio)
        active = lease; reveal = null
        if (audio) {
            if (listen?.playQueueForOwner(lease, items, index, resumePositionMs?.coerceAtLeast(0L)?.div(1000.0)) != true) { active = null; return null }
        } else {
            playback.openQueue(items.map(::favoriteQueueVideoCard), index, lease, resumePositionMs)
            if (!playback.ownsQueue(lease)) { active = null; return null }
        }
        if (!owns(lease)) { active = null; return null }
        reveal = Reveal(lease, selected.bvid, selected.cid)
        return DesktopFavoriteQueueToken(lease)
    }

    /** Original CommonList calls onVideoClick/onPlayAllAudio immediately after openQueue.
     * A consumed ticket reveals the real queue; it must never call ordinary open again. */
    fun revealIfOwned(bvid: String, cid: Long, audio: Boolean): Boolean {
        val ticket = reveal ?: return false
        reveal = null
        if (ticket.bvid != bvid || (ticket.cid > 0 && cid > 0 && ticket.cid != cid) || ticket.lease.audio != audio || !owns(ticket.lease)) return false
        if (audio) revealAudio() else revealVideo()
        return true
    }

    /** Original addAllToCurrentPlaylist: append unseen BVIDs, leaving current metadata,
     * CID, order, index and native position/pause untouched. Next/previous retains owner. */
    fun appendQueue(items: List<PlaylistItem>, token: DesktopFavoriteQueueToken) {
        val lease = token.owner as? Lease ?: return
        if (!owns(lease)) return
        if (lease.audio) {
            listen?.appendQueueForOwner(lease, items)
        } else {
            val before = playback.state.value
            val existing = before.queue.mapTo(mutableSetOf()) { it.bvid }
            val new = items.filter { it.bvid !in existing }.map(::favoriteQueueVideoCard)
            if (new.isNotEmpty()) playback.updateQueueForOwner(lease, before.queue + new, before.queueIndex)
        }
    }

    private fun owns(lease: Lease): Boolean = active === lease && stillOwned() && isSelectedTarget(lease.audio) &&
        (if (lease.audio) listen?.ownsQueue(lease) == true else playback.ownsQueue(lease))
    override fun close() { active = null; reveal = null }
}

internal fun favoriteQueueVideoCard(item: PlaylistItem) = VideoCard(item.bvid, item.title, item.cover,
    item.owner, 0L, item.duration.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), preferredCid = item.cid)

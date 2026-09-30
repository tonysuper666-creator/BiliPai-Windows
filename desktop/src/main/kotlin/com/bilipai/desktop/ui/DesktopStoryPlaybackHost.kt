package com.bilipai.desktop.ui

import com.bilipai.desktop.DesktopPlaybackController
import com.bilipai.desktop.DesktopPlaybackState
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface DesktopStoryQueuePlayer {
    fun ownsQueue(owner: Any): Boolean
    fun openQueue(cards: List<VideoCard>, index: Int, owner: Any)
    fun updateQueueForOwner(owner: Any, cards: List<VideoCard>, index: Int): Boolean
    fun stopQueueForOwner(owner: Any): Boolean
    fun retryQueueForOwner(owner: Any): Boolean = false
}

internal class ControllerStoryQueuePlayer(private val controller: DesktopPlaybackController) : DesktopStoryQueuePlayer {
    override fun ownsQueue(owner: Any) = controller.ownsQueue(owner)
    override fun openQueue(cards: List<VideoCard>, index: Int, owner: Any) = controller.openQueue(cards, index, owner)
    override fun updateQueueForOwner(owner: Any, cards: List<VideoCard>, index: Int) = controller.updateQueueForOwner(owner, cards, index)
    override fun stopQueueForOwner(owner: Any) = controller.stopQueueForOwner(owner)
    override fun retryQueueForOwner(owner: Any) = controller.retryQueueForOwner(owner)
}

/** Root route ownership is separate from the original pager; late route messages never acquire a foreign source. */
internal class DesktopStoryPlaybackHost(private val player: DesktopStoryQueuePlayer,
    private val sessionEpoch: () -> Long, private val onAcquire: () -> Unit = {}) : AutoCloseable {
    private val mutableOwner = MutableStateFlow<DesktopStoryOwner?>(null)
    val owner = mutableOwner.asStateFlow()
    private val retired = mutableSetOf<DesktopStoryOwner>()
    private var acceptedRevision = -1L
    private var closed = false

    fun owns(candidate: DesktopStoryOwner): Boolean {
        val held = owner.value ?: return false
        return held == candidate && held.sessionEpoch == sessionEpoch() && player.ownsQueue(held)
    }

    fun request(request: DesktopStoryPlaybackRequest, active: Boolean): Boolean {
        if (closed || !active || request.owner.sessionEpoch != sessionEpoch() || request.owner in retired ||
            request.revision < 0 || request.queue.size > 10_000 || request.index !in request.queue.indices ||
            request.queue.any { it.bvid.isBlank() }) return false
        val held = owner.value
        if (held == request.owner && !owns(held)) { retire(stop = false); return false }
        if (held == request.owner && request.revision <= acceptedRevision) return false
        if (!request.select) {
            if (held != request.owner || !player.updateQueueForOwner(held, request.queue, request.index)) return false
            acceptedRevision = request.revision
            return true
        }
        if (held != null && held != request.owner) retire()
        val stableOwner = owner.value?.takeIf { it == request.owner } ?: request.owner
        onAcquire()
        player.openQueue(request.queue, request.index, stableOwner)
        mutableOwner.value = stableOwner
        acceptedRevision = request.revision
        return true
    }

    /** Explicit retry neither replaces a held route nor acquires a foreign source. */
    fun retry(candidate: DesktopStoryOwner, active: Boolean): Boolean {
        if (closed || !active || candidate in retired || candidate.sessionEpoch != sessionEpoch() ||
            owner.value != candidate || !owns(candidate)) return false
        return player.retryQueueForOwner(requireNotNull(owner.value))
    }

    fun release(candidate: DesktopStoryOwner): Boolean {
        if (owner.value != candidate) { retired += candidate; return false }
        retire()
        return true
    }

    fun retire(stop: Boolean = true) {
        val held = owner.value ?: return
        if (stop && held.sessionEpoch == sessionEpoch()) player.stopQueueForOwner(held)
        retired += held
        mutableOwner.value = null
        acceptedRevision = -1
    }

    fun snapshot(state: DesktopPlaybackState, native: PlayerState, initializationError: String? = null): DesktopStoryPlaybackSnapshot {
        val held = owner.value?.takeIf(::owns) ?: return DesktopStoryPlaybackSnapshot()
        return DesktopStoryPlaybackSnapshot(held,
            bvid = state.details?.bvid ?: state.queue.getOrNull(state.queueIndex)?.bvid.orEmpty(),
            loading = state.opening || native.loading, error = state.error ?: native.error ?: initializationError,
            queueIndex = state.queueIndex,
            cid = state.details?.pages?.getOrNull(state.currentPart)?.cid ?: state.queue.getOrNull(state.queueIndex)?.preferredCid ?: 0)
    }

    override fun close() { retire(); closed = true }
}

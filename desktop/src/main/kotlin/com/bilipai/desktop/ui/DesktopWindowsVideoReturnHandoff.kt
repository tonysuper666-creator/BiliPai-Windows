package com.bilipai.desktop.ui

import com.android.purebilibili.navigation3.BiliPaiNavKey
import java.awt.EventQueue

/** A delayed request for the ONE real Root pop, never a copied navigation stack.
 * Only the captured Root checkpoint can commit it. Draw/image/native receipts may arrive
 * after another navigation, an account replacement or host disposal; none can revive it.
 * All methods belong to the real Window EDT, outside Store/entry admission monitors.
 */
internal class DesktopWindowsVideoReturnRequest internal constructor(
    val leavingKey: BiliPaiNavKey.VideoDetail,
    val targetKey: BiliPaiNavKey?,
    private val onLane: () -> Boolean,
    private val current: () -> Boolean,
    private val performBack: () -> Boolean,
) {
    private var retired = false
    private var consumed = false
    internal var result: Boolean? = null
        private set

    fun isCurrent(): Boolean {
        check(onLane())
        return !retired && !consumed && current()
    }

    /** Exactly once, including a rejected actual checkpoint. It must never be retried by
     * an old timeout after a later manual back or a same-value new entry has appeared. */
    fun commit(): Boolean {
        check(onLane())
        if (!isCurrent()) return false
        consumed = true
        return performBack().also { result = it }
    }

    internal fun retire() { check(onLane()); retired = true }
}

/** A bounded handoff owned by the actual Root route assembly. A mounted visual host may
 * claim its request and wait for a drawn cover/native clip OUTSIDE navigation locks.
 * With no host it invokes the existing synchronous return unchanged. Repeated back clicks
 * while one current request is preparing coalesce, rather than popping a second entry.
 */
internal class DesktopWindowsVideoReturnHandoff(
    private val onLane: () -> Boolean = EventQueue::isDispatchThread,
) {
    private var delegate: ((DesktopWindowsVideoReturnRequest) -> Boolean)? = null
    private var pending: DesktopWindowsVideoReturnRequest? = null

    fun bind(callback: (DesktopWindowsVideoReturnRequest) -> Boolean) {
        check(onLane())
        if (delegate === callback) return
        retirePending()
        delegate = callback
    }

    fun unbind(callback: (DesktopWindowsVideoReturnRequest) -> Boolean) {
        check(onLane())
        if (delegate !== callback) return
        delegate = null
        retirePending()
    }

    fun retirePending() {
        check(onLane())
        pending?.retire()
        pending = null
    }

    fun request(leavingKey: BiliPaiNavKey.VideoDetail, targetKey: BiliPaiNavKey?,
        stillCurrent: () -> Boolean, performBack: () -> Boolean): Boolean {
        check(onLane())
        if (!stillCurrent()) return false
        pending?.let {
            if (it.isCurrent() && it.leavingKey === leavingKey && it.targetKey === targetKey) return true
            retirePending() // A different accepted intent (for example Home) replaces preparation.
        }
        lateinit var request: DesktopWindowsVideoReturnRequest
        request = DesktopWindowsVideoReturnRequest(leavingKey, targetKey, onLane,
            current = { pending === request && stillCurrent() },
            performBack = {
                // Consume before replaceStack retires outstanding visual work. The actual
                // Root closure must independently revalidate identity/revision under its gate.
                if (pending === request) pending = null
                performBack()
            })
        pending = request
        try {
            val deferred = delegate?.invoke(request) == true
            request.result?.let { return it } // A host may commit immediately on a cache hit.
            return if (deferred) request.isCurrent() else request.commit()
        } catch (failure: Throwable) {
            if (pending === request) retirePending()
            throw failure // Includes coroutine cancellation; never turn it into a successful back.
        } finally {
            // A thrown delegate/cancel, refusal, or rejected stale checkpoint leaves no claim.
            if (pending === request && (request.result != null || !request.isCurrent())) pending = null
        }
    }
}

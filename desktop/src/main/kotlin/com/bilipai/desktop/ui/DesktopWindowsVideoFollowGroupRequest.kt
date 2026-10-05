package com.bilipai.desktop.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A lexical permission for the original VM's one group dialog. Source/account
 * authority and all group state remain with the retained original owner. */
internal class DesktopWindowsVideoFollowGroupRequest(
    val sourceOwner: DesktopOriginalVideoAcceptedPublication,
    val targetMid: Long,
    private val stillOwned: (DesktopWindowsVideoFollowGroupRequest) -> Boolean,
    private val admission: (() -> Unit) -> Boolean,
) : AutoCloseable {
    enum class Operation { LOAD, SAVE }
    internal class Ticket internal constructor(val operation: Operation) {
        internal val job = AtomicReference<Job?>()
    }
    private val alive = AtomicBoolean(true)
    private val loading = AtomicReference<Ticket?>()
    private val saving = AtomicReference<Ticket?>()

    init { require(targetMid > 0L) }

    fun isOwned(): Boolean = alive.get() && stillOwned(this)
    fun commit(action: () -> Unit): Boolean {
        if (!isOwned()) return false
        var applied = false
        return admission {
            if (isOwned()) { action(); applied = true }
        } && applied
    }
    /** The full original dialog supplies a synchronous callback, which may
     * launch an operation. Admit its captured request, then run outside locks;
     * original VM methods recheck that same request before publishing anything. */
    fun dispatch(action: () -> Unit): Boolean {
        if (!commit {} || !isOwned()) return false
        val previous = dispatched.get()
        dispatched.set(this)
        return try { if (isOwned()) { action(); true } else false }
        finally { if (previous == null) dispatched.remove() else dispatched.set(previous) }
    }
    private fun slot(operation: Operation) = if (operation == Operation.LOAD) loading else saving
    fun ticket(operation: Operation) = Ticket(operation)

    /** The job is lazy: linking it and publishing busy precede start, while all
     * actual launch/start/cancel and API work remain outside admission. */
    fun begin(ticket: Ticket, job: Job, publishBusy: () -> Unit): Boolean {
        check(ticket.job.compareAndSet(null, job))
        val lane = slot(ticket.operation)
        if (!lane.compareAndSet(null, ticket)) { job.cancel(); return false }
        var accepted = false
        try {
            accepted = commit {
                if (lane.get() === ticket && !job.isCancelled) publishBusy()
                else throw CancellationException("Follow-group operation replaced")
            }
            return accepted
        } finally {
            if (!accepted) { lane.compareAndSet(ticket, null); job.cancel() }
        }
    }
    fun assertCurrent(ticket: Ticket) {
        if (!isOwned() || slot(ticket.operation).get() !== ticket || ticket.job.get()?.isActive != true)
            throw CancellationException("Follow-group request retired")
    }
    fun publish(ticket: Ticket, action: () -> Unit): Boolean {
        assertCurrent(ticket)
        return commit { assertCurrent(ticket); action() }
    }
    /** Cancellation may finish a current operation, but an old finally cannot
     * clear a newer request or ticket's busy state. */
    fun finish(ticket: Ticket, publishIdle: () -> Unit): Boolean {
        var cleared = false
        return commit {
            if (slot(ticket.operation).compareAndSet(ticket, null)) { publishIdle(); cleared = true }
        } && cleared
    }
    override fun close() {
        alive.set(false)
        // These are only this request's exact jobs. Never read a successor.
        loading.getAndSet(null)?.job?.get()?.cancel()
        saving.getAndSet(null)?.job?.get()?.cancel()
    }
    companion object {
        private val dispatched = ThreadLocal<DesktopWindowsVideoFollowGroupRequest?>()
        fun dispatchedRequest(): DesktopWindowsVideoFollowGroupRequest? = dispatched.get()
    }
}

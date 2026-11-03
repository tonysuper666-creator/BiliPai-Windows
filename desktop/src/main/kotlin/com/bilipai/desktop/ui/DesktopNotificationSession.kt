package com.bilipai.desktop.ui

import com.android.purebilibili.feature.message.notification.PendingMessageNotification
import com.bilipai.desktop.integration.DesktopNotificationCenter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * One account's notification lifetime.
 *
 * Delivery identity: a toast is identified by `key#nonce-seq` for its whole life — across sessions
 * for the same account and across processes. A message key is stable and reused, so one key can have
 * several live deliveries (the unread count grows, the same reply is published again); cancelling a
 * key must cover all of them, and confirming one delivery must not end the entry while another lives.
 *
 * Cleanup and publication are serialised: while any group withdrawal is running, publish refuses, and
 * withdrawals run under one lock. Only the session holding the account lease may withdraw the whole
 * group, so a returning account cannot be wiped by an older session's pending retry.
 *
 * The three exits:
 * - [detach] — window going away; delivered notifications stay and stay clickable.
 * - [pause] — notifications turned off; withdraw the group, but the account stays current ([resume]).
 * - [retire] — the account is gone; publishing never comes back.
 */
internal class DesktopNotificationSession(
    private val accountId: Long?,
    private val center: DesktopNotificationCenter,
    private val report: (message: String, failure: Throwable) -> Unit = { message, failure ->
        System.err.println("$message: ${failure.javaClass.simpleName}: ${failure.message}")
    },
) {
    val group: String = "messages-$accountId"
    private val withdrawal = DesktopNotificationWithdrawal(
        withdraw = { tag, target -> center.remove(tag, target) }, report = report).apply { bind(group) }
    /** Message key -> the deliveries of it that are still on screen. */
    private val liveTags = ConcurrentHashMap<String, MutableSet<String>>()
    /** Keys that lost a delivery; whether their entry can end is decided when the caller collects them. */
    private val settledKeys = ConcurrentHashMap.newKeySet<String>()
    private val paused = AtomicBoolean()
    private val accountRetired = AtomicBoolean()
    private val groupClearPending = AtomicBoolean()
    private val clearsInFlight = AtomicInteger()
    private val clearAttempts = AtomicInteger()
    private val cleanupLock = ReentrantLock()

    init { DesktopNotificationAccountLeases.claim(accountId, this) }

    fun accountId(): Long? = accountId

    fun isPublishing(): Boolean = !paused.get() && !accountRetired.get()

    /** True while anything the surface refused — a toast or the group — is still outstanding. */
    fun isCleanupPending(): Boolean =
        groupClearPending.get() || clearsInFlight.get() > 0 || withdrawal.pendingKeys().isNotEmpty()

    fun clearAttemptCount(): Int = clearAttempts.get()

    fun pendingTags(): Set<String> = withdrawal.pendingKeys()

    fun keyFor(tag: String): String = tag.substringBefore('#')

    /** True when at least one key lost its last delivery. */
    fun hasResolved(): Boolean = settledKeys.any { liveTags[it].isNullOrEmpty() }

    /**
     * Keys that can leave the caller's list: identity is checked here, at consumption time, so a
     * delivery published after the confirmation keeps its entry alive.
     */
    fun takeResolved(): Set<String> {
        val out = linkedSetOf<String>()
        for (key in settledKeys.toList()) {
            settledKeys.remove(key)
            if (liveTags[key].isNullOrEmpty()) out += key
        }
        return out
    }

    fun detach() { paused.set(true) }

    /** Notifications turned off. Resumable through [resume]. */
    fun pause(): Boolean {
        paused.set(true)
        groupClearPending.set(true)
        return retryCleanup().also { trackIfOutstanding() }
    }

    /**
     * Notifications turned back on. Idempotent: an emission that changes anything else while the
     * master switch stayed on must not withdraw notifications the user has not read.
     * Called from the UI thread, so it never talks to the surface.
     */
    fun resume() {
        if (accountRetired.get()) return
        if (!paused.getAndSet(false)) return
        groupClearPending.set(false)
        // Deliveries of the paused period are removed one by one, by their own tags, by the driver.
        withdrawal.remember(allLiveTags())
        trackIfOutstanding()
    }

    /** The account is gone: withdraw the group and never publish again. */
    fun retire(): Boolean {
        paused.set(true)
        accountRetired.set(true)
        groupClearPending.set(true)
        return retryCleanup().also { trackIfOutstanding() }
    }

    /** Another session took this account: this one may no longer withdraw the shared group. */
    fun abandonGroupClear() {
        // Only a session that was already withdrawing that group has anything to hand over; a plain
        // detach followed by a remount must leave the unread notifications alone.
        if (!groupClearPending.getAndSet(false)) return
        withdrawal.remember(allLiveTags())
        trackIfOutstanding()
    }

    /** One attempt at everything outstanding. False while something is still on screen. */
    fun retryCleanup(): Boolean {
        cleanupLock.withLockUninterruptibly {
            val tags = withdrawal.pendingKeys()
            if (tags.isNotEmpty()) {
                withdrawal.withdraw(tags)
                (tags - withdrawal.pendingKeys()).forEach { settle(it) }
            }
            if (groupClearPending.get()) {
                val gate = DesktopNotificationAccountLeases.gate(accountId).writeLock()
                // Exclusive against deliveries: the lease check and the operation registration both
                // happen while no publisher can be inside the surface, so a takeover cannot slip in
                // between them.
                if (!gate.tryLock(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    return@withLockUninterruptibly false
                }
                var lostLease = false
                val cleared = try {
                    if (!DesktopNotificationAccountLeases.holds(accountId, this)) {
                        // A newer session owns this account's group. This is not a failed cleanup: the
                        // group is no longer ours, so the task ends here and only our own deliveries
                        // are withdrawn, one tag at a time.
                        lostLease = true
                        groupClearPending.set(false)
                        withdrawal.remember(allLiveTags())
                        false
                    } else {
                        clearAttempts.incrementAndGet()
                        clearsInFlight.incrementAndGet()
                        DesktopNotificationAccountLeases.beginGroupOperation(accountId)
                        try {
                            runCatching { center.clear(group) }.onFailure {
                                report("通知中心整组清理失败 group=$group", it)
                            }.isSuccess
                        } finally {
                            clearsInFlight.decrementAndGet()
                            DesktopNotificationAccountLeases.endGroupOperation(accountId)
                        }
                    }
                } finally {
                    gate.unlock()
                }
                when {
                    lostLease -> groupClearPending.set(false) // never requeue a task we may not perform
                    paused.get() -> {
                        groupClearPending.set(!cleared)
                        if (cleared) liveTags.clear()
                    }
                    // A resume happened while this withdrawal ran: it is over, its retry is abandoned.
                    else -> groupClearPending.set(false)
                }
            }
        }
        return !isCleanupPending()
    }

    /**
     * Withdraws every live delivery of [keys] and reports the keys that are fully gone. Pending
     * retries are never re-derived here: they keep the tag they were born with.
     */
    fun withdrawNow(keys: Collection<String>): Set<String> {
        val tags = keys.flatMap { key -> liveTags[key].orEmpty() }
        if (tags.isEmpty()) return emptySet()
        val stillOnScreen = withdrawal.withdraw(tags)
        (tags - stillOnScreen).forEach { settle(it) }
        return keys.filter { key -> liveTags[key].isNullOrEmpty() }.toSet().also { trackIfOutstanding() }
    }

    /** False when the delivery was refused, or when a group withdrawal is running right now. */
    fun publish(item: PendingMessageNotification): Boolean {
        if (!isPublishing() || clearsInFlight.get() > 0 ||
            DesktopNotificationAccountLeases.groupOperationInFlight(accountId)) return false
        val gate = DesktopNotificationAccountLeases.gate(accountId).readLock()
        // A group operation of this account is exclusive. There is no native pending-delivery queue:
        // a refused delivery is reported to the caller, which falls back to a tray balloon.
        if (!gate.tryLock(200, java.util.concurrent.TimeUnit.MILLISECONDS)) return false
        try {
            if (!isPublishing() || DesktopNotificationAccountLeases.groupOperationInFlight(accountId)) return false
            val tag = deliveryTag(item.key)
            val outcome = runCatching {
                center.show(item.title, item.text, "account:$accountId:${item.route}", tag, group)
            }
            if (!isPublishing()) {
                withdrawal.remember(listOf(tag))
                trackIfOutstanding()
                return false
            }
            outcome.onFailure { report("通知中心提交失败 tag=${item.key}", it) }
            if (outcome.isSuccess) liveTags.getOrPut(item.key) { ConcurrentHashMap.newKeySet() }.add(tag)
            return outcome.isSuccess
        } finally {
            gate.unlock()
        }
    }

    private fun settle(tag: String) {
        val key = keyFor(tag)
        val remaining = liveTags[key] ?: return
        remaining.remove(tag)
        if (remaining.isEmpty()) liveTags.remove(key)
        settledKeys += key
    }

    private fun allLiveTags(): List<String> = liveTags.values.flatMap { it.toList() }

    private fun deliveryTag(key: String): String = "$key#$PROCESS_NONCE-${DELIVERY_SEQUENCE.incrementAndGet()}"

    private fun trackIfOutstanding() {
        if (isCleanupPending()) DesktopNotificationCleanup.track(this)
    }

    private companion object {
        /** Distinguishes deliveries of this process from a previous run that may still be on screen. */
        val PROCESS_NONCE: String = java.lang.Long.toHexString(System.nanoTime() xor ProcessHandle.current().pid())
        val DELIVERY_SEQUENCE = AtomicLong()
    }
}

/** Lock helper: cleanup must not be skipped because a thread was interrupted. */
private inline fun <T> ReentrantLock.withLockUninterruptibly(block: () -> T): T {
    lock()
    return try { block() } finally { unlock() }
}

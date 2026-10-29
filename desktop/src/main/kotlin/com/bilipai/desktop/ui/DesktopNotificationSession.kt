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
 * Delivery identity: a toast is identified by `key#nonce-seq`, unique for its whole life — across
 * sessions for the same account and across processes. Message keys (`reply`, `like`, …) are stable and
 * reused, so anything that removes a toast must carry the delivery tag it was created with; deriving
 * a tag from the key at removal time is what let an old retry delete a newer notification.
 *
 * Cleanup and publication are serialised: while any group withdrawal is running, publish refuses, and
 * withdrawals themselves run under one lock so two of them can never overlap.
 *
 * The three exits:
 * - [detach] — window going away; delivered notifications stay, they stay clickable through the protocol.
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
    /** Message key -> the delivery tag currently on screen for it. */
    private val keyTags = ConcurrentHashMap<String, String>()
    private val paused = AtomicBoolean()
    private val accountRetired = AtomicBoolean()
    private val groupClearPending = AtomicBoolean()
    private val clearsInFlight = AtomicInteger()
    private val clearAttempts = AtomicInteger()
    private val cleanupLock = ReentrantLock()
    /** Keys whose older delivery the driver finally removed, so the tray list can drop them. */
    private val resolved = java.util.concurrent.ConcurrentLinkedQueue<String>()

    fun accountId(): Long? = accountId

    fun isPublishing(): Boolean = !paused.get() && !accountRetired.get()

    /** True while anything the surface refused — a toast or the group — is still outstanding. */
    fun isCleanupPending(): Boolean =
        groupClearPending.get() || clearsInFlight.get() > 0 || withdrawal.pendingKeys().isNotEmpty()

    fun clearAttemptCount(): Int = clearAttempts.get()

    /** Delivery tags the surface has not confirmed removed. */
    fun pendingTags(): Set<String> = withdrawal.pendingKeys()

    fun pendingWithdrawals(): Set<String> = withdrawal.pendingKeys().toSet()

    fun keyFor(tag: String): String = tag.substringBefore('#')

    /** Keys whose older delivery is finally gone; the caller drops them from its list. */
    fun takeResolved(): Set<String> {
        val out = linkedSetOf<String>()
        while (true) out += (resolved.poll() ?: break)
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
     * Notifications turned back on. Idempotent: a settings emission that changes anything else while
     * the master switch stayed on must not withdraw notifications the user has not read yet.
     * Called from the UI thread, so it never talks to the surface.
     */
    fun resume() {
        if (accountRetired.get()) return
        if (!paused.getAndSet(false)) return
        groupClearPending.set(false)
        // Toasts of the paused period are removed one by one, by their own tags, by the driver.
        withdrawal.remember(keyTags.values.toList())
        trackIfOutstanding()
    }

    /** The account is gone: withdraw the group and never publish again. */
    fun retire(): Boolean {
        paused.set(true)
        accountRetired.set(true)
        groupClearPending.set(true)
        return retryCleanup().also { trackIfOutstanding() }
    }

    /** One attempt at everything outstanding. False while something is still on screen. */
    fun retryCleanup(): Boolean {
        cleanupLock.withLockUninterruptibly {
            val tags = withdrawal.pendingKeys()
            if (tags.isNotEmpty()) {
                withdrawal.withdraw(tags)
                (tags - withdrawal.pendingKeys()).forEach { resolved += keyFor(it) }
            }
            if (groupClearPending.get()) {
                clearAttempts.incrementAndGet()
                clearsInFlight.incrementAndGet()
                val cleared = try {
                    runCatching { center.clear(group) }.onFailure {
                        report("通知中心整组清理失败 group=$group", it)
                    }.isSuccess
                } finally {
                    clearsInFlight.decrementAndGet()
                }
                if (paused.get()) {
                    groupClearPending.set(!cleared)
                    if (cleared) keyTags.clear()
                } else {
                    // A resume happened while the withdrawal ran: it is over, and its retry is abandoned.
                    groupClearPending.set(false)
                }
            }
        }
        return !isCleanupPending()
    }

    /**
     * Withdraws the toasts for [keys] as they exist right now, and reports the keys the surface
     * confirmed. Pending retries are never re-derived here: they keep the tag they were born with.
     */
    fun withdrawNow(keys: Collection<String>): Set<String> {
        val tags = keys.mapNotNull { keyTags[it] }
        if (tags.isEmpty()) return emptySet()
        val stillOnScreen = withdrawal.withdraw(tags)
        return keys.filterTo(linkedSetOf()) { keyTags[it] !in stillOnScreen }.also { trackIfOutstanding() }
    }

    /** False when the delivery was refused, or when a group withdrawal is running right now. */
    fun publish(item: PendingMessageNotification): Boolean {
        if (!isPublishing() || clearsInFlight.get() > 0) return false
        val tag = deliveryTag(item.key)
        val outcome = runCatching {
            center.show(item.title, item.text, "account:$accountId:${item.route}", tag, group)
        }
        if (!isPublishing() || clearsInFlight.get() > 0) {
            withdrawal.remember(listOf(tag))
            trackIfOutstanding()
            return false
        }
        outcome.onFailure { report("通知中心提交失败 tag=${item.key}", it) }
        if (outcome.isSuccess) keyTags[item.key] = tag
        return outcome.isSuccess
    }

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

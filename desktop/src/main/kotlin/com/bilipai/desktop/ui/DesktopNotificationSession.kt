package com.bilipai.desktop.ui

import com.android.purebilibili.feature.message.notification.PendingMessageNotification
import com.bilipai.desktop.integration.DesktopNotificationCenter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One account's notification lifetime.
 *
 * The exits are not the same:
 * - [detach] — the window is going away. Delivered notifications stay on screen and stay clickable.
 * - [pause] — notifications were turned off. The group is withdrawn, but the account stays current:
 *   [resume] turns publishing back on without waiting for a new port.
 * - [retire] — the account itself is gone (switch, logout). Publishing never comes back here.
 *
 * Two rules keep a late cleanup away from what the user can still see:
 * - Every delivery is tagged with the generation it belongs to, so retrying the withdrawal of an old
 *   notification can never match a newer one that reuses the same message key (reply/like are stable).
 * - While a group withdrawal is actually running, nothing is published: a started system operation
 *   cannot be cancelled, so the only safe ordering is to let it finish first.
 *
 * Only the explicit [pause]/[retire] calls talk to the surface on the caller's thread; [resume]
 * flips state and hands the remaining work to [DesktopNotificationCleanup].
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
    /** Message key -> the generation whose toast currently carries it. */
    private val keyGeneration = ConcurrentHashMap<String, Int>()
    private val accountRetired = AtomicBoolean()
    private val groupClearPending = AtomicBoolean()
    private val clearInFlight = AtomicBoolean()
    private val clearAttempts = AtomicInteger()
    private val generation = AtomicInteger()

    @Volatile private var publishing = true

    fun accountId(): Long? = accountId

    fun isPublishing(): Boolean = publishing && !accountRetired.get()

    /** True while anything the surface refused — a toast or the group — is still outstanding. */
    fun isCleanupPending(): Boolean =
        groupClearPending.get() || clearInFlight.get() || withdrawal.pendingKeys().isNotEmpty()

    fun clearAttemptCount(): Int = clearAttempts.get()

    /** Message keys whose withdrawal the surface has not confirmed. */
    fun pendingWithdrawals(): Set<String> = withdrawal.pendingKeys().mapTo(linkedSetOf()) { keyOf(it) }

    fun detach() { publishing = false }

    /** Notifications turned off. Resumable through [resume]. */
    fun pause(): Boolean {
        publishing = false
        groupClearPending.set(true)
        return retryCleanup().also { trackIfOutstanding() }
    }

    /**
     * Notifications turned back on for the same account. The next generation gives every new toast a
     * distinct tag, and a group withdrawal that never completed is abandoned: old toasts are removed
     * one by one instead. Called from the UI thread, so it must not touch the surface.
     */
    fun resume() {
        if (accountRetired.get()) return
        groupClearPending.set(false)
        generation.incrementAndGet()
        publishing = true
        withdrawal.remember(keyGeneration.entries.map { tagFor(it.key, it.value) })
        trackIfOutstanding()
    }

    /** The account is gone: withdraw the group and never publish again. */
    fun retire(): Boolean {
        publishing = false
        accountRetired.set(true)
        groupClearPending.set(true)
        return retryCleanup().also { trackIfOutstanding() }
    }

    /** One attempt at everything outstanding. False while something is still on screen. */
    fun retryCleanup(): Boolean {
        val tags = withdrawal.pendingKeys()
        if (tags.isNotEmpty()) withdrawal.withdraw(tags)
        if (groupClearPending.get()) {
            val generationAtStart = generation.get()
            clearAttempts.incrementAndGet()
            clearInFlight.set(true)
            val cleared = try {
                runCatching { center.clear(group) }.onFailure {
                    report("通知中心整组清理失败 group=$group", it)
                }.isSuccess
            } finally {
                clearInFlight.set(false)
            }
            // A resume during the withdrawal already decided to abandon it; do not resurrect the retry.
            if (generation.get() == generationAtStart) {
                groupClearPending.set(!cleared)
                if (cleared) keyGeneration.clear()
            }
        }
        return !isCleanupPending()
    }

    /** False when the delivery was refused, or when a group withdrawal is running right now. */
    fun publish(item: PendingMessageNotification): Boolean {
        if (!isPublishing() || clearInFlight.get()) return false
        val current = generation.get()
        val tag = tagFor(item.key, current)
        val outcome = runCatching {
            center.show(item.title, item.text, "account:$accountId:${item.route}", tag, group)
        }
        if (!isPublishing() || clearInFlight.get()) {
            withdrawal.remember(listOf(tag))
            trackIfOutstanding()
            return false
        }
        outcome.onFailure { report("通知中心提交失败 tag=${item.key}", it) }
        if (outcome.isSuccess) keyGeneration[item.key] = current
        return outcome.isSuccess
    }

    /** The subset of [keys] the surface confirmed it removed, in message-key terms. */
    fun withdrawn(keys: Collection<String>): Set<String> {
        val tags = keys.associateWith { currentTagOf(it) }
        val stillOnScreen = withdrawal.withdraw(tags.values)
        return keys.filterTo(linkedSetOf()) { tags[it] !in stillOnScreen }.also { trackIfOutstanding() }
    }

    private fun currentTagOf(key: String): String = tagFor(key, keyGeneration[key] ?: generation.get())

    private fun tagFor(key: String, generation: Int): String = "$key#$generation"

    private fun keyOf(tag: String): String = tag.substringBeforeLast('#')

    private fun trackIfOutstanding() {
        if (isCleanupPending()) DesktopNotificationCleanup.track(this)
    }
}

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
 * - [detach] — the window is going away. Delivered notifications stay on screen and stay clickable;
 *   the protocol handler is what makes them useful after the process is gone.
 * - [pause] — notifications were turned off. The group is withdrawn, but the account stays current:
 *   [resume] turns publishing back on without waiting for a new port.
 * - [retire] — the account itself is gone (switch, logout). Publishing never comes back here.
 *
 * A refused withdrawal, of one toast or of the whole group, is remembered and retried by
 * [DesktopNotificationCleanup] until the surface confirms it.
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
    private val publishedTags = ConcurrentHashMap.newKeySet<String>()
    private val accountRetired = AtomicBoolean()
    private val groupClearPending = AtomicBoolean()
    private val clearAttempts = AtomicInteger()

    @Volatile private var publishing = true

    fun accountId(): Long? = accountId

    /** True while this session may still publish into its account. */
    fun isPublishing(): Boolean = publishing && !accountRetired.get()

    /** True while anything the surface refused — a toast or the group — is still outstanding. */
    fun isCleanupPending(): Boolean = groupClearPending.get() || withdrawal.pendingKeys().isNotEmpty()

    fun clearAttemptCount(): Int = clearAttempts.get()

    fun pendingWithdrawals(): Set<String> = withdrawal.pendingKeys()

    /** Normal shutdown: stop publishing, leave what is already delivered where the user can click it. */
    fun detach() { publishing = false }

    /** Notifications turned off. Resumable through [resume]. */
    fun pause(): Boolean {
        publishing = false
        return withdrawGroup()
    }

    /**
     * Notifications turned back on for the same account. A group withdrawal that never completed is
     * given up here: finishing it later would delete the notifications published after resuming.
     * The toasts this session knows about are withdrawn one by one instead.
     */
    fun resume() {
        if (accountRetired.get()) return
        if (groupClearPending.getAndSet(false)) withdrawal.withdraw(publishedTags.toList())
        publishing = true
        trackIfOutstanding()
    }

    /** The account is gone: withdraw the group and never publish again. */
    fun retire(): Boolean {
        publishing = false
        accountRetired.set(true)
        return withdrawGroup()
    }

    /** One attempt at everything outstanding. False while something is still on screen. */
    fun retryCleanup(): Boolean {
        val keys = withdrawal.pendingKeys()
        if (keys.isNotEmpty()) withdrawal.withdraw(keys)
        if (groupClearPending.get()) {
            clearAttempts.incrementAndGet()
            val cleared = runCatching { center.clear(group) }.onFailure {
                report("通知中心整组清理失败 group=$group", it)
            }.isSuccess
            if (cleared) {
                groupClearPending.set(false)
                publishedTags.clear()
            }
        }
        return !isCleanupPending()
    }

    /** False when the delivery was refused or the session stopped publishing while it was in flight. */
    fun publish(item: PendingMessageNotification): Boolean {
        if (!isPublishing()) return false
        val outcome = runCatching {
            center.show(item.title, item.text, "account:$accountId:${item.route}", item.key, group)
        }
        if (!isPublishing()) {
            // Landed after the session stopped publishing: take it back, and let the driver finish
            // if the surface refuses that too.
            withdrawal.withdraw(listOf(item.key))
            trackIfOutstanding()
            return false
        }
        outcome.onFailure { report("通知中心提交失败 tag=${item.key}", it) }
        if (outcome.isSuccess) publishedTags += item.key
        return outcome.isSuccess
    }

    fun withdraw(keys: Collection<String>): Set<String> =
        withdrawal.withdraw(keys).also { trackIfOutstanding() }

    /** The subset of [keys] the surface confirmed it removed. */
    fun withdrawn(keys: Collection<String>): Set<String> =
        (keys.toSet() - withdrawal.withdraw(keys)).also { trackIfOutstanding() }

    private fun withdrawGroup(): Boolean {
        groupClearPending.set(true)
        return retryCleanup().also { trackIfOutstanding() }
    }

    /** Anything the surface still shows is handed to the driver, whatever path noticed it. */
    private fun trackIfOutstanding() {
        if (isCleanupPending()) DesktopNotificationCleanup.track(this)
    }
}

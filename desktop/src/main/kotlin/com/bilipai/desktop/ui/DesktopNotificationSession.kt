package com.bilipai.desktop.ui

import com.android.purebilibili.feature.message.notification.PendingMessageNotification
import com.bilipai.desktop.integration.DesktopNotificationCenter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One account's notification lifetime.
 *
 * Two exits exist and they are not the same:
 * - [detach] — the window is going away. Delivered notifications stay on screen and stay clickable;
 *   the protocol handler is what makes them useful after the process is gone.
 * - [retire] — the account is no longer current (switch, logout, notifications turned off). The whole
 *   group is withdrawn, and a refusal is remembered so an owner-held retry can finish the job.
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
    private val retired = AtomicBoolean()
    private val cleanupPending = AtomicBoolean()
    private val clearAttempts = AtomicInteger()

    /** True once the session must not publish any more, whether it detached or retired. */
    fun isRetired(): Boolean = retired.get()

    /** True while the group withdrawal has not been confirmed by the surface. */
    fun isCleanupPending(): Boolean = cleanupPending.get()

    fun clearAttemptCount(): Int = clearAttempts.get()

    /** Normal shutdown: stop publishing, leave what is already delivered where the user can click it. */
    fun detach() { retired.set(true) }

    /** Account switch / logout / notifications off. Returns whether the surface confirmed the removal. */
    fun retire(): Boolean {
        retired.set(true)
        return retryCleanup()
    }

    /** One attempt at everything still outstanding: every refused toast, then the group. */
    fun retryCleanup(): Boolean {
        val keys = withdrawal.pendingKeys()
        if (keys.isNotEmpty()) withdrawal.withdraw(keys)
        if (!cleanupPending.get() && clearAttempts.get() > 0) return false
        cleanupPending.set(true)
        clearAttempts.incrementAndGet()
        val cleared = runCatching { center.clear(group) }.onFailure {
            report("通知中心整组清理失败 group=$group", it)
        }.isSuccess
        if (cleared) cleanupPending.set(false)
        return cleared
    }

    /** False when the delivery was refused or the session stopped being current while it was in flight. */
    fun publish(item: PendingMessageNotification): Boolean {
        if (retired.get()) return false
        val outcome = runCatching {
            center.show(item.title, item.text, "account:$accountId:${item.route}", item.key, group)
        }
        if (retired.get()) {
            // Delivered into an account that is no longer current: take it back, and remember a refusal.
            withdrawal.withdraw(listOf(item.key))
            return false
        }
        outcome.onFailure { report("通知中心提交失败 tag=${item.key}", it) }
        return outcome.isSuccess
    }

    fun withdraw(keys: Collection<String>): Set<String> = withdrawal.withdraw(keys)

    /** The subset of [keys] the surface confirmed it removed. */
    fun withdrawn(keys: Collection<String>): Set<String> = keys.toSet() - withdrawal.withdraw(keys)

    fun pendingWithdrawals(): Set<String> = withdrawal.pendingKeys()
}

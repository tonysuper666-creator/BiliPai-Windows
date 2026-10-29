package com.bilipai.desktop.ui

import com.android.purebilibili.feature.message.notification.PendingMessageNotification
import com.bilipai.desktop.integration.DesktopNotificationCenter

/**
 * One account's notification lifetime: the group it publishes into, the withdrawals the surface
 * refused, and the moment it stops being the current account.
 *
 * Retirement is one way: after it, nothing is published, and a delivery that was already in flight is
 * taken back so an old account cannot leave a toast behind after the switch.
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
    private val retired = java.util.concurrent.atomic.AtomicBoolean()

    fun isRetired(): Boolean = retired.get()

    /** False when the delivery was refused or the session was retired while it was in flight. */
    fun publish(item: PendingMessageNotification): Boolean {
        if (retired.get()) return false
        val outcome = runCatching {
            center.show(item.title, item.text, "account:$accountId:${item.route}", item.key, group)
        }
        if (retired.get()) {
            // Published into an account that is no longer current: take it back immediately.
            withdrawal.withdraw(listOf(item.key))
            return false
        }
        outcome.onFailure { report("通知中心提交失败 tag=${item.key}", it) }
        return outcome.isSuccess
    }

    fun withdraw(keys: Collection<String>): Set<String> = withdrawal.withdraw(keys)

    fun pendingWithdrawals(): Set<String> = withdrawal.pendingKeys()

    /** Withdraws everything a single account published, whether or not this process published it. */
    fun clearAll() {
        runCatching { center.clear(group) }.onFailure { report("通知中心整组清理失败 group=$group", it) }
    }

    fun retire() {
        if (!retired.compareAndSet(false, true)) return
        clearAll()
    }
}

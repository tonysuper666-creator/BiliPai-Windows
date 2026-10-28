package com.bilipai.desktop.ui

/**
 * Withdrawal bookkeeping for delivered notifications.
 *
 * The system surface can refuse or time out. A refused withdrawal must not look successful: the
 * notification is still on screen, and the upstream checker keeps cancelling by key only while the
 * key is still active. So a key stays here until the surface confirms removal, and every later
 * attempt retries it before anything else.
 */
internal class DesktopNotificationWithdrawal(
    private val withdraw: (tag: String, group: String) -> Unit,
    private val report: (message: String, failure: Throwable) -> Unit = { message, failure ->
        System.err.println("$message: ${failure.javaClass.simpleName}: ${failure.message}")
    },
) {
    private val pending = LinkedHashMap<String, Int>() // key -> attempts
    private var group: String? = null

    fun bind(group: String) { this.group = group }

    /** Keys the surface refused so far; they stay active upstream until a later attempt succeeds. */
    fun pendingKeys(): Set<String> = pending.keys.toSet()

    /**
     * Tries every key that still needs withdrawal, including the ones requested now.
     * Returns the keys that are still on screen, i.e. the ones that must stay active.
     */
    fun withdraw(keys: Collection<String>): Set<String> {
        val target = group ?: return keys.toSet()
        for (key in keys) pending.putIfAbsent(key, 0)
        val failed = LinkedHashSet<String>()
        for (key in pending.keys.toList()) {
            pending[key] = (pending[key] ?: 0) + 1
            val outcome = runCatching { withdraw(key, target) }
            if (outcome.isSuccess) pending.remove(key)
            else { failed += key; report("通知撤销失败，稍后重试 tag=$key", outcome.exceptionOrNull()!!) }
        }
        return failed
    }

    /** Attempts left for a key, for diagnostics and tests. */
    fun attempts(key: String): Int = pending[key] ?: 0
}

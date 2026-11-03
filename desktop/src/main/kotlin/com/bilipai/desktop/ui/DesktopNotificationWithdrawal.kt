package com.bilipai.desktop.ui

/**
 * Withdrawal bookkeeping for delivered notifications.
 *
 * The system surface can refuse or time out. A refused withdrawal must not look successful: the
 * notification is still on screen, and the upstream checker keeps cancelling by key only while the
 * key is still active. So a key stays here until the surface confirms removal, and every later
 * attempt retries it before anything else.
 *
 * The table is touched from the UI thread (resume), the adapter's IO thread (cancel) and the cleanup
 * driver, so every read and write of it happens on [monitor]. The surface call itself stays outside
 * that monitor: it can block on WinRT, and on the UI thread that would freeze the window.
 */
internal class DesktopNotificationWithdrawal(
    private val withdraw: (tag: String, group: String) -> Unit,
    private val report: (message: String, failure: Throwable) -> Unit = { message, failure ->
        System.err.println("$message: ${failure.javaClass.simpleName}: ${failure.message}")
    },
) {
    private val monitor = Any()
    private val pending = LinkedHashMap<String, Int>() // tag -> attempts
    private var group: String? = null

    fun bind(group: String) { synchronized(monitor) { this.group = group } }

    /** Keys the surface refused so far; they stay active upstream until a later attempt succeeds. */
    fun pendingKeys(): Set<String> = synchronized(monitor) { pending.keys.toSet() }

    /** Remembers tags that must be withdrawn later without talking to the surface now (no blocking IO). */
    fun remember(tags: Collection<String>) {
        synchronized(monitor) { tags.forEach { pending.putIfAbsent(it, 0) } }
    }

    /**
     * Tries every key that still needs withdrawal, including the ones requested now.
     * Returns the keys that are still on screen, i.e. the ones that must stay active.
     */
    fun withdraw(keys: Collection<String>): Set<String> {
        val target = synchronized(monitor) {
            val bound = group ?: return keys.toSet()
            keys.forEach { pending.putIfAbsent(it, 0) }
            bound
        }
        val snapshot = synchronized(monitor) { pending.keys.toList() }
        val failed = LinkedHashSet<String>()
        for (key in snapshot) {
            synchronized(monitor) { pending[key] = (pending[key] ?: 0) + 1 }
            val outcome = runCatching { withdraw(key, target) }
            if (outcome.isSuccess) {
                synchronized(monitor) { pending.remove(key) }
            } else {
                failed += key
                report("通知撤销失败，稍后重试 tag=$key", outcome.exceptionOrNull()!!)
            }
        }
        return failed
    }

    /** Attempts left for a key, for diagnostics and tests. */
    fun attempts(key: String): Int = synchronized(monitor) { pending[key] ?: 0 }
}

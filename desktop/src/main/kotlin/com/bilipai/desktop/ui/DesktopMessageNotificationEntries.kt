package com.bilipai.desktop.ui

import com.android.purebilibili.feature.message.notification.PendingMessageNotification

/** Only the bounded current Windows menu, not a second business/baseline store. EDT-owned.
 * A Windows balloon cannot be withdrawn once it was submitted, so this menu is the only surface
 * where "clear on read" can actually take effect. */
internal class DesktopMessageNotificationEntries(private val capacity: Int = 200) {
    init { require(capacity in 1..200) }
    private val items = linkedMapOf<String, PendingMessageNotification>()
    fun keys(): Set<String> = items.keys.toSet()
    fun snapshot(): List<PendingMessageNotification> = items.values.toList()

    /** [pendingCounts] is the number of still unread items per group in the committed baseline
     * (null when unknown). It decides whether a group summary entry survives: like the Android
     * group summary, it ends when nothing it summarises is pending or visible. */
    fun apply(pending: List<PendingMessageNotification>, cancelled: Set<String>,
        pendingCounts: Map<String, Int>? = null) {
        cancelled.forEach { items.remove(it) }
        for (item in pending) {
            // A replacement becomes the most recent item in this presentation.
            items.remove(item.key)
            items[item.key] = item
        }
        if (pendingCounts != null) {
            val affected = linkedSetOf<String>()
            pending.mapTo(affected) { it.group }
            cancelled.mapTo(affected) { cancelledGroup(it) }
            for (group in affected) {
                val summary = "$group:summary"
                if (summary !in items) continue
                val children = items.values.count { it.group == group && !it.isGroupSummary }
                if (children == 0 && (pendingCounts[group] ?: 0) == 0) items.remove(summary)
            }
        }
        while (items.size > capacity) items.remove(items.keys.first())
    }
    fun clear() { items.clear() }

    private fun cancelledGroup(key: String): String = when {
        key.startsWith("dynamic:") -> "dynamic"
        key.startsWith("dm:") -> "dm"
        else -> "interactions"
    }
}

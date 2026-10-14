package com.bilipai.desktop.ui

import com.android.purebilibili.feature.message.notification.PendingMessageNotification

/** Only the bounded current Windows menu, not a second business/baseline store. EDT-owned. */
internal class DesktopMessageNotificationEntries(private val capacity: Int = 200) {
    init { require(capacity in 1..200) }
    private val items = linkedMapOf<String, PendingMessageNotification>()
    fun keys(): Set<String> = items.keys.toSet()
    fun snapshot(): List<PendingMessageNotification> = items.values.toList()
    fun apply(pending: List<PendingMessageNotification>, cancelled: Set<String>) {
        cancelled.forEach { items.remove(it) }
        for (item in pending) {
            // A replacement becomes the most recent item in this presentation.
            items.remove(item.key)
            items[item.key] = item
        }
        while (items.size > capacity) items.remove(items.keys.first())
    }
    fun clear() { items.clear() }
}

package com.bilipai.desktop.ui

import com.android.purebilibili.feature.message.notification.PendingMessageNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopMessageNotificationEntriesTest {
    private fun item(key: String, text: String = key) = PendingMessageNotification(key, 1, "inbox", key, text, "interactions")

    @Test fun `server read cancellation removes the exact menu key and keeps unrelated routes`() {
        val entries = DesktopMessageNotificationEntries()
        entries.apply(listOf(item("reply"), item("dm:2_1")), emptySet())
        entries.apply(emptyList(), setOf("reply"))
        assertEquals(setOf("dm:2_1"), entries.keys())
    }

    @Test fun `a new notice replaces the same key after old notice cancellation`() {
        val entries = DesktopMessageNotificationEntries()
        entries.apply(listOf(item("reply", "old")), emptySet())
        entries.apply(listOf(item("reply", "new")), setOf("reply"))
        assertEquals(listOf("new"), entries.snapshot().map { it.text })
    }

    @Test fun `presentation has a bound while updates keep the latest entries`() {
        val entries = DesktopMessageNotificationEntries(2)
        entries.apply(listOf(item("old"), item("middle")), emptySet())
        entries.apply(listOf(item("new")), emptySet())
        assertEquals(listOf("middle", "new"), entries.snapshot().map { it.key })
    }

    @Test fun `root cleanup removes every presentation key`() {
        val entries = DesktopMessageNotificationEntries()
        entries.apply(listOf(item("reply"), item("dynamic:123")), emptySet())
        entries.clear()
        assertTrue(entries.keys().isEmpty()); assertTrue(entries.snapshot().isEmpty())
    }
}

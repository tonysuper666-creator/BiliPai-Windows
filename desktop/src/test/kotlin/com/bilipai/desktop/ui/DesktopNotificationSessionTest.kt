package com.bilipai.desktop.ui

import com.android.purebilibili.feature.message.notification.PendingMessageNotification
import com.bilipai.desktop.integration.DesktopNotificationCenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopNotificationSessionTest {
    private class RecordingCenter : DesktopNotificationCenter {
        val shown = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val cleared = mutableListOf<String>()
        var failRemove = false
        var failClear = false
        var onShow: ((String) -> Unit)? = null

        override fun show(title: String, text: String, route: String, tag: String, group: String) {
            shown += "$tag@$group"
            onShow?.invoke(tag)
        }
        override fun remove(tag: String, group: String) {
            if (failRemove) throw IllegalStateException("history timeout")
            removed += "$tag@$group"
        }
        override fun clear(group: String) {
            if (failClear) throw IllegalStateException("removegroup timeout")
            cleared += group
        }
    }

    private fun item(key: String, route: String = "inbox") =
        PendingMessageNotification(key = key, notificationId = 1, route = route, title = "t", text = "x", group = "reply")

    @Test fun retiringClearsTheGroupOfTheAccountItWasBuiltFor() {
        val center = RecordingCenter()
        val session = DesktopNotificationSession(7L, center)
        session.retire()
        assertEquals(listOf(session.group), center.cleared)
        assertTrue(session.group.endsWith("-7"))
        // A second retirement is not a second cleanup.
        session.retire()
        assertEquals(1, center.cleared.size)
    }

    @Test fun aFreshProcessClearsEvenThoughItPublishedNothing() {
        val center = RecordingCenter()
        DesktopNotificationSession(9L, center).clearAll()
        assertEquals(listOf("messages-9"), center.cleared)
        assertTrue(center.shown.isEmpty())
    }

    @Test fun nothingIsPublishedAfterRetirement() {
        val center = RecordingCenter()
        val session = DesktopNotificationSession(7L, center)
        session.retire()
        assertFalse(session.publish(item("reply-1")))
        assertTrue(center.shown.isEmpty())
    }

    @Test fun aToastDeliveredIntoARetiredSessionIsTakenBack() {
        val center = RecordingCenter()
        val session = DesktopNotificationSession(7L, center)
        // The account switch lands while the toast is already being delivered.
        center.onShow = { session.retire() }
        assertFalse(session.publish(item("reply-1")))
        assertEquals(listOf("reply-1@messages-7"), center.removed)
    }

    @Test fun refusedWithdrawalStaysPendingAndIsRetried() {
        val center = RecordingCenter().apply { failRemove = true }
        val session = DesktopNotificationSession(7L, center)
        assertEquals(setOf("reply-1"), session.withdraw(setOf("reply-1")))
        assertEquals(setOf("reply-1"), session.pendingWithdrawals())

        center.failRemove = false
        assertEquals(emptySet(), session.withdraw(emptySet()))
        assertEquals(emptySet(), session.pendingWithdrawals())
        assertEquals(listOf("reply-1@messages-7"), center.removed)
    }
}

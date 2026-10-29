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
        val visible = linkedSetOf<String>()
        var failRemove = false
        var failClear = false
        var onShow: ((String) -> Unit)? = null

        override fun show(title: String, text: String, route: String, tag: String, group: String) {
            shown += "$tag@$group"
            // The toast lands on screen after the delivery call returns, which is exactly how a
            // withdrawal racing with it can miss it.
            onShow?.invoke(tag)
            visible += tag
        }
        override fun remove(tag: String, group: String) {
            if (failRemove) throw IllegalStateException("history timeout")
            removed += "$tag@$group"
            visible -= tag
        }
        override fun clear(group: String) {
            if (failClear) throw IllegalStateException("removegroup timeout")
            cleared += group
            visible.clear()
        }
    }

    private fun item(key: String, route: String = "inbox") =
        PendingMessageNotification(key = key, notificationId = 1, route = route, title = "t", text = "x", group = "reply")

    @Test fun aNormalExitKeepsDeliveredNotificationsClickable() {
        val center = RecordingCenter()
        val session = DesktopNotificationSession(7L, center)
        assertTrue(session.publish(item("reply-1")))
        session.detach()

        assertTrue(center.cleared.isEmpty(), "a normal exit must not withdraw the account group")
        assertEquals(setOf("reply-1"), center.visible)
        assertFalse(session.publish(item("reply-2")), "a detached session publishes nothing")
    }

    @Test fun turningNotificationsBackOnResumesPublishingInTheSameSession() {
        val center = RecordingCenter()
        val session = DesktopNotificationSession(7L, center)
        assertTrue(session.pause())
        assertEquals(listOf("messages-7"), center.cleared)
        assertFalse(session.publish(item("reply-1")), "paused means paused")

        session.resume()
        assertTrue(session.publish(item("reply-2")), "re-enabling must publish again without a new port")
        assertEquals(setOf("reply-2"), center.visible)
    }

    @Test fun aGroupCleanupThatNeverFinishedCannotDeleteWhatWasPublishedAfterResuming() {
        val center = RecordingCenter().apply { failClear = true }
        val session = DesktopNotificationSession(7L, center)
        session.publish(item("reply-1"))
        assertFalse(session.pause(), "the surface refused the group withdrawal")
        assertTrue(session.isCleanupPending())

        center.failClear = false
        session.resume()
        assertTrue(session.publish(item("reply-2")))

        // The abandoned group withdrawal must not run now: it would take reply-2 with it.
        DesktopNotificationCleanup.retryOutstanding()
        assertEquals(setOf("reply-2"), center.visible)
        assertTrue(center.cleared.isEmpty())
    }

    @Test fun aLateToastWhoseWithdrawalFailsIsFinishedByTheDriver() {
        val center = RecordingCenter().apply { failRemove = true }
        val session = DesktopNotificationSession(7L, center)
        center.onShow = { session.retire() }
        assertFalse(session.publish(item("reply-1")))
        assertEquals(setOf("reply-1"), session.pendingWithdrawals())

        // No manual session call: the driver alone must pick the pending key up.
        center.failRemove = false
        assertEquals(0, DesktopNotificationCleanup.retryOutstanding())
        assertEquals(emptySet(), session.pendingWithdrawals())
        assertTrue(center.visible.isEmpty())
    }

    @Test fun aRefusedGroupCleanupIsRememberedAndFinishesOnRetry() {
        val center = RecordingCenter().apply { failClear = true }
        val session = DesktopNotificationSession(7L, center)
        session.publish(item("reply-1"))

        assertFalse(session.retire())
        assertTrue(session.isCleanupPending())
        assertEquals(1, session.clearAttemptCount())
        assertEquals(setOf("reply-1"), center.visible)

        center.failClear = false
        assertEquals(0, DesktopNotificationCleanup.retryOutstanding())
        assertFalse(session.isCleanupPending())
        assertEquals(2, session.clearAttemptCount())
        assertTrue(center.visible.isEmpty())
        assertEquals(listOf("messages-7"), center.cleared)
    }

    @Test fun aRoundWithNoNewWorkStillRetriesTheKeysItCouldNotWithdraw() {
        val center = RecordingCenter().apply { failRemove = true }
        val session = DesktopNotificationSession(7L, center)
        assertEquals(setOf("reply-1"), session.withdraw(setOf("reply-1")))

        // Nothing new to cancel: the adapter passes the union of this round and the pending keys.
        assertEquals(emptySet(), session.withdrawn(emptySet()))

        center.failRemove = false
        assertEquals(setOf("reply-1"), session.withdrawn(session.pendingWithdrawals()))
        assertEquals(emptySet(), session.pendingWithdrawals())
        assertEquals(listOf("reply-1@messages-7"), center.removed)
    }

    @Test fun retiringClearsTheGroupOfTheAccountItWasBuiltFor() {
        val center = RecordingCenter()
        val session = DesktopNotificationSession(7L, center)
        assertTrue(session.retire())
        assertEquals(listOf("messages-7"), center.cleared)
        assertEquals(1, session.clearAttemptCount())
        assertFalse(session.isCleanupPending())
        assertFalse(session.isPublishing())
    }

    @Test fun aFreshProcessClearsEvenThoughItPublishedNothing() {
        val center = RecordingCenter()
        DesktopNotificationSession(9L, center).retire()
        assertEquals(listOf("messages-9"), center.cleared)
        assertTrue(center.shown.isEmpty())
    }

    @Test fun nothingIsPublishedAfterRetirementAndResumeCannotUndoIt() {
        val center = RecordingCenter()
        val session = DesktopNotificationSession(7L, center)
        session.retire()
        session.resume()
        assertFalse(session.publish(item("reply-1")))
        assertTrue(center.shown.isEmpty())
    }

    @Test fun aToastDeliveredIntoARetiredSessionIsTakenBack() {
        val center = RecordingCenter()
        val session = DesktopNotificationSession(7L, center)
        center.onShow = { session.retire() }
        assertFalse(session.publish(item("reply-1")))
        assertEquals(listOf("reply-1@messages-7"), center.removed)
    }
}

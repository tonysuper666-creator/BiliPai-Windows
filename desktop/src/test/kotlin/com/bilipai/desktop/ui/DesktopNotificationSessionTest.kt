package com.bilipai.desktop.ui

import com.android.purebilibili.feature.message.notification.PendingMessageNotification
import com.bilipai.desktop.integration.DesktopNotificationCenter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A recording surface that can refuse, block, or land a toast after the call returns. */
internal class RecordingNotificationCenter : DesktopNotificationCenter {
    val shown = mutableListOf<String>()
    val removed = mutableListOf<String>()
    val cleared = mutableListOf<String>()
    val visible = linkedSetOf<String>()
    @Volatile var failRemove = false
    @Volatile var failClear = false
    @Volatile var onShow: ((String) -> Unit)? = null
    private val clearEntered = CountDownLatch(1)
    private val clearGate = CountDownLatch(1)
    @Volatile private var blockClear = false

    fun enterClearWithBlock(): CountDownLatch { blockClear = true; return clearEntered }

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
        if (blockClear) {
            clearEntered.countDown()
            clearGate.await(10, TimeUnit.SECONDS)
        }
        if (failClear) throw IllegalStateException("removegroup timeout")
        cleared += group
        visible.clear()
    }

    fun releaseClear() { clearGate.countDown() }
}

class DesktopNotificationSessionTest {
    private fun item(key: String, route: String = "inbox") =
        PendingMessageNotification(key = key, notificationId = 1, route = route, title = "t", text = "x", group = "reply")

    private fun session(center: RecordingNotificationCenter, account: Long = 7L) =
        DesktopNotificationSession(account, center)

    @Test fun aNormalExitKeepsDeliveredNotificationsClickable() {
        val center = RecordingNotificationCenter()
        val session = session(center)
        assertTrue(session.publish(item("reply-1")))
        session.detach()

        assertTrue(center.cleared.isEmpty(), "a normal exit must not withdraw the account group")
        assertEquals(setOf("reply-1#0"), center.visible)
        assertFalse(session.publish(item("reply-2")), "a detached session publishes nothing")
    }

    @Test fun turningNotificationsBackOnResumesPublishingInTheSameSession() {
        val center = RecordingNotificationCenter()
        val session = session(center)
        assertTrue(session.pause())
        assertEquals(listOf("messages-7"), center.cleared)
        assertFalse(session.publish(item("reply-1")), "paused means paused")

        session.resume()
        assertTrue(session.publish(item("reply-2")), "re-enabling must publish again without a new port")
        assertEquals(setOf("reply-2#1"), center.visible)
    }

    @Test fun aWithdrawalThatIsAlreadyRunningBlocksPublishingUntilItFinishes() {
        val center = RecordingNotificationCenter()
        val session = session(center)
        assertTrue(session.publish(item("reply-1")))
        val entered = center.enterClearWithBlock()
        val clearing = thread { session.pause() }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the group withdrawal must actually be running")

        // Re-enabling during the running withdrawal must not let a toast land inside its range.
        session.resume()
        assertFalse(session.publish(item("reply-2")), "a started group withdrawal deletes whatever appears in it")

        center.releaseClear()
        clearing.join(5_000)
        assertTrue(session.publish(item("reply-2")), "publishing resumes once the withdrawal is over")
        assertEquals(setOf("reply-2#1"), center.visible)
    }

    @Test fun retryingAnOldWithdrawalCannotDeleteTheNewToastWithTheSameKey() {
        val center = RecordingNotificationCenter().apply { failRemove = true }
        val session = session(center)
        assertTrue(session.publish(item("reply-1")))
        assertEquals(emptySet(), session.withdrawn(setOf("reply-1")), "the surface refused the first removal")

        session.resume()
        assertTrue(session.publish(item("reply-1")), "the same message key can be delivered again")

        center.failRemove = false
        DesktopNotificationCleanup.retryOutstanding()
        assertEquals(setOf("reply-1#1"), center.visible, "the retry must hit the old tag, not the new toast")
        assertEquals(listOf("reply-1#0@messages-7"), center.removed)
    }

    @Test fun aLateToastWhoseWithdrawalFailsIsFinishedByTheDriver() {
        val center = RecordingNotificationCenter().apply { failRemove = true }
        val session = session(center)
        center.onShow = { session.retire() }
        assertFalse(session.publish(item("reply-1")))
        assertEquals(setOf("reply-1"), session.pendingWithdrawals())

        center.failRemove = false
        DesktopNotificationCleanup.retryOutstanding()
        assertEquals(emptySet(), session.pendingWithdrawals())
        assertTrue(center.visible.isEmpty())
    }

    @Test fun aRefusedGroupCleanupIsRememberedAndFinishesOnRetry() {
        val center = RecordingNotificationCenter().apply { failClear = true }
        val session = session(center)
        session.publish(item("reply-1"))

        assertFalse(session.retire())
        assertTrue(session.isCleanupPending())
        assertEquals(1, session.clearAttemptCount())
        assertEquals(setOf("reply-1#0"), center.visible)

        center.failClear = false
        DesktopNotificationCleanup.retryOutstanding()
        assertFalse(session.isCleanupPending())
        assertEquals(2, session.clearAttemptCount())
        assertTrue(center.visible.isEmpty())
        assertEquals(listOf("messages-7"), center.cleared)
    }

    @Test fun aRoundWithNoNewWorkStillRetriesTheKeysItCouldNotWithdraw() {
        val center = RecordingNotificationCenter().apply { failRemove = true }
        val session = session(center)
        assertEquals(emptySet(), session.withdrawn(setOf("reply-1")))
        assertEquals(setOf("reply-1"), session.pendingWithdrawals())

        center.failRemove = false
        assertEquals(setOf("reply-1"), session.withdrawn(session.pendingWithdrawals()))
        assertEquals(emptySet(), session.pendingWithdrawals())
        assertEquals(listOf("reply-1#0@messages-7"), center.removed)
    }

    @Test fun retiringClearsTheGroupOfTheAccountItWasBuiltFor() {
        val center = RecordingNotificationCenter()
        val session = session(center)
        assertTrue(session.retire())
        assertEquals(listOf("messages-7"), center.cleared)
        assertEquals(1, session.clearAttemptCount())
        assertFalse(session.isCleanupPending())
        assertFalse(session.isPublishing())
    }

    @Test fun aFreshProcessClearsEvenThoughItPublishedNothing() {
        val center = RecordingNotificationCenter()
        session(center, 9L).retire()
        assertEquals(listOf("messages-9"), center.cleared)
        assertTrue(center.shown.isEmpty())
    }

    @Test fun nothingIsPublishedAfterRetirementAndResumeCannotUndoIt() {
        val center = RecordingNotificationCenter()
        val session = session(center)
        session.retire()
        session.resume()
        assertFalse(session.publish(item("reply-1")))
        assertTrue(center.shown.isEmpty())
    }

    @Test fun aToastDeliveredIntoARetiredSessionIsTakenBackByTheDriver() {
        val center = RecordingNotificationCenter()
        val session = session(center)
        center.onShow = { session.retire() }
        assertFalse(session.publish(item("reply-1")))
        // publish never blocks on the surface: the take-back is handed to the driver.
        assertEquals(setOf("reply-1"), session.pendingWithdrawals())

        DesktopNotificationCleanup.retryOutstanding()
        assertEquals(emptySet(), session.pendingWithdrawals())
        assertEquals(listOf("reply-1#0@messages-7"), center.removed)
    }
}

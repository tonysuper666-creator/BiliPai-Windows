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

/** A recording surface that can refuse, block once, or land a toast after the call returns. */
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
    @Volatile private var blockNextClear = false

    /** The next group withdrawal blocks until [releaseClear]; later ones run immediately. */
    fun enterClearWithBlock(): CountDownLatch { blockNextClear = true; return clearEntered }

    override fun show(title: String, text: String, route: String, tag: String, group: String) {
        shown += "$tag@$group"
        // The toast lands on screen after the delivery call returns, which is how a withdrawal
        // racing with it can miss it.
        onShow?.invoke(tag)
        visible += tag
    }

    override fun remove(tag: String, group: String) {
        if (failRemove) throw IllegalStateException("history timeout")
        removed += "$tag@$group"
        visible -= tag
    }

    override fun clear(group: String) {
        if (blockNextClear) {
            blockNextClear = false
            clearEntered.countDown()
            clearGate.await(10, TimeUnit.SECONDS)
        }
        if (failClear) throw IllegalStateException("removegroup timeout")
        cleared += group
        visible.clear()
    }

    fun releaseClear() { clearGate.countDown() }

    /** What the user would still see. */
    fun texts(): Set<String> = visible.toSet()
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
        val delivered = center.texts()
        session.detach()

        assertTrue(center.cleared.isEmpty(), "a normal exit must not withdraw the account group")
        assertEquals(delivered, center.texts())
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
        assertEquals(1, center.texts().size)
    }

    @Test fun aRepeatedEnableDoesNotWithdrawUnreadNotifications() {
        val center = RecordingNotificationCenter()
        val session = session(center)
        assertTrue(session.publish(item("reply-1")))
        val unread = center.texts()

        session.resume() // settings re-emitted while the master switch stayed on
        session.resume()
        DesktopNotificationCleanup.retryOutstanding()
        assertEquals(unread, center.texts(), "an idempotent enable must not touch what the user has not read")
        assertTrue(center.removed.isEmpty())
    }

    @Test fun theAdapterCancelPathNeverReDerivesAnOlderDeliveryOntoANewerToast() {
        val center = RecordingNotificationCenter().apply { failRemove = true }
        val session = session(center)
        assertTrue(session.publish(item("reply-1")))
        assertEquals(emptySet(), session.withdrawNow(setOf("reply-1")), "the surface refused the first removal")

        center.failRemove = false
        assertTrue(session.publish(item("reply-1")), "the same message key is delivered again")
        val newestTag = center.shown.last().substringBefore('@')

        // A round with no new cancellations must not touch the newer delivery.
        assertEquals(emptySet(), session.withdrawNow(emptySet()))
        DesktopNotificationCleanup.retryOutstanding()
        assertTrue(newestTag in center.texts(), "the older pending tag owns only the older toast")
        assertEquals(1, center.texts().size)
    }

    @Test fun twoSessionsForTheSameAccountNeverShareADeliveryTag() {
        val center = RecordingNotificationCenter()
        val first = session(center)
        val remounted = session(center)
        assertTrue(first.publish(item("reply-1")))
        assertTrue(remounted.publish(item("reply-1")))
        val both = center.texts()
        assertEquals(2, both.size, "each delivery gets its own tag")

        center.failRemove = true
        first.withdrawNow(setOf("reply-1"))
        center.failRemove = false
        DesktopNotificationCleanup.retryOutstanding()
        assertEquals(1, center.texts().size, "the old session may only remove its own delivery")
    }

    @Test fun publishingStaysClosedWhileOverlappingCleanupsRun() {
        val center = RecordingNotificationCenter()
        val session = session(center)
        assertTrue(session.publish(item("reply-1")))
        val entered = center.enterClearWithBlock()
        val pausing = thread { session.pause() }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the first group withdrawal must be running")

        // A second cleanup overlaps: it queues behind the first, and publishing stays closed until both end.
        val retiring = thread { session.retire() }
        Thread.sleep(100)
        assertFalse(session.publish(item("reply-2")), "a running cleanup must not let a toast land inside it")

        center.releaseClear()
        pausing.join(5_000)
        retiring.join(5_000)
        assertFalse(session.isPublishing(), "retirement is final")
        assertTrue(center.texts().isEmpty())
    }

    @Test fun aLateToastWhoseWithdrawalFailsIsFinishedByTheDriver() {
        val center = RecordingNotificationCenter().apply { failRemove = true }
        val session = session(center)
        center.onShow = { session.retire() }
        assertFalse(session.publish(item("reply-1")))
        assertEquals(1, session.pendingTags().size)

        center.failRemove = false
        DesktopNotificationCleanup.retryOutstanding()
        assertTrue(session.pendingTags().isEmpty())
        assertTrue(center.texts().isEmpty())
    }

    @Test fun aRefusedGroupCleanupIsRememberedAndFinishesOnRetry() {
        val center = RecordingNotificationCenter().apply { failClear = true }
        val session = session(center)
        session.publish(item("reply-1"))

        assertFalse(session.retire())
        assertTrue(session.isCleanupPending())
        assertEquals(1, session.clearAttemptCount())

        center.failClear = false
        DesktopNotificationCleanup.retryOutstanding()
        assertFalse(session.isCleanupPending())
        assertEquals(2, session.clearAttemptCount())
        assertTrue(center.texts().isEmpty())
        assertEquals(listOf("messages-7"), center.cleared)
    }

    @Test fun retiringClearsTheGroupOfTheAccountItWasBuiltFor() {
        val center = RecordingNotificationCenter()
        val session = session(center)
        assertTrue(session.retire())
        assertEquals(listOf("messages-7"), center.cleared)
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
}

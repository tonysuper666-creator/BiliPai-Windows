package com.bilipai.desktop.ui

import com.android.purebilibili.feature.message.notification.PendingMessageNotification
import com.bilipai.desktop.integration.DesktopNotificationCenter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.BeforeTest
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
    @BeforeTest fun resetLeases() { DesktopNotificationAccountLeases.clearForTest() }

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

    @Test fun cancellingAKeyCoversEveryDeliveryItStillHas() {
        val center = RecordingNotificationCenter()
        val session = session(center)
        assertTrue(session.publish(item("reply-1")))
        assertTrue(session.publish(item("reply-1")), "an unread count that grew publishes the same key again")
        assertEquals(2, center.texts().size)

        assertEquals(setOf("reply-1"), session.withdrawNow(setOf("reply-1")))
        assertTrue(center.texts().isEmpty(), "marking read must not leave the older toast behind")
    }

    @Test fun aConfirmedOldDeliveryDoesNotEndTheEntryWhileANewerOneLives() {
        val center = RecordingNotificationCenter().apply { failRemove = true }
        val session = session(center)
        assertTrue(session.publish(item("reply-1")))
        assertEquals(emptySet(), session.withdrawNow(setOf("reply-1")))

        center.failRemove = false
        assertTrue(session.publish(item("reply-1")))
        DesktopNotificationCleanup.retryOutstanding()
        assertFalse(session.hasResolved(), "the key still has a live delivery, so its entry must stay")
        assertEquals(1, center.texts().size)
    }

    @Test fun aReturningAccountIsNotWipedByAnOlderSessionsPendingGroupWithdrawal() {
        val center = RecordingNotificationCenter().apply { failClear = true }
        val oldSession = session(center, 7L)
        assertTrue(oldSession.publish(item("reply-1")))
        assertFalse(oldSession.pause(), "the group withdrawal is refused and stays pending")

        session(center, 8L) // another account becomes current
        center.failClear = false
        val returning = session(center, 7L) // the user comes back to account 7
        assertTrue(returning.publish(item("reply-1")))

        DesktopNotificationCleanup.retryOutstanding()
        assertEquals(1, center.texts().size, "the returning account's new notification must survive")
        assertTrue(center.cleared.isEmpty(), "the old session may no longer withdraw the shared group")
    }

    @Test fun aVisitorSessionWithoutAnAccountCanBeBuiltAndPublishes() {
        // The port is created before the settings/login checks, and a visitor has mid == null.
        val center = RecordingNotificationCenter()
        val visitor = DesktopNotificationSession(null, center)
        assertTrue(visitor.publish(item("reply-1")), "a visitor must not crash the notification path")
        assertEquals("messages-null", visitor.group)
    }

    @Test fun aGroupWithdrawalAlreadyInsideTheSurfaceBlocksTheNextSessionOfThatAccount() {
        val center = RecordingNotificationCenter()
        val oldSession = session(center, 7L)
        assertTrue(oldSession.publish(item("reply-1")))
        val entered = center.enterClearWithBlock()
        val clearing = thread { oldSession.pause() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        // The account changes hands while the old RemoveGroup is still running.
        val returning = session(center, 7L)
        assertFalse(returning.publish(item("reply-2")), "a running group operation of this account still owns the range")

        center.releaseClear()
        clearing.join(5_000)
        assertTrue(returning.publish(item("reply-2")), "publishing resumes when the account has no running group operation")
    }

    @Test fun aPlainDetachAndRemountLeavesUnreadNotificationsAlone() {
        val center = RecordingNotificationCenter()
        val first = session(center, 7L)
        assertTrue(first.publish(item("reply-1")))
        first.detach() // window going away, no group withdrawal pending

        session(center, 7L) // same account remounts
        DesktopNotificationCleanup.retryOutstanding()
        assertEquals(1, center.texts().size, "an unread notification must survive a remount")
        assertTrue(center.removed.isEmpty())
    }

    @Test fun aConfirmationCollectedAfterANewPublishKeepsTheNewEntry() {
        val center = RecordingNotificationCenter().apply { failRemove = true }
        val session = session(center)
        assertTrue(session.publish(item("reply-1")))
        assertEquals(emptySet(), session.withdrawNow(setOf("reply-1")))

        // The confirmation is queued, then the same key is delivered again before anyone collects it.
        center.failRemove = false
        DesktopNotificationCleanup.retryOutstanding()
        assertTrue(session.publish(item("reply-1")))
        assertEquals(emptySet(), session.takeResolved(), "the new delivery keeps the entry alive")
        assertFalse(session.hasResolved())
    }

    @Test fun aDeliveryThatIsStillInsideTheSurfaceKeepsTheGroupWithdrawalOut() {
        val center = RecordingNotificationCenter()
        val session = session(center, 7L)
        val read = DesktopNotificationAccountLeases.gate(7L).readLock()
        read.lock() // a delivery of this account is inside the surface
        try {
            assertFalse(session.pause(), "the group withdrawal cannot start while a delivery is running")
            assertTrue(center.cleared.isEmpty())
        } finally {
            read.unlock()
        }
        assertTrue(session.retryCleanup())
        assertEquals(listOf("messages-7"), center.cleared)
    }

    @Test fun theWithdrawalTableSurvivesConcurrentCancelRetryAndResume() {
        val center = RecordingNotificationCenter()
        val session = session(center, 7L)
        assertTrue(session.publish(item("reply-1")))
        session.pause() // a paused session: resume() will remember its deliveries
        center.failRemove = false

        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val cleaner = thread {
            while (!stop.get()) {
                runCatching { session.retryCleanup() }.onFailure { errors += it }
            }
        }
        val canceller = thread {
            repeat(300) {
                runCatching { session.withdrawNow(setOf("reply-1")) }.onFailure { errors += it }
                runCatching { session.resume(); session.pause() }.onFailure { errors += it }
            }
        }
        canceller.join(20_000)
        stop.set(true)
        cleaner.join(5_000)

        assertTrue(errors.isEmpty(), "the table is shared across threads: ${errors.firstOrNull()}")
        session.retryCleanup()
        val stillPending = session.pendingTags()
        val visible = center.texts().map { it.substringBefore('@') }.toSet()
        assertTrue(stillPending.intersect(visible).isEmpty(),
            "a refused delivery is either still pending or gone from the surface, never both")
    }

    @Test fun anOldSessionLosingTheLeaseEndsItsOwnCleanupInsteadOfRetryingForever() {
        val center = RecordingNotificationCenter()
        val oldSession = session(center, 7L)
        assertTrue(oldSession.publish(item("reply-1")))

        session(center, 7L) // the account is claimed by a new session first
        assertFalse(oldSession.retire(), "the group is not this session's any more")
        assertTrue(oldSession.pendingTags().isNotEmpty(), "its own delivery is withdrawn by tag instead")

        assertTrue(oldSession.retryCleanup())
        assertFalse(oldSession.isCleanupPending(), "a lost lease must not leave an endless retry")
        assertEquals(0, DesktopNotificationCleanup.retryOutstanding())
        assertTrue(center.texts().isEmpty(), "the old session still clears what it delivered")
    }

    @Test fun aTakeoverCannotPublishBeforeTheOldGroupOperationIsRegistered() {
        val center = RecordingNotificationCenter()
        val oldSession = session(center, 7L)
        // Another thread is inside the account's exclusive section, before it registers anything.
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder = thread {
            val write = DesktopNotificationAccountLeases.gate(7L).writeLock()
            write.lock()
            held.countDown()
            release.await(5, TimeUnit.SECONDS)
            write.unlock()
        }
        assertTrue(held.await(5, TimeUnit.SECONDS))
        try {
            val returning = session(center, 7L)
            assertFalse(returning.publish(item("reply-2")), "no delivery may slip into a starting group operation")
            assertTrue(center.shown.isEmpty())
        } finally {
            release.countDown()
            holder.join(5_000)
        }
        assertTrue(session(center, 7L).publish(item("reply-2")))
        assertTrue(center.shown.isNotEmpty())
    }

    @Test fun concurrentTakeoverNeverReportsAPublicationThatIsNotThere() {
        repeat(400) {
            val center = RecordingNotificationCenter()
            val oldSession = session(center, 77L)
            center.onShow = { Thread.yield() }
            val clearing = thread { oldSession.pause() }
            val returning = session(center, 77L)
            val published = returning.publish(item("reply-$it"))
            clearing.join(2_000)
            if (published) {
                assertTrue(center.texts().isNotEmpty(),
                    "a delivery reported as published must still be on screen")
            }
        }
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

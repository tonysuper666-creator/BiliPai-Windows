package com.bilipai.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopNotificationHandoffTest {
    private fun session(account: Long, center: RecordingNotificationCenter = RecordingNotificationCenter()) =
        DesktopNotificationSession(account, center)

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test fun theRegistryStillOwnsTheOldAccountAfterTheTestDropsEveryReference() {
        val window = Any()
        val center = RecordingNotificationCenter()
        var first: DesktopNotificationSession? = session(7L, center)
        DesktopNotificationHandoff.attach(window, first!!)
        // The consumer that owned it is gone and nothing in this test holds it any more.
        first = null
        repeat(3) { System.gc(); System.runFinalization() }

        DesktopNotificationHandoff.attach(window, session(8L, center))
        assertTrue(waitUntil(3_000) { center.cleared.contains("messages-7") },
            "the previous account must still be retired after a collection")
    }

    @Test fun anotherAccountRetiresThePreviousPresentation() {
        val window = Any()
        val center = RecordingNotificationCenter()
        val first = session(7L, center)
        val second = session(8L, center)
        DesktopNotificationHandoff.attach(window, first)
        DesktopNotificationHandoff.attach(window, second)
        assertTrue(waitUntil(3_000) { center.cleared.contains("messages-7") })
        assertEquals(1, center.cleared.size, "only the old account is withdrawn")
        assertSame(second, DesktopNotificationHandoff.current(window))
    }

    @Test fun theSameAccountRemountingDoesNotWithdrawItsOwnNotifications() {
        val window = Any()
        val center = RecordingNotificationCenter()
        val first = session(7L, center)
        val remounted = session(7L, center)
        DesktopNotificationHandoff.attach(window, first)
        DesktopNotificationHandoff.attach(window, remounted)
        Thread.sleep(150)
        assertTrue(center.cleared.isEmpty(), "a root reload for the same account must keep its notifications")
        assertSame(remounted, DesktopNotificationHandoff.current(window))
    }

    @Test fun windowsDoNotSeeEachOthersPresentations() {
        val windowA = Any()
        val windowB = Any()
        val centerA = RecordingNotificationCenter()
        val centerB = RecordingNotificationCenter()
        DesktopNotificationHandoff.attach(windowA, session(7L, centerA))
        DesktopNotificationHandoff.attach(windowB, session(8L, centerB))
        Thread.sleep(150)
        assertTrue(centerA.cleared.isEmpty() && centerB.cleared.isEmpty())
    }
}

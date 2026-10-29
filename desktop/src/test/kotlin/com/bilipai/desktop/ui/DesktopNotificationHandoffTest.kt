package com.bilipai.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopNotificationHandoffTest {
    private class Holder(private val account: Long?) : DesktopNotificationHandover {
        var retired = 0
        override fun accountId(): Long? = account
        override fun retireForAccountSwitch() { retired++ }
    }

    @Test fun anotherAccountRetiresThePresentationThatWasStillRegistered() {
        val window = Any()
        val first = Holder(7L)
        val second = Holder(8L)
        // The consumer that owned `first` is torn down while the next account loads: no detach call,
        // the registry is the only thing that still knows about it.
        DesktopNotificationHandoff.attach(window, first)
        DesktopNotificationHandoff.attach(window, second)
        assertEquals(1, first.retired)
        assertEquals(0, second.retired)
        assertSame(second, DesktopNotificationHandoff.current(window))
    }

    @Test fun theSameAccountRemountingDoesNotWithdrawItsOwnNotifications() {
        val window = Any()
        val first = Holder(7L)
        val remounted = Holder(7L)
        DesktopNotificationHandoff.attach(window, first)
        DesktopNotificationHandoff.attach(window, remounted)
        assertEquals(0, first.retired, "a root reload for the same account must keep its notifications")
        assertSame(remounted, DesktopNotificationHandoff.current(window))
    }

    @Test fun windowsDoNotSeeEachOthersPresentations() {
        val windowA = Any()
        val windowB = Any()
        val a = Holder(7L)
        val b = Holder(8L)
        DesktopNotificationHandoff.attach(windowA, a)
        DesktopNotificationHandoff.attach(windowB, b)
        assertEquals(0, a.retired)
        assertSame(a, DesktopNotificationHandoff.current(windowA))
        assertTrue(b.retired == 0)
    }
}

package com.bilipai.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopNotificationWithdrawalTest {
    @Test fun refusedWithdrawalKeepsTheKeyUntilItSucceeds() {
        val failures = mutableListOf<String>()
        var reject = true
        val withdrawal = DesktopNotificationWithdrawal(
            withdraw = { _, _ -> if (reject) throw IllegalStateException("history timeout") },
            report = { message, _ -> failures += message })
        withdrawal.bind("messages-7")

        val stillOnScreen = withdrawal.withdraw(setOf("reply-1"))
        assertEquals(setOf("reply-1"), stillOnScreen)
        assertEquals(1, failures.size)
        assertEquals(1, withdrawal.attempts("reply-1"))

        // A later round retries the same key even though the caller asked about nothing new.
        reject = false
        assertEquals(emptySet(), withdrawal.withdraw(emptySet()))
        assertEquals(emptySet(), withdrawal.pendingKeys())
        assertEquals(0, withdrawal.attempts("reply-1"))
    }

    @Test fun oneRefusedKeyDoesNotHideAnotherSuccessfulWithdrawal() {
        val withdrawal = DesktopNotificationWithdrawal(
            withdraw = { tag, _ -> if (tag == "like-2") throw IllegalStateException("timeout") },
            report = { _, _ -> })
        withdrawal.bind("messages-9")

        val stillOnScreen = withdrawal.withdraw(listOf("reply-1", "like-2"))
        assertEquals(setOf("like-2"), stillOnScreen)
        assertEquals(setOf("like-2"), withdrawal.pendingKeys())
    }

    @Test fun anUnboundGroupIsNeverTreatedAsWithdrawn() {
        val withdrawal = DesktopNotificationWithdrawal(withdraw = { _, _ -> error("must not be called") }, report = { _, _ -> })
        assertEquals(setOf("reply-1"), withdrawal.withdraw(setOf("reply-1")))
        assertTrue(withdrawal.pendingKeys().isEmpty(), "an unbound group has nothing to remember")
    }
}

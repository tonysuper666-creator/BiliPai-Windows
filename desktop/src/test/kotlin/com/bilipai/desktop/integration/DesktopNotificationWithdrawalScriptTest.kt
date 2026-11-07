package com.bilipai.desktop.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopNotificationWithdrawalScriptTest {
    private val appId = DesktopToastNotifications.APP_ID_FOR_TESTS

    @Test fun withdrawalUsesTheSameIdentifiersTheDeliveryPathUses() {
        // show() sets Tag/Group from these hashes; a mismatch would silently target nothing.
        val tag = notificationKeyHash("reply-1")
        val group = notificationKeyHash("messages-7")
        val script = historyRemoveScript("reply-1", "messages-7")
        assertTrue(script.contains("History.Remove('$tag', '$group', '$appId')"), script)
    }

    @Test fun groupCleanupNamesTheAccountGroupAndTheAppId() {
        val group = notificationKeyHash("messages-7")
        val script = historyRemoveGroupScript("messages-7")
        assertTrue(script.contains("History.RemoveGroup('$group', '$appId')"), script)
        // Group cleanup must not accidentally use the tag-level call.
        assertTrue(!script.contains("History.Remove("), script)
    }

    @Test fun aFreshProcessCanStillClearTheGroupItNeverPublished() {
        // The notification center keeps toasts across processes: a new process has registered nothing,
        // yet its account may still have delivered toasts on screen.
        val original = DesktopToastNotifications.scriptRunner
        val calls = mutableListOf<String>()
        DesktopToastNotifications.scriptRunner = { calls += it }
        try {
            DesktopToastNotifications.clear("messages-7")
            assertEquals(1, calls.size)
            assertTrue(calls.single().contains("History.RemoveGroup('${notificationKeyHash("messages-7")}'"), calls.single())
        } finally { DesktopToastNotifications.scriptRunner = original }
    }

    @Test fun keysAreDistinctPerAccountAndPerItem() {
        assertEquals(notificationKeyHash("messages-7"), notificationKeyHash("messages-7"))
        assertTrue(notificationKeyHash("messages-7") != notificationKeyHash("messages-8"))
        assertTrue(notificationKeyHash("reply-1") != notificationKeyHash("reply-2"))
    }
}

package com.bilipai.desktop.ui

import com.android.purebilibili.feature.message.ChatViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import kotlin.test.*

/** Behavioral assertions against the actual generated pages, never a stand-in list or draft map. */
class DesktopMessageConsumerRetentionTest {
    @Test fun actualRootAtoBtoAKeepsSeparateDraftsAndReadingPositions(): Unit = runBlocking(Dispatchers.Swing) {
        val f = DesktopMessageConsumerFixture()
        try {
            f.Host().use { h ->
                h.settle { "Contact1" in h.contacts() }
                h.scroll(DESKTOP_MESSAGE_LIST_PANE_TAG, 18)
                h.settle { h.position(DESKTOP_MESSAGE_LIST_PANE_TAG) > 10 }
                val contacts = h.contacts().take(2)
                assertEquals(2, contacts.size, "Two real Inbox session rows must be available")
                val (a, b) = contacts
                val inboxPosition = h.position(DESKTOP_MESSAGE_LIST_PANE_TAG)
                h.select(a)
                h.settle { h.draft() != null }
                val aVm = f.owner().chat(a.removePrefix("Contact").toLong(), 1)
                ready(aVm)
                h.settle()
                assertEquals("", h.draft(), "A starts empty")
                h.type("给 A 的匿名草稿\nsecond line")
                h.settle { h.draft() == "给 A 的匿名草稿\nsecond line" }
                h.readOlderMessages()
                h.settle { h.position(DESKTOP_MESSAGE_CHAT_PANE_TAG) > 5 }
                // Let the actual scroll action finish before parking A's state.
                repeat(25) { h.settle() }
                val chatPosition = h.position(DESKTOP_MESSAGE_CHAT_PANE_TAG)

                h.select(b)
                h.settle { f.owner().chat(b.removePrefix("Contact").toLong(), 1) !== aVm && h.draft() != null }
                assertEquals("", h.draft(), "B must never inherit A's draft")
                h.type("B's independent anonymous draft")
                h.settle { h.draft() == "B's independent anonymous draft" }
                assertEquals(inboxPosition, h.position(DESKTOP_MESSAGE_LIST_PANE_TAG), "Selecting B retains Inbox reading position")

                h.select(a)
                h.settle { h.draft() != "B's independent anonymous draft" }
                ready(f.owner().chat(a.removePrefix("Contact").toLong(), 1))
                h.settle()
                assertEquals("给 A 的匿名草稿\nsecond line", h.draft(), "A's real editor restores its own saveable draft")
                assertEquals(inboxPosition, h.position(DESKTOP_MESSAGE_LIST_PANE_TAG), "A→B→A retains Inbox reading position")
                assertEquals(chatPosition, h.position(DESKTOP_MESSAGE_CHAT_PANE_TAG), "A's real message history restores its reading position")
                h.select(b); h.settle()
                assertEquals("B's independent anonymous draft", h.draft(), "B restores independently on a second visit")
                assertTrue(f.hits.any { "/SessionMain" in it }, "The Inbox must consume its real gRPC producer")
                assertTrue(f.hits.any { "fetch_session_msgs" in it }, "Chat must consume the real message request view")
                assertTrue(f.hits.none { "send_msg" in it }, "Editing must not send anything")
            }
        } finally { f.shutdown() }
    }

    @Test fun closedOwnerRejectsLateHistoryReply(): Unit = runBlocking(Dispatchers.Swing) {
        val f = DesktopMessageConsumerFixture()
        try {
            f.Host().use { h ->
                h.settle { "Contact1" in h.contacts() }
                val late = DesktopMessageConsumerFixture.LateReply(1).also { f.late = it }
                h.select("Contact1")
                h.settle { late.entered.count == 0L && h.draft() != null }
                val owner = f.owner()
                val vm = owner.chat(1, 1)
                val before = vm.uiState.value
                owner.close()
                late.release.countDown()
                await { late.returned.count == 0L }
                owner.closeAndJoin()
                h.settle()
                assertFalse(owner.isOwned())
                assertEquals(before.messages, vm.uiState.value.messages, "A closed actual owner rejects the delayed result")
                assertFalse(vm.uiState.value.messagesLoaded, "A late response cannot finish a retired page")
            }
        } finally { f.shutdown() }
    }

    @Test fun sameAccountNewSessionRejectsLateHistoryAndOldDraft(): Unit = runBlocking(Dispatchers.Swing) {
        val f = DesktopMessageConsumerFixture()
        try {
            f.Host().use { h ->
                h.settle { "Contact1" in h.contacts() }
                val late = DesktopMessageConsumerFixture.LateReply(1).also { f.late = it }
                h.select("Contact1")
                h.settle { late.entered.count == 0L && h.draft() != null }
                h.type("old session private draft")
                h.settle { h.draft() == "old session private draft" }
                val oldOwner = f.owner()
                val oldVm = oldOwner.chat(1, 1)
                val oldIdentity = oldOwner.messageAccountIdentity
                f.late = null
                f.replaceSession()
                h.settle { "Contact1" in h.contacts() && h.draft() == null }
                assertNotEquals(oldIdentity, f.owner().messageAccountIdentity, "Same mid, new real repository epoch")
                h.select("Contact1"); h.settle { h.draft() != null }
                ready(f.owner().chat(1, 1)); h.settle()
                assertEquals("", h.draft(), "The new session must not restore the previous session's draft")
                h.type("new session draft"); h.settle { h.draft() == "new session draft" }
                val newMessages = f.owner().chat(1, 1).uiState.value.messages
                late.release.countDown()
                await { late.returned.count == 0L }
                oldOwner.closeAndJoin(); h.settle()
                assertFalse(oldVm.uiState.value.messagesLoaded, "The old account-session rejects the late result")
                assertEquals(newMessages, f.owner().chat(1, 1).uiState.value.messages)
                assertEquals("new session draft", h.draft(), "The old completion cannot change the current editor")
            }
        } finally { f.shutdown() }
    }

    private suspend fun ready(vm: ChatViewModel) {
        val job = vm.javaClass.getDeclaredField("latestMessagesJob").apply { isAccessible = true }.get(vm) as Job
        withTimeout(8_000) { job.join() }
        assertTrue(vm.uiState.value.messagesLoaded, "Real ChatViewModel history must load before the assertion")
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(8_000) { while (!predicate()) delay(10) }
}

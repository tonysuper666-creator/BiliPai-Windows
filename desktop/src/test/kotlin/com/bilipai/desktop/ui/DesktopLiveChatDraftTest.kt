package com.bilipai.desktop.ui

import com.android.purebilibili.feature.live.LiveDanmakuItem
import kotlin.test.*

class DesktopLiveChatDraftTest {
    private fun reply() = LiveDanmakuItem("reply", uid = 7L, uname = "Fixture", idStr = "reply-id")

    @Test fun successClearsOnlyTheCapturedTextAndReply() {
        val draft = DesktopLiveChatDraft()
        draft.editMessage("sent"); draft.selectReply(reply())
        val submitted = draft.capture()
        assertEquals("sent", submitted.message); assertSame(draft.reply, submitted.reply)
        draft.complete(submitted)
        assertEquals("", draft.message); assertNull(draft.reply)
    }

    @Test fun newTextAndDistinctEqualReplySurviveDelayedSuccess() {
        val draft = DesktopLiveChatDraft(); val originalReply = reply()
        draft.editMessage("sent"); draft.selectReply(originalReply)
        val submitted = draft.capture()
        val newReply = originalReply.copy()
        assertEquals(originalReply, newReply); assertNotSame(originalReply, newReply)
        draft.editMessage("new draft"); draft.selectReply(newReply)
        draft.complete(submitted)
        assertEquals("new draft", draft.message); assertSame(newReply, draft.reply)
    }

    @Test fun editingBackToSameTextStillPreservesNewDraftAndClearsOnlyOldReply() {
        val draft = DesktopLiveChatDraft()
        draft.editMessage("same text"); draft.selectReply(reply())
        val submitted = draft.capture()
        draft.editMessage("edited"); draft.editMessage("same text")
        draft.complete(submitted)
        assertEquals("same text", draft.message); assertNull(draft.reply)
    }

    @Test fun cancelAndReselectSameReplyInstanceSurvivesEarlierSuccess() {
        val draft = DesktopLiveChatDraft(); val selected = reply()
        draft.editMessage("sent"); draft.selectReply(selected)
        val submitted = draft.capture()
        draft.selectReply(null); draft.selectReply(selected)
        draft.complete(submitted)
        assertEquals("", draft.message); assertSame(selected, draft.reply)
    }

    @Test fun newReplyDoesNotPreventClearingUnchangedTextAndForeignSessionCannotClear() {
        val draft = DesktopLiveChatDraft()
        draft.editMessage("sent"); val submitted = draft.capture()
        val newReply = reply(); draft.selectReply(newReply)
        draft.complete(submitted)
        assertEquals("", draft.message); assertSame(newReply, draft.reply)
        val successor = DesktopLiveChatDraft(); successor.editMessage("successor")
        assertFailsWith<IllegalStateException> { successor.complete(submitted) }
        assertEquals("successor", successor.message)
    }
}

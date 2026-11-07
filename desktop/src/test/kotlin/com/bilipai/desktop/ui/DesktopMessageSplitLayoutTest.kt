@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.feature.message.MessageCenterDestination
import com.android.purebilibili.feature.message.MessageCenterTopItem
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** K7 (spec §6/§7): the message center's list-detail container at its real breakpoint, with saveable
 * state standing in for the list scroll and the per-conversation drafts the original pages keep. */
class DesktopMessageSplitLayoutTest {
    @Test
    fun widthsFollowTheSpecBreakpointAndCaps() {
        assertEquals(DesktopMessageSplitLayout(false, 959f, 760f), desktopMessageSplitLayout(959f))
        assertEquals(DesktopMessageSplitLayout(false, 600f, 600f), desktopMessageSplitLayout(600f))
        assertEquals(DesktopMessageSplitLayout(true, 280f, 680f), desktopMessageSplitLayout(960f))
        assertEquals(DesktopMessageSplitLayout(true, 300f, 760f), desktopMessageSplitLayout(1060f))
        assertEquals(DesktopMessageSplitLayout(true, 320f, 760f), desktopMessageSplitLayout(1080f))
        assertEquals(DesktopMessageSplitLayout(true, 320f, 760f), desktopMessageSplitLayout(3413f))
    }

    private class Host : AutoCloseable {
        var width by mutableIntStateOf(1200)
        var account by mutableStateOf<Any>("account-1")
        var chatKey by mutableStateOf<String?>(null)
        val drafts = mutableMapOf<String, MutableState<TextFieldValue>>()
        val chatInstances = mutableMapOf<String, Any>()
        var chatStarts = 0
        var listComposed = false
        var listState: LazyListState? = null
        val bounds = mutableMapOf<String, Rect>()
        private var frame = 0L
        private val scene = ImageComposeScene(1400, 700, Density(1f)) {
            Box(Modifier.width(width.dp).height(700.dp)) {
                DesktopMessageListDetail(
                    stateKey = account,
                    chatKey = chatKey,
                    list = {
                        DisposableEffect(Unit) { listComposed = true; onDispose { listComposed = false } }
                        val state = rememberLazyListState().also { listState = it }
                        LazyColumn(state = state, modifier = Modifier.fillMaxSize()
                            .onGloballyPositioned { bounds["list"] = it.boundsInRoot() }) {
                            items((0 until 200).toList(), key = { it }) { Box(Modifier.fillMaxSize().height(48.dp)) }
                        }
                    },
                    chat = {
                        val key = requireNotNull(chatKey)
                        DisposableEffect(Unit) { chatStarts++; onDispose { } }
                        chatInstances[key] = remember { Any() }
                        drafts[key] = rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
                        Box(Modifier.fillMaxSize().onGloballyPositioned { bounds["chat"] = it.boundsInRoot() })
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        fun settle() { repeat(4) { frame += 16_000_000L; scene.render(frame).close() } }
        fun type(text: String) { drafts.getValue(requireNotNull(chatKey)).value = TextFieldValue(text); settle() }
        fun draft() = drafts.getValue(requireNotNull(chatKey)).value.text
        override fun close() { scene.close() }
    }

    @Test
    fun breakpointPlacesListAndCappedChatWithoutRestartingTheChat(): Unit = Host().use { h ->
        h.chatKey = "7:1"; h.width = 1200; h.settle()
        assertTrue(h.listComposed)
        assertEquals(Rect(0f, 0f, 320f, 700f), h.bounds.getValue("list"))
        assertEquals(Rect(380f, 0f, 1140f, 700f), h.bounds.getValue("chat"))
        val chat = h.chatInstances.getValue("7:1")

        h.width = 960; h.settle()
        assertEquals(Rect(0f, 0f, 280f, 700f), h.bounds.getValue("list"))
        assertEquals(Rect(280f, 0f, 960f, 700f), h.bounds.getValue("chat"))

        h.width = 959; h.settle()
        assertFalse(h.listComposed, "below 960dp the open chat is the single column")
        assertEquals(Rect(100f, 0f, 860f, 700f), h.bounds.getValue("chat"))

        h.width = 960; h.settle()
        assertTrue(h.listComposed)
        assertEquals(Rect(280f, 0f, 960f, 700f), h.bounds.getValue("chat"))
        assertSame(chat, h.chatInstances.getValue("7:1"), "crossing the breakpoint keeps the same chat composition")
        assertEquals(1, h.chatStarts)
    }

    @Test
    fun draftsSurviveTheBreakpointAndAToBToA(): Unit = Host().use { h ->
        h.chatKey = "7:1"; h.width = 960; h.settle(); h.type("给 A 的草稿")
        h.width = 959; h.settle(); assertEquals("给 A 的草稿", h.draft())
        h.width = 960; h.settle(); assertEquals("给 A 的草稿", h.draft())

        h.chatKey = "8:1"; h.settle(); assertEquals("", h.draft()); h.type("给 B 的草稿")
        h.width = 959; h.settle(); assertEquals("给 B 的草稿", h.draft())
        h.chatKey = "7:1"; h.settle(); assertEquals("给 A 的草稿", h.draft())
        h.width = 1200; h.settle(); assertEquals("给 A 的草稿", h.draft())
        h.chatKey = "8:1"; h.settle(); assertEquals("给 B 的草稿", h.draft())
    }

    @Test
    fun accountChangeDropsTheOtherAccountsDraftsAndRetiredChatComposesNothing(): Unit = Host().use { h ->
        h.chatKey = "7:1"; h.settle(); h.type("账号一的草稿")
        h.account = "account-2"; h.settle()
        assertEquals("", h.draft(), "another account never sees this account's draft")

        // The host passes null once the page owner no longer retains the selected chat (account or
        // owner retired): no chat composition, so no ChatViewModel or task can be started.
        val starts = h.chatStarts
        h.chatKey = null; h.drafts.clear(); h.bounds.remove("chat"); h.settle()
        assertEquals(starts, h.chatStarts)
        assertTrue(h.drafts.isEmpty())
        assertTrue("chat" !in h.bounds)
        assertTrue(h.listComposed)
        assertEquals(Rect(0f, 0f, 320f, 700f), h.bounds.getValue("list"), "the list keeps its pane width without a chat")
    }

    @Test
    fun listScrollSurvivesOpeningAChatInOneColumnAndTheBreakpoint(): Unit = Host().use { h ->
        h.width = 959; h.settle()
        requireNotNull(h.listState).requestScrollToItem(42, 10); h.settle()
        assertEquals(42, h.listState!!.firstVisibleItemIndex)

        h.chatKey = "7:1"; h.settle()
        assertFalse(h.listComposed)
        h.chatKey = null; h.settle()
        assertEquals(42 to 10, h.listState!!.firstVisibleItemIndex to h.listState!!.firstVisibleItemScrollOffset)

        h.chatKey = "7:1"; h.width = 960; h.settle()
        assertEquals(42 to 10, h.listState!!.firstVisibleItemIndex to h.listState!!.firstVisibleItemScrollOffset)
        h.width = 959; h.settle(); h.width = 1200; h.chatKey = "8:1"; h.settle()
        assertEquals(42 to 10, h.listState!!.firstVisibleItemIndex to h.listState!!.firstVisibleItemScrollOffset)
    }

    @Test
    fun fourNotificationEntriesAreOneTabRowAndClicksRouteToTheirOwnEntry() {
        val items = listOf(
            MessageCenterTopItem("回复我的", 3, MessageCenterDestination.ReplyMe),
            MessageCenterTopItem("@我", 0, MessageCenterDestination.AtMe),
            MessageCenterTopItem("收到的赞", 120, MessageCenterDestination.LikeMe),
            MessageCenterTopItem("系统通知", 1, MessageCenterDestination.SystemNotice),
        )
        val clicked = mutableListOf<MessageCenterDestination>()
        val scene = ImageComposeScene(320, 200, Density(1f)) {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = AppUiStyle.entries.first()),
                systemLanguageTags = listOf("zh-CN"), windowSmallestWidthDp = 320) {
                DesktopMessageNotificationTabs(items = items, onItemClick = { clicked += it })
            }
        }
        try {
            var frame = 0L
            fun settle() { repeat(4) { frame += 16_000_000L; scene.render(frame).close() } }
            fun flatten(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::flatten)
            fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
            fun tab(d: MessageCenterDestination) = assertNotNull(nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.TestTag) == desktopMessageNotificationTabTag(d) })
            settle()
            val tabs = items.map { tab(it.destination).boundsInRoot }
            tabs.forEach { assertEquals(32f, it.height); assertEquals(tabs.first().top, it.top) }
            tabs.zipWithNext().forEach { (a, b) -> assertTrue(b.left >= a.right, "tabs sit left to right in one row") }
            val labels = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
            assertTrue(labels.containsAll(listOf("回复我的", "@我", "收到的赞", "系统通知", "3", "99+", "1")))
            assertFalse("0" in labels, "no badge without unread")
            listOf(MessageCenterDestination.AtMe, MessageCenterDestination.ReplyMe).forEach { d ->
                val p = tab(d).boundsInWindow.center
                scene.sendPointerEvent(PointerEventType.Press, p, buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, p, buttons = PointerButtons()); settle()
            }
            assertEquals(listOf(MessageCenterDestination.AtMe, MessageCenterDestination.ReplyMe), clicked)
        } finally { scene.close() }
    }
}

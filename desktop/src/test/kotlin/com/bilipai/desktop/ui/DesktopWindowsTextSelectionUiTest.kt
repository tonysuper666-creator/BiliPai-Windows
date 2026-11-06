@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.*
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.components.RichTextContent
import com.android.purebilibili.feature.message.MessageBubble
import com.android.purebilibili.feature.message.RichMessageText
import com.android.purebilibili.feature.message.messageSecondaryClick
import com.android.purebilibili.feature.video.ui.components.RichCommentText
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.asAndroidContext
import java.awt.GraphicsEnvironment
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.lang.reflect.Proxy
import java.util.Locale
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

/** Windows drag-to-copy on the actual comment, dynamic body and private-message renderers. */
class DesktopWindowsTextSelectionUiTest {
    private fun styles(scenario: String, body: (AppUiStyle) -> Unit) = AppUiStyle.entries.map { style ->
        DynamicTest.dynamicTest("$scenario / ${style.name}") { body(style) }
    }

    private class FakeClipboard : Clipboard {
        var text: String? = null
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            text = (clipEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as? String
        }
        override val nativeClipboard: NativeClipboard get() = Any()
    }

    private class Scene(style: AppUiStyle, content: @Composable Scene.() -> Unit) : AutoCloseable {
        private val scheduler = TestCoroutineScheduler()
        private var frameNanos = 0L
        private var eventMillis = 10_000L
        private val errors = mutableListOf<Throwable>()
        private val previousLocale = Locale.getDefault()
        val fixture = DesktopPageTestFixture()
        var onClose: () -> Unit = {}
        val repository = DesktopRepository(DesktopSessionStore.temporary())
        val pluginContext = fixture.pluginContext
        private val owner = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        val clipboard = FakeClipboard()
        /** The context menu's copy label as this composition resolves it ("复制", or "Copy" under an English JVM). */
        var copyLabel = ""
        init { check(GraphicsEnvironment.isHeadless()); Locale.setDefault(Locale.SIMPLIFIED_CHINESE) }
        private val scene = DesktopUpstreamCalls.withOwner(owner) {
            ImageComposeScene(900, 600, Density(1f), coroutineContext = StandardTestDispatcher(scheduler) +
                DesktopUpstreamScope(owner) + CoroutineExceptionHandler { _, error -> errors += error }) {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, hapticFeedbackEnabled = false),
                    systemLanguageTags = listOf("zh-CN"), windowSmallestWidthDp = 700) {
                    val original = LocalWindowInfo.current
                    val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                    copyLabel = LocalLocalization.current.copy
                    CompositionLocalProvider(LocalWindowInfo provides window, LocalClipboard provides clipboard,
                        LocalDesktopUpstreamAndroidContext provides pluginContext.asAndroidContext()) {
                        Box(Modifier.fillMaxSize().padding(24.dp)) { this@Scene.content() }
                    }
                }
            }
        }

        fun advance(frames: Int = 20) { repeat(frames) {
            scheduler.runCurrent(); frameNanos += 30_000_000
            DesktopUpstreamCalls.withOwner(owner) { scene.render(frameNanos).close() }; scheduler.runCurrent()
            errors.firstOrNull()?.let { throw AssertionError("Text selection composition failed", it) }
        } }
        private fun flatten(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::flatten)
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        fun texts() = nodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        fun hasText(text: String) = texts().any { it == text }
        private fun node(text: String) = nodes().first { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        }
        fun bounds(text: String): Rect = node(text).boundsInRoot
        /** Centre of [part] inside [text], taken from the text's laid-out glyphs so CJK and digit widths don't matter. */
        fun centerOf(text: String, part: String): Offset {
            val n = node(text)
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true && layouts.isNotEmpty(),
                "No text layout for <$text>")
            val from = text.indexOf(part).also { assertTrue(it >= 0, "<$part> not in <$text>") }
            val first = layouts.first().getBoundingBox(from)
            val last = layouts.first().getBoundingBox(from + part.length - 1)
            return n.positionInRoot + Offset((first.left + last.right) / 2f, (first.top + last.bottom) / 2f)
        }
        /** Start of the text, its end, and a point in the last few characters (where the link sits). */
        fun start(text: String) = bounds(text).let { Offset(it.left + 2f, it.center.y) }
        fun end(text: String) = bounds(text).let { Offset(it.right - 3f, it.center.y) }
        private fun next(step: Long = 40) = eventMillis.also { eventMillis += step }

        fun click(at: Offset, type: PointerType = PointerType.Mouse) {
            eventMillis += 1_000 // never a double click with the previous gesture
            scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = next(), type = type)
            scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = next(), type = type,
                buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = next(), type = type,
                buttons = PointerButtons(), button = PointerButton.Primary); advance()
        }
        fun longPress(at: Offset, type: PointerType) {
            eventMillis += 1_000
            scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = next(), type = type)
            scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = next(), type = type,
                buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            advance(1); scheduler.advanceTimeBy(900); advance()
            scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = next(900), type = type,
                buttons = PointerButtons(), button = PointerButton.Primary); advance()
        }
        fun drag(from: Offset, to: Offset) {
            eventMillis += 1_000
            scene.sendPointerEvent(PointerEventType.Move, from, timeMillis = next())
            scene.sendPointerEvent(PointerEventType.Press, from, timeMillis = next(),
                buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary); advance(2)
            for (step in 1..8) {
                val p = from + (to - from) * (step / 8f)
                scene.sendPointerEvent(PointerEventType.Move, p, timeMillis = next(), buttons = PointerButtons(isPrimaryPressed = true))
                advance(1)
            }
            scene.sendPointerEvent(PointerEventType.Release, to, timeMillis = next(),
                buttons = PointerButtons(), button = PointerButton.Primary); advance()
        }
        /** Press, keep the mouse still past the long-press timeout, then drag and release. */
        fun holdThenDrag(from: Offset, to: Offset) {
            eventMillis += 1_000
            scene.sendPointerEvent(PointerEventType.Move, from, timeMillis = next())
            scene.sendPointerEvent(PointerEventType.Press, from, timeMillis = next(),
                buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            advance(1); scheduler.advanceTimeBy(900); advance(); eventMillis += 900
            for (step in 1..8) {
                scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * (step / 8f), timeMillis = next(),
                    buttons = PointerButtons(isPrimaryPressed = true)); advance(1)
            }
            scene.sendPointerEvent(PointerEventType.Release, to, timeMillis = next(),
                buttons = PointerButtons(), button = PointerButton.Primary); advance()
        }
        fun rightClick(at: Offset) {
            eventMillis += 1_000
            scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = next())
            scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = next(),
                buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
            scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = next(),
                buttons = PointerButtons(), button = PointerButton.Secondary); advance()
        }
        fun wheel(at: Offset, rows: Float) {
            scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = next())
            scene.sendPointerEvent(PointerEventType.Scroll, at, timeMillis = next(), scrollDelta = Offset(0f, rows)); advance()
        }
        fun tab(shift: Boolean = false) {
            for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
                scene.sendKeyEvent(KeyEvent(Key.Tab, type, isShiftPressed = shift))
            }
            advance()
        }
        fun focusedBounds() = nodes().lastOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }?.boundsInRoot
        fun focusedTag() = nodes().lastOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
            ?.config?.getOrNull(SemanticsProperties.TestTag)
        /** Ctrl+C on Windows; the same copy shortcut is Cmd+C when this suite runs on a Mac host. */
        fun copyShortcut() {
            val mac = System.getProperty("os.name").startsWith("Mac")
            for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
                scene.sendKeyEvent(KeyEvent(Key.C, type, isCtrlPressed = !mac, isMetaPressed = mac))
            }
            advance()
        }
        override fun close() {
            try { scene.close() } finally {
                try { onClose() } finally { Locale.setDefault(previousLocale); repository.logout(); fixture.close() }
            }
        }
    }

    /** Select from the first character to past the end, copy, and return what reached the clipboard. */
    private fun Scene.selectAllByDragAndCopy(text: String): String? {
        drag(start(text), end(text) + Offset(60f, 0f)); copyShortcut(); return clipboard.text
    }

    /** Two keyboard stops around the selectable text, like the buttons above and below a comment. */
    @Composable private fun Framed(content: @Composable () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(40.dp).testTag("before").focusable())
        content()
        Box(Modifier.size(40.dp).testTag("after").focusable())
    }

    /** Tab walks past plain text (links stay keyboard stops) from the control before it to the one after; a drag still copies. */
    private fun Scene.assertTabSkipsTextAndDragStillCopies(text: String) {
        advance()
        tab(); assertEquals("before", focusedTag())
        tab(); assertEquals("after", focusedTag(), "Tab never stops on selectable text")
        assertEquals(text, selectAllByDragAndCopy(text), "The text still copies from a drag")
        tab(); assertEquals("after", focusedTag(), "Tab from a selection moves on to the next control")
        tab(shift = true); assertEquals("before", focusedTag(), "Shift+Tab walks back past the text too")
    }

    /** Select the whole text, right-click the selection and copy through the menu. */
    private fun Scene.assertMenuCopies(text: String) {
        drag(start(text), end(text) + Offset(60f, 0f))
        rightClick(bounds(text).center)
        assertTrue(copyLabel.isNotEmpty() && hasText(copyLabel), "Right-click on a selection offers <$copyLabel>, menu: ${texts()}")
        click(bounds(copyLabel).center)
        assertEquals(text, clipboard.text, "The menu's <$copyLabel> puts the selection on the clipboard")
    }

    // ---- 视频评论 / 动态评论（RichCommentText） ----

    private val comment = "这条评论可以直接选中 01:23 复制"

    @Composable private fun Comment(onTime: (Long) -> Unit, onPlain: () -> Unit) {
        RichCommentText(text = comment, fontSize = 16.sp, color = Color.Unspecified, emoteMap = emptyMap(),
            onTimestampClick = onTime, maxTimestampMs = 600_000L, onUrlClick = {}, onPlainTextClick = onPlain)
    }

    @TestFactory fun commentDragSelectsAndCopiesWhileTimestampAndPlainClickStillWork() = styles("comment drag copy") { style ->
        var seek = -1L; var plain = 0
        Scene(style) { Comment({ seek = it }, { plain++ }) }.use { s ->
            s.advance()
            s.drag(s.start(comment), s.bounds(comment).center); s.copyShortcut()
            val part = s.clipboard.text
            assertTrue(!part.isNullOrEmpty() && comment.startsWith(part) && part.length < comment.length,
                "Dragging to the middle copies exactly the selected beginning, got <$part>")
            assertEquals(0, plain, "A drag selection must not count as a click that opens the thread")
            assertEquals(comment, s.selectAllByDragAndCopy(comment))
            assertEquals(0, plain)

            s.click(s.start(comment)); assertEquals(1, plain, "A plain click still opens the comment thread")
            s.click(s.centerOf(comment, "01:23")); assertEquals(83_000L, seek, "Clicking the timestamp still seeks the video")
            assertEquals(1, plain, "The timestamp click is not also a plain click")
        }
    }

    @TestFactory fun commentRightClickOnSelectionOffersCopy() = styles("comment context menu") { style ->
        Scene(style) { Comment({}, {}) }.use { s ->
            s.advance()
            s.assertMenuCopies(comment)
        }
    }

    @TestFactory fun commentDragInsideAListSelectsTextInsteadOfScrollingAndTheWheelStillScrolls() = styles("comment list") { style ->
        val state = LazyListState()
        Scene(style) {
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                items(40) { index -> if (index == 0) Comment({}, {}) else Spacer(Modifier.height(60.dp)) }
            }
        }.use { s ->
            s.advance()
            val from = s.start(comment)
            s.drag(from, from + Offset(0f, 300f))
            assertEquals(0, state.firstVisibleItemIndex); assertEquals(0, state.firstVisibleItemScrollOffset,
                "Dragging the mouse inside a comment selects text, it does not drag the list")
            assertFalse(s.clipboard.text != null, "Nothing is copied without the copy command")
            s.copyShortcut(); assertTrue(s.clipboard.text.orEmpty().startsWith(comment), "The vertical drag selected the comment")
            s.wheel(from, 3f)
            assertTrue(state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0, "The wheel still scrolls the list")
        }
    }

    // ---- 动态正文（RichTextContent） ----

    private val dynamicPlain = "动态正文不用长按也能拖选 "
    private val dynamicUser = "@测试UP"
    private val dynamicText = dynamicPlain + dynamicUser
    private val dynamicDesc = DynamicDesc(text = dynamicText, rich_text_nodes = listOf(
        RichTextNode(type = "RICH_TEXT_NODE_TYPE_TEXT", text = dynamicPlain, orig_text = dynamicPlain),
        RichTextNode(type = "RICH_TEXT_NODE_TYPE_AT", text = dynamicUser, orig_text = dynamicUser, rid = "4242"),
    ))
    private val emotes = object : DesktopDynamicEmotes {
        override fun snapshot() = emptyMap<String, String>()
        override fun currentSessionKey(): Any = "selection-fixture"
        override suspend fun ensureLoaded() = snapshot()
    }
    private fun platform(context: com.bilipai.desktop.plugins.DesktopPluginContext): DesktopDynamicCardPlatform =
        Proxy.newProxyInstance(DesktopDynamicCardPlatform::class.java.classLoader,
            arrayOf(DesktopDynamicCardPlatform::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SelectionFixturePlatform"
                "isOwned" -> true
                "getContext" -> context
                "getEmotes" -> emotes
                else -> error("Unexpected platform call: ${method.name}")
            }
        } as DesktopDynamicCardPlatform

    private fun dynamicScene(style: AppUiStyle, onUser: (Long) -> Unit, onBlank: () -> Unit,
        desc: DynamicDesc = dynamicDesc, frame: @Composable (@Composable () -> Unit) -> Unit = { it() }) = Scene(style) {
        val bindings = remember { platform(pluginContext) }
        CompositionLocalProvider(LocalDesktopDynamicCardBindings provides bindings) {
            frame { RichTextContent(desc = desc, onUserClick = onUser, onBlankTap = onBlank) }
        }
    }

    @TestFactory fun dynamicBodyDragSelectsWithoutTheLongPressDialogAndLinksStillOpen() = styles("dynamic body") { style ->
        var user = 0L; var blank = 0
        dynamicScene(style, { user = it }, { blank++ }).use { s ->
            s.advance()
            assertEquals(dynamicText, s.selectAllByDragAndCopy(dynamicText), "The dynamic body copies straight from a drag")
            assertFalse(s.hasText("选择动态内容"), "Drag selection opens no selection dialog")
            assertEquals(0, blank, "A drag is not a tap on the card")

            s.longPress(s.start(dynamicText), PointerType.Mouse)
            assertFalse(s.hasText("选择动态内容"), "Holding the mouse before dragging is not interrupted by the dialog")

            s.click(s.start(dynamicText)); assertEquals(1, blank, "A plain click on the body still reaches the card")
            s.click(s.end(dynamicText)); assertEquals(4242L, user, "The @ link still opens the user")
            assertEquals(1, blank)
        }
    }

    @TestFactory fun dynamicBodyTouchLongPressStillOpensTheFullTextSheet() = styles("dynamic touch long press") { style ->
        dynamicScene(style, {}, {}).use { s ->
            s.advance()
            s.longPress(s.start(dynamicText), PointerType.Touch)
            assertTrue(s.hasText("选择动态内容"), "Touch keeps the long-press full-text sheet as an extra entry")
        }
    }

    // ---- 私信气泡（RichMessageText） ----

    private val chatPlain = "私信纯文字也能选中复制"
    private val chatUrl = "https://www.bilibili.com/video/BV1xx411c7mD"
    private val chatLinked = "资料在这里 $chatUrl"

    /** A real message page owner around [content]; only text renders, the owner is never asked for a page or request. */
    @Composable private fun Scene.MessagePage(content: @Composable () -> Unit) {
        val pageOwner = remember {
            val community = DesktopCommunityRepository(repository, DesktopBlockedUpStore(fixture.pluginContext))
            DesktopOriginalMessagePageOwner(repository, community, repository.sessionEpoch, 42, fixture.scope,
                { true }, { true }, { action -> action(); true }, fixture.home, {}, false)
                .also { owner -> onClose = { runBlocking { owner.closeAndJoin() } } }
        }
        CompositionLocalProvider(LocalDesktopMessagePageOwner provides pageOwner, content = content)
    }

    private fun chatScene(style: AppUiStyle, withLink: Boolean = true,
        frame: @Composable (@Composable () -> Unit) -> Unit = { it() }, onLink: (String) -> Unit) = Scene(style) {
        MessagePage {
            frame {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    RichMessageText(text = chatPlain, emoteInfos = emptyList(), color = Color.Unspecified, fontSize = 16.sp)
                    if (withLink) RichMessageText(text = chatLinked, emoteInfos = emptyList(), color = Color.Unspecified,
                        fontSize = 16.sp, onLinkClick = onLink)
                }
            }
        }
    }

    /** The chat page's own bubble (MessageBubble), with its long-press / right-click action dialog counted. */
    private fun bubbleScene(style: AppUiStyle, text: String = chatPlain, onAction: (() -> Unit)?,
        frame: @Composable (@Composable () -> Unit) -> Unit = { it() }) = Scene(style) {
        MessagePage {
            frame { MessageBubble(message = PrivateMessageItem(msg_type = 1, content = text), isOwnMessage = false,
                onLongPress = onAction, onLinkClick = {}) }
        }
    }

    @TestFactory fun chatBubbleTextSelectsAndCopiesAndItsLinkStillOpens() = styles("chat bubble") { style ->
        val links = mutableListOf<String>()
        chatScene(style) { links += it }.use { s ->
            s.advance()
            assertEquals(chatPlain, s.selectAllByDragAndCopy(chatPlain), "A plain message bubble copies from a drag")
            assertEquals(chatLinked, s.selectAllByDragAndCopy(chatLinked), "A bubble with a link copies from a drag")
            assertTrue(links.isEmpty(), "Selecting across a link does not open it")
            s.click(s.end(chatLinked)); assertEquals(listOf(chatUrl), links, "Clicking the link still opens it")
        }
    }

    @TestFactory fun dynamicBodyAndChatBubbleRightClickOfferCopy() = styles("dynamic and chat context menu") { style ->
        dynamicScene(style, {}, {}).use { s -> s.advance(); s.assertMenuCopies(dynamicText) }
        chatScene(style) {}.use { s -> s.advance(); s.assertMenuCopies(chatPlain); s.assertMenuCopies(chatLinked) }
    }

    @TestFactory fun keyboardTabSkipsSelectableCommentDynamicAndChatText() = styles("tab skips text") { style ->
        val plainComment = "没有链接的评论，Tab 直接跳过"
        Scene(style) {
            Framed {
                RichCommentText(text = plainComment, fontSize = 16.sp, color = Color.Unspecified, emoteMap = emptyMap(),
                    onTimestampClick = {}, maxTimestampMs = 600_000L, onUrlClick = {}, onPlainTextClick = {})
            }
        }.use { s -> s.assertTabSkipsTextAndDragStillCopies(plainComment) }
        val plainDesc = DynamicDesc(text = dynamicPlain, rich_text_nodes = listOf(
            RichTextNode(type = "RICH_TEXT_NODE_TYPE_TEXT", text = dynamicPlain, orig_text = dynamicPlain)))
        dynamicScene(style, {}, {}, plainDesc, { Framed(it) }).use { s -> s.assertTabSkipsTextAndDragStillCopies(dynamicPlain) }
        chatScene(style, withLink = false, frame = { Framed(it) }) {}.use { s -> s.assertTabSkipsTextAndDragStillCopies(chatPlain) }
    }

    // ---- 复核用例（Sage，TON-1333）；第一条的外层改为真实气泡，见下方说明 ----

    /**
     * Review case 1. The review wrote the bubble's frame as `Framed { SelectionContainer { content() } }`, i.e. the
     * upstream bubble's native SelectionContainer copied into the test; no fix to the bubble can change a container the
     * test itself builds, so the frame here renders the real [MessageBubble] instead. Same name, same assertions.
     */
    @TestFactory fun reviewTabSkipsChatTextInsideTheRealBubbleSelectionContainer() = styles("review tab bubble") { style ->
        bubbleScene(style, onAction = null, frame = { content -> Framed { content() } })
            .use { s -> s.assertTabSkipsTextAndDragStillCopies(chatPlain) }
    }

    @TestFactory fun reviewRightClickOnSelectedBubbleTextOpensOneMenu() = styles("review bubble menu") { style ->
        var actionDialogs = 0
        chatScene(style, frame = { content -> Box(Modifier.combinedClickable(onClick = {}, onLongClick = { actionDialogs++ })
            .messageSecondaryClick { actionDialogs++ }) { content() } }) {}.use { s ->
            s.advance(); s.assertMenuCopies(chatPlain)
            assertEquals(0, actionDialogs, "Right-click on selected bubble text opened the message action dialog too")
        }
    }

    // ---- 真实私信气泡（MessageBubble） ----

    @TestFactory fun realBubbleRightClickOnSelectionOnlyCopiesAndWithoutSelectionOpensTheActions() = styles("real bubble menu") { style ->
        var actionDialogs = 0
        bubbleScene(style, onAction = { actionDialogs++ }).use { s ->
            s.advance(); s.assertMenuCopies(chatPlain)
            assertEquals(0, actionDialogs, "Right-click on the selection shows only the copy menu")
        }
        bubbleScene(style, onAction = { actionDialogs++ }).use { s ->
            s.advance(); s.rightClick(s.bounds(chatPlain).center)
            assertEquals(1, actionDialogs, "Right-click on an unselected bubble still opens its message actions")
            assertFalse(s.hasText(s.copyLabel), "Without a selection there is nothing to copy, menu: ${s.texts()}")
        }
    }

    @TestFactory fun realBubbleHoldingTheMouseBeforeDraggingStillSelectsWithoutTheActionDialog() = styles("real bubble hold") { style ->
        var actionDialogs = 0
        bubbleScene(style, onAction = { actionDialogs++ }).use { s ->
            s.advance()
            s.longPress(s.start(chatPlain), PointerType.Mouse)
            assertEquals(0, actionDialogs, "Holding the mouse on a bubble does not pop the action dialog")
            s.holdThenDrag(s.start(chatPlain), s.end(chatPlain) + Offset(60f, 0f)); s.copyShortcut()
            assertEquals(chatPlain, s.clipboard.text, "Holding before dragging still selects the whole message")
            assertEquals(0, actionDialogs, "The hold-then-drag is a selection, not a long press")
        }
    }

    @TestFactory fun realBubbleTouchLongPressStillOpensTheActions() = styles("real bubble touch") { style ->
        var actionDialogs = 0
        bubbleScene(style, onAction = { actionDialogs++ }).use { s ->
            s.advance()
            s.longPress(s.start(chatPlain), PointerType.Touch)
            assertEquals(1, actionDialogs, "Touch keeps the long press for the message actions")
        }
    }

    @TestFactory fun shiftTabFromTheControlAfterALinkedMessageStopsOnTheLinkThenLeaves() = styles("shift tab link") { style ->
        chatScene(style, frame = { Framed(it) }) {}.use { s ->
            s.advance()
            val link = s.bounds(chatLinked)
            tailrec fun Scene.walk(shift: Boolean, until: String, stops: List<Rect?> = emptyList()): List<Rect?> {
                tab(shift); val tag = focusedTag()
                return if (tag == until || stops.size > 6) stops else walk(shift, until, stops + focusedBounds())
            }
            s.tab(); assertEquals("before", s.focusedTag())
            val forward = s.walk(shift = false, until = "after")
            val backward = s.walk(shift = true, until = "before")
            assertEquals("before", s.focusedTag(), "Shift+Tab gets back to the control before the messages")
            for ((name, stops) in listOf("Tab" to forward, "Shift+Tab" to backward)) {
                assertTrue(stops.isNotEmpty(), "$name stops on the link")
                assertTrue(stops.all { it != null && link.contains(it.center) && it.width < link.width },
                    "$name only stops on the link inside the text, never on the text block itself: $stops (text $link)")
            }
        }
    }
}

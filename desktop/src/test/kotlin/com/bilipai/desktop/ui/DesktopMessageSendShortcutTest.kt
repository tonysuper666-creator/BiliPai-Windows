@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.feature.message.ChatInputBar
import com.bilipai.desktop.appearance.*
import java.awt.Canvas
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

/** K5/K7: the generated private-message ChatInputBar, its hoisted draft as ChatScreen keeps it, and
 * real IME edit commands through the intercepted platform request; no account, network or OS window. */
class DesktopMessageSendShortcutTest {
    private fun styles(body: (AppUiStyle) -> Unit) = AppUiStyle.entries.map { style ->
        DynamicTest.dynamicTest(style.name) { body(style) }
    }
    private class Scene(style: AppUiStyle) : AutoCloseable {
        var text by mutableStateOf(TextFieldValue(""))
        var sending by mutableStateOf(false)
        var outside by mutableStateOf("")
        val sent = mutableListOf<String>()
        var request: PlatformTextInputMethodRequest? = null
        private val scheduler = TestCoroutineScheduler()
        private var time = 0L
        private val scene = ImageComposeScene(900, 400, Density(1f), coroutineContext = StandardTestDispatcher(scheduler)) {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style), systemLanguageTags = listOf("zh-CN"), windowSmallestWidthDp = 900) {
                val original = LocalWindowInfo.current
                val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                CompositionLocalProvider(
                    LocalWindowInfo provides window,
                    LocalDesktopChatImeInsets provides WindowInsets(0),
                    LocalAppThemeConfig provides LocalAppThemeConfig.current.copy(nativeMiuixPopupsEnabled = false),
                ) {
                    InterceptPlatformTextInput(interceptor = { r, _ -> request = r; awaitCancellation() }) {
                        Column {
                            ChatInputBar(
                                text = text, onTextChange = { text = it }, onSend = { sent += text.text },
                                onPickImage = {}, isSending = sending, isUploadingImage = false,
                            )
                            BasicTextField(outside, { outside = it }, Modifier.testTag("outside").width(200.dp))
                        }
                    }
                }
            }
        }
        fun settle() { repeat(6) { scheduler.runCurrent(); scheduler.advanceTimeBy(30); time += 30_000_000; scene.render(time).close() }; scheduler.runCurrent() }
        private fun flatten(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::flatten)
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        fun field() = nodes().first { it.config.contains(SemanticsActions.SetText) }
        fun click(node: SemanticsNode) {
            val p = node.boundsInWindow.center
            scene.sendPointerEvent(PointerEventType.Press, p, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, p, buttons = PointerButtons()); settle()
        }
        fun focus() { click(field()); assertNotNull(request, "Real field must start an input session") }
        // The draft is hoisted into ChatScreen, so each IME edit reaches the shortcut after its recomposition.
        fun edit(vararg commands: EditCommand) { requireNotNull(request).onEditCommand(commands.toList()); settle() }
        fun type(s: String) { edit(SetSelectionCommand(0, Int.MAX_VALUE), CommitTextCommand(s, 1)) }
        fun key(type: KeyEventType, ctrl: Boolean = true): Boolean = scene.sendKeyEvent(KeyEvent(Key.Enter, type, isCtrlPressed = ctrl))
        fun typedEnter() = scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.Unknown, '\n'.code,
            nativeEvent = AwtKeyEvent(Canvas(), AwtKeyEvent.KEY_TYPED, 0, 0, AwtKeyEvent.VK_UNDEFINED, '\n')))
        fun release(ctrl: Boolean = true) { key(KeyEventType.KeyUp, ctrl); settle() }
        override fun close() { scene.close() }
    }

    @TestFactory
    fun oneHeldCtrlEnterSendsOnceAndItsTypedNewlineNeverReachesTheDraft() = styles { style ->
        Scene(style).use { s ->
            s.settle(); s.focus(); s.type("你好")
            assertTrue(s.key(KeyEventType.KeyDown)); assertTrue(s.typedEnter())
            repeat(8) { s.key(KeyEventType.KeyDown) }; s.settle()
            assertEquals(listOf("你好"), s.sent)
            assertEquals("你好", s.text.text)
            s.release(); s.key(KeyEventType.KeyDown); s.release()
            assertEquals(listOf("你好", "你好"), s.sent)
        }
    }

    @TestFactory
    fun imeCompositionNeverSendsAndItsCommitDoesNotRearmTheHeldKey() = styles { style ->
        Scene(style).use { s ->
            s.settle(); s.focus()
            s.edit(SetComposingTextCommand("组合", 1))
            assertNotNull(s.text.composition)
            s.key(KeyEventType.KeyDown); s.settle(); assertTrue(s.sent.isEmpty())
            s.edit(FinishComposingTextCommand())
            repeat(4) { s.key(KeyEventType.KeyDown) }; s.settle(); assertTrue(s.sent.isEmpty())
            s.release(); s.key(KeyEventType.KeyDown); s.release()
            assertEquals(listOf("组合"), s.sent)
        }
    }

    @TestFactory
    fun plainEnterBlankBusyAndFocusElsewhereNeverSend() = styles { style ->
        Scene(style).use { s ->
            s.settle(); s.focus(); s.type("换行")
            s.key(KeyEventType.KeyDown, ctrl = false); s.release(ctrl = false)
            assertTrue(s.sent.isEmpty(), "plain Enter stays the field's newline")
            s.type("   "); s.key(KeyEventType.KeyDown); s.release(); assertTrue(s.sent.isEmpty())
            s.type("消息"); s.sending = true; s.settle()
            s.key(KeyEventType.KeyDown); s.release(); assertTrue(s.sent.isEmpty(), "no send while the previous one is in flight")
            s.sending = false; s.settle()
            s.key(KeyEventType.KeyDown); s.release(); assertEquals(listOf("消息"), s.sent)
            s.click(s.nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == "outside" })
            s.key(KeyEventType.KeyDown); s.release(); assertEquals(listOf("消息"), s.sent)
        }
    }
}

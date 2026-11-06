@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
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
import com.android.purebilibili.feature.dynamic.components.DynamicInlineCommentComposer
import com.bilipai.desktop.appearance.*
import java.awt.Canvas
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent as AwtKeyEvent
import java.lang.reflect.Proxy
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DynamicTest
import kotlin.test.*

/** Real style-specific editor -> original composer submit -> onPostComment.
 * The intercepted platform request accepts real IME edit commands, no account/network/OS window. */
class DesktopDynamicCommentShortcutTest {
    private fun styles(body: (AppUiStyle) -> Unit) = AppUiStyle.entries.map { style ->
        DynamicTest.dynamicTest(style.name) { body(style) }
    }
    private inner class Scene(style: AppUiStyle, reply: Boolean = false) : AutoCloseable {
        var owned = true
        val sent = mutableListOf<Pair<String, List<String>>>()
        var result: ((Boolean) -> Unit)? = null
        var finishImmediately = false
        var cleared = 0
        var outsideKeys = 0
        var request: PlatformTextInputMethodRequest? = null
        private val scheduler = TestCoroutineScheduler()
        private var time = 0L
        private val platform = Proxy.newProxyInstance(DesktopCommentPlatform::class.java.classLoader,
            arrayOf(DesktopCommentPlatform::class.java)) { proxy, method, args ->
            when(method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "IsolatedDynamicComposer"
                "isOwned" -> owned
                "pickCommentImages" -> { @Suppress("UNCHECKED_CAST")
                    (args!![1] as (List<String>) -> Unit)(listOf("fixture-image")); null }
                else -> error("Unexpected platform call ${method.name}")
            }
        } as DesktopCommentPlatform
        private val scene = ImageComposeScene(900, 300, Density(1f), coroutineContext = StandardTestDispatcher(scheduler)) {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style), systemLanguageTags = listOf("zh-CN"), windowSmallestWidthDp = 900) {
                val original = LocalWindowInfo.current
                val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                CompositionLocalProvider(LocalWindowInfo provides window, LocalDesktopCommentBindings provides platform) {
                    InterceptPlatformTextInput(interceptor = { r, _ -> request = r; awaitCancellation() }) {
                        Column {
                            Button({}, Modifier.testTag("outside").onPreviewKeyEvent { outsideKeys++; false }) { Text("Outside") }
                            DynamicInlineCommentComposer(onPostComment = { text, images, done ->
                                sent += text to images
                                result = done
                                if (finishImmediately) done(false)
                            }, replyTargetUname = if (reply) "Fixture" else null, onClearReplyTarget = { cleared++ })
                        }
                    }
                }
            }
        }
        fun settle() { repeat(6) { scheduler.runCurrent(); scheduler.advanceTimeBy(30); time += 30_000_000; scene.render(time).close() }; scheduler.runCurrent() }
        private fun flatten(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::flatten)
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        fun field() = nodes().first { it.config.contains(SemanticsActions.SetText) }
        fun label(text: String) = nodes().first { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
        fun click(node: SemanticsNode) {
            val p = node.boundsInWindow.center
            scene.sendPointerEvent(PointerEventType.Press, p, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, p, buttons = PointerButtons()); settle()
        }
        fun focus() { click(field()); assertNotNull(request, "Real field must start an input session") }
        fun edit(vararg commands: EditCommand, render: Boolean = true) {
            requireNotNull(request).onEditCommand(commands.toList()); if (render) settle()
        }
        fun text(s: String) { edit(SetSelectionCommand(0, Int.MAX_VALUE), CommitTextCommand(s, 1)) }
        fun key(type: KeyEventType, ctrl: Boolean = true, key: Key = Key.Enter): Boolean =
            scene.sendKeyEvent(KeyEvent(key, type, isCtrlPressed = ctrl))
        fun typedEnter() = scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.Unknown, '\n'.code,
            nativeEvent = AwtKeyEvent(Canvas(), AwtKeyEvent.KEY_TYPED, 0, 0, AwtKeyEvent.VK_UNDEFINED, '\n')))
        fun release() { key(KeyEventType.KeyUp); settle() }
        fun outsideRelease() {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(
                AwtKeyEvent(Canvas(), AwtKeyEvent.KEY_RELEASED, 0, 0, AwtKeyEvent.VK_ENTER, '\n'))
        }
        override fun close() { scene.close() }
    }

    @TestFactory
    fun heldEnterSubmitsOnceEvenWhenFailureCompletesSynchronously() = styles { style ->
        Scene(style).use { s ->
            s.settle(); s.focus(); s.text("  first  "); s.finishImmediately = true
            assertTrue(s.key(KeyEventType.KeyDown)); assertTrue(s.typedEnter()); repeat(8) { s.key(KeyEventType.KeyDown) }; s.settle()
            assertEquals(listOf("first" to emptyList<String>()), s.sent)
            s.release(); s.key(KeyEventType.KeyDown); s.release(); assertEquals(2, s.sent.size)
        }
    }
    @TestFactory
    fun realImeCompositionBlocksImmediatelyAndCommitDoesNotRearmHeldKey() = styles { style ->
        Scene(style).use { s ->
            s.settle(); s.focus()
            s.edit(SetComposingTextCommand("组合", 1), render = false)
            s.key(KeyEventType.KeyDown); assertTrue(s.sent.isEmpty())
            s.edit(FinishComposingTextCommand(), render = false)
            repeat(4) { s.key(KeyEventType.KeyDown) }; assertTrue(s.sent.isEmpty())
            s.release(); s.key(KeyEventType.KeyDown); assertEquals("组合", s.sent.single().first)
        }
    }
    @TestFactory
    fun blankSendingAndOwnerGuardsReachOriginalAction() = styles { style ->
        Scene(style).use { s ->
            s.settle(); s.focus(); s.text("   "); s.key(KeyEventType.KeyDown); s.release(); assertTrue(s.sent.isEmpty())
            s.text("message"); s.owned = false; s.key(KeyEventType.KeyDown); s.release(); assertTrue(s.sent.isEmpty())
            s.owned = true; s.key(KeyEventType.KeyDown); s.release(); assertEquals(1,s.sent.size)
            s.key(KeyEventType.KeyDown); s.release(); assertEquals(1,s.sent.size)
            s.result!!(false); s.settle(); s.key(KeyEventType.KeyDown); assertEquals(2,s.sent.size)
        }
    }
    @TestFactory
    fun ordinaryEnterRetainsOriginalImeSendAndNoExtraShortcutCallback() = styles { style ->
        Scene(style).use { s ->
            s.settle(); s.focus(); s.text("ordinary"); s.finishImmediately = true
            s.key(KeyEventType.KeyDown, ctrl=false); s.key(KeyEventType.KeyUp, ctrl=false); s.settle()
            assertEquals(listOf("ordinary" to emptyList<String>()),s.sent)
        }
    }
    @TestFactory
    fun outsideFocusDoesNotStealKeysAndOutsideReleaseRearms() = styles { style ->
        Scene(style).use { s ->
            s.settle(); s.focus(); s.text("draft"); s.finishImmediately = true
            s.key(KeyEventType.KeyDown); s.settle(); assertEquals(1,s.sent.size)
            s.click(s.nodes().first { it.config.getOrNull(SemanticsProperties.TestTag)=="outside" })
            s.key(KeyEventType.KeyDown); assertTrue(s.outsideKeys>0); assertEquals(1,s.sent.size)
            s.outsideRelease(); s.focus(); s.key(KeyEventType.KeyDown); assertEquals(2,s.sent.size)
        }
    }
    @TestFactory
    fun successClearsDraftAndFocusWithoutRearmingHeldKey() = styles { style ->
        Scene(style,reply=true).use { s ->
            s.settle(); s.focus(); s.text("reply"); s.key(KeyEventType.KeyDown); s.result!!(true); s.settle()
            assertEquals(1,s.cleared)
            assertEquals("",s.field().config[SemanticsProperties.EditableText].text)
            s.focus(); s.text("next"); s.key(KeyEventType.KeyDown); assertEquals(1,s.sent.size)
            s.release(); s.key(KeyEventType.KeyDown); assertEquals(listOf("reply","next"),s.sent.map{it.first})
        }
    }
    @TestFactory
    fun imageOnlyShortcutAndButtonUseSamePayloadAndGuard() = styles { style ->
        Scene(style).use { s ->
            s.settle()
            s.click(s.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith("添加图片") } == true })
            s.focus(); s.key(KeyEventType.KeyDown); s.release()
            assertEquals("" to listOf("fixture-image"),s.sent.single())
            s.result!!(false); s.settle(); s.click(s.label("发送")); assertEquals(2,s.sent.size)
            assertEquals(s.sent[0],s.sent[1])
        }
    }
    @TestFactory
    fun addingCtrlToAlreadyHeldOrdinaryEnterDoesNotCreateNewSubmission() = styles { style ->
        Scene(style).use { s ->
            s.settle(); s.focus(); s.text("ordinary"); s.finishImmediately=true
            s.key(KeyEventType.KeyDown,ctrl=false); s.settle(); assertEquals(1,s.sent.size)
            repeat(4){s.key(KeyEventType.KeyDown)}; assertEquals(1,s.sent.size)
            s.release(); s.key(KeyEventType.KeyDown); assertEquals(2,s.sent.size)
        }
    }
}

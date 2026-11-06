@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import com.bilipai.desktop.DesktopShellEscape
import com.bilipai.desktop.desktopShellEscape
import java.awt.im.InputMethodRequests
import java.lang.reflect.Proxy
import javax.swing.JPanel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

/**
 * Esc with the caret in a text field of the shell (A3 of the desktop audit): the search box, the comment box and the private message
 * input each keep the page. The root surface of this scene carries the Esc decision of DesktopShell.kt (desktopShellEscape) and
 * reads the caret from the same text editor predicate the shell uses (desktopShellTextEditorFocused): the focus owner of the scene is
 * a Compose layer that reports a text input session while a field has the caret, exactly as the real window does.
 * No account, no network, no OS window.
 */
class DesktopShellTextFieldEscapeTest {
    /** The three places of the shell that hold the caret while the user types. */
    private enum class Caret(val label: String) { SEARCH("搜索框"), COMMENT("评论框"), MESSAGE("私信输入框") }

    private val scheduler = TestCoroutineScheduler()
    private var time = 0L
    private var scene: ImageComposeScene? = null
    private var caretHadFocus = false
    private var back = 0
    private var leftFullscreen = 0
    private var fullscreen = false
    private var draft = ""
    private var blur by mutableStateOf(0)

    /** The focus owner of the scene: a Compose layer answers with the request of its active text input session, else with null. */
    private val layer = object : JPanel() {
        override fun getInputMethodRequests(): InputMethodRequests? = if (caretHadFocus) requests else null
    }
    private val requests: InputMethodRequests = Proxy.newProxyInstance(javaClass.classLoader,
        arrayOf(InputMethodRequests::class.java)) { _, _, _ -> null } as InputMethodRequests
    private val outside = FocusRequester()

    /** The shell: a root surface with the Esc handler of DesktopShell.kt and one text field standing where the caret lives. */
    private fun build(caret: Caret) {
        scene = ImageComposeScene(900, 480, Density(1f), coroutineContext = StandardTestDispatcher(scheduler)) {
            MaterialTheme {
                val original = LocalWindowInfo.current
                val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                CompositionLocalProvider(LocalWindowInfo provides window) {
                    InterceptPlatformTextInput(interceptor = { _, _ -> awaitCancellation() }) {
                        Surface(Modifier.fillMaxSize().onKeyEvent { event ->
                            val escape = if (event.key == Key.Escape) desktopShellEscape(event, desktopShellTextEditorFocused(layer), fullscreen) else DesktopShellEscape.NONE
                            if (escape == DesktopShellEscape.LEAVE_FULLSCREEN) { leftFullscreen++; true }
                            else if (escape == DesktopShellEscape.BACK) { back++; true }
                            else false
                        }) {
                            Column(Modifier.fillMaxSize()) {
                                LaunchedEffect(blur) { if (blur > 0) outside.requestFocus() }
                                Button({}, Modifier.testTag("outside").focusRequester(outside)) { Text("Outside") }
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    when (caret) {
                                        // The search box of the shell bar: one line, the search action on Enter.
                                        Caret.SEARCH -> OutlinedTextField(draft, { draft = it }, singleLine = true,
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                            modifier = Modifier.fillMaxWidth().testTag("caret").onFocusChanged { caretHadFocus = it.isFocused })
                                        // The comment box: the original multi-line basic editor the composer embeds.
                                        Caret.COMMENT -> BasicTextField(draft, { draft = it }, maxLines = 4,
                                            modifier = Modifier.fillMaxWidth().testTag("caret").onFocusChanged { caretHadFocus = it.isFocused })
                                        // The private message input: one line, the send action on Enter.
                                        Caret.MESSAGE -> OutlinedTextField(draft, { draft = it }, singleLine = true,
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                            modifier = Modifier.fillMaxWidth().testTag("caret").onFocusChanged { caretHadFocus = it.isFocused })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun settle() {
        repeat(6) { scheduler.runCurrent(); scheduler.advanceTimeBy(30); time += 30_000_000; scene!!.render(time).close() }
        scheduler.runCurrent()
    }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun nodes() = scene!!.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
    private fun node(tag: String) = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun click(target: SemanticsNode) {
        val point = target.boundsInWindow.center
        scene!!.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true))
        scene!!.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons()); settle()
    }
    /** The caret goes into the text field, and the scene reports the text input session the real window reports. */
    private fun type() {
        click(node("caret"))
        assertTrue(caretHadFocus, "The text field must hold the caret")
        draft = "草稿"
        settle()
    }
    private fun escape() { scene!!.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown)); settle() }

    private fun closeScene() { scene?.let { runCatching { it.close() } }; scene = null }

    /** Every case starts from an unfocused shell with nothing counted. */
    private fun reset() {
        closeScene()
        caretHadFocus = false; back = 0; leftFullscreen = 0; fullscreen = false; draft = ""; blur = 0
    }

    @TestFactory
    fun escWithTheCaretInATextFieldKeepsThePage() = Caret.entries.map { caret ->
        DynamicTest.dynamicTest(caret.label) {
            reset()
            try {
                build(caret); settle(); type()
                escape()
                assertEquals(0, back, "${caret.label}: Esc must not go back while the caret is in a text field")
                assertEquals(0, leftFullscreen, "${caret.label}: Esc must not leave fullscreen either")
                assertTrue(caretHadFocus, "${caret.label}: the text field keeps the caret")
                assertEquals("草稿", draft, "${caret.label}: the draft is untouched")
            } finally { closeScene() }
        }
    }

    @TestFactory
    fun escWithoutTheCaretStillLeavesThePageAndFullscreen() = Caret.entries.map { caret ->
        DynamicTest.dynamicTest(caret.label) {
            reset()
            try {
                build(caret); settle()
                blur++; settle()
                assertFalse(caretHadFocus, "${caret.label}: no text field has the caret")
                escape()
                assertEquals(1, back, "${caret.label}: outside a text field Esc still goes back")
                type()
                blur++; settle()
                assertFalse(caretHadFocus, "${caret.label}: the caret left the text field")
                escape()
                assertEquals(2, back, "${caret.label}: the caret left the field, so Esc goes back again")
                fullscreen = true
                escape()
                assertEquals(1, leftFullscreen, "${caret.label}: in fullscreen Esc leaves fullscreen first")
                assertEquals(2, back, "${caret.label}: and does not also go back")
            } finally { closeScene() }
        }
    }
}

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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

/**
 * Enter in the account or the password row of the login dialog (B4 of the desktop audit): the same submit as the 登录 button, the
 * prompt that names the row that is still blank, one login per key press even when the key is held or the login disables the rows
 * mid-press, and no login at all while an input method composes the text or while a modifier turns Enter into someone else's shortcut.
 *
 * The scene hosts the very composable the dialog renders (LoginPasswordFields), so both affordances are driven through the same
 * production code; the dialog around it needs a repository, a captcha bridge and a network, which is why this test stops at the form.
 * The text field sessions are intercepted: real IME edit commands, no account, no network, no OS window.
 */
class DesktopLoginPasswordEnterSubmitTest {
    private val scheduler = TestCoroutineScheduler()
    private var time = 0L
    private var scene: ImageComposeScene? = null
    private var request: PlatformTextInputMethodRequest? = null
    private var username by mutableStateOf(TextFieldValue(""))
    private var password by mutableStateOf(TextFieldValue(""))
    private var busy by mutableStateOf(false)
    private var markBusyOnSubmit = false
    private val submits = mutableListOf<Pair<String, String>>()

    private fun build() {
        scene = ImageComposeScene(760, 460, Density(1f), coroutineContext = StandardTestDispatcher(scheduler)) {
            MaterialTheme {
                val original = LocalWindowInfo.current
                val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                CompositionLocalProvider(LocalWindowInfo provides window) {
                    InterceptPlatformTextInput(interceptor = { r, _ -> request = r; awaitCancellation() }) {
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            LoginPasswordFields(username, password, busy = busy, submitLabel = "登录",
                                onUsername = { username = it }, onPassword = { password = it },
                                onSubmit = { submits += username.text to password.text; if (markBusyOnSubmit) busy = true })
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
    private fun click(target: SemanticsNode) {
        val point = target.boundsInWindow.center
        scene!!.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true))
        scene!!.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons()); settle()
    }
    /** The two credential rows in reading order: the account row first, the password row below it. */
    private fun row(index: Int) = nodes().filter { it.config.contains(SemanticsActions.SetText) }
        .distinctBy { it.boundsInWindow.top to it.boundsInWindow.left }
        .sortedWith(compareBy({ it.boundsInWindow.top }, { it.boundsInWindow.left }))[index]
    private fun button() = nodes().first { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "登录" } == true
    }
    /** Everything the user can read on the form right now. */
    private fun shown(): List<String> = nodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
    }
    private fun focus(index: Int) {
        click(row(index))
        assertNotNull(request, "Row $index must start a text input session")
    }
    private fun text(value: String) {
        requireNotNull(request).onEditCommand(listOf(SetSelectionCommand(0, Int.MAX_VALUE), CommitTextCommand(value, 1)))
        settle()
    }
    /** The input method is mid-composition on the focused row: the candidate is shown in the field but not yet committed. */
    private fun compose(value: String) {
        requireNotNull(request).onEditCommand(listOf(SetComposingTextCommand(value, 1)))
        settle()
    }
    private fun commit(value: String) {
        requireNotNull(request).onEditCommand(listOf(CommitTextCommand(value, 1)))
        settle()
    }
    private fun enter(down: Boolean = true, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false, meta: Boolean = false) {
        scene!!.sendKeyEvent(KeyEvent(Key.Enter, if (down) KeyEventType.KeyDown else KeyEventType.KeyUp,
            isCtrlPressed = ctrl, isAltPressed = alt, isShiftPressed = shift, isMetaPressed = meta)); settle()
    }

    /** One press is a down followed by a release, as the window delivers it. */
    private fun press() { enter(); enter(down = false) }

    private fun closeScene() { scene?.let { runCatching { it.close() } }; scene = null }
    private fun reset() {
        closeScene()
        request = null; username = TextFieldValue(""); password = TextFieldValue("")
        busy = false; markBusyOnSubmit = false; submits.clear()
    }

    @TestFactory
    fun enterInEitherRowSubmitsExactlyLikeTheLoginButton() = listOf("密码框" to 1, "账号框" to 0).map { (row, index) ->
        DynamicTest.dynamicTest("回车于$row") {
            reset()
            try {
                build(); settle()
                focus(0); text("alice"); focus(1); text("secret")
                assertTrue(submits.isEmpty(), "Typing alone must not log in")
                focus(index); press()
                assertEquals(listOf("alice" to "secret"), submits, "$row: Enter submits the credentials")
                click(button())
                assertEquals(2, submits.size, "$row: the button submits too")
                assertEquals(submits[0], submits[1], "$row: Enter and the button submit the very same pair")
            } finally { closeScene() }
        }
    }

    @TestFactory
    fun aBlankRowAsksForTheRowItIsMissing() = listOf("两行都空" to 0, "只有账号空" to 1, "只有密码空" to 0).map { (state, row) ->
        DynamicTest.dynamicTest(state) {
            reset()
            try {
                build(); settle()
                when (state) {
                    "只有账号空" -> { focus(1); text("secret") }
                    "只有密码空" -> { focus(0); text("alice") }
                }
                val expected = when (state) {
                    "两行都空" -> "请输入手机号 / 邮箱 / 用户名和密码"
                    "只有账号空" -> "请输入手机号 / 邮箱 / 用户名"
                    else -> "请输入密码"
                }
                focus(row); press()
                assertTrue(submits.isEmpty(), "$state: Enter must not log in")
                assertTrue(shown().contains(expected), "$state: Enter tells the user what is missing, shown=${shown()}")
                click(button())
                assertTrue(submits.isEmpty(), "$state: the button must not log in either")
                assertTrue(shown().contains(expected), "$state: the button says the same thing, shown=${shown()}")
                // Filling the missing row clears the prompt and the next Enter really logs in.
                when (state) {
                    "只有账号空" -> { focus(0); text("alice") }
                    "只有密码空" -> { focus(1); text("secret") }
                    else -> { focus(0); text("alice"); focus(1); text("secret") }
                }
                assertFalse(shown().contains(expected), "$state: typing clears the prompt")
                focus(1); press()
                assertEquals(listOf("alice" to "secret"), submits, "$state: once both rows are filled Enter submits")
            } finally { closeScene() }
        }
    }

    @TestFactory
    fun aHeldEnterLogsInOnce() = listOf(1).map { _ ->
        DynamicTest.dynamicTest("按住回车") {
            reset()
            try {
                build(); settle()
                focus(0); text("alice"); focus(1); text("secret")
                repeat(6) { enter() }
                assertEquals(1, submits.size, "A held Enter must log in once, not once per repeat")
                enter(down = false); press()
                assertEquals(2, submits.size, "A fresh press after the release logs in again")
            } finally { closeScene() }
        }
    }

    @TestFactory
    fun aLoginThatDisablesTheRowsDoesNotSwallowTheNextEnter() = listOf(1).map { _ ->
        DynamicTest.dynamicTest("登录中禁用两行后重试") {
            reset()
            try {
                build(); settle()
                focus(0); text("alice"); focus(1); text("secret")
                markBusyOnSubmit = true
                enter()
                assertTrue(busy, "The first Enter starts the login")
                assertEquals(1, submits.size, "The first Enter submits once")
                // The running login disables both rows, so this release never reaches the form; the press must not stay armed.
                enter(down = false)
                busy = false; settle()
                focus(1); press()
                assertEquals(2, submits.size, "After the login ends, a fresh Enter must submit again")
            } finally { closeScene() }
        }
    }

    @TestFactory
    fun aModifiedEnterBelongsToTheShortcutLayers() = listOf("ctrl", "alt", "shift", "meta").map { modifier ->
        DynamicTest.dynamicTest("修饰键 $modifier + 回车") {
            reset()
            try {
                build(); settle()
                focus(0); text("alice"); focus(1); text("secret")
                enter(ctrl = modifier == "ctrl", alt = modifier == "alt", shift = modifier == "shift", meta = modifier == "meta")
                assertTrue(submits.isEmpty(), "$modifier+Enter must not log in")
                enter(down = false, ctrl = modifier == "ctrl", alt = modifier == "alt", shift = modifier == "shift", meta = modifier == "meta")
                press()
                assertEquals(1, submits.size, "$modifier+Enter must not have armed the plain Enter either")
            } finally { closeScene() }
        }
    }

    @TestFactory
    fun enterDuringAnImeCompositionConfirmsTheCandidateFirst() = listOf("账号框" to 0, "密码框" to 1).map { (row, index) ->
        DynamicTest.dynamicTest("预编辑于$row") {
            reset()
            try {
                build(); settle()
                focus(0); text("alice"); focus(1); text("secret")
                focus(index); compose("候选")
                enter()
                assertTrue(submits.isEmpty(), "$row: Enter must confirm the composition, not log in; actual=$submits")
                commit("候选")
                press()
                val expected = if (index == 0) "alice候选" to "secret" else "alice" to "secret候选"
                assertEquals(listOf(expected), submits, "$row: after the commit Enter logs in with the confirmed text")
            } finally { closeScene() }
        }
    }
}

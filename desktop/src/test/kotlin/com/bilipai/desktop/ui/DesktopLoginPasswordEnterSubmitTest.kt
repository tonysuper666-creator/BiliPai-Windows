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
 * Enter in the account or the password row of the login dialog (B4 of the desktop audit): the same submit as the 登录 button, nothing
 * at all while a row is blank, and one login per key press even when the key is held.
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
    private var username by mutableStateOf("")
    private var password by mutableStateOf("")
    private val submits = mutableListOf<Pair<String, String>>()

    private fun build() {
        scene = ImageComposeScene(760, 460, Density(1f), coroutineContext = StandardTestDispatcher(scheduler)) {
            MaterialTheme {
                val original = LocalWindowInfo.current
                val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                CompositionLocalProvider(LocalWindowInfo provides window) {
                    InterceptPlatformTextInput(interceptor = { r, _ -> request = r; awaitCancellation() }) {
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // The readiness rule the dialog passes in: busy or a blank row disables the submit.
                            LoginPasswordFields(username, password, enabled = username.isNotBlank() && password.isNotBlank(),
                                busy = false, submitLabel = "登录",
                                onUsername = { username = it }, onPassword = { password = it },
                                onSubmit = { submits += username to password })
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
    private fun focus(index: Int) {
        click(row(index))
        assertNotNull(request, "Row $index must start a text input session")
    }
    private fun text(value: String) {
        requireNotNull(request).onEditCommand(listOf(SetSelectionCommand(0, Int.MAX_VALUE), CommitTextCommand(value, 1)))
        settle()
    }
    private fun enter(down: Boolean = true) {
        scene!!.sendKeyEvent(KeyEvent(Key.Enter, if (down) KeyEventType.KeyDown else KeyEventType.KeyUp)); settle()
    }

    /** One press is a down followed by a release, as the window delivers it. */
    private fun press() { enter(); enter(down = false) }

    private fun closeScene() { scene?.let { runCatching { it.close() } }; scene = null }
    private fun reset() {
        closeScene()
        request = null; username = ""; password = ""; submits.clear()
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
    fun aBlankRowDoesNothingJustLikeTheDisabledButton() = listOf("both blank" to 0, "password blank" to 1).map { (state, _) ->
        DynamicTest.dynamicTest(state) {
            reset()
            try {
                build(); settle()
                if (state == "password blank") { focus(0); text("alice") }
                focus(if (state == "password blank") 1 else 0)
                press()
                assertTrue(submits.isEmpty(), "$state: Enter must do nothing")
                click(button())
                assertTrue(submits.isEmpty(), "$state: so must the disabled button")
                if (state == "password blank") { focus(1); text("secret") }
                else { focus(0); text("alice"); focus(1); text("secret") }
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
}

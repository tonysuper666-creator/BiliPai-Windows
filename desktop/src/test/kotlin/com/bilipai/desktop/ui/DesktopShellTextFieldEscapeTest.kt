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
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.SetSelectionCommand
import androidx.compose.ui.unit.*
import com.android.purebilibili.data.model.response.SessionItem
import com.android.purebilibili.feature.dynamic.components.DynamicInlineCommentComposer
import com.bilipai.desktop.data.*
import com.bilipai.desktop.desktopShellRootEsc
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import kotlin.test.*

/**
 * Esc with the caret in a text field of the shell (A3 of the desktop audit). The scene mounts the three real places of the shell that
 * hold the caret while the user types — the search screen, the comment box and the private message input — and drives them through
 * the very branch the Root runs (desktopShellRootEsc, the F11/Esc arm of rootKeyHandler): F11 toggles fullscreen, Esc leaves
 * fullscreen, then Esc goes back, and with the caret in a field the branch leaves the key alone so the page, the caret and the draft
 * stay.
 *
 * Whether a field holds the caret is read from the mounted page itself: the focused semantics node that carries SetText is a text
 * field with the caret (the same fact desktopShellTextEditorFocused reads from the window's focus owner; the offscreen scene has no
 * AWT focus owner, so the page's own focus state is the honest source here). No account, no network, no OS window: the HTTP client
 * of the search page is answered locally with an empty payload and the text input sessions are intercepted.
 */
class DesktopShellTextFieldEscapeTest {
    /** The three places of the shell that hold the caret while the user types. */
    private enum class Page(val label: String) { SEARCH("搜索页"), COMMENT("评论框"), MESSAGE("私信输入框") }

    private val scheduler = TestCoroutineScheduler()
    private var time = 0L
    private var scene: ImageComposeScene? = null
    private var request: PlatformTextInputMethodRequest? = null
    private var back = 0
    private var leftFullscreen = 0
    private var fullscreenToggles = 0
    private var escapeSeen = 0
    private var handled: Boolean? = null
    private var fullscreen = false

    private val root = Files.createTempDirectory("bp-esc-test-")
    private val repository = DesktopRepository(DesktopSessionStore(root.resolve("synthetic-session.json"), persistent = false)).also {
        // The search page asks for its landing sections as it mounts. Answer that transport locally instead of reaching the network.
        it.javaClass.getDeclaredField("client").apply { isAccessible = true }.set(it, offlineClient)
    }
    private val community = DesktopCommunityRepository(repository,
        DesktopBlockedUpStore(DesktopPluginContext(DesktopPluginStore(root.resolve("global")))))
    private val navigation = CommunityNavigation({}, {}, {}, {}, {}, {}, {}, onMessageLink = {})
    /** The reply owner the comment composer is mounted with; this test never opens an emote panel or an image picker. */
    private val comments = java.lang.reflect.Proxy.newProxyInstance(
        DesktopCommentPlatform::class.java.classLoader, arrayOf(DesktopCommentPlatform::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "DesktopShellTextFieldEscapeTestCommentOwner"
            "isOwned" -> false
            "pickCommentImages" -> null
            else -> error("The comment owner was asked for ${method.name}")
        }
    } as DesktopCommentPlatform

    /** The shell: the Root Esc branch of DesktopShell.kt around the real page that holds the caret. */
    private fun build(page: Page) {
        scene = ImageComposeScene(1100, 780, Density(1f), coroutineContext = StandardTestDispatcher(scheduler)) {
            MaterialTheme {
                val original = LocalWindowInfo.current
                val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                CompositionLocalProvider(LocalWindowInfo provides window, LocalDesktopCommentBindings provides comments) {
                    InterceptPlatformTextInput(interceptor = { input, _ -> request = input; awaitCancellation() }) {
                        Box(Modifier.fillMaxSize().onKeyEvent(::rootKey)) {
                            Column(Modifier.fillMaxSize()) {
                                TextButton({}, Modifier.testTag("outside")) { Text("别处") }
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    when (page) {
                                        Page.SEARCH -> CommunitySearch("", community, navigation)
                                        Page.COMMENT -> DynamicInlineCommentComposer(onPostComment = { _, _, done -> done(true) })
                                        Page.MESSAGE -> CommunityPrivateComposer(SessionItem(talker_id = 7), community, navigation) {}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun rootKey(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) escapeSeen++
        val result = desktopShellRootEsc(event, caretInTextField(), fullscreen,
            toggleFullscreen = { fullscreen = !fullscreen; fullscreenToggles++ },
            leaveFullscreen = { fullscreen = false; leftFullscreen++ },
            requestBack = { back++ })
        handled = result
        return result
    }

    private fun settle() {
        repeat(6) { scheduler.runCurrent(); scheduler.advanceTimeBy(30); time += 30_000_000; scene!!.render(time).close() }
        scheduler.runCurrent()
    }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun nodes() = scene!!.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
    /** The caret is wherever the page itself reports a focused text field. */
    private fun caretInTextField() = nodes().any {
        it.config.getOrNull(SemanticsProperties.Focused) == true && it.config.contains(SemanticsActions.SetText)
    }
    private fun field() = nodes().first { it.config.contains(SemanticsActions.SetText) }
    private fun draft() = nodes().firstOrNull {
        it.config.getOrNull(SemanticsProperties.Focused) == true && it.config.contains(SemanticsActions.SetText)
    }?.config?.getOrNull(SemanticsProperties.EditableText)?.text
    private fun click(target: SemanticsNode) {
        val point = target.boundsInWindow.center
        scene!!.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true))
        scene!!.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons()); settle()
    }
    private fun outside() = click(nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == "outside" })
    private fun type(value: String) {
        requireNotNull(request).onEditCommand(listOf(SetSelectionCommand(0, Int.MAX_VALUE), CommitTextCommand(value, 1)))
        settle()
    }
    private fun escape() { scene!!.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown)); settle() }
    private fun f11() { scene!!.sendKeyEvent(KeyEvent(Key.F11, KeyEventType.KeyDown)); settle() }

    private fun closeScene() { scene?.let { runCatching { it.close() } }; scene = null }
    private fun reset() {
        closeScene()
        request = null; back = 0; leftFullscreen = 0; fullscreenToggles = 0; escapeSeen = 0; handled = null; fullscreen = false
    }

    @TestFactory
    fun escWithTheCaretInTheFieldBelongsToTheField() = Page.entries.map { page ->
        DynamicTest.dynamicTest(page.label) {
            reset()
            try {
                build(page); settle()
                click(field())
                assertNotNull(request, "${page.label}: the real field must start a text input session")
                type("草稿")
                assertTrue(caretInTextField(), "${page.label}: the page reports the caret inside its field")

                escape()
                assertEquals(0, back, "${page.label}: Esc must not go back while the caret is in a text field")
                assertEquals(0, leftFullscreen, "${page.label}: and must not leave fullscreen either")
                assertEquals(1, escapeSeen, "${page.label}: the Root really received the key")
                assertEquals(false, handled, "${page.label}: the Root declines the key")
                assertTrue(caretInTextField(), "${page.label}: the field keeps the caret")
                assertEquals("草稿", draft(), "${page.label}: the draft is untouched")

                outside()
                assertFalse(caretInTextField(), "${page.label}: the caret left the field")
                escape()
                assertEquals(1, back, "${page.label}: outside a text field Esc still goes back")
                assertEquals(true, handled, "${page.label}: and the Root takes the key")
            } finally { closeScene() }
        }
    }

    @TestFactory
    fun escInFullscreenLeavesFullscreenFirstUnlessTheCaretOwnsIt() = Page.entries.map { page ->
        DynamicTest.dynamicTest(page.label) {
            reset()
            try {
                build(page); settle()
                click(field()); type("草稿")
                fullscreen = true
                escape()
                assertEquals(0, back, "${page.label}: the caret keeps the page in fullscreen too")
                assertEquals(0, leftFullscreen, "${page.label}: and keeps fullscreen")
                assertTrue(fullscreen, "${page.label}: still fullscreen")

                outside()
                escape()
                assertEquals(1, leftFullscreen, "${page.label}: Esc leaves fullscreen first")
                assertFalse(fullscreen, "${page.label}: fullscreen is off")
                assertEquals(0, back, "${page.label}: and does not also go back")
                escape()
                assertEquals(1, back, "${page.label}: the next Esc goes back")
            } finally { closeScene() }
        }
    }

    @TestFactory
    fun f11TogglesFullscreenEvenWithTheCaretInTheField() = Page.entries.map { page ->
        DynamicTest.dynamicTest(page.label) {
            reset()
            try {
                build(page); settle()
                click(field()); type("草稿")
                f11()
                assertEquals(1, fullscreenToggles, "${page.label}: F11 toggles fullscreen with the caret in the field")
                assertTrue(fullscreen, "${page.label}: fullscreen on")
                f11()
                assertEquals(2, fullscreenToggles, "${page.label}: F11 toggles back")
                assertFalse(fullscreen, "${page.label}: fullscreen off")
                assertEquals(0, back, "${page.label}: F11 never goes back")
            } finally { closeScene() }
        }
    }

    private companion object {
        /** No test traffic leaves the process: every request is answered locally with an empty success payload. */
        val offlineClient: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"data":{}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
    }
}

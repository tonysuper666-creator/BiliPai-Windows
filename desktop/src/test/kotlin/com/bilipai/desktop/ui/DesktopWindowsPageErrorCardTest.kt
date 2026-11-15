@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.bilipai.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.android.purebilibili.data.model.VideoLoadError
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopWindowsPageErrorCardTest {
    @Test fun inlineErrorCardNeverTakesFocusFromAnEditorOnMountOrWindowReturn() {
        val showError = mutableStateOf(false)
        val foreground = mutableStateOf(true)
        val editorFocus = FocusRequester()
        val windowInfo = object : WindowInfo {
            override val isWindowFocused: Boolean get() = foreground.value
        }
        val scene = ImageComposeScene(800, 600) {
            CompositionLocalProvider(LocalWindowInfo provides windowInfo) {
                MaterialTheme {
                    Column {
                        TextField("正在写评论", {}, modifier = Modifier.focusRequester(editorFocus))
                        if (showError.value) DesktopWindowsPageErrorCard("请检查网络连接后重试。", {})
                    }
                    LaunchedEffect(Unit) { editorFocus.requestFocus() }
                }
            }
        }
        try {
            var time = 0L
            fun settle() { repeat(3) { scene.render(time).close(); time += 16_000_000 } }
            fun assertEditorFocused() {
                val focused = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                    .single { it.config.getOrNull(SemanticsProperties.Focused) == true }
                assertEquals("正在写评论", focused.config.getOrNull(SemanticsProperties.EditableText)?.text)
            }
            settle()
            assertEditorFocused()
            showError.value = true
            settle()
            assertEditorFocused()
            foreground.value = false
            settle()
            foreground.value = true
            settle()
            assertEditorFocused()
        } finally { scene.close() }
    }

    @Test fun transportAndApiDetailsNeverBecomeVisibleCopy() {
        val raw = "java.lang.IllegalStateException: secret fixture detail\n at example.Player.start(Player.kt:42)"
        for (error in listOf(VideoLoadError.UnknownError(IllegalStateException(raw)),
            VideoLoadError.ApiError(-500, raw), null)) {
            val message = desktopWindowsVideoErrorMessage(error)
            assertEquals("请稍后重试，或返回选择其他视频。", message)
            assertFalse(message.contains(raw))
        }
        assertEquals("请检查网络连接后重试。", desktopWindowsVideoErrorMessage(VideoLoadError.NetworkError))
        assertEquals("连接超时，请稍后重试。", desktopWindowsVideoErrorMessage(VideoLoadError.Timeout))
        assertTrue(desktopWindowsVideoErrorMessage(VideoLoadError.VideoNotFound).contains("已被删除"))
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun text(node: SemanticsNode): List<String> = flatten(node).flatMap {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { value -> value.text }
    }

    @Test fun recoveryWaitsForItsOwnerAndNeverActivatesABackgroundOrRetiredPage() {
        val focus = DesktopWindowsPageErrorFocusReturn()
        focus.mount()
        assertFalse(focus.release(stillCurrent = true, foregroundOwned = false))
        assertFalse(focus.ownerActivated(stillCurrent = true))
        focus.mount()
        assertFalse(focus.release(stillCurrent = false, foregroundOwned = true))
        assertFalse(focus.ownerActivated(stillCurrent = true))
        focus.mount()
        assertTrue(focus.release(stillCurrent = true, foregroundOwned = true))
        assertTrue(focus.ownerActivated(stillCurrent = true))
        assertFalse(focus.ownerActivated(stillCurrent = true))
    }

    @Test fun anotherErrorOrNavigationCancelsThePendingViewportReturn() {
        val focus = DesktopWindowsPageErrorFocusReturn()
        focus.mount()
        assertTrue(focus.release(stillCurrent = true, foregroundOwned = true))
        focus.mount()
        assertFalse(focus.ownerActivated(stillCurrent = true))
        assertTrue(focus.release(stillCurrent = true, foregroundOwned = true))
        assertFalse(focus.ownerActivated(stillCurrent = false))
        assertFalse(focus.ownerActivated(stillCurrent = true))
    }

    @Test fun navigationBubblesWithoutTabButRetryKeysStayWithTheButton() {
        val forwarded = mutableListOf<Key>()
        var retries = 0
        val left = Key(java.awt.event.KeyEvent.VK_LEFT)
        val one = Key(java.awt.event.KeyEvent.VK_1)
        val navigation = setOf(left, Key.Escape, Key.K, one, Key.F11)
        val scene = ImageComposeScene(800, 400) {
            MaterialTheme {
                DesktopWindowsPageErrorOverlay(onUnhandledKey = { event ->
                    forwarded += event.key
                    event.key in navigation
                }) { DesktopWindowsPageErrorCard("请检查网络连接后重试。", onRetry = { retries++ }) }
            }
        }
        try {
            var time = 0L
            fun settle() { repeat(3) { scene.render(time).close(); time += 16_000_000 } }
            fun press(key: Key, ctrl: Boolean = false, alt: Boolean = false) {
                scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl, isAltPressed = alt))
                scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl, isAltPressed = alt))
                settle()
            }
            settle()
            val focused = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                .single { it.config.getOrNull(SemanticsProperties.Focused) == true }
            assertTrue("重试" in text(focused))
            forwarded.clear()
            press(left, alt = true)
            press(Key.Escape)
            press(Key.K, ctrl = true)
            press(one, ctrl = true)
            press(Key.F11)
            assertTrue(forwarded.containsAll(navigation))
            assertEquals(0, retries)
            forwarded.clear()
            press(Key.Enter)
            press(Key.Spacebar)
            assertEquals(2, retries)
            assertFalse(Key.Enter in forwarded)
            assertFalse(Key.Spacebar in forwarded)
        } finally { scene.close() }
    }

    @Test fun aBackgroundCardWaitsForWindowFocusAndRefocusesRetryOnReturn() {
        val foreground = mutableStateOf(false)
        val windowInfo = object : WindowInfo {
            override val isWindowFocused: Boolean get() = foreground.value
        }
        var retries = 0
        val scene = ImageComposeScene(800, 400) {
            CompositionLocalProvider(LocalWindowInfo provides windowInfo) {
                MaterialTheme {
                    DesktopWindowsPageErrorOverlay {
                        DesktopWindowsPageErrorCard("请检查网络连接后重试。", { retries++ }, onLogin = {})
                    }
                }
            }
        }
        try {
            var time = 0L
            fun settle() { repeat(3) { scene.render(time).close(); time += 16_000_000 } }
            fun focusedText(): List<String> = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                .filter { it.config.getOrNull(SemanticsProperties.Focused) == true }.flatMap(::text)
            settle()
            assertTrue(focusedText().isEmpty())
            foreground.value = true
            settle()
            assertTrue("重试" in focusedText())
            scene.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyUp))
            settle()
            assertTrue("登录并返回" in focusedText())
            foreground.value = false
            settle()
            assertTrue("登录并返回" in focusedText()) // No focus request while backgrounded.
            foreground.value = true
            settle()
            assertTrue("重试" in focusedText())
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp))
            assertEquals(1, retries)
        } finally { scene.close() }
    }

    @Test fun tabCanReachRetryAndEnterOrSpaceRestoresThePage() {
        for (activation in listOf(Key.Enter, Key.Spacebar)) {
            val error = mutableStateOf(true)
            var retries = 0
            val scene = ImageComposeScene(800, 400) {
                MaterialTheme {
                    if (error.value) DesktopWindowsPageErrorOverlay {
                        DesktopWindowsPageErrorCard("请检查网络连接后重试。", onRetry = {
                            retries++
                            error.value = false
                        }, onLogin = {})
                    } else Text("视频内容")
                }
            }
            try {
                var time = 0L
                fun settle() { repeat(3) { scene.render(time).close(); time += 16_000_000 } }
                fun press(key: Key, shift: Boolean = false) {
                    scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
                    scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift))
                    settle()
                }
                settle()
                press(Key.Tab)
                val next = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                    .single { it.config.getOrNull(SemanticsProperties.Focused) == true }
                assertTrue("登录并返回" in text(next))
                press(Key.Tab, shift = true)
                val focused = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                    .single { it.config.getOrNull(SemanticsProperties.Focused) == true }
                assertTrue("重试" in text(focused))
                press(activation)
                assertEquals(1, retries)
                assertTrue(scene.semanticsOwners.any { "视频内容" in text(it.unmergedRootSemanticsNode) })
            } finally { scene.close() }
        }
    }

    @Test fun theErrorFitsTheStageAndRetryReturnsToPageContent() {
        for (width in listOf(280, 800)) {
            val error = mutableStateOf(true)
            var retries = 0
            val scene = ImageComposeScene(width, 400) {
                MaterialTheme {
                    if (error.value) DesktopWindowsPageErrorOverlay { DesktopWindowsPageErrorCard("请检查网络连接后重试。", onRetry = {
                        retries++
                        error.value = false
                    }) } else Text("视频内容")
                }
            }
            try {
                scene.render(0).close()
                val nodes = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                val card = nodes.single { it.config.getOrNull(SemanticsProperties.LiveRegion) != null }
                assertEquals(minOf(360, width).toFloat(), card.boundsInRoot.width)
                assertEquals(width / 2f, card.boundsInRoot.center.x, .5f)
                assertEquals(200f, card.boundsInRoot.center.y, .5f)
                assertTrue(nodes.any { "暂时无法播放" in text(it) })
                val retry = nodes.single { it.config.getOrNull(SemanticsActions.OnClick) != null && "重试" in text(it) }
                assertTrue(retry.config[SemanticsActions.OnClick].action!!.invoke())
                scene.render(30_000_000).close()
                val after = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                assertEquals(1, retries)
                assertTrue(after.any { "视频内容" in text(it) })
                assertFalse(after.any { "暂时无法播放" in text(it) })
            } finally { scene.close() }
        }
    }
}

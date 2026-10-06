@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.danmaku.parser.DanmakuProto
import com.android.purebilibili.feature.video.danmaku.*
import com.android.purebilibili.feature.video.ui.overlay.CommandDanmakuOverlay
import com.android.purebilibili.feature.video.ui.overlay.CommandDanmakuOverlayState
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import com.bilipai.desktop.player.MpvPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Whole original card on an actual CPU Compose scene. No native player attachment/window,
 * confirmation HWND, HTTP, real account or synthetic state injection is used. */
class DesktopWindowsCommandLinkUiTest {
    @Test fun originalWholeCardPointerOpensLinkAndCloseWithoutLeakingPlayerGesture(): Unit = runBlocking {
        for (style in AppUiStyle.entries) {
            val fixture = Scene(style)
            try {
                fixture.await { fixture.card() != null }
                fixture.pressRelease(assertNotNull(fixture.card()).boundsInRoot.center)
                fixture.await { fixture.binding.pending.value != null }
                val first = assertNotNull(fixture.binding.pending.value)
                assertEquals("BVactualLink", first.targetBvid)
                assertSame(fixture.item, first.item)
                assertEquals(0, fixture.playerGestures)
                // The original close region remains a distinct action on the same card.
                fixture.pressRelease(assertNotNull(fixture.closeButton()).boundsInRoot.center)
                fixture.await { fixture.state.isDismissed(fixture.item.id) }
                assertTrue(fixture.routes.isEmpty())
                assertEquals(0, fixture.playerGestures)
            } finally { fixture.close() }
        }
    }

    @Test fun mountedCardClickAfterSourceRetirementCannotCreateDialogReceipt(): Unit = runBlocking {
        val fixture = Scene(AppUiStyle.entries.first())
        try {
            fixture.await { fixture.card() != null }
            fixture.owned = false
            fixture.pressRelease(assertNotNull(fixture.card()).boundsInRoot.center)
            fixture.pumpFor(100)
            assertNull(fixture.binding.pending.value)
            assertFalse(fixture.state.isDismissed(fixture.item.id))
            assertTrue(fixture.routes.isEmpty())
        } finally { fixture.close() }
    }

    private class Scene(style: AppUiStyle) : AutoCloseable {
        val item = assertNotNull(buildCommandDanmakuItem(DanmakuProto.CommandDm(id = 71, command = "#LINK#",
            content = "原LINK目标", progress = 0, extra = """{"bvid":"BVactualLink","duration":9000}""")))
        val state = CommandDanmakuOverlayState()
        val routes = mutableListOf<String>()
        var owned = true
        var playerGestures = 0
        private var nanos = 0L
        private val player = MpvPlayer()
        val binding = DesktopWindowsCommandLinkBinding(Any(), state, { owned },
            { action -> if (owned) { action(); true } else false }, { routes += it }, { error(it) })
        private val platform = DesktopWindowsCommandVoteBinding(Any(), 17, 7, { owned }, { owned },
            { action -> if (owned) { action(); true } else false },
            readVote = { _, _ -> error("LINK never reads vote metadata") },
            writeVote = { _, _, _ -> error("LINK never votes") },
            writeGrade = { _, _, _, _, _, _ -> error("LINK never grades") },
            readGrade = { _, _, _, _ -> error("LINK never reads grade statistics") }, onFeedback = { error(it) })
        private val scene = ImageComposeScene(640, 360, Density(1f)) {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, hapticFeedbackEnabled = false), systemLanguageTags = listOf("en")) {
                CompositionLocalProvider(LocalDesktopWindowsCommandVotePlatform provides platform) {
                    Box(Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures(onTap = { playerGestures++ }, onDoubleTap = { playerGestures++ })
                    }) {
                        CommandDanmakuOverlay(listOf(item), player, DanmakuViewport(640, 360, 1f), 0,
                            state, 1f, onFollowClick = { error("LINK cannot follow") },
                            onTripleClick = { error("LINK cannot triple") },
                            onVoteSubmit = { _, _, _ -> error("LINK cannot vote") },
                            onLinkClick = { binding.open(it) })
                    }
                }
            }
        }
        private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        private fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun card() = all().filter { node -> node.config.getOrNull(SemanticsActions.OnClick) != null &&
            nodes(node).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "原LINK目标" } == true }
        }.minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
        fun closeButton() = all().filter { node -> node.config.getOrNull(SemanticsActions.OnClick) != null &&
            nodes(node).any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("关闭提示") == true }
        }.minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
        private fun render() {
            nanos = maxOf(System.nanoTime(), nanos + 1)
            scene.render(nanos).close()
        }
        suspend fun pumpFor(ms: Long) {
            val end = System.nanoTime() + ms * 1_000_000
            do { render(); delay(12) } while (System.nanoTime() < end)
        }
        suspend fun await(condition: () -> Boolean) {
            withTimeout(4_000) { do { render(); if (condition()) return@withTimeout; delay(12) } while (true) }
        }
        fun pressRelease(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = nanos / 1_000_000,
                buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = nanos / 1_000_000 + 40,
                buttons = PointerButtons())
            scene.sendPointerEvent(PointerEventType.Move, Offset(620f, 340f), timeMillis = nanos / 1_000_000 + 41,
                buttons = PointerButtons())
        }
        override fun close() { binding.close(); scene.close(); player.close() }
    }
}

@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.feature.video.danmaku.*
import com.android.purebilibili.feature.video.ui.overlay.*
import com.bilipai.desktop.player.MpvPlayer
import java.awt.GraphicsEnvironment
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import kotlin.test.*

/** Production generated overlay + production session state, driven by pointer
 * press/release in the existing CPU scene. No extracted boolean UI substitute,
 * network request, native playback handle, login or OS window. */
@Execution(ExecutionMode.SAME_THREAD)
class DesktopCommandAttentionDismissUiTest {
    @Test fun tripleOnlyCardHasOneWorkingOutsideClose(): Unit = runBlocking { closeCases(1) }
    @Test fun tripleAndFollowCardHasOneWorkingOutsideClose(): Unit = runBlocking { closeCases(2) }
    @Test fun followOnlyCardHasOneWorkingOutsideClose(): Unit = runBlocking { closeCases(0) }

    private suspend fun CoroutineScope.closeCases(type: Int) {
        for (following in listOf(false, true)) {
            Scene(attention(type), coroutineContext, following).use { f ->
                f.ready()
                assertEquals(1, f.buttons("关闭提示").size)
                val point = f.point("关闭提示")
                f.click(point)
                f.await { f.state.isDismissed("same-id") && f.buttons("关闭提示").isEmpty() }
                // An already removed close must not dispatch follow/triple on a repeat tap.
                f.click(point); f.pump()
                assertEquals(0, f.follows); assertEquals(0, f.triples)
            }
        }
    }

    @Test fun switchingVideoDuringPressDoesNotDismissReusedCommandId(): Unit = runBlocking {
        Scene(attention(2), coroutineContext).use { f ->
            f.ready()
            val retired = f.state
            val point = f.point("关闭提示")
            f.press(point)
            f.video++
            f.await { f.state !== retired && f.buttons("关闭提示").size == 1 }
            f.release(point); f.pump()
            assertFalse(retired.isDismissed("same-id"))
            assertFalse(f.state.isDismissed("same-id"), "Old video press must not close the new video's same-ID card")
            f.click(f.point("关闭提示"))
            f.await { f.state.isDismissed("same-id") }
            f.video++
            f.await { !f.state.isDismissed("same-id") && f.buttons("关闭提示").size == 1 }
        }
    }

    @Test fun expiringCardCancelsPressedCloseAndDoesNotDismissAnotherCard(): Unit = runBlocking {
        Scene(attention(1), coroutineContext).use { f ->
            f.ready()
            val point = f.point("关闭提示")
            f.press(point)
            f.items = listOf(attention(1).copy(startTimeMs = -10_000, durationMs = 100))
            f.await { f.buttons("关闭提示").isEmpty() }
            f.release(point); f.pump()
            assertFalse(f.state.isDismissed("same-id"))
            f.items = listOf(attention(1).copy(id = "new-card"))
            f.ready()
            assertFalse(f.state.isDismissed("new-card"))
            f.click(f.point("关闭提示"))
            f.await { f.state.isDismissed("new-card") }
            assertFalse(f.state.isDismissed("same-id"))
        }
    }

    @Test fun followAndTripleActionsRemainClickableAndSeparateFromClose(): Unit = runBlocking {
        Scene(attention(2), coroutineContext).use { f ->
            f.ready()
            f.click(f.point("一键三连：点赞、投币、收藏"))
            f.await { f.triples == 1 }
            assertFalse(f.state.isDismissed("same-id"))
            f.click(f.point("关注"))
            f.await { f.follows == 1 }
            f.click(f.point("关闭提示"))
            f.await { f.state.isDismissed("same-id") }
            assertEquals(1, f.triples); assertEquals(1, f.follows)
        }
    }

    @Test fun voteOptionAndVoteCloseStillUseTheirOriginalCallbacks(): Unit = runBlocking {
        val option = VoteOption("one", "选项一", optionIndex = 1)
        val item = attention(0).copy(type = CommandDanmakuType.VOTE, voteKind = VoteDanmakuKind.VOTE,
            voteTitle = "真实投票卡", voteOptions = listOf(option))
        Scene(item, coroutineContext).use { f ->
            f.ready()
            f.click(f.point("选项一"))
            f.await { f.votes.size == 1 }
            assertEquals(Triple(item, option, 1), f.votes.single())
            assertFalse(f.state.isDismissed(item.id))
            f.click(f.point("关闭提示"))
            f.await { f.state.isDismissed(item.id) }
            assertEquals(1, f.votes.size)
        }
    }

    private fun attention(type: Int) = CommandDanmakuItem("same-id", CommandDanmakuType.ATTENTION,
        "关注卡", 0, 60_000, attentionType = type, positionXRatio = 0.3f, positionYRatio = 0.3f)

    private class Scene(item: CommandDanmakuItem, context: CoroutineContext, following: Boolean = false) : AutoCloseable {
        var video by mutableIntStateOf(1)
        var items by mutableStateOf(listOf(item))
        lateinit var state: CommandDanmakuOverlayState
        var follows = 0
        var triples = 0
        val votes = mutableListOf<Triple<CommandDanmakuItem, VoteOption, Int>>()
        private val player = MpvPlayer()
        private val scene: ImageComposeScene
        private var nanos = 0L
        private var eventMillis = 0L
        init {
            check(GraphicsEnvironment.isHeadless())
            scene = ImageComposeScene(640, 400, Density(1f), coroutineContext = context) {
                val window = LocalWindowInfo.current
                val focused = remember(window) { object : WindowInfo by window { override val isWindowFocused = true } }
                CompositionLocalProvider(LocalWindowInfo provides focused,
                    LocalAppThemeConfig provides AppThemeConfig(nativeMiuixPopupsEnabled = false)) {
                    MaterialTheme {
                        val source = video
                        val port = remember(source) {
                            DesktopWindowsCommandVoteBinding(Any(), 1, 2,
                                current = { video == source }, cleanupCurrent = { video == source },
                                admission = { action -> if (video == source) { action(); true } else false },
                                readVote = { _, _ -> error("Unexpected vote read") },
                                writeVote = { _, _, _ -> error("Unexpected vote write") },
                                writeGrade = { _, _, _, _, _, _ -> error("Unexpected grade write") },
                                readGrade = { _, _, _, _ -> error("Unexpected grade read") },
                                onFeedback = { error("Unexpected feedback") })
                        }
                        state = rememberCommandDanmakuOverlayState(port.sourceLease)
                        DesktopWindowsSectionCommandOverlay(commandPort = port, items = items, player = player,
                            viewport = DanmakuViewport(640, 400, 1f), bottomInsetPx = 0, state = state, fontScale = 1f,
                            onFollowClick = { follows++ }, onTripleClick = { triples++ },
                            onLinkClick = { error("Unexpected link") },
                            onVoteSubmit = { command, option, index -> votes += Triple(command, option, index) },
                            isFollowing = following, followStatusKnown = true, likeStatusKnown = true,
                            isLiked = false, isCoined = false, isFavorited = false,
                            isFollowSubmitting = false, isTripleSubmitting = false,
                            modifier = Modifier.fillMaxSize(), renderingPaused = true)
                    }
                }
            }
        }
        private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        fun buttons(label: String) = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }.filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null && nodes(node).any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
            }
        }
        fun point(label: String): Offset {
            val node = assertNotNull(buttons(label).minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height },
                "Production overlay must expose a clickable $label")
            return node.boundsInRoot.center.also { assertTrue(it.x in 0f..640f && it.y in 0f..400f) }
        }
        suspend fun ready() { pump(); point("关闭提示") }
        private fun render() { nanos = maxOf(System.nanoTime(), nanos + 1); scene.render(nanos).close() }
        suspend fun pump() { repeat(5) { render(); delay(12) } }
        suspend fun await(condition: () -> Boolean) {
            withTimeout(5_000) { while (true) { render(); if (condition()) break; delay(12) } }
        }
        private fun time() = maxOf(System.nanoTime() / 1_000_000, eventMillis + 1).also { eventMillis = it }
        fun press(point: Offset) { scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = time(), buttons = PointerButtons(isPrimaryPressed = true)) }
        fun release(point: Offset) { scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = time(), buttons = PointerButtons()) }
        fun click(point: Offset) { press(point); release(point) }
        override fun close() { scene.close(); player.close() }
    }
}

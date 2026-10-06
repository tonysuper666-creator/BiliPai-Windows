@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import com.android.purebilibili.app.newImageLoader
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.feature.live.LiveDanmakuItem
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.Call
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.test.*

/** Actual production list on the existing CPU Compose scene. Message inputs are synthetic
 * memory data; gestures are real scene pointer events. No OS window, socket or account. */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("coil3.SingletonImageLoader")
class DesktopLiveChatMessagesUiTest {
    @TempDir lateinit var imageDirectory: Path

    @Test fun full200RowRingFollowsDuringContinuousArrivalAndKeepsReplyIdentity(): Unit = runBlocking {
        val f = Scene()
        try {
            f.await { f.latestVisible() }
            val initialTail = f.lastSequence
            var followedDuringBurst = false
            repeat(36) { index ->
                f.append()
                assertEquals(200, f.messages.size)
                f.pumpFor(60)
                if (index < 35 && f.visibleRows().any { it.number > initialTail + 3 }) followedDuringBurst = true
            }
            assertTrue(followedDuringBurst, "Continuous arrivals must not postpone following until the burst ends")
            f.await { f.latestVisible() }
            val last = f.messages.last()
            f.click(assertNotNull(f.visibleRows().find { it.text == last.text }).bounds.center)
            f.await { f.replies.isNotEmpty() }
            assertSame(last, f.replies.single(), "The relocated production row must forward its original message instance")
        } finally { f.close() }
    }

    @Test fun actualWheelReadingPositionSurvivesAppendAtThe200MessageLimit(): Unit = runBlocking {
        val f = Scene()
        try {
            f.await { f.latestVisible() }
            f.readOlderRows()
            val anchor = f.visibleRows().first { it.bounds.top >= 1f && it.bounds.bottom < Scene.HEIGHT - 1f }
            repeat(12) { f.append(); f.pumpFor(60) }
            f.pumpFor(450)
            val after = assertNotNull(f.visibleRows().find { it.text == anchor.text }, "The retained reading anchor disappeared")
            assertTrue(abs(after.bounds.top - anchor.bounds.top) <= 1f, "A new message moved the visible reading anchor")
            assertFalse(f.latestVisible(), "A wheel user must not be pulled to the latest message")
            assertNotNull(f.returnButton())
        } finally { f.close() }
    }

    @Test fun lostFocusWithoutReleaseDoesNotLeaveReturnToBottomBlocked(): Unit = runBlocking {
        val f = Scene()
        try {
            f.await { f.latestVisible() }
            f.readOlderRows()
            val anchor = f.visibleRows().first { it.bounds.top >= 1f && it.bounds.bottom < Scene.HEIGHT - 1f }
            f.press(anchor.bounds.center)
            f.pumpFor(80)
            repeat(12) { f.append(); f.pumpFor(30) }
            assertFalse(f.latestVisible())
            f.focused = false
            f.pumpFor(100)
            f.focused = true
            f.pumpFor(100)
            // The held press deliberately gets no Release. A new, unpressed mouse move
            // models the returning host pointer, but cannot invoke a Release-only cleanup.
            f.moveUnpressed(Offset(8f, 8f))
            f.await { f.returnButton() != null }
            f.click(assertNotNull(f.returnButton()).boundsInRoot.center)
            f.await { f.latestVisible() }
        } finally { f.close() }
    }

    @Test fun originalImageNodeDrawsLocalPngAt32DpAndKeepsReplyIdentity(): Unit = runBlocking {
        val images = LocalImages(imageDirectory)
        val color = 0xff00d8ff.toInt()
        val item = imageMessage("本地合成表情", images.png("visible", color))
        val f = Scene(listOf(item))
        try {
            f.await { f.imagePixels(item.text, color) }
            val image = assertNotNull(f.imageNode(item.text))
            assertEquals(listOf(item.text), image.config[SemanticsProperties.ContentDescription])
            assertEquals(32.dp, AppSpacingTokens.DoubleExtraLarge)
            assertEquals(32f, image.boundsInRoot.width)
            assertEquals(32f, image.boundsInRoot.height)
            assertFalse(f.hasText(item.text), "The image must render instead of its fallback text")
            f.click(image.boundsInRoot.center)
            f.await { f.replies.isNotEmpty() }
            assertSame(item, f.replies.single())
        } finally { f.close(); images.close() }
    }

    @Test fun failedLocalImageShowsOriginalTextAndANewUrlCanRender(): Unit = runBlocking {
        val images = LocalImages(imageDirectory)
        val failed = imageMessage("加载失败时保留原始文字", images.missing("absent"))
        val f = Scene(listOf(failed))
        try {
            f.await { f.hasText(failed.text) && f.imageNode(failed.text) == null }
            val color = 0xffff00c8.toInt()
            f.replaceImage(failed.copy(emoticonUrl = images.png("recovered", color)))
            f.await { f.imagePixels(failed.text, color) }
            assertFalse(f.hasText(failed.text), "An old error must not hide the replacement image")
        } finally { f.close(); images.close() }
    }

    @Test fun lateOldUrlAndRoomResultsCannotReplaceTheCurrentImage(): Unit = runBlocking {
        val images = LocalImages(imageDirectory)
        val oldUrl = images.missing("late-url-error")
        val oldUrlGate = images.hold(oldUrl)
        val old = imageMessage("相同消息标识的表情", oldUrl)
        val f = Scene(listOf(old))
        try {
            f.await { oldUrlGate.started.isCompleted }
            val firstColor = 0xff00d8ff.toInt()
            f.replaceImage(old.copy(emoticonUrl = images.png("current-url", firstColor)))
            f.await { f.imagePixels(old.text, firstColor) }
            oldUrlGate.release.complete(Unit)
            f.await { oldUrlGate.finished.isCompleted }
            assertTrue(oldUrlGate.outcome is ErrorResult ||
                (oldUrlGate.failure != null && oldUrlGate.failure !is CancellationException),
                "The released old local URL must actually fail, not merely cancel")
            f.pumpFor(200)
            assertTrue(f.imagePixels(old.text, firstColor), "A late old-URL error changed the current image")
            assertFalse(f.hasText(old.text))

            val oldRoomUrl = images.png("late-room-image", 0xffff0000.toInt())
            val oldRoomGate = images.hold(oldRoomUrl)
            f.replaceImage(old.copy(emoticonUrl = oldRoomUrl))
            f.await { oldRoomGate.started.isCompleted }
            val secondColor = 0xff00e040.toInt()
            // Same message id/text in a new session deliberately exercises session isolation.
            f.replaceImage(old.copy(emoticonUrl = images.png("new-room-image", secondColor)), newSession = true)
            f.await { f.imagePixels(old.text, secondColor) }
            oldRoomGate.release.complete(Unit)
            f.await { oldRoomGate.finished.isCompleted }
            assertIs<SuccessResult>(oldRoomGate.outcome, "The released old-room PNG must actually finish decoding")
            f.pumpFor(200)
            assertTrue(f.imagePixels(old.text, secondColor), "An old-room decoded image replaced the new room")
            assertFalse(f.hasText(old.text))
        } finally { f.close(); images.close() }
    }

    private fun imageMessage(text: String, url: String) =
        LiveDanmakuItem(text, uid = 7L, uname = "Fixture", idStr = "same-image-id", emoticonUrl = url)

    private class PendingImage {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        @Volatile var outcome: ImageResult? = null
        @Volatile var failure: Throwable? = null
    }

    /** Original Coil configuration and real local-file PNG decoding. The only interceptor
     * delays named synthetic files; no request can reach a socket. A cancelled old request
     * is allowed to finish its bounded load so disposal/result isolation is exercised. */
    @OptIn(coil3.annotation.DelicateCoilApi::class)
    private class LocalImages(private val directory: Path) {
        private val calls = AtomicInteger()
        private val pending = ConcurrentHashMap<String, PendingImage>()
        private val previous = SingletonImageLoader.get(PlatformContext.INSTANCE)
        private val original = newImageLoader(PlatformContext.INSTANCE, Call.Factory {
            calls.incrementAndGet()
            error("Live image CPU tests must never access the network")
        }, directory.resolve("cache").toFile())
        private val loader = original.newBuilder().components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                    val gate = pending[chain.request.data.toString()] ?: return chain.proceed()
                    return withContext(NonCancellable) {
                        gate.started.complete(Unit)
                        try {
                            withTimeout(5_000) { gate.release.await() }
                            chain.proceed().also { gate.outcome = it }
                        } catch (failure: Throwable) {
                            gate.failure = failure
                            throw failure
                        } finally { gate.finished.complete(Unit) }
                    }
                }
            })
        }.build()
        init { SingletonImageLoader.setUnsafe(loader) }
        fun png(name: String, color: Int): String {
            val file = directory.resolve("$name.png")
            val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 32) for (x in 0 until 32) image.setRGB(x, y, color)
            check(ImageIO.write(image, "png", file.toFile()))
            return file.toUri().toString()
        }
        fun missing(name: String) = directory.resolve("$name.png").toUri().toString()
        fun hold(model: String) = PendingImage().also { check(pending.putIfAbsent(model, it) == null) }
        suspend fun close() {
            pending.values.forEach { it.release.complete(Unit) }
            try {
                withTimeout(5_000) { pending.values.filter { it.started.isCompleted }.forEach { it.finished.await() } }
            } finally {
                SingletonImageLoader.setUnsafe(previous)
                loader.shutdown()
                original.shutdown()
            }
            assertEquals(0, calls.get(), "The synthetic file tests attempted network access")
        }
    }

    private data class VisibleRow(val text: String, val number: Int, val bounds: Rect)
    private class Scene(initialMessages: List<LiveDanmakuItem>? = null) : AutoCloseable {
        companion object { const val HEIGHT = 380 }
        private var serial = 0
        private fun message(): LiveDanmakuItem {
            val number = serial++
            return LiveDanmakuItem("live-row-$number", uid = 7L, uname = "Fixture", idStr = "message-$number")
        }
        var messages by mutableStateOf(initialMessages ?: List(200) { message() })
            private set
        val lastSequence: Int get() = serial - 1
        var focused by mutableStateOf(true)
        private var roomKey by mutableStateOf<Any>(Any())
        val replies = mutableListOf<LiveDanmakuItem>()
        private var nanos = 0L
        private var eventMillis = 0L
        private val scene: ImageComposeScene
        init {
            check(GraphicsEnvironment.isHeadless()) { "Live list verification uses the existing CPU test JVM" }
            scene = ImageComposeScene(420, HEIGHT, Density(1f)) {
                val actualWindow = LocalWindowInfo.current
                val focusWindow = remember(actualWindow) { object : WindowInfo by actualWindow {
                    override val isWindowFocused: Boolean get() = focused
                } }
                CompositionLocalProvider(LocalWindowInfo provides focusWindow) {
                    MaterialTheme {
                        DesktopLiveChatMessages(roomKey, messages, true, { replies += it }, Modifier.fillMaxSize())
                    }
                }
            }
        }
        fun append() { messages = (messages + message()).takeLast(200) }
        fun replaceImage(item: LiveDanmakuItem, newSession: Boolean = false) {
            if (newSession) roomKey = Any()
            messages = listOf(item)
        }
        private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        private fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun visibleRows(): List<VisibleRow> = all().mapNotNull { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text ?: return@mapNotNull null
            if (!text.startsWith("live-row-")) return@mapNotNull null
            val bounds = node.boundsInRoot
            if (bounds.width <= 0f || bounds.height <= 0f || bounds.bottom <= 0f || bounds.top >= HEIGHT) return@mapNotNull null
            VisibleRow(text, text.removePrefix("live-row-").toInt(), bounds)
        }.sortedBy { it.bounds.top }
        fun latestVisible() = visibleRows().any { it.text == messages.last().text }
        fun imageNode(text: String) = all().singleOrNull {
            it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(text)
        }
        fun hasText(text: String) = all().any {
            it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
        }
        fun imagePixels(text: String, expected: Int): Boolean {
            val bounds = imageNode(text)?.boundsInRoot ?: return false
            if (bounds.width <= 0f || bounds.height <= 0f || bounds.left < 0f || bounds.top < 0f ||
                bounds.right > 420f || bounds.bottom > HEIGHT.toFloat()) return false
            nanos = maxOf(System.nanoTime(), nanos + 1)
            return scene.render(nanos).use { image ->
                Bitmap().use { bitmap ->
                    check(bitmap.allocN32Pixels(image.width, image.height) && image.readPixels(bitmap))
                    // Four interior 3x3 patches require actual decoded pixels, not semantics
                    // or a placeholder. Solid PNG colors also identify late stale results.
                    listOf(-8, 8).all { dx -> listOf(-8, 8).all { dy ->
                        (-1..1).all { x -> (-1..1).all { y ->
                            bitmap.getColor(bounds.center.x.toInt() + dx + x,
                                bounds.center.y.toInt() + dy + y) == expected
                        } }
                    } }
                }
            }
        }
        fun returnButton() = all().filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null && nodes(node).any {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "回到底部" } == true
            }
        }.minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
        private fun render() { nanos = maxOf(System.nanoTime(), nanos + 1); scene.render(nanos).close() }
        suspend fun pumpFor(ms: Long) {
            val end = System.nanoTime() + ms * 1_000_000
            do { render(); delay(12) } while (System.nanoTime() < end)
        }
        suspend fun await(condition: () -> Boolean) {
            withTimeout(5_000) { do { render(); if (condition()) return@withTimeout; delay(12) } while (true) }
        }
        private fun time() = maxOf(System.nanoTime() / 1_000_000, eventMillis + 1).also { eventMillis = it }
        fun press(point: Offset) = scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = time(),
            buttons = PointerButtons(isPrimaryPressed = true))
        fun click(point: Offset) {
            press(point)
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = time(), buttons = PointerButtons())
        }
        fun moveUnpressed(point: Offset) = scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = time(), buttons = PointerButtons())
        suspend fun readOlderRows() {
            repeat(3) {
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(210f, 170f), scrollDelta = Offset(0f, -6f),
                    timeMillis = time(), buttons = PointerButtons())
                pumpFor(180)
            }
            await { !latestVisible() && returnButton() != null }
            // Let the real wheel animation/input-settle jobs finish before measuring an anchor.
            pumpFor(400)
        }
        override fun close() = scene.close()
    }
}

package com.bilipai.desktop.player

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.purebilibili.core.ui.LocalSystemReduceMotion
import com.android.purebilibili.core.ui.LocalVisualWorkAllowed
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.jetbrains.skia.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopBufferingAnimationCompositionTest {
    private class Tracked(val real: DesktopBufferingFrames) : DesktopBufferingFrames by real {
        val closes = AtomicInteger()
        val draws = mutableListOf<Double>()
        var destination: Rect? = null
        override fun draw(canvas: Canvas, destination: Rect, seconds: Double) {
            draws += seconds; this.destination = destination; real.draw(canvas, destination, seconds)
        }
        override fun close() { assertEquals(1, closes.incrementAndGet()); real.close() }
    }
    private class Host(val loader: (Boolean) -> DesktopBufferingFrames, initiallyReduced: Boolean = false) : AutoCloseable {
        val reduced = mutableStateOf(initiallyReduced)
        val allowed = mutableStateOf(true)
        val visible = mutableStateOf(true)
        val tint = mutableStateOf(Color.Red)
        val scheduler = TestCoroutineScheduler()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler) + CoroutineExceptionHandler { _, e -> errors += e })
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val scene = ImageComposeScene(120, 80, Density(1f), coroutineContext = scope.coroutineContext) {
            val window = LocalWindowInfo.current
            val focused = remember(window) { object : WindowInfo by window { override val isWindowFocused = true } }
            CompositionLocalProvider(LocalWindowInfo provides focused, LocalLifecycleOwner provides owner,
                LocalVisualWorkAllowed provides allowed.value, LocalSystemReduceMotion provides reduced.value) {
                if (visible.value) DesktopBufferingAnimation(tint.value, Modifier.requiredSize(120.dp, 72.dp), 72.dp, loader)
            }
        }
        fun frame(): IntArray {
            Snapshot.sendApplyNotifications(); scheduler.runCurrent()
            val pixels = scene.render(scheduler.currentTime * 1_000_000L).use { image ->
                Bitmap().use { b ->
                    assertTrue(b.allocN32Pixels(120,80)); assertTrue(image.readPixels(b))
                    IntArray(120*80) { b.getColor(it%120,it/120) }
                }
            }
            scheduler.runCurrent(); assertTrue(errors.isEmpty(), errors.toString())
            scheduler.advanceTimeBy(16)
            return pixels
        }
        fun until(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!condition()) { frame(); check(System.nanoTime()<deadline) { "Async result did not arrive" }; Thread.yield() }
        }
        override fun close() { scene.close(); scope.cancel(); scheduler.runCurrent(); assertTrue(errors.isEmpty(), errors.toString()) }
    }

    @Test fun cancellationDuringLoadClosesLateResultWithoutPublishingAndRecompositionDoesNotReload() {
        val started=CountDownLatch(1); val release=CountDownLatch(1)
        val loads=AtomicInteger(); lateinit var result: Tracked
        Host({ reduced ->
            loads.incrementAndGet(); result=Tracked(loadDesktopBufferingFrames(reduced)); started.countDown()
            check(release.await(10,TimeUnit.SECONDS)); result
        }).use { h ->
            assertTrue(h.frame().all { it ushr 24 == 0 })
            assertTrue(started.await(10,TimeUnit.SECONDS))
            h.tint.value=Color.Blue; h.frame(); h.tint.value=Color.Green; h.frame()
            assertEquals(1,loads.get()); assertTrue(result.draws.isEmpty())
            h.visible.value=false; h.frame(); release.countDown()
            h.until { result.closes.get()==1 }
            assertTrue(result.draws.isEmpty()); assertEquals(1,loads.get())
        }
    }

    @Test fun realPixelsAnimateFreezeResumeTintAndDisposeWithoutReloading() {
        val loads=AtomicInteger(); lateinit var result: Tracked
        Host({ reduced -> loads.incrementAndGet(); Tracked(loadDesktopBufferingFrames(reduced)).also { result=it } }).use { h ->
            h.until { loads.get()==1 && runCatching { result.draws.isNotEmpty() }.getOrDefault(false) }
            val first=h.frame(); repeat(8) { h.frame() }; val later=h.frame()
            assertFalse(first.contentEquals(later))
            assertTrue(later.filter { it ushr 24 !=0 }.all { it and 0x00ffff == 0 }, "SRC_IN red tint")
            assertEquals(72f,result.destination!!.width); assertEquals(72f,result.destination!!.height)
            assertEquals(24f,result.destination!!.left, "non-square canvas must center the fitted square resource")
            h.allowed.value=false; h.frame(); h.frame(); val frozen=h.frame(); val stopped=result.draws.last()
            repeat(12) { assertContentEquals(frozen,h.frame()) }
            assertEquals(stopped,result.draws.last())
            h.scheduler.advanceTimeBy(100_000)
            h.allowed.value=true; h.frame(); h.frame()
            assertTrue(result.draws.last()-stopped < 0.1, "resume must not catch up background time")
            h.tint.value=Color.Blue; h.frame(); h.tint.value=Color.Green; h.frame(); assertEquals(1,loads.get())
            h.visible.value=false; h.frame(); assertEquals(1,result.closes.get())
            val count=result.draws.size; repeat(10) { h.frame() }; assertEquals(count,result.draws.size)
        }
    }

    @Test fun reducedMotionSkipsDecoderAndDecodeFailureUsesRasterStaticFallback() {
        val decodes=AtomicInteger()
        fun read(path:String)=checkNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }
        val reduced=loadDesktopBufferingFrames(true,::read) { decodes.incrementAndGet(); error("must not decode animation") }
        assertFalse(reduced.animated); assertEquals(0,decodes.get()); reduced.close()
        val fallback=loadDesktopBufferingFrames(false,::read) { decodes.incrementAndGet(); error("broken animation") }
        assertFalse(fallback.animated); assertEquals(1,decodes.get())
        Surface.makeRasterN32Premul(72,72).use { s -> fallback.draw(s.canvas,Rect.makeWH(72f,72f),0.0) }
        fallback.close()
        assertFails { loadDesktopBufferingFrames(true,{ error("missing static resource") }) }
        val loads=AtomicInteger(); lateinit var result: Tracked
        Host({ flag -> assertTrue(flag); loads.incrementAndGet(); Tracked(loadDesktopBufferingFrames(flag,::read) { error("decoder in reduced mode") }).also { result=it } }, true).use { h ->
            h.until { runCatching { result.draws.isNotEmpty() }.getOrDefault(false) }
            val pixels=h.frame(); repeat(12) { assertContentEquals(pixels,h.frame()) }
            h.tint.value=Color.Blue; h.frame(); assertEquals(1,loads.get())
        }
        assertEquals(1,result.closes.get())
    }

    @Test fun packagedResourcesMatchMergedUpstreamBytes() {
        val names=mapOf("bili_tv_buffering.webp" to "raw", "bili_tv_buffering_static.png" to "drawable-nodpi")
        for ((name,folder) in names) {
            val packaged=checkNotNull(javaClass.getResourceAsStream("/video-buffering/$name")).use { it.readBytes() }
            val upstream=Files.readAllBytes(Path.of("../app/src/main/res/$folder/$name"))
            val sha=MessageDigest.getInstance("SHA-256")
            assertContentEquals(sha.digest(upstream),sha.digest(packaged))
        }
    }
}

package com.bilipai.desktop.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.LocalSystemReduceMotion
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.jetbrains.skia.Rect
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Architect 01a192ee: one static Canvas sample, then one first animation sample; no retries. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopBufferingAnimationUiTimingTest {
    @Test fun preparesAllPixelsOffUiAndFirstDisplayAddsLessThanOneFrameToStaticCanvas() = runBlocking {
        val bean=ManagementFactory.getThreadMXBean()
        assertTrue(bean.isCurrentThreadCpuTimeSupported); bean.isThreadCpuTimeEnabled=true
        val ui=Thread.currentThread().id
        val phases=CopyOnWriteArrayList<Pair<String,Long>>()
        val scheduler=TestCoroutineScheduler()
        val errors=mutableListOf<Throwable>()
        val scope=CoroutineScope(SupervisorJob()+StandardTestDispatcher(scheduler)+CoroutineExceptionHandler { _,e -> errors+=e })
        val ready=CountDownLatch(1)
        val background=AtomicLong()
        val draws=AtomicLong()
        var totalUi=0L
        val scene=ImageComposeScene(72,72,Density(1f),coroutineContext=scope.coroutineContext) {}
        val static=withContext(Dispatchers.Default) { loadDesktopBufferingFrames(true) }
        fun measure(action: () -> Unit) {
            val start=bean.currentThreadCpuTime
            try { action() } finally { totalUi+=bean.currentThreadCpuTime-start }
        }
        fun render() { Snapshot.sendApplyNotifications(); scheduler.runCurrent(); scene.render(0).close(); scheduler.runCurrent() }
        try {
            measure {
                scene.setContent {
                    Canvas(Modifier.size(72.dp)) { drawIntoCanvas { static.draw(it.nativeCanvas,Rect.makeWH(72f,72f),0.0) } }
                }
                render()
            }
            val baseline=totalUi/1_000_000.0; totalUi=0
            measure {
                scene.setContent {
                    CompositionLocalProvider(LocalSystemReduceMotion provides false) {
                        DesktopBufferingAnimation(Color.White,Modifier,72.dp) { reduced ->
                            val start=System.nanoTime()
                            val result=loadDesktopBufferingFrames(reduced,observePreparation={ stage -> phases+=stage to Thread.currentThread().id })
                            background.set(System.nanoTime()-start); ready.countDown()
                            object : DesktopBufferingFrames by result {
                                override fun draw(canvas: org.jetbrains.skia.Canvas, destination: Rect, seconds: Double) {
                                    result.draw(canvas,destination,seconds); draws.incrementAndGet()
                                }
                            }
                        }
                    }
                }
                render()
            }
            assertTrue(ready.await(10,TimeUnit.SECONDS))
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(draws.get()==0L) { measure { render() }; check(System.nanoTime()<deadline); Thread.yield() }
            val first=totalUi/1_000_000.0
            val warm=mutableListOf<Double>()
            repeat(8) { n ->
                val before=bean.currentThreadCpuTime
                scene.render((n+1)*16_000_000L).close(); scheduler.runCurrent()
                warm+=(bean.currentThreadCpuTime-before)/1_000_000.0
            }
            println("BUFFERING_UI_TIMING baselineUiCpuMs=$baseline animationFirstUiCpuMs=$first deltaUiCpuMs=${first-baseline} backgroundWallMs=${background.get()/1_000_000.0} warmUiCpuMs=$warm uiThread=$ui phases=$phases")
            assertEquals(listOf("readStaticBytes","decodeStaticPixels","readAnimationBytes","constructDecoder","decodeFrameZero"),phases.map { it.first })
            assertTrue(phases.all { it.second!=ui },"all reads and pixel preparation must run off UI")
            assertTrue(errors.isEmpty(),errors.toString())
            assertTrue(first-baseline<6.0,"First animation UI delta exceeded one 165 Hz frame: ${first-baseline}")
        } finally { scene.close(); scope.cancel(); scheduler.runCurrent(); static.close() }
    }
}

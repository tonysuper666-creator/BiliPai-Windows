package com.bilipai.desktop.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.bilipai.desktop.ui.DesktopToastController
import com.bilipai.desktop.ui.DesktopToastHost
import kotlinx.coroutines.test.UncaughtExceptionsBeforeTest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** TON-1114 diagnostic probe: does a toast timer fired on the timer thread leak an uncaught failure into the next runTest? */
class DesktopKitTimerThreadProbeTest {
    private fun cycle(idle: Boolean) {
        val controller = DesktopToastController()
        val scene = ImageComposeScene(480, 320, Density(1f)) {
            Box(Modifier.fillMaxSize()) { DesktopToastHost(controller, lifetimeMillis = { 40L }) }
        }
        var nanos = 0L
        fun render() { nanos += 30_000_000L; scene.render(nanos).close() }
        try {
            repeat(3) { render() }
            controller.show("Later")
            repeat(3) { render() }
            if (idle) Thread.sleep(150) // the timer fires while the render thread does nothing
            val end = System.nanoTime() + 3_000_000_000L
            while (System.nanoTime() < end && controller.state.current != null) {
                Snapshot.sendApplyNotifications(); render(); Thread.sleep(16)
            }
            assertTrue(controller.state.current == null, "toast expired")
        } finally { scene.close() }
    }

    @Test fun probe() {
        val leaks = mutableListOf<String>()
        for (round in 1..40) {
            cycle(idle = round % 2 == 0)
            try { runTest { } } catch (e: UncaughtExceptionsBeforeTest) {
                leaks += "round $round: ${e.suppressedExceptions.map { it.message?.take(120) }}"
            }
        }
        println("PROBE_LEAKS=${leaks.size}")
        leaks.forEach { println("PROBE_LEAK $it") }
        assertTrue(leaks.isEmpty(), leaks.joinToString("\n"))
    }
}

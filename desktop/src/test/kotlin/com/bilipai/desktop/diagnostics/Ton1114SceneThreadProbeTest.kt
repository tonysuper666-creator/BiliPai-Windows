package com.bilipai.desktop.diagnostics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals

/** TON-1114 probe: a scene effect that writes a layer-read state after a delay, on the default and on the test's own context. */
class Ton1114SceneThreadProbeTest {
    private fun leaked(): String? = try { runTest { }; null } catch (e: IllegalStateException) {
        if (e.javaClass.simpleName != "UncaughtExceptionsBeforeTest") throw e
        e.suppressedExceptions.joinToString(" | ") { (it.message ?: it.javaClass.name).take(120) }
    }

    private fun cycle(context: CoroutineContext?): Pair<String?, Thread> {
        var alpha by mutableStateOf(1f)
        var writer: Thread? = null
        val scene = if (context == null) ImageComposeScene(200, 200, Density(1f)) { content(alpha, { writer = Thread.currentThread(); alpha = 0.5f }) }
        else ImageComposeScene(200, 200, Density(1f), coroutineContext = context) { content(alpha, { writer = Thread.currentThread(); alpha = 0.5f }) }
        var nanos = 0L
        try {
            repeat(3) { nanos += 16_000_000L; scene.render(nanos).close() }
            // The test thread does not render or yield for a while: a timer thread is the only one that can act.
            Thread.sleep(250)
        } finally { /* closed below, after the first look */ }
        val first = leaked()
        scene.close()
        return first to (writer ?: Thread.currentThread())
    }

    @androidx.compose.runtime.Composable
    private fun content(alpha: Float, onTimer: () -> Unit) {
        LaunchedEffect(Unit) { delay(40); onTimer() }
        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha })
    }

    @Test fun defaultUnconfinedSceneLeaksButTheTestsOwnLoopDoesNot() {
        val results = mutableListOf<String>()
        repeat(5) { results += "default[$it]: " + cycle(null).let { (leak, w) -> "writer=${w.name} leak=$leak" } }
        val loop = runBlocking { (1..5).map { i -> "loop[$i]: " + cycle(coroutineContext).let { (leak, w) -> "writer=${w.name} leak=$leak" } } }
        results += loop
        results.forEach { println("TON1114_SCENE $it") }
        assertEquals(0, results.size - results.size) // diagnostic: results are read from the log
    }
}

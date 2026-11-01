package com.bilipai.desktop.ui.kit

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertTrue

/**
 * TON-1114 diagnostic probe: runs the kit scene class repeatedly, with and without CPU contention, and asks
 * kotlinx-coroutines-test after every round whether a scene left an uncaught coroutine failure behind.
 */
class DesktopKitTimerThreadProbeTest {
    private fun roundOfKitClass(): Pair<Int, List<String>> {
        val failures = mutableListOf<String>()
        for (method in DesktopKitUiTest::class.java.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }) {
            try { method.invoke(DesktopKitUiTest()) } catch (e: InvocationTargetException) {
                failures += method.name + ": " + (e.targetException.message ?: e.targetException.javaClass.name).take(160)
            }
        }
        return failures.size to failures
    }

    private fun leakAfterRound(): String? = try { runTest { }; null } catch (e: IllegalStateException) {
        if (e.javaClass.simpleName != "UncaughtExceptionsBeforeTest") throw e
        e.suppressedExceptions.joinToString(" | ") { (it.message ?: it.javaClass.name).take(200) }
    }

    @Test fun probe() {
        val leaks = mutableListOf<String>()
        val spinners = mutableListOf<Thread>()
        val stop = AtomicBoolean(false)
        for (mode in listOf("idle", "loaded")) {
            if (mode == "loaded") repeat(Runtime.getRuntime().availableProcessors()) {
                spinners += Thread { var x = 0L; while (!stop.get()) x++ }.also { it.isDaemon = true; it.start() }
            }
            for (round in 1..25) {
                val (failed, failures) = roundOfKitClass()
                if (failed > 0) leaks += "$mode round $round: kit class failures $failures"
                leakAfterRound()?.let { leaks += "$mode round $round: leaked $it" }
            }
        }
        stop.set(true)
        println("PROBE_LEAKS=${leaks.size}")
        leaks.forEach { println("PROBE_LEAK $it") }
        assertTrue(leaks.isEmpty(), leaks.joinToString("\n"))
    }
}

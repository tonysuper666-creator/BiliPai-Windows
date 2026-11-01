package com.bilipai.desktop.ui.kit

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import kotlin.test.assertTrue

/**
 * TON-1114 diagnostic probe: runs the kit scene class repeatedly, with and without CPU contention, and asks
 * kotlinx-coroutines-test after every round whether a scene left an uncaught coroutine failure behind.
 */
class DesktopKitTimerThreadProbeTest {
    private fun roundOfKitClass(): Pair<Long, List<String>> {
        val listener = SummaryGeneratingListener()
        val request = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(DesktopKitUiTest::class.java)).build()
        LauncherFactory.create().execute(request, listener)
        val summary = listener.summary
        return summary.testsFailedCount to summary.failures.map { it.testIdentifier.displayName + ": " + it.exception.message?.take(160) }
    }

    private fun leakAfterRound(): String? = try { runTest { }; null } catch (e: IllegalStateException) {
        if (e.javaClass.simpleName != "UncaughtExceptionsBeforeTest") throw e
        e.suppressedExceptions.joinToString(" | ") { (it.message ?: it.javaClass.name).take(200) }
    }

    @Test fun probe() {
        val leaks = mutableListOf<String>()
        val spinners = mutableListOf<Thread>()
        @Volatile var stop = false
        for (mode in listOf("idle", "loaded")) {
            if (mode == "loaded") repeat(Runtime.getRuntime().availableProcessors()) {
                spinners += Thread { var x = 0L; while (!stop) x++ }.also { it.isDaemon = true; it.start() }
            }
            for (round in 1..25) {
                val (failed, failures) = roundOfKitClass()
                if (failed > 0) leaks += "$mode round $round: kit class failures $failures"
                leakAfterRound()?.let { leaks += "$mode round $round: leaked $it" }
            }
        }
        stop = true
        println("PROBE_LEAKS=${leaks.size}")
        leaks.forEach { println("PROBE_LEAK $it") }
        assertTrue(leaks.isEmpty(), leaks.joinToString("\n"))
    }
}

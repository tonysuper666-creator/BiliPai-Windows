@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bilipai.desktop.diagnostics

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.EventQueue
import java.awt.Window as AwtWindow
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JDialog

/** Actual Compose entry for an uncaught window exception. The failing coroutine
 * is launched from a composition scope with its own SupervisorJob, which is the
 * shape the running client uses (page entry scopes); it therefore reaches the
 * same ComposeContainer handler the client hits. Writes a JSON report and exits
 * non-zero when a gating observation fails. Run with
 * `desktopWindowExceptionUiSmoke`.
 *
 * This is a JavaExec fixture because the JUnit `test` task runs headless. */
object DesktopWindowExceptionHandlerUiFixture {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: DesktopWindowExceptionHandlerUiFixture <report.json>" }
        val report = Path.of(args[0])
        val recorded = CopyOnWriteArrayList<Throwable>()
        val notices = CopyOnWriteArrayList<String>()
        var noticeText by mutableStateOf<String?>(null)
        var counter by mutableStateOf(0)
        val noticeRendered = CountDownLatch(1)
        val effectAfterFailure = CountDownLatch(1)
        val uiScopeUsable = CountDownLatch(1)
        var compositionScope: CoroutineScope? = null
        val handler = DesktopWindowExceptionHandler(
            record = { recorded += it; true },
            fallback = { false },
            notify = { message -> notices += message; noticeText = message },
            fatal = { throw it },
        )
        var window: ComposeWindow? = null
        val observations = LinkedHashMap<String, String>()
        var failure: Throwable? = null
        try {
            EventQueue.invokeAndWait {
                val opened = ComposeWindow()
                window = opened
                opened.exceptionHandler = handler.exceptionHandler(opened)
                opened.setContent {
                    val scope = rememberCoroutineScope()
                    SideEffect { compositionScope = scope }
                    val value = counter
                    val notice = noticeText
                    SideEffect { if (notice != null) noticeRendered.countDown() }
                    LaunchedEffect(Unit) {
                        scope.launch { throw IllegalStateException("synthetic composition failure") }
                    }
                    LaunchedEffect(value) { if (value > 0) effectAfterFailure.countDown() }
                    Text(notice ?: "value=$value")
                }
                opened.isVisible = true
            }
            val opened = checkNotNull(window)

            // The failure must arrive through Compose's own handler path.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (notices.isEmpty() && System.nanoTime() < deadline) Thread.sleep(20L)
            observations["noticeStateSet"] = notices.isNotEmpty().toString()
            observations["recordedFailures"] = recorded.size.toString()
            observations["notice"] = notices.singleOrNull().orEmpty()
            observations["renderedInComposition"] = noticeRendered.await(5, TimeUnit.SECONDS).toString()

            Thread.sleep(750L)
            observations["systemDialogs"] =
                AwtWindow.getWindows().count { it is JDialog && it.isShowing }.toString()
            observations["windowAlive"] = (opened.isShowing && opened.isDisplayable).toString()

            // The UI coroutine scope must survive the failure and still run work.
            val scopeAfter = compositionScope
            if (scopeAfter == null) observations["compositionScopeUsable"] = "no-scope"
            else {
                scopeAfter.launch { uiScopeUsable.countDown() }
                observations["compositionScopeUsable"] = uiScopeUsable.await(5, TimeUnit.SECONDS).toString()
            }
            EventQueue.invokeAndWait { counter = 1 }
            observations["effectAfterFailure"] = effectAfterFailure.await(10, TimeUnit.SECONDS).toString()
        } catch (thrown: Throwable) {
            failure = thrown
        } finally {
            EventQueue.invokeAndWait { window?.dispose() }
        }
        val passed = failure == null && observations["noticeStateSet"] == "true" &&
            observations["recordedFailures"] == "1" && observations["systemDialogs"] == "0" &&
            observations["windowAlive"] == "true" && observations["compositionScopeUsable"] == "true"
        Files.createDirectories(report.toAbsolutePath().parent)
        Files.writeString(report, buildString {
            append("{\n  \"passed\": ").append(passed).append(",\n")
            for ((key, value) in observations)
                append("  \"").append(key).append("\": \"").append(value.replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\",\n")
            append("  \"failure\": ").append(failure?.let { "\"${it.javaClass.name}: ${it.message}\"" } ?: "null").append('\n')
            append("}\n")
        })
        println("WINDOW_EXCEPTION_UI_REPORT $report")
        if (!passed) kotlin.system.exitProcess(1)
    }
}

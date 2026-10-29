package com.bilipai.desktop.diagnostics

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import java.awt.EventQueue
import java.awt.Window as AwtWindow
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JDialog

/** Actual Compose entry for an uncaught window exception: the failing coroutine
 * is thrown from a composed effect, so the delivery goes through the same
 * ComposeContainer path the running client uses. Writes a JSON report and exits
 * non-zero when any observation fails. Run with `desktopWindowExceptionUiSmoke`.
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
        val recomposedAfterFailure = CountDownLatch(1)
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
                    val value = counter
                    val notice = noticeText
                    SideEffect {
                        if (notice != null) noticeRendered.countDown()
                        if (notice != null && value > 0) recomposedAfterFailure.countDown()
                    }
                    LaunchedEffect(Unit) { throw IllegalStateException("synthetic composition failure") }
                    Text(notice ?: "value=$value")
                }
                opened.isVisible = true
            }
            val opened = checkNotNull(window)
            observations["noticeDelivered"] = noticeRendered.await(30, TimeUnit.SECONDS).toString()
            observations["recordedFailures"] = recorded.size.toString()
            observations["notice"] = notices.singleOrNull().orEmpty()
            Thread.sleep(750L)
            observations["systemDialogs"] =
                AwtWindow.getWindows().count { it is JDialog && it.isShowing }.toString()
            observations["windowAlive"] = (opened.isShowing && opened.isDisplayable).toString()
            EventQueue.invokeAndWait { counter = 1 }
            observations["recomposedAfterFailure"] = recomposedAfterFailure.await(15, TimeUnit.SECONDS).toString()
        } catch (thrown: Throwable) {
            failure = thrown
        } finally {
            EventQueue.invokeAndWait { window?.dispose() }
        }
        val passed = failure == null && observations["noticeDelivered"] == "true" &&
            observations["recordedFailures"] == "1" && observations["systemDialogs"] == "0" &&
            observations["windowAlive"] == "true" && observations["recomposedAfterFailure"] == "true"
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

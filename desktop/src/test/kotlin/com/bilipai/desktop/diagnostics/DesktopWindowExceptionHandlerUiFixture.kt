@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bilipai.desktop.diagnostics

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.awt.ComposeWindow
import kotlinx.coroutines.launch
import java.awt.EventQueue
import java.awt.Window as AwtWindow
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.swing.JDialog

/** Actual Compose entry for an uncaught window exception. The failing coroutine
 * is launched from a composition scope with its own SupervisorJob, which is the
 * shape the running client uses (page entry scopes); it therefore reaches the
 * same ComposeContainer handler the client hits. The notice must then appear in
 * the desktop's own window, with no system dialog. Writes a JSON report and exits
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
        val notice = DesktopWindowExceptionNotice()
        val handler = DesktopWindowExceptionHandler(
            record = { recorded += it; true },
            fallback = { false },
            notify = { message -> notices += message; notice.show(message) },
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
                    LaunchedEffect(Unit) {
                        scope.launch { throw IllegalStateException("synthetic composition failure") }
                    }
                    Text("main window content")
                }
                opened.setSize(320, 200)
                opened.isVisible = true
            }
            val opened = checkNotNull(window)

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (notices.isEmpty() && System.nanoTime() < deadline) Thread.sleep(20L)
            observations["noticeDelivered"] = notices.isNotEmpty().toString()
            observations["recordedFailures"] = recorded.size.toString()
            val noticeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!notice.isShowing() && System.nanoTime() < noticeDeadline) Thread.sleep(20L)
            observations["noticeWindowShowing"] = notice.isShowing().toString()
            observations["noticeText"] = notice.currentMessage().orEmpty()
            observations["composeWindows"] =
                AwtWindow.getWindows().count { it is ComposeWindow && it.isShowing }.toString()

            Thread.sleep(750L)
            observations["systemDialogs"] =
                AwtWindow.getWindows().count { it is JDialog && it.isShowing }.toString()
            observations["mainWindowAlive"] = (opened.isShowing && opened.isDisplayable).toString()
        } catch (thrown: Throwable) {
            failure = thrown
        } finally {
            notice.close()
            EventQueue.invokeAndWait { window?.dispose() }
        }
        val passed = failure == null && observations["noticeDelivered"] == "true" &&
            observations["recordedFailures"] == "1" && observations["noticeWindowShowing"] == "true" &&
            observations["noticeText"]!!.contains("内部错误") && observations["systemDialogs"] == "0" &&
            observations["mainWindowAlive"] == "true"
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

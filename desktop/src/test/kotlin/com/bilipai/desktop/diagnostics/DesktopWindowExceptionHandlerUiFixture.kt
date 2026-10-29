@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bilipai.desktop.diagnostics

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import kotlinx.coroutines.launch
import java.awt.Container
import java.awt.EventQueue
import java.awt.Point
import java.awt.Window as AwtWindow
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JDialog

/** Actual Compose entry for an uncaught window exception, followed through to the
 * user's way back. The failing coroutine is launched from a composition scope with
 * its own SupervisorJob, the shape the running client uses; it reaches the same
 * ComposeContainer handler the client hits. Then:
 *
 *  1. the notice must appear in the desktop's own window, with no system dialog;
 *  2. the failed window must really be unresponsive (a state change no longer recomposes);
 *  3. a real mouse press and release on the notice's restart button must reach the
 *     restart action, and the UI that action produces must respond to a state change;
 *  4. a restart that reports failure, and one that never answers, must be visible
 *     in the notice instead of leaving a button that says "restarting" forever.
 *
 * Input is delivered as AWT mouse events to the notice window's Compose layer, the
 * same path a pointer takes. Writes a JSON report and exits non-zero when a gating
 * observation fails. Run with `desktopWindowExceptionUiSmoke`.
 *
 * This is a JavaExec fixture because the JUnit `test` task runs headless. */
object DesktopWindowExceptionHandlerUiFixture {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: DesktopWindowExceptionHandlerUiFixture <report.json>" }
        val report = Path.of(args[0])
        val observations = LinkedHashMap<String, String>()
        var failure: Throwable? = null
        try {
            recoveryScenario(observations)
            restartFailureScenario(observations)
            restartTimeoutScenario(observations)
        } catch (thrown: Throwable) {
            failure = thrown
        }
        val passed = failure == null && observations["noticeWindowShowing"] == "true" &&
            observations["recordedFailures"] == "1" && observations["noticeText"]!!.contains("重启客户端") &&
            observations["systemDialogs"] == "0" &&
            observations["failedWindowRecomposesAfterFailure"] == "false" &&
            observations["restartButtonLaidOut"] == "true" &&
            observations["restartActionReachedByClick"] == "true" &&
            observations["restartedWindowRecomposes"] == "true" &&
            observations["failedRestartVisible"] == "true" && observations["stuckRestartVisible"] == "true"
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

    /** The failed window, the notice, a real click, and the UI the restart produces. */
    private fun recoveryScenario(observations: MutableMap<String, String>) {
        val recorded = CopyOnWriteArrayList<Throwable>()
        val restartRequests = AtomicInteger()
        val failedComposed = AtomicInteger()
        val restartedComposed = AtomicInteger()
        var failedTick by mutableStateOf(0)
        var restartedTick by mutableStateOf(0)
        val notice = DesktopWindowExceptionNotice()
        var restarted: ComposeWindow? = null
        var window: ComposeWindow? = null
        val restartAction: DesktopRestartAction = { _ ->
            restartRequests.incrementAndGet()
            // What a restart gives the user: the broken window is gone and a live UI takes its place.
            EventQueue.invokeLater {
                window?.dispose()
                val fresh = ComposeWindow()
                restarted = fresh
                fresh.setContent {
                    SideEffect { restartedComposed.incrementAndGet() }
                    Text("restarted window content $restartedTick")
                }
                fresh.setSize(320, 200)
                fresh.isVisible = true
            }
        }
        val handler = DesktopWindowExceptionHandler(
            record = { recorded += it; true },
            fallback = { false },
            notify = { message -> notice.show(message, restartAction) },
            fatal = { throw it },
        )
        try {
            EventQueue.invokeAndWait {
                val opened = ComposeWindow()
                window = opened
                opened.exceptionHandler = handler.exceptionHandler(opened)
                opened.setContent {
                    val scope = rememberCoroutineScope()
                    SideEffect { failedComposed.incrementAndGet() }
                    LaunchedEffect(Unit) {
                        scope.launch { throw IllegalStateException("synthetic composition failure") }
                    }
                    Text("main window content $failedTick")
                }
                opened.setSize(320, 200)
                opened.isVisible = true
            }

            waitUntil(30) { notice.isShowing() }
            observations["noticeWindowShowing"] = notice.isShowing().toString()
            observations["recordedFailures"] = recorded.size.toString()
            observations["noticeText"] = notice.currentMessage().orEmpty()
            observations["composeWindowsWithNotice"] =
                AwtWindow.getWindows().count { it is ComposeWindow && it.isShowing }.toString()

            // The failure being modelled: after it, the window no longer reacts to state.
            Thread.sleep(500L)
            val composedBefore = failedComposed.get()
            failedTick += 1
            Thread.sleep(750L)
            observations["failedWindowRecomposesAfterFailure"] = (failedComposed.get() != composedBefore).toString()
            observations["systemDialogs"] = AwtWindow.getWindows().count { it is JDialog && it.isShowing }.toString()

            val laidOut = waitUntil(10) { notice.restartButtonCenter() != null }
            observations["restartButtonLaidOut"] = laidOut.toString()
            val center = checkNotNull(notice.restartButtonCenter()) { "the restart button was never laid out" }
            observations["noticeComponentTree"] = describeTree(checkNotNull(notice.noticeWindow()))
            observations["buttonCenter"] = "${center.x},${center.y}"
            click(checkNotNull(notice.noticeWindow()), center)
            val reached = waitUntil(10) { restartRequests.get() > 0 }
            observations["restartActionReachedByClick"] = (reached && restartRequests.get() == 1).toString()
            observations["noticeStatusAfterClick"] = notice.currentRestartStatus().orEmpty()

            waitUntil(10) { restarted?.isShowing == true }
            Thread.sleep(500L)
            val restartedBefore = restartedComposed.get()
            restartedTick += 1
            val recomposed = waitUntil(10) { restartedComposed.get() != restartedBefore }
            observations["restartedWindowRecomposes"] = recomposed.toString()
        } finally {
            notice.close()
            EventQueue.invokeAndWait { window?.dispose(); restarted?.dispose() }
        }
    }

    /** A restart that reports failure must say so in the notice and let the user try again. */
    private fun restartFailureScenario(observations: MutableMap<String, String>) {
        val notice = DesktopWindowExceptionNotice()
        try {
            notice.show("synthetic notice", { onFailure -> onFailure("无法确定客户端启动路径") })
            waitUntil(30) { notice.restartButtonCenter() != null }
            click(checkNotNull(notice.noticeWindow()), checkNotNull(notice.restartButtonCenter()))
            val visible = waitUntil(10) { notice.currentRestartStatus()?.contains("重启未成功：无法确定客户端启动路径") == true }
            observations["failedRestartVisible"] = visible.toString()
        } finally {
            notice.close()
        }
    }

    /** A restart that never answers must not leave "restarting" on screen for good. */
    private fun restartTimeoutScenario(observations: MutableMap<String, String>) {
        val notice = DesktopWindowExceptionNotice(restartTimeoutMillis = 1500)
        try {
            notice.show("synthetic notice", { _ -> })
            waitUntil(30) { notice.restartButtonCenter() != null }
            click(checkNotNull(notice.noticeWindow()), checkNotNull(notice.restartButtonCenter()))
            val sawProgress = waitUntil(5) { notice.currentRestartStatus()?.contains("正在重启") == true }
            val visible = waitUntil(10) { notice.currentRestartStatus()?.contains("没有完成") == true }
            observations["stuckRestartVisible"] = (sawProgress && visible).toString()
        } finally {
            notice.close()
        }
    }

    private fun waitUntil(seconds: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(20L)
        }
        return condition()
    }

    /** A pointer click as AWT delivers it: enter, move, press, release, click on the Compose layer. */
    private fun click(window: ComposeWindow, at: Point) {
        EventQueue.invokeAndWait {
            // The component a pointer at this spot would reach: the Skia layer under the content pane.
            val layer = javax.swing.SwingUtilities.getDeepestComponentAt(window.contentPane, at.x, at.y)
                ?: error("no component at the restart button inside the notice window")
            check(layer.javaClass.name.contains("SkiaLayer")) { "input would reach ${layer.javaClass.name}, not the Compose layer" }
            fun send(id: Int, modifiers: Int, button: Int) = layer.dispatchEvent(
                MouseEvent(layer, id, System.currentTimeMillis(), modifiers, at.x, at.y, 1, false, button))
            send(MouseEvent.MOUSE_ENTERED, 0, MouseEvent.NOBUTTON)
            send(MouseEvent.MOUSE_MOVED, 0, MouseEvent.NOBUTTON)
            send(MouseEvent.MOUSE_PRESSED, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1)
            send(MouseEvent.MOUSE_RELEASED, 0, MouseEvent.BUTTON1)
            send(MouseEvent.MOUSE_CLICKED, 0, MouseEvent.BUTTON1)
        }
    }

    private fun describeTree(root: Container, depth: Int = 0): String = root.components.joinToString("") { child ->
        "\n" + "  ".repeat(depth) + child.javaClass.name + " " + child.bounds.let { "${it.x},${it.y} ${it.width}x${it.height}" } +
            ((child as? Container)?.let { describeTree(it, depth + 1) } ?: "")
    }
}

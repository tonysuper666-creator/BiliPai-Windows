package com.bilipai.desktop.diagnostics

import com.bilipai.desktop.DesktopApplicationRestart
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Machine-checkable evidence for the Windows integration card, produced inside a normal launch:
 * the restored window rectangle, the window this process actually shows, the install's process
 * list, and what the system media transport controls answer. Started only by
 * `--integration-self-test=<report>`; it never changes user data or opens other applications.
 */
internal object DesktopIntegrationSelfTest {
    private val defaultMove = intArrayOf(200, 150, 1600, 1000)

    private interface Rect32 : StdCallLibrary {
        fun GetWindowRect(hwnd: Pointer, rect: IntArray): Boolean
        companion object {
            val instance: Rect32 by lazy { Native.load("user32", Rect32::class.java) }
        }
    }

    fun start(report: File, smtcReport: File?, move: IntArray? = null, smtcDelaySeconds: Int = 0) {
        Thread({ run(report, smtcReport, move ?: defaultMove, smtcDelaySeconds) }, "BiliPai-integration-self-test")
            .apply { isDaemon = true }.start()
    }

    private fun run(report: File, smtcReport: File?, move: IntArray, smtcDelaySeconds: Int) {
        val body = runCatching { measure(report, smtcReport, move, smtcDelaySeconds) }.getOrElse { error ->
            "{\"error\":\"${escape(error.toString())}\"}"
        }
        report.absoluteFile.parentFile?.mkdirs()
        report.writeText(body)
        println("INTEGRATION_SELF_TEST $body")
    }

    private fun measure(report: File, smtcReport: File?, move: IntArray, smtcDelaySeconds: Int): String {
        val window = awaitWindow(90_000) ?: return "{\"window\":null,\"error\":\"no window after 90 s\"}"
        val restored = rectangle(window)
        onEdt {
            window.setBounds(move[0], move[1], move[2], move[3])
            window.toFront()
        }
        Thread.sleep(6_000)
        val moved = rectangle(window)
        val before = processes()
        val successorReport = File(report.absoluteFile.parentFile, "successor.json").apply { delete() }
        val child = DesktopApplicationRestart.prepare()
            .launch(listOf("--single-instance-probe=" + successorReport.absolutePath))
        val exited = child.waitFor(30, TimeUnit.SECONDS)
        if (!exited) child.destroyForcibly()
        val after = processes()
        if (smtcDelaySeconds > 0) Thread.sleep(smtcDelaySeconds * 1_000L)
        val smtc = smtcReport?.let { observeSystemMedia(it) }
        return buildString {
            append("{\"ownerPid\":").append(ProcessHandle.current().pid())
            append(",\"windowVisible\":").append(window.isShowing)
            append(",\"windowRestored\":").append(restored)
            append(",\"windowMoved\":").append(moved)
            append(",\"moveRequested\":[").append(move.joinToString(",")).append("]")
            append(",\"processesBefore\":").append(before)
            append(",\"successor\":{")
            append("\"childPid\":").append(child.pid())
            append(",\"childExited\":").append(exited)
            append(",\"childExitCode\":").append(if (exited) child.exitValue() else -1)
            append(",\"report\":").append(if (successorReport.isFile) successorReport.readText().trim() else "null")
            append("}")
            append(",\"processesAfter\":").append(after)
            if (smtc != null) append(",\"systemMedia\":").append(smtc)
            append("}")
        }
    }

    private fun awaitWindow(timeoutMs: Long): Window? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = onEdt {
                Window.getWindows().firstOrNull { it.isShowing && it.width > 0 && it.height > 0 }
            }
            if (found != null) return found
            Thread.sleep(250)
        }
        return null
    }

    private fun rectangle(window: Window): String {
        val bounds = onEdt { window.bounds }
        val native = if (System.getProperty("os.name").startsWith("Windows")) runCatching {
            val hwnd = Native.getWindowPointer(window)
            val rect = IntArray(4)
            if (Rect32.instance.GetWindowRect(hwnd, rect)) rect else null
        }.getOrNull() else null
        return "{\"x\":${bounds.x},\"y\":${bounds.y},\"w\":${bounds.width},\"h\":${bounds.height}" +
            ",\"osRect\":${native?.let { "[${it[0]},${it[1]},${it[2]},${it[3]}]" } ?: "null"}}"
    }

    private fun processes(): String {
        val executable = ProcessHandle.current().info().command().orElse("")
        val rows = ProcessHandle.allProcesses()
            .filter { it.info().command().orElse("") == executable }
            .map { "{\"pid\":${it.pid()},\"start\":${it.info().startInstant().map { s -> "\"$s\"" }.orElse("null")}}" }
            .toList()
        return "[${rows.joinToString(",")}]"
    }

    /** The system view, asked in this session by a separate PowerShell process. */
    private fun observeSystemMedia(target: File): String {
        target.absoluteFile.parentFile?.mkdirs()
        target.delete()
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            Add-Type -AssemblyName System.Runtime.WindowsRuntime
            ${'$'}asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
              ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.GetParameters().Count -eq 1 -and ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
            function Await-Op(${'$'}op, ${'$'}type) {
              ${'$'}task = ${'$'}asTask.MakeGenericMethod(${'$'}type).Invoke(${'$'}null, @(${'$'}op))
              if (-not ${'$'}task.Wait(15000)) { throw 'await timeout' }
              ${'$'}task.Result
            }
            ${'$'}managerType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
            ${'$'}propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]
            ${'$'}manager = Await-Op (${'$'}managerType::RequestAsync()) ${'$'}managerType
            ${'$'}rows = @()
            foreach (${'$'}session in ${'$'}manager.GetSessions()) {
              ${'$'}props = Await-Op (${'$'}session.TryGetMediaPropertiesAsync()) ${'$'}propsType
              ${'$'}rows += [ordered]@{ aumid = ${'$'}session.SourceAppUserModelId; title = ${'$'}props.Title;
                artist = ${'$'}props.Artist; status = ${'$'}session.GetPlaybackInfo().PlaybackStatus.ToString() }
            }
            (${'$'}rows | ConvertTo-Json -Depth 4 -Compress) | Set-Content -Encoding utf8 -LiteralPath '${escape(target.absolutePath)}'
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectErrorStream(true).start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val answer = if (target.isFile) target.readText().trim().removePrefix("\uFEFF") else ""
        // The report embeds this verbatim, so anything that is not a JSON object/array stays out of it.
        return if (answer.startsWith("[") || answer.startsWith("{")) answer
        else "{\"error\":\"system media query failed\"}"
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: T? = null
        SwingUtilities.invokeAndWait { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "'")
}

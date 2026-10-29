@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bilipai.desktop.diagnostics

import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/** Sanitized local backup for one uncaught window exception. It exists so a
 * failure is never lost when the diagnostic actor is absent or its write does
 * not succeed, and it reports whether the record really reached the disk. */
internal class DesktopWindowExceptionFallbackLog(
    private val file: Path = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"),
        "BiliPaiWindows", "diagnostics", "window-exceptions.log"),
    private val limitBytes: Long = 256L * 1024L,
    private val now: () -> Instant = Instant::now,
) {
    fun append(failure: Throwable): Boolean = runCatching {
        val record = sanitizeDesktopDiagnosticText(buildString {
            append(now().toString()).append(' ').append(failure.javaClass.name)
            append(": ").append(failure.message.orEmpty()).append('\n')
            append(failure.stackTraceToString()).append('\n')
        })
        file.parent?.let(Files::createDirectories)
        val bytes = record.toByteArray(Charsets.UTF_8)
        val existing = if (Files.isRegularFile(file)) Files.size(file) else 0L
        val options = if (existing + bytes.size > limitBytes)
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        else arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
        Files.write(file, bytes, *options)
        true
    }.getOrDefault(false)
}

/** Compose's default factory answers an uncaught window exception with a system
 * message box (`JOptionPane`) and then rethrows the failure. The desktop records
 * the failure first and keeps the notice inside its own window; only an error the
 * JVM cannot recover from takes the fatal path, and that path is explicit. */
internal class DesktopWindowExceptionHandler(
    private val record: (Throwable) -> Boolean,
    private val fallback: (Throwable) -> Boolean,
    private val notify: (String) -> Unit,
    private val fatal: (Throwable) -> Unit,
) : WindowExceptionHandlerFactory {
    override fun exceptionHandler(window: java.awt.Window): WindowExceptionHandler =
        object : WindowExceptionHandler {
            override fun onException(failure: Throwable) = handle(failure)
        }

    /** The whole reaction, so it is exercised without an AWT window. The notice
     * states what really happened to the log, never a hopeful claim. */
    fun handle(failure: Throwable) {
        val recorded = runCatching { record(failure) }.getOrDefault(false)
        val backedUp = recorded || runCatching { fallback(failure) }.getOrDefault(false)
        if (isFatal(failure)) {
            fatal(failure)
            return
        }
        // A last-resort handler that threw here would replace the original failure
        // with its own, so only the notice step is guarded; the record outcome is
        // already part of the message.
        runCatching { notify(message(recorded, backedUp, failure)) }
    }

    companion object {
        /** Errors with no recovery path: the process must not pretend to survive. */
        fun isFatal(failure: Throwable): Boolean =
            failure is VirtualMachineError || failure is LinkageError || failure is ThreadDeath

        /** Stable user-facing text only; no exception text, URL or account detail. */
        fun message(recorded: Boolean, backedUp: Boolean, failure: Throwable): String {
            val name = failure.javaClass.simpleName
            return when {
                recorded -> "界面遇到一个内部错误（$name），已记录到诊断日志。" +
                    "回到首页重新打开即可继续使用；反复出现请在设置里导出日志。"
                backedUp -> "界面遇到一个内部错误（$name），诊断日志不可用，已写入本地后备日志 window-exceptions.log。" +
                    "回到首页重新打开即可继续使用。"
                else -> "界面遇到一个内部错误（$name），本次未能保存日志。请重试；仍反复出现请重启客户端。"
            }
        }
    }
}

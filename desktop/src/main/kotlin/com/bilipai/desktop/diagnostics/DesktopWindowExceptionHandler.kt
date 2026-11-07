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
 * not succeed, and it reports whether the record really reached the disk. The
 * file never exceeds [limitBytes]: one record is cut to that size on a UTF-8
 * character boundary, and the older content is rotated out when it would overflow. */
internal class DesktopWindowExceptionFallbackLog(
    private val file: Path = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"),
        "BiliPaiWindows", "diagnostics", "window-exceptions.log"),
    private val limitBytes: Long = 256L * 1024L,
    private val now: () -> Instant = Instant::now,
) {
    fun append(failure: Throwable): Boolean = runCatching {
        val record = sanitizeDesktopDiagnosticText(describe(failure))
        file.parent?.let(Files::createDirectories)
        val bytes = boundedUtf8(record, limitBytes)
        val existing = if (Files.isRegularFile(file)) Files.size(file) else 0L
        val options = if (existing + bytes.size > limitBytes)
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        else arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
        Files.write(file, bytes, *options)
        true
    }.getOrDefault(false)

    /** The text handed to the sanitizer is bounded first: its patterns rescan a long
     * unbroken run from every position, so an exception message of a million
     * characters would hold the UI thread for minutes. Bounded here, the cost is fixed. */
    private fun describe(failure: Throwable): String = buildString {
        append(now().toString()).append(' ')
        var current: Throwable? = failure
        var depth = 0
        while (current != null && depth < MAX_CAUSES) {
            if (depth > 0) append("Caused by: ")
            append(current.javaClass.name).append(": ").append(clip(current.message.orEmpty(), MAX_MESSAGE_CHARS)).append('\n')
            for (frame in current.stackTrace.take(MAX_FRAMES)) append("\tat ").append(frame).append('\n')
            current = current.cause?.takeIf { it !== current }
            depth++
        }
    }

    internal companion object {
        private const val MAX_MESSAGE_CHARS = 4_096
        private const val MAX_FRAMES = 40
        private const val MAX_CAUSES = 5

        /** [text] cut to [max] characters without splitting a surrogate pair. */
        fun clip(text: String, max: Int): String {
            if (text.length <= max) return text
            val end = if (max > 0 && Character.isHighSurrogate(text[max - 1])) max - 1 else max
            return text.substring(0, end) + "...[message truncated]"
        }

        private val TRUNCATION_MARK = "\n[record truncated]\n".toByteArray(Charsets.UTF_8)

        /** UTF-8 bytes of [text], at most [limit] of them, never ending inside a character. */
        fun boundedUtf8(text: String, limit: Long): ByteArray {
            val all = text.toByteArray(Charsets.UTF_8)
            if (all.size <= limit) return all
            val mark = if (limit >= TRUNCATION_MARK.size) TRUNCATION_MARK else ByteArray(0)
            var end = (limit - mark.size).toInt()
            // 10xxxxxx bytes continue a character; back up to its first byte and drop it.
            while (end > 0 && (all[end].toInt() and 0xC0) == 0x80) end--
            return all.copyOfRange(0, end) + mark
        }
    }
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
                    "当前窗口可能已无法操作，请点击「重启客户端」恢复；反复出现请重启后在设置里导出日志。"
                backedUp -> "界面遇到一个内部错误（$name），诊断日志不可用，已写入本地后备日志 window-exceptions.log。" +
                    "当前窗口可能已无法操作，请点击「重启客户端」恢复。"
                else -> "界面遇到一个内部错误（$name），本次未能保存日志。当前窗口可能已无法操作，请点击「重启客户端」恢复。"
            }
        }
    }
}

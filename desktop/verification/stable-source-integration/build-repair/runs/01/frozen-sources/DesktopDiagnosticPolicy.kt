// GENERATED from app/src/main/java/com/android/purebilibili/core/util/Logger.kt; do not edit.
// LF-normalized SHA-256: aa4af545b0c4a59fd7e57d5b2e64b3c1506b7732042a51d1643ca331a05a7c5e
package com.android.purebilibili.core.util
import com.android.purebilibili.core.performance.AbnormalProcessExitException
import com.android.purebilibili.core.performance.nativeExitTraceSummary
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private const val LOG_DIRECTORY_NAME = "logs"
private const val RUNTIME_LOG_FILE_NAME = "runtime.log"
private const val BASIC_LOG_FILE_NAME = "basic.log"
private const val CRASH_SNAPSHOT_FILE_NAME = "last_crash_log.txt"
private const val RAW_CRASH_TRACE_FILE_NAME = "last_crash_trace.pb"
private const val CRASH_SNAPSHOT_MARKER_FILE_NAME = "pending_crash.marker"
private const val TOMBSTONE_BEGIN = "----- BEGIN TOMBSTONE PROTOBUF BASE64 -----"
private const val TOMBSTONE_END = "----- END TOMBSTONE PROTOBUF BASE64 -----"
private const val DOWNLOAD_LOG_RELATIVE_PATH = "Download/BiliPai/logs"
internal const val ENHANCED_DIAGNOSTIC_LOG_PREFS_NAME = "diagnostic_logging"
internal const val ENHANCED_DIAGNOSTIC_LOG_PREF_KEY = "enhanced_enabled"

internal fun resolveLogPersistenceDir(baseDir: File): File = File(baseDir, LOG_DIRECTORY_NAME)

internal fun resolveRuntimeLogFile(baseDir: File): File =
    File(resolveLogPersistenceDir(baseDir), RUNTIME_LOG_FILE_NAME)

internal fun resolveBasicLogFile(baseDir: File): File =
    File(resolveLogPersistenceDir(baseDir), BASIC_LOG_FILE_NAME)

internal fun resolveCrashSnapshotFile(baseDir: File): File =
    File(resolveLogPersistenceDir(baseDir), CRASH_SNAPSHOT_FILE_NAME)

internal fun resolveRawCrashTraceFile(baseDir: File): File =
    File(resolveLogPersistenceDir(baseDir), RAW_CRASH_TRACE_FILE_NAME)

internal fun resolveCrashSnapshotMarkerFile(baseDir: File): File =
    File(resolveLogPersistenceDir(baseDir), CRASH_SNAPSHOT_MARKER_FILE_NAME)

internal fun resolvePlayerDiagnosticExportFileName(
    exportedAtMillis: Long
): String {
    val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    return "player_diagnostic_${formatter.format(Date(exportedAtMillis))}.txt"
}

internal fun shouldEnableVerboseRuntimeLogs(
    isDebugBuild: Boolean,
    verboseDebugLogsEnabled: Boolean,
    enhancedDiagnosticLoggingEnabled: Boolean,
): Boolean = (isDebugBuild && verboseDebugLogsEnabled) || enhancedDiagnosticLoggingEnabled

internal fun shouldEmitVerboseLogcat(
    isDebugBuild: Boolean,
    verboseDebugLogsEnabled: Boolean,
): Boolean = isDebugBuild && verboseDebugLogsEnabled

internal fun shouldCaptureRuntimeLogEntry(
    level: String,
    verboseRuntimeLogsEnabled: Boolean
): Boolean = when (level) {
    "W", "E" -> true
    else -> verboseRuntimeLogsEnabled
}

internal fun shouldPersistRuntimeLogEntry(
    level: String,
    verboseRuntimeLogPersistenceEnabled: Boolean
): Boolean = level == "W" || level == "E" || verboseRuntimeLogPersistenceEnabled

internal fun hasExportableDiagnostics(
    logCount: Int,
    hasCrashSnapshot: Boolean,
    processExitCount: Int,
    profilingArtifactCount: Int
): Boolean = logCount > 0 || hasCrashSnapshot || processExitCount > 0 || profilingArtifactCount > 0

/** Limits bytes, including multibyte UTF-8 messages and a single oversized entry. */
internal fun appendRollingDiagnosticLog(file: File, text: String, maxBytes: Int) {
    require(maxBytes > 0)
    file.parentFile?.mkdirs()
    val incoming = text.toByteArray(Charsets.UTF_8)
    if (file.length() + incoming.size <= maxBytes) {
        file.appendBytes(incoming)
        return
    }
    val existing = if (file.isFile) file.readBytes() else byteArrayOf()
    val combined = existing + incoming
    var start = (combined.size - maxBytes / 2).coerceAtLeast(0)
    // Do not begin a retained file in the middle of a UTF-8 code point.
    while (start < combined.size && (combined[start].toInt() and 0xC0) == 0x80) start++
    file.writeBytes(combined.copyOfRange(start, combined.size))
}

internal fun sanitizeLogMessage(message: String): String =
    DesktopDiagnosticCollector.sanitizeMessage(message)

/** Older snapshots embedded base64 directly. Never run text redaction across that payload. */
internal fun removeEmbeddedNativeTombstone(content: String): String {
    val start = content.indexOf(TOMBSTONE_BEGIN)
    if (start < 0) return content
    val end = content.indexOf(TOMBSTONE_END, start + TOMBSTONE_BEGIN.length)
    if (end < 0) return content.substring(0, start) + "原始回溯数据不可用（旧版导出不完整）\n"
    return content.replaceRange(
        start,
        end + TOMBSTONE_END.length,
        "原始回溯数据已从文本日志中移除；如有保留，可单独分享 .pb 附件"
    )
}

/** Keep multiline exceptions attached to their timestamp while merging basic and verbose logs. */
internal fun groupDiagnosticLogLines(lines: List<String>): List<String> {
    val entries = mutableListOf<String>()
    var current: StringBuilder? = null
    lines.forEach { line ->
        if (diagnosticTimestampKey(line) != null) {
            current?.toString()?.takeIf(String::isNotBlank)?.let(entries::add)
            current = StringBuilder(line)
        } else if (current != null) {
            current?.apply { append('\n'); append(line) }
        } else if (line.isNotBlank()) {
            entries.add(line)
        }
    }
    current?.toString()?.takeIf(String::isNotBlank)?.let(entries::add)
    return entries
}

internal fun mergeDiagnosticLogEntries(
    persistedEntries: List<String>,
    inMemoryEntries: List<String>,
): List<String> = (persistedEntries + inMemoryEntries)
    .filter(String::isNotBlank)
    .distinct()
    .sortedBy { diagnosticTimestampKey(it) ?: "9999-99-99 99:99:99.999" }

private fun diagnosticTimestampKey(entry: String): String? =
    entry.takeIf { it.length >= 25 && it[0] == '[' && it[24] == ']' }
        ?.substring(1, 24)

internal fun resolveLogArtifactDirsToClear(
    filesDir: File,
    cacheDir: File
): List<File> = listOf(
    resolveLogPersistenceDir(filesDir),
    resolveLogPersistenceDir(cacheDir)
).distinctBy { it.absolutePath }

internal fun hasPendingCrashSnapshot(
    markerExists: Boolean,
    snapshotExists: Boolean
): Boolean = markerExists && snapshotExists

internal fun buildCrashSnapshotContent(
    throwable: Throwable,
    entries: List<DesktopDiagnosticCollector.LogEntry>,
    exportedAtMillis: Long,
    appVersionName: String,
    versionCode: Int,
    manufacturer: String,
    model: String,
    androidRelease: String,
    apiLevel: Int,
    buildType: String = "unknown",
    buildCommit: String = "unknown"
): String {
    val headerDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    return buildString {
        appendLine("========================================")
        appendLine("BiliPai 崩溃日志快照")
        appendLine("========================================")
        appendLine("生成时间: ${headerDateFormat.format(Date(exportedAtMillis))}")
        appendLine("应用版本: $appVersionName ($versionCode)")
        appendLine("构建: $buildType / $buildCommit")
        appendLine("设备信息: $manufacturer $model")
        appendLine("系统版本: $androidRelease；运行时: $apiLevel")
        appendLine("异常类型: ${throwable.javaClass.simpleName}")
        appendLine("异常信息: ${sanitizeLogMessage(throwable.message.orEmpty())}")
        appendLine("========================================")
        appendLine()
        appendLine("----- Throwable -----")
        val nativeTrace = (throwable as? AbnormalProcessExitException)?.nativeTrace
        val throwableText = if (!nativeTrace.isNullOrBlank()) {
            buildString {
                appendLine(throwable.toString())
                appendLine()
                appendLine("----- 系统异常回溯摘要 -----")
                appendLine(nativeExitTraceSummary(nativeTrace))
                if (nativeTrace.contains(TOMBSTONE_BEGIN)) {
                    appendLine("原始回溯: 不在本文本内；如保存成功，可主动选择分享 .pb 附件")
                }
            }
        } else {
            throwable.stackTraceToString()
        }
        appendLine(sanitizeLogMessage(throwableText))
        appendLine("----- Recent Logs -----")
        entries.forEach { appendLine(it.format()) }
    }
}

// GENERATED from app/src/main/java/com/android/purebilibili/core/util/Logger.kt; do not edit.
// LF-normalized SHA-256: 8564e68e99c3e557c9b52811803792fdaa155f483d5b015e5c9a4ab1a1925111
package com.android.purebilibili.core.util
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private const val LOG_DIRECTORY_NAME = "logs"
private const val RUNTIME_LOG_FILE_NAME = "runtime.log"
private const val BASIC_LOG_FILE_NAME = "basic.log"
private const val CRASH_SNAPSHOT_FILE_NAME = "last_crash_log.txt"
private const val CRASH_SNAPSHOT_MARKER_FILE_NAME = "pending_crash.marker"
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
        appendLine(sanitizeLogMessage(throwable.stackTraceToString()))
        appendLine("----- Recent Logs -----")
        entries.forEach { appendLine(it.format()) }
    }
}

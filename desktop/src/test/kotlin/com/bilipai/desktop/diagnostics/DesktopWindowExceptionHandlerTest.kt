@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bilipai.desktop.diagnostics

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Compose's default window handler opens a system message box and rethrows.
 * The desktop records first, states the real log outcome, and keeps a fatal path. */
class DesktopWindowExceptionHandlerTest {
    private class Harness(
        val recordResult: () -> Boolean = { true },
        val fallbackResult: () -> Boolean = { true },
    ) {
        val recorded = mutableListOf<Throwable>()
        val fallbacks = mutableListOf<Throwable>()
        val notices = mutableListOf<String>()
        val fatal = mutableListOf<Throwable>()
        val handler = DesktopWindowExceptionHandler(
            record = { recorded += it; recordResult() },
            fallback = { fallbacks += it; fallbackResult() },
            notify = { notices += it },
            fatal = { fatal += it },
        )
    }

    @Test fun recordedFailureSaysTheLogWasWrittenAndSkipsTheBackup() {
        val h = Harness()
        val failure = IllegalStateException("Original resolved video metadata is required")

        h.handler.handle(failure)

        assertEquals(listOf<Throwable>(failure), h.recorded)
        assertTrue(h.fallbacks.isEmpty(), "a successful write needs no backup")
        val notice = h.notices.single()
        assertTrue(notice.contains("已记录到诊断日志"))
        assertFalse(notice.contains("Original resolved video metadata"), "no raw exception text in the notice")
        assertTrue(notice.contains("IllegalStateException"))
        assertTrue(h.fatal.isEmpty())
    }

    @Test fun absentDiagnosticsFallsBackToTheLocalRecordAndSaysSo() {
        val h = Harness(recordResult = { false }, fallbackResult = { true })

        h.handler.handle(RuntimeException("window failure"))

        assertEquals(1, h.recorded.size)
        assertEquals(1, h.fallbacks.size)
        val notice = h.notices.single()
        assertTrue(notice.contains("本地后备日志"))
        assertFalse(notice.contains("已记录到诊断日志"), "the notice must not claim the diagnostic log was written")
    }

    @Test fun aThrowingWriteIsAFailureAndStillFallsBack() {
        val h = Harness(recordResult = { throw IllegalStateException("disk unavailable") }, fallbackResult = { true })

        h.handler.handle(RuntimeException("window failure"))

        assertEquals(1, h.fallbacks.size)
        assertTrue(h.notices.single().contains("本地后备日志"))
    }

    @Test fun aTotalLogFailureIsAdmittedInsteadOfClaimedSaved() {
        val h = Harness(recordResult = { false }, fallbackResult = { false })

        h.handler.handle(RuntimeException("window failure"))

        val notice = h.notices.single()
        assertTrue(notice.contains("未能保存日志"))
        assertFalse(notice.contains("已记录到诊断日志"))
        assertFalse(notice.contains("本地后备日志"))
        assertTrue(h.fatal.isEmpty())
    }

    @Test fun aFailingNoticeStepNeverEscapesToTheWindow() {
        val records = mutableListOf<Throwable>()
        val handler = DesktopWindowExceptionHandler(
            record = { records += it; true }, fallback = { true },
            notify = { throw IllegalArgumentException("ui unavailable") },
            fatal = { throw it })

        handler.handle(RuntimeException("window failure"))

        assertEquals(1, records.size)
    }

    @Test fun nonRecoverableErrorsTakeTheFatalPathInsteadOfAnInAppNotice() {
        for (failure in listOf(LinkageError("synthetic fatal"), OutOfMemoryError("synthetic fatal"),
                StackOverflowError(), ThreadDeath())) {
            val h = Harness()
            h.handler.handle(failure)
            assertEquals(listOf<Throwable>(failure), h.fatal, "${failure.javaClass.simpleName} must stay fatal")
            assertTrue(h.notices.isEmpty(), "${failure.javaClass.simpleName} must not become a recoverable notice")
            assertEquals(1, h.recorded.size, "the fatal path still records first")
        }
    }

    @Test fun ordinaryFailuresAreNotFatal() {
        for (failure in listOf(IllegalStateException(), IllegalArgumentException(), RuntimeException(),
                AssertionError("assertion"), java.io.IOException("io"))) {
            assertFalse(DesktopWindowExceptionHandler.isFatal(failure), "$failure is recoverable")
        }
    }

    @Test fun backupRecordIsSanitizedAndReportsRealWrites() {
        val directory = Files.createTempDirectory("window-exception-fallback-")
        try {
            val file = directory.resolve("nested").resolve("window-exceptions.log")
            val log = DesktopWindowExceptionFallbackLog(file)
            val failure = IllegalStateException(
                "failed https://api.bilibili.com/x/player/playurl?auth_key=secret-value at C:\\Users\\tony\\videofile.mp4")

            assertTrue(log.append(failure))

            val text = Files.readString(file)
            assertTrue(text.contains("IllegalStateException"))
            assertFalse(text.contains("secret-value"), "an auth_key value must be removed")
            assertFalse(text.contains("tony"), "the user home must be removed")
            assertFalse(text.contains("api.bilibili.com"), "the network address must be removed")
        } finally {
            deleteCreatedDirectory(directory)
        }
    }

    @Test fun backupRecordReportsFailureWhenNothingCanBeWritten() {
        val directory = Files.createTempDirectory("window-exception-unwritable-")
        try {
            // A regular file cannot become the parent directory of the log.
            val blocker = directory.resolve("blocker")
            Files.writeString(blocker, "not a directory")
            assertFalse(DesktopWindowExceptionFallbackLog(blocker.resolve("window-exceptions.log"))
                .append(IllegalStateException("cannot write")))
        } finally {
            deleteCreatedDirectory(directory)
        }
    }

    @Test fun backupRecordStaysBoundedInsteadOfGrowingForever() {
        val directory = Files.createTempDirectory("window-exception-rotate-")
        try {
            val file = directory.resolve("window-exceptions.log")
            val log = DesktopWindowExceptionFallbackLog(file, limitBytes = 512L)
            assertTrue(log.append(IllegalStateException("failure 0 " + "x".repeat(80))))
            val oneRecord = Files.size(file)
            repeat(19) { assertTrue(log.append(IllegalStateException("failure ${it + 1} " + "x".repeat(80)))) }

            // 20 unabridged records would be far larger; the file is rewritten instead.
            assertTrue(Files.size(file) <= oneRecord + 512L,
                "the backup must not grow with the number of failures (one=$oneRecord, now=${Files.size(file)})")
            assertTrue(Files.readString(file).contains("IllegalStateException"))
        } finally {
            deleteCreatedDirectory(directory)
        }
    }

    private fun deleteCreatedDirectory(directory: Path) {
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

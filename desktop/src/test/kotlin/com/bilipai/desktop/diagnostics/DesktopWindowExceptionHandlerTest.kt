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

    @Test fun aSingleHugeRecordIsCutToTheLimitInsteadOfWrittenWhole() {
        val directory = Files.createTempDirectory("window-exception-huge-")
        try {
            val file = directory.resolve("window-exceptions.log")
            val limit = 256L * 1024L
            val log = DesktopWindowExceptionFallbackLog(file, limitBytes = limit)

            // 102,400 Han characters are 307,200 UTF-8 bytes in the message alone.
            assertTrue(log.append(IllegalStateException("\u6c49".repeat(102_400))))

            assertTrue(Files.size(file) <= limit, "one record must not pass the limit (now=${Files.size(file)})")
            val text = decodeStrictly(file)
            assertTrue(text.contains("IllegalStateException"), "the start of the record is kept")
            assertTrue(text.contains("truncated"), "a cut record says so")

            // A limit below one bounded record forces the byte cut on multi-byte text.
            val small = directory.resolve("small.log")
            assertTrue(DesktopWindowExceptionFallbackLog(small, limitBytes = 2048L)
                .append(IllegalStateException("\u6c49".repeat(102_400))))
            assertTrue(Files.size(small) <= 2048L, "now=${Files.size(small)}")
            assertTrue(decodeStrictly(small).contains("[record truncated]"))
        } finally {
            deleteCreatedDirectory(directory)
        }
    }

    @Test fun aHugeAsciiRecordAfterExistingContentStillStaysUnderTheLimit() {
        val directory = Files.createTempDirectory("window-exception-huge-ascii-")
        try {
            val file = directory.resolve("window-exceptions.log")
            val limit = 4096L
            val log = DesktopWindowExceptionFallbackLog(file, limitBytes = limit)
            repeat(3) { assertTrue(log.append(RuntimeException("small $it"))) }

            // An unbroken million-character message must also return promptly.
            val started = System.nanoTime()
            assertTrue(log.append(RuntimeException("x".repeat(1_000_000))))
            assertTrue((System.nanoTime() - started) < 30_000_000_000L, "bounding must happen before sanitizing")
            assertTrue(Files.size(file) <= limit, "now=${Files.size(file)}")

            // Rotation after the cut record keeps working and keeps the limit.
            repeat(30) { assertTrue(log.append(RuntimeException("after $it"))) }
            assertTrue(Files.size(file) <= limit, "now=${Files.size(file)}")
            assertTrue(decodeStrictly(file).contains("after 29"), "the newest record is the one that survives")
        } finally {
            deleteCreatedDirectory(directory)
        }
    }

    @Test fun recordKeepsFramesAndTheCauseChainWithoutRepeatingTheMessage() {
        val directory = Files.createTempDirectory("window-exception-cause-")
        try {
            val file = directory.resolve("window-exceptions.log")
            val log = DesktopWindowExceptionFallbackLog(file)
            assertTrue(log.append(IllegalStateException("outer", IllegalArgumentException("inner cause"))))

            val text = Files.readString(file)
            assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException: inner cause"))
            assertTrue(text.contains("\tat "), "stack frames are kept")
            assertEquals(1, Regex("outer").findAll(text).count(), "the message appears once")
        } finally {
            deleteCreatedDirectory(directory)
        }
    }

    @Test fun clipDoesNotSplitASurrogatePair() {
        val clipped = DesktopWindowExceptionFallbackLog.clip("a\ud83d\ude00b", 2)
        assertTrue(clipped.startsWith("a") && !clipped.contains('\ud83d'), clipped)
        assertEquals("abc", DesktopWindowExceptionFallbackLog.clip("abc", 3))
    }

    @Test fun truncationNeverSplitsAMultiByteCharacter() {
        // 1-, 2-, 3- and 4-byte characters, cut at every limit in a range that crosses their boundaries.
        val text = "a\u00e9\u6c49\ud83d\ude00".repeat(200)
        for (limit in 30L..80L) {
            val bytes = DesktopWindowExceptionFallbackLog.boundedUtf8(text, limit)
            assertTrue(bytes.size <= limit, "limit=$limit size=${bytes.size}")
            // Throws on a malformed or truncated sequence.
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
        }
        assertEquals(0, DesktopWindowExceptionFallbackLog.boundedUtf8("abc", 0L).size)
        assertContentEquals("abc".toByteArray(), DesktopWindowExceptionFallbackLog.boundedUtf8("abc", 3L))
    }

    @Test fun noticeNamesTheRestartAsTheWayBackAndNoLongerPromisesAHomeScreen() {
        for ((recorded, backedUp) in listOf(true to true, false to true, false to false)) {
            val text = DesktopWindowExceptionHandler.message(recorded, backedUp, IllegalStateException())
            assertTrue(text.contains("重启客户端"), "the way back must be named: $text")
            assertFalse(text.contains("回到首页"), "a dead window cannot go back to the home page: $text")
        }
    }

    private fun decodeStrictly(file: Path): String =
        Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString()

    private fun deleteCreatedDirectory(directory: Path) {
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

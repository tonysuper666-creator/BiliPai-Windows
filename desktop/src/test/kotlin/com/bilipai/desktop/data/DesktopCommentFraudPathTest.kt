package com.bilipai.desktop.data

import com.android.purebilibili.core.database.entity.CommentFraudRecord
import com.bilipai.desktop.update.UpdateStorage
import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopCommentFraudPathTest {
    @TempDir lateinit var temporary: Path

    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private fun dao(root: Path) = DesktopCommentFraudJsonDao(root, 71, { true }, { it(); true })

    @Test fun reopenRecordsThroughShortStateDirectory(): Unit = runBlocking {
        val longRoot = Files.createDirectory(temporary.resolve("Comment fraud long state directory"))
        val root = if (windows) shortAlias(longRoot) else CanonicalAliasFileSystem(longRoot).root
        val record = CommentFraudRecord(17, 19)
        dao(root).use { it.insertOrUpdate(record) }
        val file = root.resolve("comment_fraud_records/71/records.json")
        // Exercise the old predicate on every host, not just when NTFS supplies 8.3 names.
        assertNotEquals(file.toAbsolutePath().normalize(), UpdateStorage.existingPathWithoutLinks(file),
            "The fixture did not produce distinct lexical and canonical paths")
        assertEquals(longRoot.resolve("comment_fraud_records/71/records.json").toRealPath(NOFOLLOW_LINKS),
            UpdateStorage.existingPathWithoutLinks(file))
        dao(root).use { assertEquals(listOf(record), it.getAllRecords()) }
    }

    @Test fun symbolicStateDirectoryAndRecordFileAreRejected() {
        val target = Files.createDirectory(temporary.resolve("actual"))
        val stateLink = temporary.resolve("state-link")
        Files.createSymbolicLink(stateLink, target)
        try {
            assertFailsWith<IllegalArgumentException> { dao(stateLink) }
            assertTrue(Files.isDirectory(target))
        } finally { Files.deleteIfExists(stateLink) }

        val state = Files.createDirectory(temporary.resolve("state"))
        val recordDirectory = Files.createDirectories(state.resolve("comment_fraud_records/71"))
        val outside = Files.writeString(temporary.resolve("outside.json"), "[]")
        val recordLink = recordDirectory.resolve("records.json")
        Files.createSymbolicLink(recordLink, outside)
        try {
            assertFailsWith<IllegalArgumentException> { dao(state) }
            assertEquals("[]", Files.readString(outside))
        } finally { Files.deleteIfExists(recordLink) }
    }

    @Test fun redirectedAccountDirectoryIsRejected() {
        val state = Files.createDirectory(temporary.resolve("junction-state"))
        val parent = Files.createDirectory(state.resolve("comment_fraud_records"))
        val target = Files.createDirectory(temporary.resolve("junction-target"))
        val marker = Files.writeString(target.resolve("records.json"), "[]")
        val redirected = parent.resolve("71")
        if (windows) {
            command("cmd.exe", "/d", "/c", "mklink", "/J", redirected.toString(), target.toString())
        } else {
            Files.createSymbolicLink(redirected, target)
        }
        try {
            assertFailsWith<IllegalArgumentException> { dao(state) }
            assertEquals("[]", Files.readString(marker))
        } finally { Files.deleteIfExists(redirected) }
    }

    @Test fun canonicalAliasDoesNotHideSymbolicLinks() {
        val state = Files.createDirectory(temporary.resolve("alias-state"))
        val link = Files.createSymbolicLink(temporary.resolve("alias-state-link"), state)
        try {
            assertFailsWith<IllegalArgumentException> { dao(CanonicalAliasFileSystem(link).root) }
            assertTrue(Files.isDirectory(state))
        } finally { Files.deleteIfExists(link) }

        val directory = Files.createDirectories(state.resolve("comment_fraud_records/71"))
        val outside = Files.writeString(temporary.resolve("alias-outside.json"), "[]")
        val recordLink = Files.createSymbolicLink(directory.resolve("records.json"), outside)
        try {
            assertFailsWith<IllegalArgumentException> { dao(CanonicalAliasFileSystem(state).root) }
            assertEquals("[]", Files.readString(outside))
        } finally { Files.deleteIfExists(recordLink) }
    }

    private fun shortAlias(path: Path): Path {
        fun lookup(): Path {
            val kernel = Native.load("kernel32", WindowsShortPathLibrary::class.java)
            val buffer = CharArray(32_768)
            val length = kernel.GetShortPathNameW(WString(path.toAbsolutePath().toString()), buffer, buffer.size)
            require(length in 1 until buffer.size) { "GetShortPathNameW failed: ${Native.getLastError()}" }
            return Path.of(Native.toString(buffer))
        }
        var alias = lookup()
        if (alias.fileName == path.fileName) {
            // Only touch the directory created by this test. Lack of volume support or
            // privilege is a fixture failure, never a skipped or weakened assertion.
            command("fsutil.exe", "file", "setshortname", path.toString(), "BILIP~1")
            alias = lookup()
        }
        assertNotEquals(path.toRealPath(NOFOLLOW_LINKS), alias.toAbsolutePath().normalize(),
            "8.3 alias unavailable even after attempting to create it")
        return alias
    }

    private fun command(vararg args: String) {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor()
        }
        val output = process.inputStream.bufferedReader().readText()
        check(finished && process.exitValue() == 0) { "Fixture command failed: ${args.first()}: $output" }
    }

    interface WindowsShortPathLibrary : StdCallLibrary {
        fun GetShortPathNameW(longPath: WString, shortPath: CharArray, bufferLength: Int): Int
    }
}

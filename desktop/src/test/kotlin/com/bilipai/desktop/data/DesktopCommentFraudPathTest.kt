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
import org.junit.jupiter.api.BeforeEach
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

    @BeforeEach fun canonicalTemporaryDirectory() {
        // macOS may supply /var through a link to /private/var. Reject only the
        // links created by each fixture, rather than an unrelated temp ancestor.
        temporary = temporary.toRealPath()
        assertEquals(temporary, UpdateStorage.existingPathWithoutLinks(temporary))
    }

    @Test fun reopenRecordsThroughShortStateDirectory(): Unit = runBlocking {
        val longRoot = Files.createDirectory(temporary.resolve("Comment fraud long state directory"))
        val root = if (windows) shortAlias(longRoot) else CanonicalAliasFileSystem(longRoot).root
        val record = CommentFraudRecord(17, 19)
        dao(root).use { it.insertOrUpdate(record) }
        val file = root.resolve("comment_fraud_records/71/records.json")
        val persisted = Files.readAllBytes(file)
        val canonical = UpdateStorage.existingPathWithoutLinks(file)
        assertTrue(file.fileSystem === canonical.fileSystem)
        assertEquals(file.javaClass, canonical.javaClass)
        assertNotEquals(file.toAbsolutePath().normalize().toString(), canonical.toString())
        // Exercise the old predicate on every host, not just when NTFS supplies 8.3 names.
        assertNotEquals(file.toAbsolutePath().normalize(), canonical,
            "The fixture did not produce distinct lexical and canonical paths")
        val expected = longRoot.resolve("comment_fraud_records/71/records.json").toRealPath(NOFOLLOW_LINKS)
        assertEquals(root.fileSystem.getPath(expected.toString()), canonical)
        assertEquals(expected.toString(), canonical.toString())
        assertTrue(Files.isSameFile(file, expected))
        dao(root).use { assertEquals(listOf(record), it.getAllRecords()) }
        assertTrue(persisted.contentEquals(Files.readAllBytes(expected)))
        assertChildren(expected.parent, "records.json")
    }

    @Test fun symbolicStateDirectoryAndRecordFileAreRejected() {
        val target = Files.createDirectory(temporary.resolve("actual"))
        val stateLink = temporary.resolve("state-link")
        Files.createSymbolicLink(stateLink, target)
        try {
            assertFailsWith<IllegalArgumentException> { dao(stateLink) }
            assertTrue(Files.isDirectory(target))
            assertSymbolicLink(stateLink, target)
            assertChildren(target)
        } finally { Files.deleteIfExists(stateLink) }

        val state = Files.createDirectory(temporary.resolve("state"))
        val recordDirectory = Files.createDirectories(state.resolve("comment_fraud_records/71"))
        val outside = Files.writeString(temporary.resolve("outside.json"), "[]")
        val recordLink = recordDirectory.resolve("records.json")
        Files.createSymbolicLink(recordLink, outside)
        try {
            assertFailsWith<IllegalArgumentException> { dao(state) }
            assertEquals("[]", Files.readString(outside))
            assertSymbolicLink(recordLink, outside)
            assertChildren(recordDirectory, "records.json")
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
            assertTrue(Files.exists(redirected, NOFOLLOW_LINKS))
            assertTrue(Files.isSameFile(redirected, target))
            assertChildren(target, "records.json")
            assertChildren(parent, "71")
        } finally { Files.deleteIfExists(redirected) }
    }

    @Test fun canonicalAliasDoesNotHideSymbolicLinks() {
        val state = Files.createDirectory(temporary.resolve("alias-state"))
        val link = Files.createSymbolicLink(temporary.resolve("alias-state-link"), state)
        try {
            assertFailsWith<IllegalArgumentException> { dao(CanonicalAliasFileSystem(link).root) }
            assertTrue(Files.isDirectory(state))
            assertSymbolicLink(link, state)
            assertChildren(state)
        } finally { Files.deleteIfExists(link) }

        val directory = Files.createDirectories(state.resolve("comment_fraud_records/71"))
        val outside = Files.writeString(temporary.resolve("alias-outside.json"), "[]")
        val recordLink = Files.createSymbolicLink(directory.resolve("records.json"), outside)
        try {
            assertFailsWith<IllegalArgumentException> { dao(CanonicalAliasFileSystem(state).root) }
            assertEquals("[]", Files.readString(outside))
            assertSymbolicLink(recordLink, outside)
            assertChildren(directory, "records.json")
        } finally { Files.deleteIfExists(recordLink) }
    }

    @Test fun aliasRecordLinkInstalledAfterOpenCannotChangeRecords(): Unit = runBlocking {
        val state = Files.createDirectory(temporary.resolve("alias-write-state"))
        val root = CanonicalAliasFileSystem(state).root
        val saved = CommentFraudRecord(17, 19)
        dao(root).use { opened ->
            opened.insertOrUpdate(saved)
            val directory = state.resolve("comment_fraud_records/71")
            val records = directory.resolve("records.json")
            val original = Files.move(records, directory.resolve("original.json"))
            val persisted = Files.readAllBytes(original)
            val outside = Files.writeString(temporary.resolve("write-outside.json"), "[]")
            Files.createSymbolicLink(records, outside)
            try {
                assertFailsWith<IllegalArgumentException> { opened.insertOrUpdate(CommentFraudRecord(23, 29)) }
                assertEquals(listOf(saved), opened.getAllRecords())
                assertTrue(persisted.contentEquals(Files.readAllBytes(original)))
                assertEquals("[]", Files.readString(outside))
                assertSymbolicLink(records, outside)
                assertChildren(directory, "original.json", "records.json")
            } finally { Files.deleteIfExists(records) }
        }
    }

    @Test fun redirectedAccountInstalledAfterOpenCannotChangeRecords(): Unit = runBlocking {
        val state = Files.createDirectory(temporary.resolve("redirected-write-state"))
        dao(state).use { opened ->
            val saved = CommentFraudRecord(17, 19)
            opened.insertOrUpdate(saved)
            val parent = state.resolve("comment_fraud_records")
            val redirected = parent.resolve("71")
            val original = Files.move(redirected, parent.resolve("original"))
            val persisted = Files.readAllBytes(original.resolve("records.json"))
            val target = Files.createDirectory(temporary.resolve("write-junction-target"))
            val outside = Files.writeString(target.resolve("records.json"), "[]")
            if (windows) {
                command("cmd.exe", "/d", "/c", "mklink", "/J", redirected.toString(), target.toString())
            } else {
                Files.createSymbolicLink(redirected, target)
            }
            try {
                assertFailsWith<IllegalArgumentException> { opened.insertOrUpdate(CommentFraudRecord(23, 29)) }
                assertEquals(listOf(saved), opened.getAllRecords())
                assertTrue(persisted.contentEquals(Files.readAllBytes(original.resolve("records.json"))))
                assertEquals("[]", Files.readString(outside))
                assertTrue(Files.exists(redirected, NOFOLLOW_LINKS))
                assertTrue(Files.isSameFile(redirected, target))
                assertChildren(target, "records.json")
                assertChildren(original, "records.json")
                assertChildren(parent, "71", "original")
            } finally { Files.deleteIfExists(redirected) }
        }
    }

    @Test fun recordLinkInstalledBeforeCommitCannotLeaveTemporaryFiles(): Unit = runBlocking {
        val state = Files.createDirectory(temporary.resolve("commit-state"))
        val directory = state.resolve("comment_fraud_records/71")
        val records = directory.resolve("records.json")
        val original = directory.resolve("original.json")
        val outside = Files.writeString(temporary.resolve("commit-outside.json"), "[]")
        var armed = false
        var intercepted = emptyList<Path>()
        val opened = DesktopCommentFraudJsonDao(CanonicalAliasFileSystem(state).root, 71, { true }, { action ->
            if (armed) {
                val pending = Files.list(directory).use { children ->
                    children.filter { it.fileName.toString().startsWith("records-") &&
                        it.fileName.toString().endsWith(".tmp") }.toList()
                }
                if (pending.isNotEmpty()) {
                    intercepted = pending
                    armed = false
                    Files.move(records, original)
                    Files.createSymbolicLink(records, outside)
                }
            }
            action()
            true
        })
        opened.use {
            val saved = CommentFraudRecord(17, 19)
            it.insertOrUpdate(saved)
            val persisted = Files.readAllBytes(records)
            armed = true
            try {
                assertFailsWith<IllegalArgumentException> { it.insertOrUpdate(CommentFraudRecord(23, 29)) }
                assertTrue(intercepted.isNotEmpty(), "The fixture must reach commit with an actual temporary file")
                assertTrue(intercepted.all { path -> !Files.exists(path, NOFOLLOW_LINKS) })
                assertEquals(listOf(saved), it.getAllRecords())
                assertTrue(persisted.contentEquals(Files.readAllBytes(original)))
                assertEquals("[]", Files.readString(outside))
                assertSymbolicLink(records, outside)
                assertChildren(directory, "original.json", "records.json")
            } finally { Files.deleteIfExists(records) }
        }
    }

    private fun assertSymbolicLink(link: Path, target: Path) {
        assertTrue(Files.isSymbolicLink(link))
        assertEquals(target, Files.readSymbolicLink(link))
    }

    private fun assertChildren(directory: Path, vararg names: String) {
        Files.list(directory).use { children ->
            assertEquals(names.toSet(), children.map { it.fileName.toString() }.toList().toSet(),
                "Unexpected entries or temporary write residue in $directory")
        }
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

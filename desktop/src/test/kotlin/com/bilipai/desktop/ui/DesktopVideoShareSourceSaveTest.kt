package com.bilipai.desktop.ui

import kotlinx.coroutines.*
import java.nio.file.*
import kotlin.test.*

/** Real file copy/publication in an exclusive temp fixture; no user destinations/native share. */
class DesktopVideoShareSourceSaveTest {
    @Test fun sourceRetiresAtFinalCommitWhileCallerRemainsActiveDoesNotPublish(): Unit = runBlocking {
        val directory = Files.createTempDirectory("source-owned-share-save-")
        try {
            val files = DesktopVideoShareFiles(directory, { error("No network in save fixture") }, { true }, { action -> action(); true })
            val card = files.publish("BiliPai_share_fixture.png", "image/png") { Files.write(it, byteArrayOf(1, 2, 3)) }
            val destination = directory.resolve("destination.png")
            val caller = checkNotNull(currentCoroutineContext()[Job])
            assertFailsWith<CancellationException> {
                files.save(card, destination) { _ -> assertTrue(caller.isActive); false }
            }
            assertFalse(Files.exists(destination)); assertTrue(Files.exists(card.path))
            Files.newDirectoryStream(directory, "saving-share-*").use { assertFalse(it.iterator().hasNext()) }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun completeCopyPrecedesSourceAdmissionWhichPrecedesRetainedRootCommit(): Unit = runBlocking {
        val directory = Files.createTempDirectory("source-owned-share-save-")
        try {
            var saving = false; var inSource = false; var rootCommits = 0
            val bytes = ByteArray(200_000) { (it % 127).toByte() }
            val files = DesktopVideoShareFiles(directory, { error("No network in save fixture") }, { true }, { action ->
                if (saving) { assertTrue(inSource); rootCommits++ }
                action(); true
            })
            val card = files.publish("BiliPai_share_fixture.png", "image/png") { Files.write(it, bytes) }
            saving = true
            val destination = directory.resolve("destination.png")
            files.save(card, destination) { publish ->
                val temp = Files.newDirectoryStream(directory, "saving-share-*").use { it.toList().single() }
                assertContentEquals(bytes, Files.readAllBytes(temp))
                assertFalse(Files.exists(destination))
                inSource = true; try { publish(); true } finally { inSource = false }
            }
            assertEquals(1, rootCommits); assertContentEquals(bytes, Files.readAllBytes(destination))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun finalSourceAdmissionKeepsExistingNoOverwriteAndCleansTemporaryFile(): Unit = runBlocking {
        val directory = Files.createTempDirectory("source-owned-share-save-")
        try {
            val files = DesktopVideoShareFiles(directory, { error("No network in save fixture") }, { true }, { action -> action(); true })
            val card = files.publish("BiliPai_share_fixture.png", "image/png") { Files.write(it, byteArrayOf(1)) }
            val destination = directory.resolve("destination.png")
            Files.write(destination, byteArrayOf(9))
            assertFailsWith<FileAlreadyExistsException> { files.save(card, destination) { action -> action(); true } }
            assertContentEquals(byteArrayOf(9), Files.readAllBytes(destination))
            Files.newDirectoryStream(directory, "saving-share-*").use { assertFalse(it.iterator().hasNext()) }
        } finally { directory.toFile().deleteRecursively() }
    }
}

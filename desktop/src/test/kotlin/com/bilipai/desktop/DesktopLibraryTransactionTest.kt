package com.bilipai.desktop

import com.bilipai.desktop.data.VideoCard
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DesktopLibraryTransactionTest {
    @Test fun `failed file replacement retains every in memory field and successful retry matches restart`() {
        directory { root ->
            val library = DesktopLibrary(root) { false }
            val original = VideoCard("BV-original", "original", "", "", 1, 100, progressSeconds = 3, preferredCid = 7)
            library.record(original)
            val oldFile = Files.readAllBytes(root.resolve("library.json"))
            val oldHistory = library.history(); val oldFavorites = library.favorites()
            val oldDark = library.dark; val oldUpdates = library.automaticUpdates
            val file = root.resolve("library.json")
            Files.delete(file); Files.createDirectory(file); Files.writeString(file.resolve("marker"), "preserve")

            assertFailsWith<IOException> { library.checkpoint(original.bvid, 9, 2, 77.0) }
            assertEquals(oldHistory, library.history())
            assertFailsWith<IOException> { library.record(original.copy(bvid = "BV-new", title = "new")) }
            assertEquals(oldHistory, library.history())
            assertFailsWith<IOException> { library.toggleFavorite(original) }
            assertEquals(oldFavorites, library.favorites())
            assertFailsWith<IOException> { library.setDark(!oldDark) }
            assertEquals(oldDark, library.dark)
            assertFailsWith<IOException> { library.setAutomaticUpdates(!oldUpdates) }
            assertEquals(oldUpdates, library.automaticUpdates)
            assertEquals("preserve", Files.readString(file.resolve("marker")))
            Files.list(root).use { stream -> assertFalse(stream.anyMatch { it.fileName.toString().startsWith("library-") }) }

            Files.delete(file.resolve("marker")); Files.delete(file); Files.write(file, oldFile)
            library.checkpoint(original.bvid, 9, 2, 77.0)
            assertEquals(77, library.resumeCard(original.bvid)?.progressSeconds)
            assertEquals(9L, library.resumeCard(original.bvid)?.preferredCid)
            assertEquals(2, library.resumeCard(original.bvid)?.pageIndex)
            val restarted = DesktopLibrary(root) { false }
            assertEquals(library.history(), restarted.history())
            assertEquals(library.favorites(), restarted.favorites())
            assertEquals(library.dark, restarted.dark)
            assertEquals(library.automaticUpdates, restarted.automaticUpdates)
            assertNull(library.resumeCard("BV-new"))
        }
    }

    @Test fun `failure before temp creation does not commit memory and can retry after directory repair`() {
        directory { root ->
            val blocked = root.resolve("blocked")
            Files.writeString(blocked, "original file")
            val library = DesktopLibrary(blocked) { false }
            assertFailsWith<IOException> { library.setDark(true) }
            assertFalse(library.dark); assertTrue(library.history().isEmpty())
            assertEquals("original file", Files.readString(blocked))
            Files.delete(blocked)
            library.setDark(true)
            assertTrue(library.dark)
            assertTrue(DesktopLibrary(blocked) { false }.dark)
        }
    }

    @Test fun `successful saves preserve an unrelated legacy temp file`() {
        directory { root ->
            Files.writeString(root.resolve("library.json.tmp"), "unrelated legacy writer")
            val library = DesktopLibrary(root) { false }
            library.setDark(true)
            assertTrue(library.dark)
            assertEquals("unrelated legacy writer", Files.readString(root.resolve("library.json.tmp")))
            Files.list(root).use { stream -> assertFalse(stream.anyMatch { it.fileName.toString().startsWith("library-") }) }
        }
    }

    private fun directory(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("bilipai-library-transaction-").toAbsolutePath().normalize()
        try { block(root) } finally {
            check(root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            check(root.fileName.toString().startsWith("bilipai-library-transaction-"))
            Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { path ->
                check(path.toAbsolutePath().normalize().startsWith(root)); Files.deleteIfExists(path)
            } }
        }
    }
}

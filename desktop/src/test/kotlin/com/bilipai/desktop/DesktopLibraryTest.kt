package com.bilipai.desktop

import com.bilipai.desktop.data.VideoCard
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DesktopLibraryTest {
    @Test fun `only an explicitly stored old dark preference migrates`() = withLibrary { directory ->
        assertNull(DesktopLibrary(directory) { false }.storedDark)
        Files.writeString(directory.resolve("library.json"), """{"history":[],"favorites":[]}""")
        assertNull(DesktopLibrary(directory) { false }.storedDark)
        Files.writeString(directory.resolve("library.json"), """{"dark":false}""")
        assertEquals(false, DesktopLibrary(directory) { false }.storedDark)
        Files.writeString(directory.resolve("library.json"), """{"dark":true}""")
        assertEquals(true, DesktopLibrary(directory) { false }.storedDark)
    }

    @Test fun `old library migrates and resume survives restart without losing preferences or favorites`() = withLibrary { directory ->
        Files.writeString(directory.resolve("library.json"), """{"history":[{"bvid":"BV1test000001","title":"旧视频","cover":"","author":"UP","playCount":5,"duration":300,"timestamp":1}],"favorites":[],"dark":true,"automaticUpdates":false}""")
        val library = DesktopLibrary(directory) { false }
        assertTrue(library.dark)
        assertFalse(library.automaticUpdates)
        val card = library.history().single()
        assertNull(card.progressSeconds)
        library.toggleFavorite(card)
        library.checkpoint(card.bvid, 400, 2, 87.9)
        library.record(card.copy(title = "新标题"))
        val restored = DesktopLibrary(directory) { false }
        assertEquals(87, restored.resumeCard(card.bvid)?.progressSeconds)
        assertEquals(400L, restored.resumeCard(card.bvid)?.preferredCid)
        assertEquals(2, restored.resumeCard(card.bvid)?.pageIndex)
        assertEquals("新标题", restored.history().single().title)
        assertTrue(restored.isFavorite(card.bvid))
        assertTrue(restored.dark)
        assertFalse(restored.automaticUpdates)
    }

    @Test fun `invalid checkpoints do not replace a valid resume point`() = withLibrary { directory ->
        val library = DesktopLibrary(directory) { false }
        val card = VideoCard("BV1test000001", "test", "", "UP", 0, 400, authorMid = 777)
        library.record(card)
        library.checkpoint(card.bvid, 100, 0, 30.0)
        library.checkpoint(card.bvid, 0, 0, 90.0)
        library.checkpoint(card.bvid, 100, -1, 90.0)
        library.checkpoint(card.bvid, 100, 0, Double.NaN)
        library.checkpoint("unknown", 100, 0, 90.0)
        assertEquals(30, library.resumeCard(card.bvid)?.progressSeconds)
        assertEquals(777L, DesktopLibrary(directory) { false }.history().single().authorMid)
        assertEquals(1, library.history().size)
    }

    private fun withLibrary(action: (Path) -> Unit) {
        val directory = Files.createTempDirectory("bilipai-library-")
        try { action(directory) }
        finally { Files.list(directory).use { paths -> paths.forEach(Files::deleteIfExists) }; Files.deleteIfExists(directory) }
    }
}

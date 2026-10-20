package com.android.purebilibili.core.ui.wallpaper

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure checks of the Windows meaning of rememberIsStaticWallpaper: the container and size come from the first bytes of
 * the file, never from its name. No window, GPU, account or network; the one file test writes a temp file.
 */
class DesktopWallpaperImageProbeTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ints(vararg values: Int) = values
    private fun be32(value: Int) = ints(value ushr 24 and 0xFF, value ushr 16 and 0xFF, value ushr 8 and 0xFF, value and 0xFF)
    private fun le16(value: Int) = ints(value and 0xFF, value ushr 8 and 0xFF)
    private fun le24(value: Int) = ints(value and 0xFF, value ushr 8 and 0xFF, value ushr 16 and 0xFF)
    private fun le32(value: Int) = ints(value and 0xFF, value ushr 8 and 0xFF, value ushr 16 and 0xFF, value ushr 24 and 0xFF)
    private fun join(vararg parts: IntArray): ByteArray = bytes(*parts.flatMap { it.toList() }.toIntArray())
    private fun ascii(text: String) = text.map { it.code }.toIntArray()

    private fun png(width: Int, height: Int) = join(
        ints(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), ints(0, 0, 0, 13), ascii("IHDR"),
        be32(width), be32(height), ints(8, 6, 0, 0, 0),
    )

    /** An APP0 segment first, then the frame header: the size is not at a fixed offset. */
    private fun jpeg(width: Int, height: Int) = join(
        ints(0xFF, 0xD8), ints(0xFF, 0xE0, 0x00, 0x10), ascii("JFIF"), ints(0, 1, 1, 0, 0, 1, 0, 1, 0, 0),
        ints(0xFF, 0xC0, 0x00, 0x11, 0x08), ints(height ushr 8 and 0xFF, height and 0xFF),
        ints(width ushr 8 and 0xFF, width and 0xFF), ints(3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1),
    )

    private fun bmp(width: Int, height: Int) = join(
        ascii("BM"), ints(0, 0, 0, 0, 0, 0, 0, 0, 54, 0, 0, 0), le32(40), le32(width), le32(height), ints(1, 0, 24, 0),
    )

    private fun gif(width: Int, height: Int) = join(ascii("GIF89a"), le16(width), le16(height), ints(0, 0, 0))

    private fun riff(vararg chunk: IntArray) = join(ascii("RIFF"), ints(0, 0, 0, 0), ascii("WEBP"), *chunk)

    private fun webpExtended(width: Int, height: Int) =
        riff(ascii("VP8X"), ints(10, 0, 0, 0), ints(0, 0, 0, 0), le24(width - 1), le24(height - 1))

    private fun webpLossless(width: Int, height: Int) =
        riff(ascii("VP8L"), ints(5, 0, 0, 0), ints(0x2F), le32((width - 1) or ((height - 1) shl 14)))

    private fun webpLossy(width: Int, height: Int) =
        riff(ascii("VP8 "), ints(10, 0, 0, 0), ints(0x10, 0, 0), ints(0x9D, 0x01, 0x2A), le16(width), le16(height))

    private fun header(format: DesktopWallpaperImageFormat, width: Int, height: Int) =
        DesktopWallpaperImageHeader(format, width, height)

    @Test
    fun everyContainerNamesItsSizeFromTheHeaderAlone() {
        assertEquals(header(DesktopWallpaperImageFormat.PNG, 3840, 2160), parseDesktopWallpaperImageHeader(png(3840, 2160)))
        assertEquals(header(DesktopWallpaperImageFormat.JPEG, 5120, 2160), parseDesktopWallpaperImageHeader(jpeg(5120, 2160)))
        assertEquals(header(DesktopWallpaperImageFormat.BMP, 11, 13), parseDesktopWallpaperImageHeader(bmp(11, 13)))
        assertEquals(header(DesktopWallpaperImageFormat.BMP, 11, 13), parseDesktopWallpaperImageHeader(bmp(11, -13)))
        assertEquals(header(DesktopWallpaperImageFormat.GIF, 480, 270), parseDesktopWallpaperImageHeader(gif(480, 270)))
        assertEquals(header(DesktopWallpaperImageFormat.WEBP, 2000, 1500), parseDesktopWallpaperImageHeader(webpExtended(2000, 1500)))
        assertEquals(header(DesktopWallpaperImageFormat.WEBP, 640, 480), parseDesktopWallpaperImageHeader(webpLossless(640, 480)))
        assertEquals(header(DesktopWallpaperImageFormat.WEBP, 1280, 720), parseDesktopWallpaperImageHeader(webpLossy(1280, 720)))
    }

    @Test
    fun anythingElseHasNoHeader() {
        assertNull(parseDesktopWallpaperImageHeader(ByteArray(0)))
        assertNull(parseDesktopWallpaperImageHeader("not an image at all, just text".toByteArray()))
        assertNull(parseDesktopWallpaperImageHeader(png(3, 5).copyOf(20)))
        assertNull(parseDesktopWallpaperImageHeader(png(0, 5)))
        assertNull(parseDesktopWallpaperImageHeader(bmp(0, 5)))
        assertNull(parseDesktopWallpaperImageHeader(gif(0, 0)))
        assertNull(parseDesktopWallpaperImageHeader(jpeg(7, 9).copyOf(24)))
        // a scan header before any frame header ends the search instead of reading image data as markers
        assertNull(parseDesktopWallpaperImageHeader(join(ints(0xFF, 0xD8), ints(0xFF, 0xDA, 0x00, 0x08, 1, 2, 3, 4, 5, 6))))
        assertNull(parseDesktopWallpaperImageHeader(riff(ascii("ZZZZ"), ints(10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))))
        // an mp4 starts with a box size and "ftyp": video is never a still image
        assertNull(parseDesktopWallpaperImageHeader(join(ints(0, 0, 0, 24), ascii("ftypisom"), ints(0, 0, 2, 0, 0, 0, 0, 0))))
    }

    @Test
    fun onlyStillContainersWithASizeAreStatic() {
        assertTrue(desktopWallpaperHeaderIsStatic(header(DesktopWallpaperImageFormat.PNG, 1, 1)))
        assertTrue(desktopWallpaperHeaderIsStatic(header(DesktopWallpaperImageFormat.JPEG, 1, 1)))
        assertTrue(desktopWallpaperHeaderIsStatic(header(DesktopWallpaperImageFormat.BMP, 1, 1)))
        assertFalse(desktopWallpaperHeaderIsStatic(header(DesktopWallpaperImageFormat.GIF, 1, 1)))
        assertFalse(desktopWallpaperHeaderIsStatic(header(DesktopWallpaperImageFormat.WEBP, 1, 1)))
        assertFalse(desktopWallpaperHeaderIsStatic(header(DesktopWallpaperImageFormat.PNG, 0, 1)))
        assertFalse(desktopWallpaperHeaderIsStatic(null))
    }

    @Test
    fun onlyTheImporterNameAndContentUrisNeedTheHeader() {
        assertTrue(desktopWallpaperNeedsHeaderProbe("file:///C:/state/home_wallpaper/wallpaper_1.img"))
        assertTrue(desktopWallpaperNeedsHeaderProbe("C:\\state\\home_wallpaper\\wallpaper_1.IMG"))
        assertTrue(desktopWallpaperNeedsHeaderProbe("file:///C:/a/b.img?cache=1"))
        assertTrue(desktopWallpaperNeedsHeaderProbe("content://media/external/images/1"))
        assertFalse(desktopWallpaperNeedsHeaderProbe("file:///C:/a/b.png"))
        assertFalse(desktopWallpaperNeedsHeaderProbe("file:///C:/a/b.mp4"))
    }

    @Test
    fun anImgFileIsDecidedByItsHeaderAndEveryOtherNameByTheExtensionRule() {
        val calls = mutableListOf<String>()
        fun probe(result: DesktopWallpaperImageHeader?) = { uri: String -> calls += uri; result }
        assertTrue(desktopWallpaperIsStatic("file:///w/wallpaper_a.img", probe(header(DesktopWallpaperImageFormat.PNG, 4, 4))))
        assertFalse(desktopWallpaperIsStatic("file:///w/wallpaper_b.img", probe(header(DesktopWallpaperImageFormat.GIF, 4, 4))))
        assertFalse(desktopWallpaperIsStatic("file:///w/wallpaper_c.img", probe(header(DesktopWallpaperImageFormat.WEBP, 4, 4))))
        assertFalse(desktopWallpaperIsStatic("file:///w/wallpaper_d.img", probe(null)))
        assertEquals(4, calls.size)
        calls.clear()
        assertTrue(desktopWallpaperIsStatic("file:///w/photo.png", probe(null)))
        assertFalse(desktopWallpaperIsStatic("file:///w/loop.gif", probe(header(DesktopWallpaperImageFormat.PNG, 4, 4))))
        assertFalse(desktopWallpaperIsStatic("file:///w/clip.mp4", probe(header(DesktopWallpaperImageFormat.PNG, 4, 4))))
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun aLocalImgFileIsProbedThroughItsFileUri() {
        val directory = Files.createTempDirectory("bp-wallpaper-probe-")
        try {
            val still = directory.resolve("wallpaper_still.img")
            Files.write(still, png(3, 5))
            val animated = directory.resolve("wallpaper_animated.img")
            Files.write(animated, gif(6, 4))
            val garbage = directory.resolve("wallpaper_garbage.img")
            Files.write(garbage, "garbage".toByteArray())
            assertEquals(header(DesktopWallpaperImageFormat.PNG, 3, 5), probeDesktopWallpaperUri(still.toUri().toString()))
            assertTrue(desktopWallpaperIsStatic(still.toUri().toString()))
            assertNotNull(probeDesktopWallpaperUri(animated.toUri().toString()))
            assertFalse(desktopWallpaperIsStatic(animated.toUri().toString()))
            assertFalse(desktopWallpaperIsStatic(garbage.toUri().toString()))
            assertNull(probeDesktopWallpaperUri(directory.resolve("missing.img").toUri().toString()))
            // content: and network URIs name no local file on Windows
            assertNull(probeDesktopWallpaperUri("content://media/external/images/1"))
            assertNull(probeDesktopWallpaperUri("https://example.com/a.img"))
        } finally {
            Files.walk(directory).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

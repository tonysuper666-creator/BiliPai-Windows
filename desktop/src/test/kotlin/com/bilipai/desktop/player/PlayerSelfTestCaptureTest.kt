package com.bilipai.desktop.player

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Headless image contracts using the actual smoke-test frame and actual rendering checker. No Robot/native core. */
class PlayerSelfTestCaptureTest {
    @Test fun originalFramesAndJpegCompressionRemainValidAtSeveralScales(): Unit {
        for (index in listOf(10, 40, 100, 180)) for (scale in listOf(0.75, 1.0, 1.25, 2.2, 3.0)) {
            val decoded = jpeg(PlayerSelfTest.createFixtureFrame(index), 0.60f)
            val width = (decoded.width * scale).toInt()
            val height = (decoded.height * scale).toInt()
            val viewport = PlayerVideoViewport(width, height, 0, 0, width, height)
            val image = project(decoded, width, height, viewport)
            val result = PlayerSelfTest.checkRenderedFixtureSurface(image, viewport)
            assertEquals(2, result.bands.size)
            assertTrue(result.bands.all { it.fillFraction >= 0.98 })
        }
    }

    @Test fun realOsdMarginsMapLetterboxedAndCroppedFramesWithoutTestingBlackBars(): Unit {
        val decoded = jpeg(PlayerSelfTest.createFixtureFrame(40), 0.75f)
        // Nonuniform crop/pan and system-pixel OSD are legitimate native observations.
        val letterboxed = PlayerVideoViewport(1536, 960, 0, 48, 1536, 864)
        PlayerSelfTest.checkRenderedFixtureSurface(project(decoded, 1024, 640, letterboxed), letterboxed)
        val cropped = PlayerVideoViewport(700, 440, -41, 0, 782, 440)
        PlayerSelfTest.checkRenderedFixtureSurface(project(decoded, 700, 440, cropped), cropped)
    }

    @Test fun oldGridArtifactRetainsBothOriginalColorsButIsRejected(): Unit {
        val viewport = PlayerVideoViewport(704, 396, 0, 0, 704, 396)
        val image = project(jpeg(PlayerSelfTest.createFixtureFrame(10), 0.75f), 704, 396, viewport)
        image.createGraphics().apply {
            color = Color.BLACK
            for (x in 0 until image.width step 11) drawLine(x, 0, x, image.height - 1)
            for (y in 0 until image.height step 11) drawLine(0, y, image.width - 1, y)
            dispose()
        }
        assertOriginalColorPresence(image)
        val failure = assertFailsWith<IllegalStateException> { PlayerSelfTest.checkRenderedFixtureSurface(image, viewport) }
        assertTrue(failure.message.orEmpty().contains("background was discontinuous"))
    }

    @Test fun sparseFullWidthBlackStripeCannotHideInsideTheAggregateTolerance(): Unit {
        val viewport = PlayerVideoViewport(1920, 1080, 0, 0, 1920, 1080)
        val image = project(PlayerSelfTest.createFixtureFrame(40), 1920, 1080, viewport)
        // One row in this 70-pixel interior band is <2% overall damage, but
        // continuity still fails: its own row is entirely missing.
        image.createGraphics().apply { color = Color.BLACK; fillRect(0, 103 * 6, 1920, 1); dispose() }
        assertOriginalColorPresence(image)
        assertFailsWith<IllegalStateException> { PlayerSelfTest.checkRenderedFixtureSurface(image, viewport) }
    }

    @Test fun aFewCompressionLikePixelsWithinTheColorToleranceRemainValid(): Unit {
        val image = PlayerSelfTest.createFixtureFrame(40)
        for (y in 99..108) for (x in 6..312 step 17) image.setRGB(x, y, Color(10, 39, 22).rgb)
        PlayerSelfTest.checkRenderedFixtureSurface(image, PlayerVideoViewport(320, 180, 0, 0, 320, 180))
    }

    @Test fun missingFrameCannotPassOnlyBecauseTwoSmallColoredPatchesRemain(): Unit {
        val image = BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color(82, 191, 248); fillRect(10, 10, 15, 15)
            color = Color(250, 106, 151); fillRect(40, 10, 15, 15)
            dispose()
        }
        assertOriginalColorPresence(image)
        assertFailsWith<IllegalStateException> {
            PlayerSelfTest.checkRenderedFixtureSurface(image, PlayerVideoViewport(320, 180, 0, 0, 320, 180))
        }
    }

    @Test fun publishedPngContainsEveryPixelOfExactlyTheCheckedImage(): Unit {
        val directory = Files.createTempDirectory("native-pixel-contract-")
        val destination = directory.resolve("surface.png").toFile()
        try {
            val image = project(jpeg(PlayerSelfTest.createFixtureFrame(40), 0.75f), 704, 396,
                PlayerVideoViewport(704, 396, 0, 0, 704, 396))
            val checked = PlayerSelfTest.saveCheckedSurfaceCapture(image, destination,
                PlayerVideoViewport(704, 396, 0, 0, 704, 396))
            assertTrue(checked.cyanPixels >= 100 && checked.pinkPixels >= 100)
            val saved = ImageIO.read(destination)
            assertEquals(image.width, saved.width)
            assertEquals(image.height, saved.height)
            for (y in 0 until image.height) for (x in 0 until image.width) assertEquals(image.getRGB(x, y), saved.getRGB(x, y))
        } finally {
            Files.deleteIfExists(destination.toPath())
            Files.deleteIfExists(directory)
        }
    }

    @Test fun rejectedImageCannotProduceAFileNamedAsValidatedSurface(): Unit {
        val directory = Files.createTempDirectory("native-pixel-rejected-")
        val destination = directory.resolve("surface.png").toFile()
        try {
            val image = PlayerSelfTest.createFixtureFrame(40)
            image.createGraphics().apply { color = Color.BLACK; fillRect(0, 98, 320, 12); dispose() }
            assertFailsWith<IllegalStateException> {
                PlayerSelfTest.saveCheckedSurfaceCapture(image, destination, PlayerVideoViewport(320, 180, 0, 0, 320, 180))
            }
            assertFalse(destination.exists())
        } finally {
            Files.deleteIfExists(destination.toPath())
            Files.deleteIfExists(directory)
        }
    }

    private fun jpeg(image: BufferedImage, quality: Float): BufferedImage {
        val bytes = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        ImageIO.createImageOutputStream(bytes).use { output ->
            try {
                writer.output = output
                writer.write(null, javax.imageio.IIOImage(image, null, null), writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                })
            } finally { writer.dispose() }
        }
        return ImageIO.read(ByteArrayInputStream(bytes.toByteArray()))
    }

    private fun project(source: BufferedImage, width: Int, height: Int, viewport: PlayerVideoViewport): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { image ->
            image.createGraphics().apply {
                color = Color.BLACK; fillRect(0, 0, width, height)
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                drawImage(source, viewport.sourceToPhysicalTransform(width, height, source.width, source.height), null)
                dispose()
            }
        }

    private fun assertOriginalColorPresence(image: BufferedImage) {
        var cyan = 0
        var pink = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val color = Color(image.getRGB(x, y))
            if (color.blue > 180 && color.green > 135 && color.red < 135) cyan++
            if (color.red > 180 && color.green < 145 && color.blue in 100..200) pink++
        }
        assertTrue(cyan >= 100 && pink >= 100, "The negative input must still satisfy the original presence threshold.")
    }
}

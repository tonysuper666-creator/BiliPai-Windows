package com.bilipai.desktop.brand

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path as FilePath
import java.nio.file.StandardOpenOption
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

class BrandMotionRasterTest {
    private fun resource(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/brand-motion/$name")).use { it.readBytes() }
    private fun open(animation: DesktopMaidAnimation): BrandMotionRenderer = assertIs<BrandMotionRendererOpenResult.Ready>(
        BrandMotionRenderer.open(animation, resource(animation.asset.jsonFileName), resource(animation.asset.pngFileName)),
    ).renderer
    private fun pixels(image: Image, artifact: String? = null): BufferedImage {
        val bytes = requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
        if (artifact != null) Files.write(artifactRoot.resolve(artifact), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        return requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
    }
    private fun alpha(image: BufferedImage, x: Int, y: Int): Int = image.getRGB(x, y).ushr(24)
    private fun assertColor(expected: Int, actual: Int, tolerance: Int = 2) {
        for (shift in listOf(0, 8, 16, 24)) assertTrue(abs((expected.ushr(shift) and 255) - (actual.ushr(shift) and 255)) <= tolerance,
            "Color differs: expected ${expected.toUInt().toString(16)}, actual ${actual.toUInt().toString(16)}, channel $shift")
    }
    private fun bucket(color: Int): Int = ((color ushr 19) and 31) shl 10 or (((color ushr 11) and 31) shl 5) or ((color ushr 3) and 31)

    @Test fun thirteenOriginalNonStartupScenesWriteFirstMiddleFinalRealRasters() {
        val animations = DesktopMaidAnimation.runtimeAnimations.distinctBy { it.asset }
        assertEquals(13, animations.size)
        val receipts = StringBuilder("animation\tstage\talphaCoverage\tpaletteIntersection\n")
        for (animation in animations) {
            val source = requireNotNull(ImageIO.read(ByteArrayInputStream(resource(animation.asset.pngFileName))))
            val sourceColors = mutableSetOf<Int>()
            var sourceCoverage = 0
            for (y in 0 until source.height) for (x in 0 until source.width) if (alpha(source, x, y) >= 128) {
                sourceCoverage++; sourceColors += bucket(source.getRGB(x, y))
            }
            assertTrue(sourceCoverage > 1000 && sourceColors.size > 8, "Original artwork contract changed")
            open(animation).use { renderer ->
                assertTrue(renderer.supportsAnimation)
                assertNull(renderer.degradationReason)
                for ((stage, progress) in listOf("first" to 0.0, "middle" to 0.5, "final" to 1.0)) {
                    val frame = renderer.rasterSnapshot(512, 512, progress).use { pixels(it, "${animation.name}-$stage.png") }
                    var coverage = 0
                    val palette = mutableSetOf<Int>()
                    for (y in 0 until frame.height) for (x in 0 until frame.width) if (alpha(frame, x, y) >= 128) {
                        coverage++; palette += bucket(frame.getRGB(x, y))
                    }
                    val common = palette.intersect(sourceColors).size
                    receipts.append("${animation.name}\t$stage\t$coverage\t$common\n")
                    // Artwork occupies a substantial source-relative area and retains many
                    // original bitmap colors: vector-only/blank mascot output cannot pass.
                    assertTrue(coverage > sourceCoverage / 5, "${animation.name}/$stage lost original image coverage")
                    assertTrue(common >= min(16, sourceColors.size / 2), "${animation.name}/$stage lost original bitmap palette")
                }
            }
        }
        Files.writeString(artifactRoot.resolve("original-raster-measurements.tsv"), receipts, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    @Test fun originalCleaningQuarterFrameMovesArtworkRatherThanReturningStillPng() {
        open(DesktopMaidAnimation.CLEANING).use { renderer ->
            val start = renderer.rasterSnapshot(512, 512, 0.0).use { pixels(it) }
            val quarter = renderer.rasterSnapshot(512, 512, 0.25).use { pixels(it, "CLEANING-quarter.png") }
            var changed = 0
            for (y in 0 until 512) for (x in 0 until 512) if (start.getRGB(x, y) != quarter.getRGB(x, y)) changed++
            // Original broom -2 degrees and parent -0.4 degrees at frame24, with real PNG.
            assertTrue(changed > 100, "Temporal transform did not move the original artwork")
        }
    }

    @Test fun originalImageLayerUsesItsActualPixelsAndWorldTranslationOnce() {
        val animation = DesktopMaidAnimation.EMPTY
        val png = resource(animation.asset.pngFileName)
        val source = requireNotNull(ImageIO.read(ByteArrayInputStream(png)))
        val layer = layer(1, BrandMotionLayerType.IMAGE, transform(64.0, 32.0), reference = "image")
        BrandMotionRenderer.forTest(scene(listOf(layer)), png).use { renderer ->
            val result = renderer.rasterSnapshot(512, 512, 0.0).use { pixels(it) }
            var checked = 0
            // The original artwork is mostly alpha 253/254. Probe substantial image
            // pixels and compare all ARGB channels, including the retained alpha.
            for (y in 32 until 320 step 13) for (x in 32 until 320 step 11) if (alpha(source, x, y) >= 240) {
                assertColor(source.getRGB(x, y), result.getRGB(x + 64, y + 32))
                checked++
            }
            assertTrue(checked > 20, "Too few original bitmap pixels were checked")
            assertEquals(0, alpha(result, 32, 256))
        }
    }

    @Test fun addMasksUnionAndOrderedSubtractCutsOnlyItsOwnRegion() {
        val masks = listOf(
            mask(BrandMotionMaskMode.ADD, 0.0, 0.0, 256.0, 512.0),
            mask(BrandMotionMaskMode.ADD, 128.0, 0.0, 384.0, 512.0),
            mask(BrandMotionMaskMode.SUBTRACT, 192.0, 0.0, 224.0, 512.0),
        )
        render(scene(listOf(layer(1, BrandMotionLayerType.IMAGE, masks = masks, reference = "image")))).let { result ->
            assertEquals(255, alpha(result, 64, 256)) // not intersection
            assertEquals(255, alpha(result, 160, 256)) // opaque ADD overlap remains opaque
            assertEquals(255, alpha(result, 320, 256))
            assertEquals(0, alpha(result, 208, 256))
            assertEquals(0, alpha(result, 448, 256))
        }
    }

    @Test fun firstSubtractStartsFullAndLaterAddCanRestoreItsRegion() {
        val masks = listOf(
            mask(BrandMotionMaskMode.SUBTRACT, 0.0, 0.0, 128.0, 512.0),
            mask(BrandMotionMaskMode.ADD, 32.0, 0.0, 64.0, 512.0),
        )
        val result = render(scene(listOf(layer(1, BrandMotionLayerType.IMAGE, masks = masks, reference = "image"))))
        assertEquals(0, alpha(result, 16, 256))
        assertEquals(255, alpha(result, 48, 256))
        assertEquals(255, alpha(result, 256, 256))
    }

    @Test fun partialAddAlphaCombinesWithoutIntersectionOrDoubleApplyingLayerOpacity() {
        val masks = listOf(
            mask(BrandMotionMaskMode.ADD, 0.0, 0.0, 256.0, 512.0, 0.5),
            mask(BrandMotionMaskMode.ADD, 128.0, 0.0, 384.0, 512.0, 0.5),
        )
        val result = render(scene(listOf(layer(1, BrandMotionLayerType.IMAGE, masks = masks, reference = "image"))))
        assertTrue(alpha(result, 64, 256) in 126..129)
        assertTrue(alpha(result, 160, 256) in 190..193) // 0.5 + 0.5 * (1 - 0.5)
        assertEquals(0, alpha(result, 448, 256))
    }

    @Test fun precompClipWorldTransformAndOverlapUseOneGroupOpacity() {
        val red = shapeLayer(1, rectangle(24.0, 24.0, 48.0, 48.0), RED)
        val blue = shapeLayer(2, rectangle(40.0, 24.0, 48.0, 48.0), BLUE)
        val outer = layer(1, BrandMotionLayerType.PRECOMPOSITION, transform(128.0, 128.0, opacity = 50.0), reference = "art", width = 48, height = 48)
        val result = render(scene(listOf(outer), mapOf("art" to listOf(red, blue))))
        assertTrue(alpha(result, 132, 144) in 126..129)
        assertTrue(alpha(result, 156, 144) in 126..129) // overlapping siblings: not 0.75 and not 0.25
        assertEquals(0, alpha(result, 180, 144)) // child extends beyond local precomp clip
        assertEquals(0, alpha(result, 260, 260)) // no double-concatenated world translation
        assertEquals(255, (result.getRGB(156, 144) ushr 16) and 255) // front red layer preserved
    }

    @Test fun nestedShapeGroupOpacityAlsoCompositesOverlapOnce() {
        val red = BrandMotionShape.Group("red", listOf(rectangle(96.0, 96.0, 64.0, 64.0), fill(RED)), transform())
        val blue = BrandMotionShape.Group("blue", listOf(rectangle(112.0, 96.0, 64.0, 64.0), fill(BLUE)), transform())
        val group = BrandMotionShape.Group("half", listOf(red, blue), transform(opacity = 50.0))
        val result = render(scene(listOf(layer(1, BrandMotionLayerType.SHAPE, shapes = listOf(group)))))
        assertTrue(alpha(result, 72, 96) in 126..129)
        assertTrue(alpha(result, 104, 96) in 126..129)
    }

    @Test fun fillConsumesMultiplePrecedingGeometryAndPaintOrderMatchesOriginal() {
        val group = BrandMotionShape.Group("two paths", listOf(
            rectangle(96.0, 96.0, 64.0, 64.0), rectangle(224.0, 96.0, 64.0, 64.0),
            fill(RED), BrandMotionShape.Stroke(BLUE, 1.0, 8.0, 1, 1),
        ), transform())
        val result = render(scene(listOf(layer(1, BrandMotionLayerType.SHAPE, shapes = listOf(group)))))
        assertColor(0xffff0000.toInt(), result.getRGB(96, 96))
        assertColor(0xffff0000.toInt(), result.getRGB(224, 96))
        assertColor(0xffff0000.toInt(), result.getRGB(66, 96)) // fill after stroke covers its inner half
        assertColor(0xff0000ff.toInt(), result.getRGB(62, 96)) // external stroke remains
    }

    @Test fun parentPaintUsesNestedGeometryTransformAndCurveTangentsAreNotFlattened() {
        val curved = BrandMotionShape.Path(BrandMotionPath(
            listOf(point(64.0, 128.0), point(128.0, 128.0), point(128.0, 192.0), point(64.0, 192.0)),
            listOf(point(0.0, 0.0), point(0.0, -64.0), point(0.0, 0.0), point(0.0, 0.0)),
            listOf(point(0.0, -64.0), point(0.0, 0.0), point(0.0, 0.0), point(0.0, 0.0)), true,
        ))
        val nested = BrandMotionShape.Group("curve geometry", listOf(curved), transform(64.0, 0.0))
        val outer = BrandMotionShape.Group("parent fill", listOf(nested, fill(RED)), transform())
        val result = render(scene(listOf(layer(1, BrandMotionLayerType.SHAPE, shapes = listOf(outer)))))
        assertColor(0xffff0000.toInt(), result.getRGB(160, 96)) // above polygon's straight top, inside cubic arch
        assertEquals(0, alpha(result, 160, 70))
        assertEquals(0, alpha(result, 96, 96)) // nested translation applied exactly once
    }

    @Test fun ellipseAndRoundedRectangleRetainTheirInteriorAndCurvedCorners() {
        val ellipse = BrandMotionShape.Ellipse(point(96.0, 96.0), point(64.0, 48.0), 1)
        val round = BrandMotionShape.Rectangle(point(224.0, 96.0), point(64.0, 64.0), 16.0, 1)
        val result = render(scene(listOf(shapeLayer(1, ellipse, RED), shapeLayer(2, round, BLUE))))
        assertColor(0xffff0000.toInt(), result.getRGB(96, 96))
        assertEquals(0, alpha(result, 126, 75))
        assertColor(0xff0000ff.toInt(), result.getRGB(224, 96))
        assertEquals(0, alpha(result, 193, 65))
    }

    @Test fun maskFollowsWorldAndDoesNotErasePreviouslyDrawnSibling() {
        val front = layer(1, BrandMotionLayerType.IMAGE, transform(128.0, 0.0), masks = listOf(mask(BrandMotionMaskMode.ADD, 0.0, 0.0, 64.0, 64.0)), reference = "image")
        val behind = shapeLayer(2, rectangle(128.0, 128.0, 256.0, 256.0), RED)
        val result = render(scene(listOf(front, behind)))
        assertColor(-1, result.getRGB(144, 32))
        assertColor(0xffff0000.toInt(), result.getRGB(32, 32))
        assertColor(0xffff0000.toInt(), result.getRGB(144, 96)) // mask does not DST_IN the already-rendered backdrop
    }

    @Test fun invalidAnimationFallsBackToVerifiedStillAndUnsupportedWelcomeOrPngCannotOpen() {
        val animation = DesktopMaidAnimation.EMPTY
        val png = resource(animation.asset.pngFileName)
        val changedJson = resource(animation.asset.jsonFileName).copyOf().also { it[0] = 32 }
        assertIs<BrandMotionRendererOpenResult.Ready>(BrandMotionRenderer.open(animation, changedJson, png)).renderer.use { renderer ->
            assertFalse(renderer.supportsAnimation)
            assertNotNull(renderer.degradationReason)
            val result = renderer.rasterSnapshot(512, 512, 0.5).use { pixels(it) }
            val source = requireNotNull(ImageIO.read(ByteArrayInputStream(png)))
            for (y in 16 until 496 step 17) for (x in 16 until 496 step 19) assertColor(source.getRGB(x, y), result.getRGB(x, y))
        }
        assertIs<BrandMotionRendererOpenResult.Rejected>(BrandMotionRenderer.open(DesktopMaidAnimation.WELCOME, byteArrayOf(), byteArrayOf()))
        assertIs<BrandMotionRendererOpenResult.Rejected>(BrandMotionRenderer.open(animation, resource(animation.asset.jsonFileName), byteArrayOf()))
    }

    @Test fun sizeBudgetBeforeAllocationResizeAndCloseReleaseOwnedResources() {
        val renderer = open(DesktopMaidAnimation.TRIPLE_SUCCESS)
        assertEquals(0L, renderer.resources().surfacePixels)
        assertFailsWith<IllegalArgumentException> { renderer.rasterSnapshot(2048, 1, 0.0) }
        assertEquals(0L, renderer.resources().surfacePixels)
        renderer.rasterSnapshot(512, 512, 0.5).use { assertEquals(512, it.width) }
        assertTrue(renderer.resources().cachedPaths > 0)
        renderer.rasterSnapshot(128, 128, 0.5).use { assertEquals(128, it.width) }
        assertEquals(16384L, renderer.resources().surfacePixels)
        renderer.close(); renderer.close()
        assertEquals(BrandMotionRenderResources(0L, 0, 0, 0, true), renderer.resources())
        assertFailsWith<IllegalStateException> { renderer.rasterSnapshot(32, 32, 0.0) }
    }

    @Test fun publicRenderAndStillRespectTheCallersMatrixClipAndSaveDepth() {
        BrandMotionRenderer.forTest(scene(listOf(layer(1, BrandMotionLayerType.IMAGE, reference = "image"))), whitePng()).use { renderer ->
            for (still in listOf(false, true)) Surface.makeRasterN32Premul(128, 128).use { target ->
                val canvas = target.canvas
                canvas.clear(0); canvas.translate(16f, 8f); canvas.clipRect(Rect.makeWH(32f, 32f))
                val depth = canvas.saveCount
                if (still) renderer.renderStill(canvas, 64, 64) else renderer.render(canvas, 64, 64, 0.0)
                assertEquals(depth, canvas.saveCount)
                Paint().use { paint ->
                    paint.color = 0xffff0000.toInt()
                    canvas.drawRect(Rect.makeXYWH(0f, 0f, 8f, 8f), paint)
                }
                val result = target.makeImageSnapshot().use { pixels(it) }
                assertColor(0xffff0000.toInt(), result.getRGB(20, 12)) // subsequent caller draw keeps its matrix
                assertColor(-1, result.getRGB(30, 20)) // both public routes draw the actual PNG
                assertEquals(0, alpha(result, 49, 20)) // caller clip remains in force
                assertEquals(0, alpha(result, 0, 0))
            }
        }
    }

    @Test fun unsupportedSubtractOpacityRejectsBeforeImageDecode() {
        val candidate = scene(listOf(layer(1, BrandMotionLayerType.IMAGE,
            masks = listOf(mask(BrandMotionMaskMode.SUBTRACT, 0.0, 0.0, 64.0, 64.0, 0.5)), reference = "image")))
        assertFailsWith<IllegalArgumentException> { BrandMotionRenderer.forTest(candidate, whitePng()) }
    }

    private fun render(scene: BrandMotionScene): BufferedImage = BrandMotionRenderer.forTest(scene, whitePng()).use { renderer ->
        renderer.rasterSnapshot(512, 512, 0.0).use { pixels(it) }
    }
    private fun point(x: Double, y: Double) = BrandMotionPoint(x, y)
    private fun constant(vararg values: Double) = BrandMotionProperty(values.toList(), emptyList())
    private fun transform(x: Double = 0.0, y: Double = 0.0, opacity: Double = 100.0) = BrandMotionTransform(
        constant(x, y, 0.0), constant(0.0, 0.0, 0.0), constant(100.0, 100.0, 100.0), constant(0.0), constant(opacity),
    )
    private fun rectangle(x: Double, y: Double, w: Double, h: Double) = BrandMotionShape.Rectangle(point(x, y), point(w, h), 0.0, 1)
    private fun fill(color: List<Double>) = BrandMotionShape.Fill(color, 1.0, 1)
    private fun shapeLayer(id: Int, shape: BrandMotionShape, color: List<Double>) = layer(id, BrandMotionLayerType.SHAPE,
        shapes = listOf(BrandMotionShape.Group("shape$id", listOf(shape, fill(color)), transform())))
    private fun layer(id: Int, type: BrandMotionLayerType, transform: BrandMotionTransform = transform(),
        masks: List<BrandMotionMask> = emptyList(), shapes: List<BrandMotionShape> = emptyList(), reference: String? = null,
        width: Int = 512, height: Int = 512) = BrandMotionLayer(id, "layer$id", type, null, transform, 0.0, 60.0, 0.0, 1.0,
            reference, width, height, masks, shapes)
    private fun scene(layers: List<BrandMotionLayer>, precompositions: Map<String, List<BrandMotionLayer>> = emptyMap()) =
        BrandMotionScene("synthetic deterministic geometry", 512, 512, 60.0, 0.0, 60.0, layers, precompositions,
            BrandMotionImage("image", "owned-white.png", 512, 512))
    private fun mask(mode: BrandMotionMaskMode, left: Double, top: Double, right: Double, bottom: Double, opacity: Double = 1.0): BrandMotionMask {
        val zero = List(4) { point(0.0, 0.0) }
        return BrandMotionMask("rectangle", mode, BrandMotionPath(listOf(point(left, top), point(right, top), point(right, bottom), point(left, bottom)), zero, zero, true), opacity)
    }
    private fun whitePng(): ByteArray {
        val image = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 512) for (x in 0 until 512) image.setRGB(x, y, -1)
        return ByteArrayOutputStream().use { bytes -> check(ImageIO.write(image, "png", bytes)); bytes.toByteArray() }
    }
    companion object {
        private val RED = listOf(1.0, 0.0, 0.0, 1.0)
        private val BLUE = listOf(0.0, 0.0, 1.0, 1.0)
        private val artifactRoot: FilePath by lazy {
            val explicit = System.getProperty("bilipai.brand.raster.output") ?: System.getenv("BILIPAI_BRAND_RASTER_OUTPUT")
            val root = if (explicit == null) Files.createTempDirectory("bilipai-brand-cpu-raster-") else FilePath.of(explicit).toAbsolutePath()
            if (!Files.exists(root)) Files.createDirectory(root)
            require(Files.isDirectory(root))
            root
        }
    }
}

package com.bilipai.desktop.brand

import java.util.IdentityHashMap
import kotlin.math.hypot
import kotlin.math.min
import org.jetbrains.skia.*

sealed interface BrandMotionRendererOpenResult {
    data class Ready(val renderer: BrandMotionRenderer) : BrandMotionRendererOpenResult
    data class Rejected(val reason: String) : BrandMotionRendererOpenResult
}
data class BrandMotionRenderResources(val surfacePixels: Long, val cachedPaths: Int, val pathBytes: Int, val liveImages: Int, val closed: Boolean)

/** Owned, synchronized CPU renderer. No DirectContext, GPU, window, player or global asset cache.
 * Width/height are pixels. Caller owns the target matrix/clip; they are left unchanged.
 * Layer matrices are absolute WORLD, while shape-group matrices are relative to their layer.
 */
class BrandMotionRenderer private constructor(
    private val image: Image, private val scene: BrandMotionScene?,
    val degradationReason: String?, val durationMs: Double,
) : AutoCloseable {
    val supportsAnimation: Boolean get() = scene != null
    private val sampler = scene?.let(::BrandMotionSampler)
    private val budget = BrandMotionRenderBudget()
    private val paths = IdentityHashMap<Any, Path>()
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var closed = false
    private var viewportMatrix = BrandMotionMatrix()
    private val sceneBounds = Rect.makeWH(512f, 512f)

    @Synchronized fun render(target: Canvas, widthPx: Int, heightPx: Int, progress: Double) {
        check(!closed)
        val raster = draw(widthPx, heightPx, progress)
        // Surface.draw respects the caller's existing matrix and clip and mutates neither.
        raster.draw(target, 0, 0, null)
    }
    @Synchronized fun renderStill(target: Canvas, widthPx: Int, heightPx: Int) {
        check(!closed); BrandMotionRenderBudget.dimensions(widthPx, heightPx)
        val fit = fit(widthPx, heightPx)
        val destination = Rect.makeXYWH(fit.tx.toFloat(), fit.ty.toFloat(), (512.0 * fit.a).toFloat(), (512.0 * fit.d).toFloat())
        Paint().use { paint ->
            paint.isAntiAlias = true
            target.drawImageRect(image, sceneBounds, destination, SamplingMode.LINEAR, paint, true)
        }
    }
    /** Test/export only. The returned image belongs to the caller and must be closed with use.
     * It proves a CPU raster, not an onscreen frame or lifecycle/operation completion.
     */
    @Synchronized fun rasterSnapshot(widthPx: Int, heightPx: Int, progress: Double): Image {
        check(!closed)
        return draw(widthPx, heightPx, progress).makeImageSnapshot()
    }
    @Synchronized fun resources(): BrandMotionRenderResources = BrandMotionRenderResources(
        width.toLong() * height, budget.pathCount, budget.pathBytes, if (closed) 0 else 1, closed,
    )

    private fun draw(widthPx: Int, heightPx: Int, progress: Double): Surface {
        require(progress.isFinite()) { "Brand progress must be finite" }
        budget.beginFrame(widthPx, heightPx)
        if (width != widthPx || height != heightPx) {
            releaseSurface()
            surface = Surface.makeRasterN32Premul(widthPx, heightPx)
            width = widthPx; height = heightPx
        }
        val current = checkNotNull(surface)
        val canvas = current.canvas
        val saved = canvas.save()
        try {
            canvas.resetMatrix(); canvas.clear(0)
            viewportMatrix = fit(widthPx, heightPx)
            absolute(canvas, BrandMotionMatrix())
            canvas.clipRect(sceneBounds, ClipMode.INTERSECT, true)
            if (sampler == null) {
                budget.paint()
                canvas.drawImageRect(image, sceneBounds, sceneBounds, SamplingMode.LINEAR, null, true)
            } else {
                val frame = sampler.sampleProgress(progress)
                for (layer in frame.layers.asReversed()) drawLayer(canvas, layer)
            }
        } finally { canvas.restoreToCount(saved) }
        return current
    }
    private fun fit(width: Int, height: Int): BrandMotionMatrix {
        val scale = min(width / 512.0, height / 512.0)
        return BrandMotionMatrix(a = scale, d = scale, tx = (width - 512.0 * scale) / 2.0, ty = (height - 512.0 * scale) / 2.0)
    }
    private fun absolute(canvas: Canvas, world: BrandMotionMatrix) {
        BrandMotionRenderBudget.matrix(world)
        canvas.setMatrix(native(viewportMatrix * world))
    }
    private fun native(value: BrandMotionMatrix): Matrix33 = Matrix33(
        BrandMotionRenderBudget.coordinate(value.a), BrandMotionRenderBudget.coordinate(value.c), BrandMotionRenderBudget.coordinate(value.tx),
        BrandMotionRenderBudget.coordinate(value.b), BrandMotionRenderBudget.coordinate(value.d), BrandMotionRenderBudget.coordinate(value.ty), 0f, 0f, 1f,
    )

    private fun drawLayer(canvas: Canvas, layer: BrandMotionSampledLayer) {
        if (layer.source.type == BrandMotionLayerType.NULL || layer.ownOpacity == 0.0) return
        BrandMotionRenderBudget.alpha(layer.ownOpacity)
        composite(canvas, layer.ownOpacity, layer.source.masks.isNotEmpty()) {
            if (layer.source.type == BrandMotionLayerType.PRECOMPOSITION) {
                absolute(canvas, layer.matrix)
                canvas.clipRect(Rect.makeWH(requireNotNull(layer.source.width).toFloat(), requireNotNull(layer.source.height).toFloat()), ClipMode.INTERSECT, true)
                // Children carry full world transforms: do not concatenate this matrix again.
                absolute(canvas, BrandMotionMatrix())
                for (child in layer.children.asReversed()) drawLayer(canvas, child)
            } else if (layer.source.type == BrandMotionLayerType.IMAGE) {
                require(layer.source.reference == requireNotNull(scene).image.id)
                absolute(canvas, layer.matrix); budget.paint()
                canvas.drawImageRect(image, sceneBounds, sceneBounds, SamplingMode.LINEAR, null, true)
            } else {
                drawItems(canvas, layer.shapes, layer.matrix)
            }
            if (layer.source.masks.isNotEmpty()) applyMasks(canvas, layer)
        }
    }
    private inline fun composite(canvas: Canvas, opacity: Double, isolate: Boolean = false, blend: BlendMode = BlendMode.SRC_OVER, body: () -> Unit) {
        absolute(canvas, BrandMotionMatrix())
        val offscreen = isolate || opacity != 1.0 || blend != BlendMode.SRC_OVER
        if (!offscreen) {
            val saved = canvas.save()
            try { body() } finally { canvas.restoreToCount(saved) }
            return
        }
        budget.enterLayer()
        try {
            Paint().use { paint ->
                paint.setAlphaf(BrandMotionRenderBudget.alpha(opacity)); paint.blendMode = blend
                val saved = canvas.saveLayer(sceneBounds, paint)
                try { body() } finally { canvas.restoreToCount(saved) }
            }
        } finally { budget.leaveLayer() }
    }
    private fun applyMasks(canvas: Canvas, layer: BrandMotionSampledLayer) {
        composite(canvas, 1.0, true, BlendMode.DST_IN) {
            for ((index, mask) in layer.source.masks.withIndex()) {
                if (index == 0 && mask.mode == BrandMotionMaskMode.SUBTRACT) {
                    Paint().use { it.color = -1; budget.paint(); canvas.drawPaint(it) }
                }
                absolute(canvas, layer.matrix)
                Paint().use { paint ->
                    paint.isAntiAlias = true; paint.color = -1
                    paint.blendMode = if (mask.mode == BrandMotionMaskMode.ADD) BlendMode.SRC_OVER else BlendMode.DST_OUT
                    paint.setAlphaf(if (mask.mode == BrandMotionMaskMode.SUBTRACT) 1f else BrandMotionRenderBudget.alpha(mask.opacity))
                    budget.paint(); canvas.drawPath(path(mask.path), paint)
                }
            }
        }
    }

    /** Lottie contents draw back-to-front; each paint consumes the preceding PathContents.
     * Nested groups still draw their own paints and donate transformed geometry to a parent paint.
     */
    private fun drawItems(canvas: Canvas, items: List<BrandMotionSampledShape>, world: BrandMotionMatrix) {
        for (index in items.indices.reversed()) when (val item = items[index]) {
            is BrandMotionSampledShape.Group -> if (item.opacity > 0.0) composite(canvas, item.opacity) {
                drawItems(canvas, item.items, world * item.matrix)
            }
            is BrandMotionSampledShape.Static -> when (val style = item.source) {
                is BrandMotionShape.Fill -> drawStyle(canvas, items.subList(0, index), world, style)
                is BrandMotionShape.Stroke -> drawStyle(canvas, items.subList(0, index), world, style)
                else -> Unit
            }
        }
    }
    private fun drawStyle(canvas: Canvas, items: List<BrandMotionSampledShape>, world: BrandMotionMatrix, style: BrandMotionShape) {
        absolute(canvas, BrandMotionMatrix())
        val fillMode = if (style is BrandMotionShape.Fill && style.fillRule == 2) PathFillMode.EVEN_ODD else PathFillMode.WINDING
        PathBuilder(fillMode).use { builder ->
            appendGeometry(builder, items, world)
            builder.detach().use { combined ->
                require(combined.isFinite && combined.approximateBytesUsed <= BrandMotionRenderBudget.MAX_PATH_BYTES)
                Paint().use { paint ->
                    paint.isAntiAlias = true
                    val color: List<Double>; val opacity: Double
                    when (style) {
                        is BrandMotionShape.Fill -> { color = style.color; opacity = style.opacity; paint.mode = PaintMode.FILL }
                        is BrandMotionShape.Stroke -> {
                            color = style.color; opacity = style.opacity; paint.mode = PaintMode.STROKE
                            // Original Lottie scales strokes by the transformed diagonal, not
                            // by an anisotropic Canvas stroke after transforming path geometry.
                            val scale = hypot(world.a + world.c, world.b + world.d) / kotlin.math.sqrt(2.0)
                            paint.strokeWidth = (style.width * scale).toFloat()
                            paint.strokeCap = PaintStrokeCap.entries[style.cap - 1]
                            paint.strokeJoin = PaintStrokeJoin.entries[style.join - 1]
                            paint.strokeMiter = 4f
                        }
                        else -> error("Not a drawing style")
                    }
                    paint.color4f = Color4f(color[0].toFloat(), color[1].toFloat(), color[2].toFloat(), (color[3] * opacity).toFloat())
                    budget.paint(); canvas.drawPath(combined, paint)
                }
            }
        }
    }
    private fun appendGeometry(builder: PathBuilder, items: List<BrandMotionSampledShape>, world: BrandMotionMatrix) {
        BrandMotionRenderBudget.matrix(world)
        for (item in items.asReversed()) when (item) {
            is BrandMotionSampledShape.Group -> appendGeometry(builder, item.items, world * item.matrix)
            is BrandMotionSampledShape.Static -> when (val shape = item.source) {
                is BrandMotionShape.Path, is BrandMotionShape.Ellipse, is BrandMotionShape.Rectangle -> {
                    val nativePath = geometry(shape)
                    budget.copyPath(nativePath.pointsCount)
                    builder.addPath(nativePath, native(world))
                }
                else -> Unit
            }
        }
    }
    private fun path(source: BrandMotionPath): Path = cached(source, source.vertices.size * 64 + 512) { builder ->
        val vertices = source.vertices
        builder.moveTo(vertices[0].x.toFloat(), vertices[0].y.toFloat())
        fun segment(previous: Int, next: Int) {
            val from = vertices[previous]; val to = vertices[next]
            val outgoing = source.outgoing[previous]; val incoming = source.incoming[next]
            if (outgoing.x == 0.0 && outgoing.y == 0.0 && incoming.x == 0.0 && incoming.y == 0.0)
                builder.lineTo(to.x.toFloat(), to.y.toFloat())
            else builder.cubicTo(
                (from.x + outgoing.x).toFloat(), (from.y + outgoing.y).toFloat(),
                (to.x + incoming.x).toFloat(), (to.y + incoming.y).toFloat(), to.x.toFloat(), to.y.toFloat(),
            )
        }
        for (index in 1 until vertices.size) segment(index - 1, index)
        if (source.closed) { segment(vertices.lastIndex, 0); builder.closePath() }
    }
    private fun geometry(shape: BrandMotionShape): Path = when (shape) {
        is BrandMotionShape.Path -> path(shape.path)
        is BrandMotionShape.Ellipse -> cached(shape, 2048) { builder ->
            val x = shape.position.x; val y = shape.position.y; val rx = shape.size.x * 0.5; val ry = shape.size.y * 0.5
            val cx = rx * 0.55228; val cy = ry * 0.55228 // original EllipseContent cubic handles
            builder.moveTo(x.toFloat(), (y - ry).toFloat())
            if (shape.direction == 1) {
                builder.cubicTo((x + cx).toFloat(), (y - ry).toFloat(), (x + rx).toFloat(), (y - cy).toFloat(), (x + rx).toFloat(), y.toFloat())
                builder.cubicTo((x + rx).toFloat(), (y + cy).toFloat(), (x + cx).toFloat(), (y + ry).toFloat(), x.toFloat(), (y + ry).toFloat())
                builder.cubicTo((x - cx).toFloat(), (y + ry).toFloat(), (x - rx).toFloat(), (y + cy).toFloat(), (x - rx).toFloat(), y.toFloat())
                builder.cubicTo((x - rx).toFloat(), (y - cy).toFloat(), (x - cx).toFloat(), (y - ry).toFloat(), x.toFloat(), (y - ry).toFloat())
            } else {
                builder.cubicTo((x - cx).toFloat(), (y - ry).toFloat(), (x - rx).toFloat(), (y - cy).toFloat(), (x - rx).toFloat(), y.toFloat())
                builder.cubicTo((x - rx).toFloat(), (y + cy).toFloat(), (x - cx).toFloat(), (y + ry).toFloat(), x.toFloat(), (y + ry).toFloat())
                builder.cubicTo((x + cx).toFloat(), (y + ry).toFloat(), (x + rx).toFloat(), (y + cy).toFloat(), (x + rx).toFloat(), y.toFloat())
                builder.cubicTo((x + rx).toFloat(), (y - cy).toFloat(), (x + cx).toFloat(), (y - ry).toFloat(), x.toFloat(), (y - ry).toFloat())
            }
            builder.closePath()
        }
        is BrandMotionShape.Rectangle -> cached(shape, 2048) { builder ->
            val radius = min(shape.radius, min(shape.size.x, shape.size.y) * 0.5).toFloat()
            builder.addRRect(RRect.makeXYWH((shape.position.x - shape.size.x * 0.5).toFloat(), (shape.position.y - shape.size.y * 0.5).toFloat(),
                shape.size.x.toFloat(), shape.size.y.toFloat(), radius, radius),
                if (shape.direction == 1) PathDirection.CLOCKWISE else PathDirection.COUNTER_CLOCKWISE)
        }
        else -> error("Style cannot be used as geometry")
    }
    private inline fun cached(key: Any, estimatedBytes: Int, build: (PathBuilder) -> Unit): Path {
        paths[key]?.let { return it }
        // Reserve a conservative native point/verb bound before creating a builder.
        budget.beforePath(estimatedBytes)
        val created = PathBuilder().use { builder -> build(builder); builder.detach() }
        try {
            require(created.isFinite && created.verbsCount <= 4096 && created.pointsCount <= 12288)
            budget.retainPath(created.approximateBytesUsed)
            paths[key] = created
            return created
        } catch (failure: Throwable) { created.close(); throw failure }
    }
    private fun releaseSurface() { surface?.close(); surface = null; width = 0; height = 0 }
    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            try { releaseSurface() } finally {
                try { paths.values.forEach { it.close() }; paths.clear(); budget.releasePaths() } finally { image.close() }
            }
        }
    }
    companion object {
        fun open(animation: DesktopMaidAnimation, jsonBytes: ByteArray, pngBytes: ByteArray): BrandMotionRendererOpenResult {
            val decoded = BrandMotionDecoder.decode(animation, jsonBytes, pngBytes)
            if (decoded is BrandMotionDecodeResult.Rejected && !decoded.fallbackVerified)
                return BrandMotionRendererOpenResult.Rejected(decoded.reason)
            val scene = (decoded as? BrandMotionDecodeResult.Decoded)?.scene
            val error = (decoded as? BrandMotionDecodeResult.Rejected)?.reason
            return try { BrandMotionRendererOpenResult.Ready(create(scene, pngBytes, error, animation.durationMs.toDouble())) }
            catch (failure: Exception) { BrandMotionRendererOpenResult.Rejected("Brand CPU image/scene failed: ${failure.message}") }
        }
        private fun create(scene: BrandMotionScene?, png: ByteArray, error: String?, durationMs: Double): BrandMotionRenderer {
            require(png.size in 1..1_048_576) { "Brand encoded PNG exceeds budget" }
            scene?.let { BrandMotionRenderBudget.scene(it) }
            val image = Image.makeFromEncoded(png)
            try {
                require(image.width == 512 && image.height == 512) { "Brand decoded PNG dimensions mismatch" }
                return BrandMotionRenderer(image, scene, error, durationMs)
            } catch (failure: Throwable) { image.close(); throw failure }
        }
        /** Package-internal deterministic raster-test seam; no production/runtime caller bypasses decode. */
        internal fun forTest(scene: BrandMotionScene, pngBytes: ByteArray): BrandMotionRenderer = create(scene, pngBytes, null, scene.durationMs)
    }
}

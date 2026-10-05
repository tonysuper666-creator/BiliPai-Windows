package com.bilipai.desktop.brand

import kotlin.math.cos
import kotlin.math.sin

/** Scene coordinates and timeline frames are the original JSON units, never dp. */
data class BrandMotionPoint(val x: Double, val y: Double)

/** Column-vector affine transform. Multiplication applies [other] first. */
data class BrandMotionMatrix(
    val a: Double = 1.0, val b: Double = 0.0,
    val c: Double = 0.0, val d: Double = 1.0,
    val tx: Double = 0.0, val ty: Double = 0.0,
) {
    operator fun times(other: BrandMotionMatrix) = BrandMotionMatrix(
        a * other.a + c * other.b, b * other.a + d * other.b,
        a * other.c + c * other.d, b * other.c + d * other.d,
        a * other.tx + c * other.ty + tx, b * other.tx + d * other.ty + ty,
    )
    fun map(point: BrandMotionPoint) = BrandMotionPoint(
        a * point.x + c * point.y + tx, b * point.x + d * point.y + ty,
    )
    companion object {
        fun translation(x: Double, y: Double) = BrandMotionMatrix(tx = x, ty = y)
        fun scale(x: Double, y: Double) = BrandMotionMatrix(a = x, d = y)
        fun rotation(degrees: Double): BrandMotionMatrix {
            val radians = Math.toRadians(degrees)
            return BrandMotionMatrix(cos(radians), sin(radians), -sin(radians), cos(radians))
        }
    }
}

data class BrandMotionBezier(val outX: Double, val outY: Double, val inX: Double, val inY: Double) {
    fun progress(fraction: Double): Double {
        if (fraction <= 0.0) return 0.0
        if (fraction >= 1.0) return 1.0
        // Bisection of the temporal curve is deterministic, including flat tangents.
        var low = 0.0
        var high = 1.0
        repeat(40) {
            val mid = (low + high) * 0.5
            if (cubic(mid, outX, inX) < fraction) low = mid else high = mid
        }
        return cubic((low + high) * 0.5, outY, inY)
    }
    private fun cubic(t: Double, first: Double, second: Double): Double {
        val u = 1.0 - t
        return 3.0 * u * u * t * first + 3.0 * u * t * t * second + t * t * t
    }
}

data class BrandMotionKeyframe(
    val time: Double,
    val start: List<Double>,
    val end: List<Double>?,
    val easing: List<BrandMotionBezier>?,
)

data class BrandMotionProperty(val constant: List<Double>?, val keyframes: List<BrandMotionKeyframe>) {
    val components: Int get() = constant?.size ?: keyframes.first().start.size
    fun sample(frame: Double): List<Double> {
        require(frame.isFinite())
        constant?.let { return it }
        if (frame <= keyframes.first().time) return keyframes.first().start
        if (frame >= keyframes.last().time) return keyframes.last().start
        var low = 0
        var high = keyframes.lastIndex
        while (high - low > 1) {
            val mid = (low + high) ushr 1
            if (keyframes[mid].time <= frame) low = mid else high = mid
        }
        val first = keyframes[low]
        val next = keyframes[low + 1]
        val fraction = (frame - first.time) / (next.time - first.time)
        val end = requireNotNull(first.end)
        val easing = requireNotNull(first.easing)
        return first.start.indices.map { component ->
            val progress = easing[component].progress(fraction)
            first.start[component] + (end[component] - first.start[component]) * progress
        }
    }
}

data class BrandMotionTransform(
    val position: BrandMotionProperty, val anchor: BrandMotionProperty,
    val scale: BrandMotionProperty, val rotation: BrandMotionProperty,
    val opacity: BrandMotionProperty,
) {
    fun matrix(frame: Double): BrandMotionMatrix {
        val p = position.sample(frame)
        val a = anchor.sample(frame)
        val s = scale.sample(frame)
        return BrandMotionMatrix.translation(p[0], p[1]) *
            BrandMotionMatrix.rotation(rotation.sample(frame)[0]) *
            BrandMotionMatrix.scale(s[0] / 100.0, s[1] / 100.0) *
            BrandMotionMatrix.translation(-a[0], -a[1])
    }
    fun alpha(frame: Double): Double = (opacity.sample(frame)[0] / 100.0).coerceIn(0.0, 1.0)
}

/** Tangents are relative to their matching vertices, as stored in the original JSON. */
data class BrandMotionPath(
    val vertices: List<BrandMotionPoint>,
    val incoming: List<BrandMotionPoint>,
    val outgoing: List<BrandMotionPoint>,
    val closed: Boolean,
)

enum class BrandMotionMaskMode { ADD, SUBTRACT }
data class BrandMotionMask(val name: String, val mode: BrandMotionMaskMode, val path: BrandMotionPath, val opacity: Double)

/** Item order is retained. Fill/stroke apply to preceding geometry within their group. */
sealed interface BrandMotionShape {
    data class Group(val name: String, val items: List<BrandMotionShape>, val transform: BrandMotionTransform) : BrandMotionShape
    data class Path(val path: BrandMotionPath) : BrandMotionShape
    data class Ellipse(val position: BrandMotionPoint, val size: BrandMotionPoint, val direction: Int) : BrandMotionShape
    data class Rectangle(val position: BrandMotionPoint, val size: BrandMotionPoint, val radius: Double, val direction: Int) : BrandMotionShape
    data class Fill(val color: List<Double>, val opacity: Double, val fillRule: Int) : BrandMotionShape
    data class Stroke(val color: List<Double>, val opacity: Double, val width: Double, val cap: Int, val join: Int) : BrandMotionShape
}

enum class BrandMotionLayerType { PRECOMPOSITION, IMAGE, NULL, SHAPE }
data class BrandMotionLayer(
    val index: Int, val name: String, val type: BrandMotionLayerType, val parent: Int?,
    val transform: BrandMotionTransform, val inFrame: Double, val outFrame: Double,
    val startFrame: Double, val stretch: Double,
    val reference: String?, val width: Int?, val height: Int?,
    val masks: List<BrandMotionMask>, val shapes: List<BrandMotionShape>,
)
data class BrandMotionImage(val id: String, val fileName: String, val width: Int, val height: Int)
data class BrandMotionScene(
    val name: String, val width: Int, val height: Int, val framesPerSecond: Double,
    val inFrame: Double, val outFrame: Double,
    val layers: List<BrandMotionLayer>,
    val precompositions: Map<String, List<BrandMotionLayer>>,
    val image: BrandMotionImage,
) {
    val durationMs: Double get() = (outFrame - inFrame) * 1000.0 / framesPerSecond
}

sealed interface BrandMotionSampledShape {
    data class Group(
        val name: String, val matrix: BrandMotionMatrix, val opacity: Double,
        val items: List<BrandMotionSampledShape>,
    ) : BrandMotionSampledShape
    data class Static(val source: BrandMotionShape) : BrandMotionSampledShape
}

/** World matrices already include transform parents and all enclosing precompositions.
 * Masks and precomp clip rectangles are local to [source] and use this layer's [matrix].
 * Parent NULL opacity is deliberately not inherited: Lottie parents affect transforms.
 * Renderer must group/composite this tree, applying [ownOpacity] once per drawable layer.
 * [worldOpacity] is diagnostic only: using it on children inside an opacity group would
 * double-apply alpha, while flattening overlapping siblings would change the artwork.
 */
data class BrandMotionSampledLayer(
    val instancePath: String, val source: BrandMotionLayer, val localFrame: Double,
    val matrix: BrandMotionMatrix, val ownOpacity: Double, val worldOpacity: Double,
    val shapes: List<BrandMotionSampledShape>, val children: List<BrandMotionSampledLayer>,
)
data class BrandMotionFrame(val scene: BrandMotionScene, val frame: Double, val layers: List<BrandMotionSampledLayer>)

class BrandMotionSampler(private val scene: BrandMotionScene) {
    fun sampleProgress(progress: Double): BrandMotionFrame {
        require(progress.isFinite())
        val frame = scene.inFrame + (scene.outFrame - scene.inFrame) * progress.coerceIn(0.0, 1.0)
        // ip is inclusive and op exclusive. The completed pose samples just before op.
        return sampleFrame(if (frame >= scene.outFrame) Math.nextDown(scene.outFrame) else frame)
    }
    fun sampleElapsedMs(elapsedMs: Double): BrandMotionFrame {
        require(elapsedMs.isFinite() && elapsedMs >= 0.0)
        return sampleProgress(elapsedMs / scene.durationMs)
    }
    fun sampleFrame(frame: Double): BrandMotionFrame {
        require(frame.isFinite())
        return BrandMotionFrame(scene, frame, if (frame < scene.inFrame || frame >= scene.outFrame) emptyList()
            else layers(scene.layers, frame, BrandMotionMatrix(), 1.0, "root"))
    }
    private fun layers(
        source: List<BrandMotionLayer>, frame: Double, outerMatrix: BrandMotionMatrix,
        outerOpacity: Double, path: String,
    ): List<BrandMotionSampledLayer> {
        val byIndex = source.associateBy { it.index }
        val matrices = mutableMapOf<Int, BrandMotionMatrix>()
        fun matrix(layer: BrandMotionLayer): BrandMotionMatrix = matrices.getOrPut(layer.index) {
            val parent = layer.parent?.let { matrix(byIndex.getValue(it)) } ?: BrandMotionMatrix()
            parent * layer.transform.matrix(frame)
        }
        // Original layers are front-to-back. Keep that order; painter reverses it once.
        return source.filter { frame >= it.inFrame && frame < it.outFrame }.map { layer ->
            val world = outerMatrix * matrix(layer)
            val ownOpacity = layer.transform.alpha(frame)
            val opacity = outerOpacity * ownOpacity
            val instance = "$path/${layer.index}"
            val localFrame = (frame - layer.startFrame) / layer.stretch
            BrandMotionSampledLayer(
                instance, layer, localFrame, world, ownOpacity, opacity, shapes(layer.shapes, frame),
                if (layer.type == BrandMotionLayerType.PRECOMPOSITION)
                    layers(scene.precompositions.getValue(requireNotNull(layer.reference)), localFrame, world, opacity, instance)
                else emptyList(),
            )
        }
    }
    private fun shapes(source: List<BrandMotionShape>, frame: Double): List<BrandMotionSampledShape> = source.map {
        when (it) {
            is BrandMotionShape.Group -> BrandMotionSampledShape.Group(
                it.name, it.transform.matrix(frame), it.transform.alpha(frame), shapes(it.items, frame),
            )
            else -> BrandMotionSampledShape.Static(it)
        }
    }
}

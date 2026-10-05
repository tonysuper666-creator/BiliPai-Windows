package com.bilipai.desktop.brand

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.serialization.json.*

sealed interface BrandMotionDecodeResult {
    data class Decoded(val scene: BrandMotionScene) : BrandMotionDecodeResult
    /** A matching, verified PNG may be shown by the later UI adapter. This is no rendered animation. */
    data class Rejected(val reason: String, val fallbackFileName: String?, val fallbackVerified: Boolean) : BrandMotionDecodeResult
}

/** Only the fixed, audited image-backed brand-motion schema is accepted. Unknown fields or
 * features reject the complete scene, rather than silently dropping a layer/effect/mask.
 * No URI, filesystem, account, network, raster or global playback authority exists here.
 */
object BrandMotionDecoder {
    fun decode(animation: DesktopMaidAnimation, jsonBytes: ByteArray, pngBytes: ByteArray): BrandMotionDecodeResult {
        if (!animation.runtimeAllowed)
            return BrandMotionDecodeResult.Rejected("WELCOME is excluded from the Windows runtime", null, false)
        val asset = animation.asset
        val pngVerified = pngBytes.size == asset.pngBytes && sha256(pngBytes) == asset.pngSha256 && pngHeader(pngBytes)
        if (!pngVerified) return BrandMotionDecodeResult.Rejected("Matching packaged PNG identity/dimensions failed", null, false)
        if (jsonBytes.size != asset.jsonBytes || sha256(jsonBytes) != asset.jsonSha256)
            return BrandMotionDecodeResult.Rejected("Packaged JSON identity failed", asset.pngFileName, true)
        return parseRestrictedJson(jsonBytes, asset.pngFileName).let {
            when (it) {
                is BrandMotionDecodeResult.Decoded -> if (kotlin.math.abs(it.scene.durationMs - animation.durationMs) < 0.001) it
                    else BrandMotionDecodeResult.Rejected("Original duration contract failed", asset.pngFileName, true)
                is BrandMotionDecodeResult.Rejected -> it.copy(fallbackVerified = true)
            }
        }
    }

    /** Internal pure schema seam for tests. Runtime callers must use hash-bound [decode]. */
    internal fun parseRestrictedJson(bytes: ByteArray, expectedImageFileName: String): BrandMotionDecodeResult = try {
        require(bytes.size in 1..262_144) { "JSON byte budget exceeded" }
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "JSON is not valid UTF-8" }
        require(!text.startsWith('\uFEFF')) { "Unexpected JSON BOM" }
        checkNesting(text)
        BrandMotionDecodeResult.Decoded(Reader(expectedImageFileName).scene(Json.parseToJsonElement(text).jsonObject))
    } catch (error: IllegalArgumentException) {
        BrandMotionDecodeResult.Rejected(error.message ?: "Unsupported brand scene", expectedImageFileName, false)
    } catch (error: NoSuchElementException) {
        BrandMotionDecodeResult.Rejected("Required schema field missing: ${error.message}", expectedImageFileName, false)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun pngHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 33 || !bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) return false
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return header.getInt(8) == 13 && header.getInt(12) == 0x49484452 &&
            header.getInt(16) == 512 && header.getInt(20) == 512
    }
    private fun checkNesting(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 48) { "JSON nesting budget exceeded" } }
                '}', ']' -> { depth--; require(depth >= 0) { "Unbalanced JSON" } }
            }
        }
        require(!quoted && depth == 0) { "Incomplete JSON" }
    }

    private class Reader(private val expectedImageFileName: String) {
        private var layerCount = 0
        private var shapeCount = 0
        private var vertexCount = 0
        private var keyframeCount = 0
        private var maskCount = 0

        fun scene(root: JsonObject): BrandMotionScene {
            root.keysOnly("v", "fr", "ip", "op", "w", "h", "nm", "ddd", "assets", "layers", "markers")
            require(root.string("v") == "5.12.2") { "Unknown Lottie schema version" }
            root.zero("ddd")
            require(root.array("markers").isEmpty()) { "Timeline markers are unsupported" }
            val width = root.integer("w")
            val height = root.integer("h")
            require(width == 512 && height == 512) { "Original viewport contract failed" }
            val fps = root.number("fr")
            val first = root.number("ip")
            val last = root.number("op")
            require(fps == 60.0 && first == 0.0 && last > first && last <= 144.0) { "Original timeline contract failed" }
            val assets = root.array("assets")
            require(assets.size == 2) { "Expected one local image and one precomposition" }
            val images = mutableMapOf<String, BrandMotionImage>()
            val compositions = mutableMapOf<String, List<BrandMotionLayer>>()
            val assetIds = mutableSetOf<String>()
            for (element in assets) {
                val asset = element.jsonObject
                val id = asset.string("id")
                require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")) && assetIds.add(id)) { "Invalid/duplicate asset identity" }
                if ("layers" in asset) {
                    asset.keysOnly("id", "layers")
                    compositions[id] = layers(asset.array("layers"))
                } else {
                    asset.keysOnly("id", "w", "h", "u", "p", "e")
                    require(asset.string("u").isEmpty() && asset.integer("e") == 0) { "External/embedded images are unsupported" }
                    require(asset.string("p") == expectedImageFileName && !expectedImageFileName.contains('/') &&
                        !expectedImageFileName.contains('\\')) { "Image must be the matching packaged PNG" }
                    require(asset.integer("w") == 512 && asset.integer("h") == 512)
                    images[id] = BrandMotionImage(id, expectedImageFileName, 512, 512)
                }
            }
            require(images.size == 1 && compositions.size == 1) { "Unknown asset structure" }
            val top = layers(root.array("layers"))
            for (list in compositions.values + listOf(top)) validateParents(list)
            var expanded = 0
            fun validateReferences(list: List<BrandMotionLayer>, ancestors: Set<String>, depth: Int) {
                require(depth <= 12) { "Precomposition depth budget exceeded" }
                for (layer in list) {
                    require(++expanded <= 512) { "Expanded layer budget exceeded" }
                    when (layer.type) {
                        BrandMotionLayerType.PRECOMPOSITION -> {
                            val id = requireNotNull(layer.reference)
                            require(id !in ancestors && id in compositions) { "Missing/cyclic precomposition" }
                            validateReferences(compositions.getValue(id), ancestors + id, depth + 1)
                        }
                        BrandMotionLayerType.IMAGE -> require(layer.reference in images) { "Missing image asset" }
                        else -> Unit
                    }
                }
            }
            for ((id, list) in compositions) validateReferences(list, setOf(id), 1)
            validateReferences(top, emptySet(), 0)
            return BrandMotionScene(root.string("nm"), width, height, fps, first, last, top, compositions.toMap(), images.values.single())
        }

        private fun layers(array: JsonArray): List<BrandMotionLayer> {
            require(array.size in 1..64) { "Layer-list budget exceeded" }
            return array.map { element ->
                require(++layerCount <= 128) { "Layer budget exceeded" }
                val layer = element.jsonObject
                val type = when (layer.integer("ty")) {
                    0 -> BrandMotionLayerType.PRECOMPOSITION
                    2 -> BrandMotionLayerType.IMAGE
                    3 -> BrandMotionLayerType.NULL
                    4 -> BrandMotionLayerType.SHAPE
                    else -> errorSchema("Unknown layer type")
                }
                val common = setOf("ddd", "ind", "ty", "nm", "sr", "ks", "ao", "ip", "op", "st", "bm", "parent")
                val extras = when (type) {
                    BrandMotionLayerType.PRECOMPOSITION -> setOf("refId", "w", "h", "masksProperties", "hasMask")
                    BrandMotionLayerType.IMAGE -> setOf("refId", "w", "h")
                    BrandMotionLayerType.SHAPE -> setOf("shapes")
                    BrandMotionLayerType.NULL -> emptySet()
                }
                layer.keysOnly(*(common + extras).toTypedArray())
                layer.zero("ddd"); layer.zero("ao"); layer.zero("bm")
                // The audited assets have no stretched/time-remapped layers. Fail closed
                // until a new explicit timeline contract is audited, not approximate it.
                require(layer.number("sr") == 1.0 && layer.number("st") == 0.0) { "Time stretch/remapping is unsupported" }
                val first = layer.number("ip")
                val last = layer.number("op")
                require(first >= 0.0 && first < last && last <= 144.0)
                val index = layer.integer("ind")
                require(index in 1..128)
                val width = if ("w" in layer) layer.integer("w") else null
                val height = if ("h" in layer) layer.integer("h") else null
                if (width != null || height != null) require(width == 512 && height == 512)
                val masks = layer["masksProperties"]?.jsonArray?.map { mask(it.jsonObject) } ?: emptyList()
                layer["hasMask"]?.let { require(boolean(it) == masks.isNotEmpty()) { "Mask flag mismatch" } }
                BrandMotionLayer(
                    index, layer.string("nm"), type, layer["parent"]?.let { number(it).toInt().also { value -> require(value.toDouble() == number(it)) } },
                    transform(layer.getValue("ks").jsonObject, 3, true), first, last, 0.0, 1.0,
                    if ("refId" in layer) layer.string("refId") else null, width, height, masks,
                    layer["shapes"]?.jsonArray?.map { shape(it.jsonObject, 0) } ?: emptyList(),
                )
            }
        }

        private fun validateParents(layers: List<BrandMotionLayer>) {
            val indexed = layers.associateBy { it.index }
            require(indexed.size == layers.size) { "Duplicate layer index" }
            for (layer in layers) {
                val seen = mutableSetOf<Int>()
                var current: BrandMotionLayer? = layer
                while (current != null) {
                    require(seen.add(current.index) && seen.size <= 16) { "Cyclic/deep transform parents" }
                    current = current.parent?.let { requireNotNull(indexed[it]) { "Missing parent layer" } }
                }
            }
        }

        private fun transform(value: JsonObject, dimensions: Int, animated: Boolean): BrandMotionTransform {
            value.keysOnly("p", "a", "s", "r", "o", "sk", "sa", "ty")
            value["ty"]?.let { require(it.jsonPrimitive.content == "tr") }
            for (key in listOf("sk", "sa")) value[key]?.let {
                require(property(it.jsonObject, 1, false).constant == listOf(0.0)) { "Skew is unsupported" }
            }
            val position = property(value.getValue("p").jsonObject, dimensions, animated)
            val anchor = property(value.getValue("a").jsonObject, dimensions, animated)
            val scale = property(value.getValue("s").jsonObject, dimensions, animated)
            if (dimensions == 3) {
                require(allValues(position).all { it[2] == 0.0 } && allValues(anchor).all { it[2] == 0.0 } &&
                    allValues(scale).all { it[2] == 100.0 }) { "3D transforms are unsupported" }
            }
            val opacity = property(value.getValue("o").jsonObject, 1, animated)
            require(allValues(opacity).all { it[0] in 0.0..100.0 }) { "Opacity out of range" }
            return BrandMotionTransform(position, anchor, scale,
                property(value.getValue("r").jsonObject, 1, animated), opacity)
        }

        private fun property(value: JsonObject, dimensions: Int, animatedAllowed: Boolean): BrandMotionProperty {
            value.keysOnly("a", "k")
            return when (value.integer("a")) {
                0 -> BrandMotionProperty(vector(value.getValue("k"), dimensions), emptyList())
                1 -> {
                    require(animatedAllowed) { "Only layer transform properties may be animated" }
                    val array = value.array("k")
                    require(array.size in 2..128) { "Keyframe-list budget exceeded" }
                    val frames = array.mapIndexed { index, element ->
                        require(++keyframeCount <= 2048) { "Keyframe budget exceeded" }
                        val keyframe = element.jsonObject
                        keyframe.keysOnly("t", "s", "e", "i", "o")
                        val time = keyframe.number("t")
                        require(time in 0.0..144.0)
                        val start = vector(keyframe.getValue("s"), dimensions)
                        if (index == array.lastIndex) {
                            require(keyframe.keys == setOf("t", "s")) { "Unexpected terminal keyframe" }
                            BrandMotionKeyframe(time, start, null, null)
                        } else {
                            val end = vector(keyframe.getValue("e"), dimensions)
                            val outgoing = handle(keyframe.getValue("o").jsonObject, dimensions)
                            val incoming = handle(keyframe.getValue("i").jsonObject, dimensions)
                            BrandMotionKeyframe(time, start, end, start.indices.map {
                                BrandMotionBezier(outgoing.first[it], outgoing.second[it], incoming.first[it], incoming.second[it])
                            })
                        }
                    }
                    require(frames.zipWithNext().all { (a, b) -> a.time < b.time }) { "Non-increasing keyframe times" }
                    BrandMotionProperty(null, frames)
                }
                else -> errorSchema("Unknown animated property encoding")
            }
        }
        private fun allValues(property: BrandMotionProperty): List<List<Double>> = property.constant?.let { listOf(it) }
            ?: property.keyframes.flatMap { listOfNotNull(it.start, it.end) }
        private fun handle(value: JsonObject, dimensions: Int): Pair<List<Double>, List<Double>> {
            value.keysOnly("x", "y")
            fun components(key: String): List<Double> {
                val element = value.getValue(key)
                val numbers = if (element is JsonArray) element.map(::number) else listOf(number(element))
                require(numbers.size == 1 || numbers.size == dimensions) { "Easing dimension mismatch" }
                require(numbers.all { it in 0.0..1.0 }) { "Unknown temporal easing range" }
                return if (numbers.size == 1) List(dimensions) { numbers.single() } else numbers
            }
            return components("x") to components("y")
        }
        private fun vector(value: JsonElement, dimensions: Int): List<Double> {
            val numbers = if (value is JsonArray) value.map(::number) else listOf(number(value))
            require(numbers.size == dimensions) { "Property dimension mismatch" }
            return numbers
        }
        private fun mask(value: JsonObject): BrandMotionMask {
            require(++maskCount <= 64) { "Mask budget exceeded" }
            value.keysOnly("inv", "mode", "pt", "o", "x", "nm")
            require(!boolean(value.getValue("inv"))) { "Inverted masks are unsupported" }
            val mode = when (value.string("mode")) {
                "a" -> BrandMotionMaskMode.ADD
                "s" -> BrandMotionMaskMode.SUBTRACT
                else -> errorSchema("Unknown mask mode")
            }
            require(property(value.getValue("x").jsonObject, 1, false).constant == listOf(0.0)) { "Expanded masks are unsupported" }
            val opacity = scalar(value.getValue("o"))
            require(opacity in 0.0..100.0)
            return BrandMotionMask(value.string("nm"), mode, staticPath(value.getValue("pt").jsonObject), opacity / 100.0)
        }
        private fun staticPath(value: JsonObject): BrandMotionPath {
            value.keysOnly("a", "k")
            require(value.integer("a") == 0) { "Animated paths are unsupported" }
            val path = value.getValue("k").jsonObject
            path.keysOnly("v", "i", "o", "c")
            fun points(key: String): List<BrandMotionPoint> = path.array(key).map {
                val point = vector(it, 2)
                BrandMotionPoint(point[0], point[1])
            }
            val vertices = points("v")
            require(vertices.size in 1..512)
            vertexCount += vertices.size
            require(vertexCount <= 4096) { "Path vertex budget exceeded" }
            val incoming = points("i")
            val outgoing = points("o")
            require(incoming.size == vertices.size && outgoing.size == vertices.size) { "Path tangent mismatch" }
            return BrandMotionPath(vertices, incoming, outgoing, boolean(path.getValue("c")))
        }
        private fun scalar(value: JsonElement): Double = requireNotNull(property(value.jsonObject, 1, false).constant).single()
        private fun point(value: JsonElement): BrandMotionPoint {
            val v = requireNotNull(property(value.jsonObject, 2, false).constant)
            return BrandMotionPoint(v[0], v[1])
        }
        private fun color(value: JsonElement): List<Double> = requireNotNull(property(value.jsonObject, 4, false).constant).also {
            require(it.all { component -> component in 0.0..1.0 }) { "Color out of range" }
        }
        private fun shape(value: JsonObject, depth: Int): BrandMotionShape {
            require(++shapeCount <= 512 && depth <= 12) { "Shape budget exceeded" }
            return when (value.string("ty")) {
                "gr" -> {
                    value.keysOnly("ty", "nm", "it")
                    val items = value.array("it")
                    require(items.size in 2..64 && items.last().jsonObject.string("ty") == "tr") { "Group transform must be last" }
                    require(items.dropLast(1).none { it.jsonObject.string("ty") == "tr" }) { "Multiple group transforms" }
                    BrandMotionShape.Group(value.string("nm"), items.dropLast(1).map { shape(it.jsonObject, depth + 1) },
                        transform(items.last().jsonObject, 2, false))
                }
                "sh" -> { value.keysOnly("ty", "ks"); BrandMotionShape.Path(staticPath(value.getValue("ks").jsonObject)) }
                "el" -> {
                    value.keysOnly("ty", "p", "s", "d")
                    BrandMotionShape.Ellipse(point(value.getValue("p")), size(value.getValue("s")), direction(value))
                }
                "rc" -> {
                    value.keysOnly("ty", "p", "s", "r", "d")
                    val radius = scalar(value.getValue("r")); require(radius in 0.0..1024.0)
                    BrandMotionShape.Rectangle(point(value.getValue("p")), size(value.getValue("s")), radius, direction(value))
                }
                "fl" -> {
                    value.keysOnly("ty", "c", "o", "r")
                    val rule = value.integer("r"); require(rule in 1..2)
                    BrandMotionShape.Fill(color(value.getValue("c")), alpha(value.getValue("o")), rule)
                }
                "st" -> {
                    value.keysOnly("ty", "c", "o", "w", "lc", "lj")
                    val width = scalar(value.getValue("w")); require(width in 0.0..128.0)
                    val cap = value.integer("lc"); val join = value.integer("lj"); require(cap in 1..3 && join in 1..3)
                    BrandMotionShape.Stroke(color(value.getValue("c")), alpha(value.getValue("o")), width, cap, join)
                }
                else -> errorSchema("Unknown shape type")
            }
        }
        private fun size(value: JsonElement): BrandMotionPoint = point(value).also { require(it.x in 0.0..2048.0 && it.y in 0.0..2048.0) }
        private fun direction(value: JsonObject): Int = value.integer("d").also { require(it == 1 || it == 3) }
        private fun alpha(value: JsonElement): Double = scalar(value).also { require(it in 0.0..100.0) } / 100.0
        private fun errorSchema(message: String): Nothing = throw IllegalArgumentException(message)
        private fun JsonObject.keysOnly(vararg allowed: String) {
            require(keys.all { it in allowed }) { "Unknown schema field: ${keys.first { it !in allowed }}" }
        }
        private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let {
            require(it.isString && it.content.length <= 512); it.content
        }
        private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray
        private fun JsonObject.number(key: String): Double = number(getValue(key))
        private fun JsonObject.integer(key: String): Int = number(key).let { require(it == it.toInt().toDouble()); it.toInt() }
        private fun JsonObject.zero(key: String) { require(number(key) == 0.0) { "Unsupported $key option" } }
        private fun number(value: JsonElement): Double = value.jsonPrimitive.let {
            require(!it.isString)
            requireNotNull(it.doubleOrNull).also { result -> require(result.isFinite() && result in -1_000_000.0..1_000_000.0) }
        }
        private fun boolean(value: JsonElement): Boolean = value.jsonPrimitive.let {
            require(!it.isString); requireNotNull(it.booleanOrNull) { "Expected boolean" }
        }
    }
}

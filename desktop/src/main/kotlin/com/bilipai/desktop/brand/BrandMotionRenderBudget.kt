package com.bilipai.desktop.brand

/** CPU-only limits are checked before decoding, path builders, surfaces and saveLayers. */
internal class BrandMotionRenderBudget {
    companion object {
        const val MAX_SIDE = 1024
        const val MAX_PIXELS = 1_048_576L
        const val MAX_PATHS = 256
        const val MAX_PATH_BYTES = 1_048_576
        const val MAX_LAYER_DEPTH = 12
        const val MAX_FRAME_GROUPS = 128
        const val MAX_FRAME_PATH_POINTS = 16_384
        const val MAX_PAINTS = 2048
        fun dimensions(width: Int, height: Int): Long {
            require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "Brand raster dimensions exceed the CPU budget" }
            return (width.toLong() * height).also { require(it <= MAX_PIXELS) }
        }
        fun coordinate(value: Double): Float {
            require(value.isFinite() && value in -32768.0..32768.0) { "Brand coordinate exceeds drawing budget" }
            return value.toFloat()
        }
        fun alpha(value: Double): Float {
            require(value.isFinite() && value in 0.0..1.0) { "Invalid brand drawing alpha" }
            return value.toFloat()
        }
        fun matrix(value: BrandMotionMatrix) {
            listOf(value.a, value.b, value.c, value.d).forEach {
                require(it.isFinite() && it in -32.0..32.0) { "Brand matrix scale exceeds drawing budget" }
            }
            coordinate(value.tx); coordinate(value.ty)
        }
        fun scene(scene: BrandMotionScene) {
            require(scene.width == 512 && scene.height == 512 && scene.image.width == 512 && scene.image.height == 512)
            var layers = 0; var shapes = 0; var points = 0; var masks = 0; var frames = 0
            fun property(value: BrandMotionProperty) {
                val vectors = value.constant?.let { listOf(it) } ?: value.keyframes.flatMap { listOfNotNull(it.start, it.end) }
                require(vectors.isNotEmpty() && vectors.all { it.size == value.components })
                vectors.flatten().forEach { coordinate(it) }
                frames += value.keyframes.size; require(frames <= 2048)
            }
            fun transform(value: BrandMotionTransform) {
                listOf(value.position, value.anchor, value.scale, value.rotation, value.opacity).forEach(::property)
                val scales = value.scale.constant?.let { listOf(it) } ?: value.scale.keyframes.flatMap { listOfNotNull(it.start, it.end) }
                require(scales.all { it.take(2).all { component -> component in -1600.0..1600.0 } })
            }
            fun path(value: BrandMotionPath) {
                require(value.vertices.isNotEmpty() && value.vertices.size == value.incoming.size && value.vertices.size == value.outgoing.size)
                points += value.vertices.size; require(points <= 4096)
                (value.vertices + value.incoming + value.outgoing).forEach { coordinate(it.x); coordinate(it.y) }
            }
            fun shape(value: BrandMotionShape, depth: Int) {
                require(++shapes <= 512 && depth <= 12)
                when (value) {
                    is BrandMotionShape.Group -> { transform(value.transform); value.items.forEach { shape(it, depth + 1) } }
                    is BrandMotionShape.Path -> path(value.path)
                    is BrandMotionShape.Ellipse -> {
                        coordinate(value.position.x); coordinate(value.position.y)
                        require(value.size.x in 0.0..2048.0 && value.size.y in 0.0..2048.0 && value.direction in listOf(1, 3))
                    }
                    is BrandMotionShape.Rectangle -> {
                        coordinate(value.position.x); coordinate(value.position.y)
                        require(value.size.x in 0.0..2048.0 && value.size.y in 0.0..2048.0 && value.radius in 0.0..1024.0 && value.direction in listOf(1, 3))
                    }
                    is BrandMotionShape.Fill -> { require(value.color.size == 4 && value.color.all { it in 0.0..1.0 }); alpha(value.opacity); require(value.fillRule in 1..2) }
                    is BrandMotionShape.Stroke -> {
                        require(value.color.size == 4 && value.color.all { it in 0.0..1.0 }); alpha(value.opacity)
                        require(value.width in 0.0..128.0 && value.cap in 1..3 && value.join in 1..3)
                    }
                }
            }
            fun list(source: List<BrandMotionLayer>) {
                val indexed = source.associateBy { it.index }; require(indexed.size == source.size)
                for (layer in source) {
                    require(++layers <= 128); transform(layer.transform)
                    if (layer.type == BrandMotionLayerType.PRECOMPOSITION) {
                        require(requireNotNull(layer.width) in 1..512 && requireNotNull(layer.height) in 1..512) { "Brand precomposition bounds exceed scene budget" }
                    }
                    var current: BrandMotionLayer? = layer
                    val seen = mutableSetOf<Int>()
                    while (current != null) {
                        require(seen.add(current.index) && seen.size <= 16) { "Cyclic drawing parent" }
                        current = current.parent?.let { requireNotNull(indexed[it]) }
                    }
                    for (mask in layer.masks) {
                        require(++masks <= 64); path(mask.path); alpha(mask.opacity)
                        // Fixed Android Lottie non-inverted subtract is full DST_OUT.
                        // Audited resources are all 100%; do not invent another contract.
                        require(mask.mode != BrandMotionMaskMode.SUBTRACT || mask.opacity == 1.0)
                    }
                    layer.shapes.forEach { shape(it, 0) }
                }
            }
            list(scene.layers); scene.precompositions.values.forEach(::list)
            var expanded = 0
            fun references(source: List<BrandMotionLayer>, stack: Set<String>, depth: Int) {
                require(depth <= 12)
                for (layer in source) {
                    require(++expanded <= 512)
                    if (layer.type == BrandMotionLayerType.PRECOMPOSITION) {
                        val key = requireNotNull(layer.reference)
                        require(key !in stack) { "Cyclic drawing precomposition" }
                        references(requireNotNull(scene.precompositions[key]), stack + key, depth + 1)
                    }
                }
            }
            references(scene.layers, emptySet(), 0)
        }
    }
    var pathBytes = 0
        private set
    var pathCount = 0
        private set
    private var framePixels = 0L
    private var depth = 0
    private var groups = 0
    private var copiedPoints = 0
    private var paints = 0
    fun beginFrame(width: Int, height: Int) {
        framePixels = dimensions(width, height); depth = 0; groups = 0; copiedPoints = 0; paints = 0
    }
    fun beforePath(estimatedBytes: Int) {
        require(pathCount < MAX_PATHS) { "Brand native path count budget exceeded" }
        require(estimatedBytes >= 0 && pathBytes.toLong() + estimatedBytes <= MAX_PATH_BYTES) { "Brand native path allocation budget exceeded" }
    }
    fun retainPath(bytes: Int) {
        require(bytes >= 0 && pathBytes.toLong() + bytes <= MAX_PATH_BYTES) { "Brand native path byte budget exceeded" }
        pathBytes += bytes; pathCount++
    }
    fun releasePaths() { pathBytes = 0; pathCount = 0 }
    fun enterLayer() {
        require(depth + 1 <= MAX_LAYER_DEPTH && groups + 1 <= MAX_FRAME_GROUPS) { "Brand offscreen layer budget exceeded" }
        require(framePixels * (depth + 2L) <= 16L * MAX_PIXELS) { "Brand peak raster byte budget exceeded" }
        depth++; groups++
    }
    fun leaveLayer() { depth-- }
    fun copyPath(points: Int) { copiedPoints += points; require(copiedPoints <= MAX_FRAME_PATH_POINTS) { "Brand per-frame path work budget exceeded" } }
    fun paint() { require(++paints <= MAX_PAINTS) { "Brand per-frame paint budget exceeded" } }
}

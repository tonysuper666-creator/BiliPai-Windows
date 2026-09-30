package com.bilipai.desktop.plugins

import org.jetbrains.skia.*

/** Real animated WebP decoding with one reusable bitmap; no unbounded decoded-frame cache. */
class DesktopAnimatedSkinImage private constructor(private val data: Data, private val codec: Codec,
    private val bitmap: Bitmap, private val durationsMs: List<Int>) : AutoCloseable {
    val width: Int = codec.width
    val height: Int = codec.height
    val frameCount: Int = codec.frameCount
    val durationSeconds: Double = durationsMs.sumOf { it.toLong() } / 1000.0
    private val repetitions = codec.repetitionCount
    private var closed = false
    private var decodedFrame = -1
    private var image: Image? = null

    @Synchronized fun render(canvas: Canvas, destination: Rect, seconds: Double, loop: Boolean = true) {
        check(!closed) { "动画图片已释放" }
        require(seconds.isFinite() && seconds >= 0)
        val cycles = if (!loop) 1L else if (repetitions >= 0) repetitions.toLong() + 1 else null
        val time = if (cycles != null && seconds >= durationSeconds * cycles) durationSeconds
            else seconds % durationSeconds
        var accumulated = 0.0
        val index = durationsMs.indexOfFirst { accumulated += it / 1000.0; time < accumulated }.let { if (it < 0) frameCount - 1 else it }
        if (index != decodedFrame) {
            // A prior frame index of -1 asks Skia to compose required earlier frames itself.
            codec.readPixels(bitmap, index, -1)
            val next = Image.makeFromBitmap(bitmap)
            image?.close(); image = next; decodedFrame = index
        }
        canvas.drawImageRect(image ?: error("动画帧无法解码"), destination)
    }
    @Synchronized override fun close() {
        if (!closed) { closed = true; image?.close(); bitmap.close(); codec.close(); data.close() }
    }
    companion object {
        fun decodeOrNull(bytes: ByteArray): DesktopAnimatedSkinImage? {
            require(bytes.size <= 32 * 1024 * 1024) { "动画图片超过大小限制" }
            val data = Data.makeFromBytes(bytes, 0, bytes.size)
            var codec: Codec? = null
            var bitmap: Bitmap? = null
            try {
                val reader = Codec.makeFromData(data).also { codec = it }
                if (reader.frameCount <= 1) { reader.close(); data.close(); return null }
                require(reader.width.toLong() * reader.height in 1L..16_777_216L && reader.frameCount <= 512) { "动画图片像素或帧数超过限制" }
                val durations = reader.framesInfo.map { it.duration.coerceAtLeast(10) }
                require(durations.size == reader.frameCount && durations.sumOf { it.toLong() } <= 3_600_000) { "动画图片时间轴无效" }
                val pixels = Bitmap().also { bitmap = it }
                require(pixels.allocN32Pixels(reader.width, reader.height)) { "动画图片画布无法创建" }
                return DesktopAnimatedSkinImage(data, reader, pixels, durations)
            } catch (failure: Throwable) { bitmap?.close(); codec?.close(); data.close(); throw failure }
        }
    }
}

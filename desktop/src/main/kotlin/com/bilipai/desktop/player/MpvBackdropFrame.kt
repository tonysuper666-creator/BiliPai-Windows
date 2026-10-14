package com.bilipai.desktop.player

import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlin.math.min

/** Owned copies only. Native node memory and source credentials never leave the worker. */
internal class MpvBackdropFrame(
    val sourceVersion: Long,
    val playbackRevision: Long,
    val seekSerial: Long,
    val capturedAtNanos: Long,
    val nativeWidth: Int,
    val nativeHeight: Int,
    val width: Int,
    val height: Int,
    internal val opaqueBgra: ByteArray,
)

internal data class MpvBackdropRasterLayout(
    val width: Int, val height: Int, val stride: Int,
    val outputWidth: Int, val outputHeight: Int,
)

/** Validate before allocating or reading any pixel. The native byte array is at most 128 MiB. */
internal fun mpvBackdropRasterLayout(
    width: Long, height: Long, stride: Long, byteCount: Long,
    maximumWidth: Int = 480, maximumHeight: Int = 270,
): MpvBackdropRasterLayout? {
    if (width !in 1L..8192L || height !in 1L..8192L ||
        maximumWidth !in 1..960 || maximumHeight !in 1..540 ||
        stride == Long.MIN_VALUE || kotlin.math.abs(stride) !in width * 4L..128L * 1024L ||
        byteCount !in 1L..128L * 1024L * 1024L) return null
    val required = kotlin.math.abs(stride) * (height - 1L) + width * 4L
    if (required > byteCount) return null
    val scale = min(1.0, min(maximumWidth.toDouble() / width, maximumHeight.toDouble() / height))
    return MpvBackdropRasterLayout(width.toInt(), height.toInt(), stride.toInt(),
        (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
}

/** Read just the sampled rows; never copy the complete 4K/5K native raster to the JVM. */
internal fun copyMpvBackdropRaster(
    layout: MpvBackdropRasterLayout,
    readRow: (byteOffset: Long, byteCount: Int) -> ByteArray,
): ByteArray {
    val output = ByteArray(layout.outputWidth * layout.outputHeight * 4)
    for (y in 0 until layout.outputHeight) {
        val sourceY = ((y.toLong() * layout.height) / layout.outputHeight).toInt()
        val row = readRow(sourceY.toLong() * layout.stride, layout.width * 4)
        require(row.size == layout.width * 4) { "Native backdrop row was truncated" }
        for (x in 0 until layout.outputWidth) {
            val sourceX = ((x.toLong() * layout.width) / layout.outputWidth).toInt()
            val input = sourceX * 4
            val destination = (y * layout.outputWidth + x) * 4
            output[destination] = row[input]
            output[destination + 1] = row[input + 1]
            output[destination + 2] = row[input + 2]
            output[destination + 3] = 0xff.toByte()
        }
    }
    return output
}

/** mpv_node map/byte-array ABI for the application's required Windows x64 runtime. */
internal fun copyMpvBackdropNode(
    root: Pointer, sourceVersion: Long, playbackRevision: Long, seekSerial: Long,
    maximumWidth: Int, maximumHeight: Int, stillCurrent: () -> Boolean,
): MpvBackdropFrame? {
    if (Native.POINTER_SIZE != 8 || root.getInt(8) != 8 || !stillCurrent()) return null
    val map = root.getPointer(0) ?: return null
    val count = map.getInt(0)
    if (count !in 1..16) return null
    val values = map.getPointer(8) ?: return null
    val keys = map.getPointer(16) ?: return null
    val fields = mutableMapOf<String, Pointer>()
    repeat(count) { index ->
        val key = mpvBackdropNativeString(keys.getPointer(index * 8L), 32) ?: return null
        if (key in setOf("w", "h", "stride", "format", "data")) {
            if (fields.put(key, values.share(index * 16L)) != null) return null
        }
    }
    fun integer(name: String): Long? = fields[name]?.takeIf { it.getInt(8) == 4 }?.getLong(0)
    val width = integer("w") ?: return null
    val height = integer("h") ?: return null
    val stride = integer("stride") ?: return null
    val formatNode = fields["format"]?.takeIf { it.getInt(8) == 1 } ?: return null
    val format = mpvBackdropNativeString(formatNode.getPointer(0), 16)
    if (format != "bgra" && format != "bgr0") return null
    val byteNode = fields["data"]?.takeIf { it.getInt(8) == 9 } ?: return null
    val array = byteNode.getPointer(0) ?: return null
    val data = array.getPointer(0) ?: return null
    val size = array.getLong(8)
    val layout = mpvBackdropRasterLayout(width, height, stride, size, maximumWidth, maximumHeight)
        ?: return null
    if (!stillCurrent()) return null
    val copied = copyMpvBackdropRaster(layout) { offset, length ->
        if (!stillCurrent()) throw kotlinx.coroutines.CancellationException("Native backdrop source retired")
        data.getByteArray(offset, length)
    }
    if (!stillCurrent()) return null
    return MpvBackdropFrame(sourceVersion, playbackRevision, seekSerial, System.nanoTime(),
        layout.width, layout.height, layout.outputWidth, layout.outputHeight, copied)
}

private fun mpvBackdropNativeString(pointer: Pointer?, maximum: Int): String? {
    pointer ?: return null
    var count = 0
    while (count <= maximum && pointer.getByte(count.toLong()) != 0.toByte()) count++
    return if (count > maximum) null else String(pointer.getByteArray(0, count), Charsets.UTF_8)
}

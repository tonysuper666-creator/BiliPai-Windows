package com.bilipai.desktop.player

import com.sun.jna.Memory
import com.sun.jna.Pointer

/** Read from the running player's own core. A preference for hwdec is not a
 * decoder capability, and absence of an initialized core is not unsupported. */
internal sealed interface MpvDecoderCapabilities {
    data class Available(val decoders: List<MpvDecoderInfo>) : MpvDecoderCapabilities {
        fun supports(codec: String): Boolean = decoders.any { it.codec == codec }
    }
    data class Unavailable(val nativeCode: Int?, val reason: String) : MpvDecoderCapabilities
}

internal data class MpvDecoderInfo(val codec: String, val driver: String, val description: String)

/** Stable x64 client.h node layout, matching the existing MpvNodes writer.
 * Called only on the existing Session actor; its core and allocations never
 * escape this function. No media URL, header, account or second core is read. */
internal fun readMpvDecoderCapabilities(native: MpvNative, handle: Pointer): MpvDecoderCapabilities {
    check(com.sun.jna.Native.POINTER_SIZE == 8)
    Memory(16).use { node ->
        node.clear()
        val status = native.mpv_get_property(handle, "decoder-list", 6, node) // MPV_FORMAT_NODE
        if (status < 0) return MpvDecoderCapabilities.Unavailable(status, "decoder-list unavailable")
        try {
            check(node.getInt(8) == 7) { "decoder-list is not a node array" }
            val array = checkNotNull(node.getPointer(0)) { "decoder-list has no array" }
            val count = array.getInt(0)
            check(count in 0..4096) { "decoder-list count is outside the native contract" }
            val values = array.getPointer(8)
            check(count == 0 || values != null) { "decoder-list has no values" }
            val result = List(count) { index ->
                val item = checkNotNull(values).share(index * 16L)
                check(item.getInt(8) == 8) { "decoder-list item is not a node map" }
                val map = checkNotNull(item.getPointer(0))
                val fields = map.getInt(0)
                check(fields in 0..32) { "decoder-list field count is outside the native contract" }
                val fieldValues = map.getPointer(8)
                val fieldKeys = map.getPointer(16)
                check(fields == 0 || (fieldValues != null && fieldKeys != null))
                val strings = mutableMapOf<String, String>()
                repeat(fields) { field ->
                    val key = checkNotNull(checkNotNull(fieldKeys).getPointer(field * 8L)).getString(0, "UTF-8")
                    if (key == "codec" || key == "driver" || key == "description") {
                        val value = checkNotNull(fieldValues).share(field * 16L)
                        check(value.getInt(8) == 1) { "decoder-list field is not a string" }
                        strings[key] = checkNotNull(value.getPointer(0)).getString(0, "UTF-8")
                    }
                }
                MpvDecoderInfo(checkNotNull(strings["codec"]), checkNotNull(strings["driver"]),
                    checkNotNull(strings["description"]))
            }
            return MpvDecoderCapabilities.Available(result)
        } catch (_: IllegalStateException) {
            return MpvDecoderCapabilities.Unavailable(null, "decoder-list node contract unavailable")
        } finally {
            native.mpv_free_node_contents(node)
        }
    }
}

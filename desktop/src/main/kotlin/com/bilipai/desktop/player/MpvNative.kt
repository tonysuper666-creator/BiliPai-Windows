package com.bilipai.desktop.player

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8

/** The stable libmpv C client API, using cdecl and UTF-8 strings. */
internal interface MpvNative : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(handle: Pointer): Int
    fun mpv_terminate_destroy(handle: Pointer)
    fun mpv_set_option_string(handle: Pointer, name: String, value: String): Int
    fun mpv_set_property_string(handle: Pointer, name: String, value: String): Int
    fun mpv_get_property_string(handle: Pointer, name: String): Pointer?
    fun mpv_set_property(handle: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_get_property(handle: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_free_node_contents(node: Pointer)
    fun mpv_command(handle: Pointer, args: StringArray): Int
    fun mpv_command_node(handle: Pointer, args: Pointer, result: Pointer?): Int
    fun mpv_request_log_messages(handle: Pointer, level: String): Int
    fun mpv_wait_event(handle: Pointer, timeout: Double): Pointer
    fun mpv_error_string(error: Int): String
    fun mpv_free(data: Pointer)

    companion object {
        fun load(): MpvNative {
            check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                "This player package requires Windows."
            }
            check(Native.POINTER_SIZE == 8) { "This player package requires 64-bit Windows." }
            val dll = locateLibrary()
            return Native.load(
                dll.absolutePath,
                MpvNative::class.java,
                mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"),
            )
        }

        internal fun locateLibrary(): File {
            val override = System.getProperty("bilipai.mpv.path")
                ?.takeIf { it.isNotBlank() }
                ?: System.getenv("BILIPAI_MPV_PATH")?.takeIf { it.isNotBlank() }
            if (override != null) {
                val location = File(override)
                val dll = if (location.isDirectory) File(location, "libmpv-2.dll") else location
                check(dll.isFile) { "Native player library not found: ${dll.absolutePath}" }
                return dll
            }
            val resourceDir = System.getProperty("compose.application.resources.dir")
            val candidates = buildList {
                if (!resourceDir.isNullOrBlank()) {
                    add(File(resourceDir, "native/windows-x64/libmpv-2.dll"))
                    add(File(resourceDir, "windows/native/windows-x64/libmpv-2.dll"))
                }
                add(File("native/windows-x64/libmpv-2.dll"))
                add(File("desktop/native/windows-x64/libmpv-2.dll"))
                add(File("resources/common/native/windows-x64/libmpv-2.dll"))
                add(File("desktop/resources/common/native/windows-x64/libmpv-2.dll"))
            }
            return candidates.firstOrNull { it.isFile }
                ?: error("Native player is missing. Run desktop/tools/fetch-mpv.ps1, then restart BiliPai.")
        }
    }
}

/** Owns all native buffers for a command, including nested maps and UTF-8 strings. */
internal class MpvNodes : AutoCloseable {
    private val buffers = mutableListOf<Memory>()

    private fun buffer(size: Long): Memory = Memory(size.coerceAtLeast(1)).also {
        it.clear()
        buffers += it
    }

    private fun string(value: String): Memory {
        val bytes = value.toByteArray(UTF_8)
        return buffer(bytes.size + 1L).also { it.write(0, bytes, 0, bytes.size) }
    }

    private fun writeNode(destination: Pointer, offset: Long, value: Any) {
        when (value) {
            is String -> {
                destination.setPointer(offset, string(value))
                destination.setInt(offset + 8, 1) // MPV_FORMAT_STRING
            }
            is Map<*, *> -> {
                val values = buffer(value.size * 16L)
                val keys = buffer(value.size * 8L)
                value.entries.forEachIndexed { index, entry ->
                    keys.setPointer(index * 8L, string(entry.key as String))
                    writeNode(values, index * 16L, requireNotNull(entry.value))
                }
                destination.setPointer(offset, list(value.size, values, keys))
                destination.setInt(offset + 8, 8) // MPV_FORMAT_NODE_MAP
            }
            is List<*> -> {
                val values = buffer(value.size * 16L)
                value.forEachIndexed { index, child -> writeNode(values, index * 16L, requireNotNull(child)) }
                destination.setPointer(offset, list(value.size, values, null))
                destination.setInt(offset + 8, 7) // MPV_FORMAT_NODE_ARRAY
            }
            else -> error("Unsupported mpv node type")
        }
    }

    private fun list(size: Int, values: Pointer, keys: Pointer?): Memory = buffer(24).also {
        it.setInt(0, size)
        it.setPointer(8, values)
        it.setPointer(16, keys)
    }

    fun array(values: List<Any>): Pointer = buffer(16).also { writeNode(it, 0, values) }

    override fun close() {
        buffers.asReversed().forEach { it.close() }
        buffers.clear()
    }
}

/** mpv string/path list escaping differs from shell quoting. */
internal fun escapeMpvListItem(value: String, separator: Char): String = buildString {
    value.forEach { character ->
        // mpv only removes a backslash immediately before the separator;
        // ordinary Windows path backslashes must stay unchanged.
        if (character == separator) append('\\')
        append(character)
    }
}

internal fun PlaybackSource.mpvFileOptions(): Map<String, String> = buildMap {
    audioUrl?.takeIf { it.isNotBlank() }?.let { put("audio-files", escapeMpvListItem(it, ';')) }
    put("referrer", referer)
    put("user-agent", userAgent)
    if (cookieHeader.isNotBlank()) put("http-header-fields", escapeMpvListItem("Cookie: $cookieHeader", ','))
    put("force-media-title", title)
    put("start", startPositionSeconds.toString())
    put("pause", if (startPaused) "yes" else "no")
}

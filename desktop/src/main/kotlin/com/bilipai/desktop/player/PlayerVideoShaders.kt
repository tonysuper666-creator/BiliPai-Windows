package com.bilipai.desktop.player

import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.nio.file.Files
import java.nio.file.Path

/** Native option acknowledgement and executed passes are separate observations. */
data class PlayerVideoShaderState(
    val configurationVersion: Long = 0,
    val requestedFiles: List<String> = emptyList(),
    val appliedFiles: List<String> = emptyList(),
    val executedPasses: List<String> = emptyList(),
    val active: Boolean = false,
    val error: String? = null,
)

internal data class PreparedVideoShaders(val paths: List<String>, val descriptions: Set<String>) {
    fun executed(passes: List<String>): Boolean = passes.any { pass -> descriptions.any { description -> pass.contains(description) } }
}

/** Resource selection and hash checking belong to the upstream plugin asset adapter. */
internal fun prepareVideoShaders(files: List<Path>): PreparedVideoShaders {
    require(files.size <= 32) { "Too many video shader files." }
    val descriptions = linkedSetOf<String>()
    val paths = files.map { file ->
        val normalized = file.toAbsolutePath().normalize()
        require(Files.isRegularFile(normalized) && Files.isReadable(normalized)) { "Video shader file does not exist." }
        require(Files.size(normalized) in 1..2_097_152L) { "Video shader file is empty or too large." }
        require('\u0000' !in normalized.toString() && '\r' !in normalized.toString() && '\n' !in normalized.toString()) {
            "Invalid video shader path."
        }
        val text = Files.readString(normalized)
        require(text.lineSequence().any { it.trimStart().startsWith("//!HOOK ") }) { "Video shader has no mpv hook." }
        text.lineSequence().filter { it.trimStart().startsWith("//!DESC ") }.forEach { line ->
            val description = line.trimStart().removePrefix("//!DESC ").trim().take(512)
            if (description.isNotEmpty() && descriptions.size < 512) descriptions += description
        }
        nativeVideoShaderPath(normalized.toString())
    }
    require(paths.isEmpty() || descriptions.isNotEmpty()) { "Video shader has no render pass description." }
    return PreparedVideoShaders(java.util.Collections.unmodifiableList(paths.toList()), java.util.Collections.unmodifiableSet(descriptions.toSet()))
}

/** mpv's pinned Win32 file reader uses CreateFileW without adding the extended path prefix. */
internal fun nativeVideoShaderPath(path: String, isWindows: Boolean = com.sun.jna.Platform.isWindows()): String {
    if (!isWindows || path.startsWith("\\\\?\\")) return path
    if (path.startsWith("\\\\")) return "\\\\?\\UNC\\" + path.removePrefix("\\\\")
    require(path.length >= 3 && path[0].isLetter() && path[1] == ':' && path[2] == '\\') {
        "Video shader path must be an absolute Windows file path."
    }
    return "\\\\?\\" + path
}

/** mpv_node is a 16-byte value/format pair on the already-required Windows x64 client ABI. */
internal object MpvVideoShaderProperties {
    private const val NODE = 6
    private const val STRING = 1
    private const val ARRAY = 7
    private const val MAP = 8

    fun set(native: MpvNative, handle: Pointer, files: List<String>): Int = MpvNodes().use { nodes ->
        native.mpv_set_property(handle, "glsl-shaders", NODE, nodes.array(files))
    }

    fun files(native: MpvNative, handle: Pointer): List<String>? = read(native, handle, "glsl-shaders") { root ->
        array(root, 32)?.mapNotNull(::string)
    }

    fun passes(native: MpvNative, handle: Pointer): List<String>? = read(native, handle, "vo-passes") { root ->
        val frameTypes = map(root, 8) ?: return@read null
        frameTypes.values.flatMap { frame ->
            array(frame, 512).orEmpty().mapNotNull { pass -> map(pass, 16)?.get("desc")?.let(::string) }
        }.distinct().take(512)
    }

    private fun <T> read(native: MpvNative, handle: Pointer, name: String, decode: (Pointer) -> T?): T? = Memory(16).use { root ->
        root.clear()
        if (native.mpv_get_property(handle, name, NODE, root) < 0) return@use null
        try { decode(root) } finally { native.mpv_free_node_contents(root) }
    }

    private fun array(node: Pointer, maximum: Int): List<Pointer>? {
        if (node.getInt(8) != ARRAY) return null
        val list = node.getPointer(0) ?: return null
        val count = list.getInt(0)
        if (count !in 0..maximum) return null
        val values = list.getPointer(8) ?: return if (count == 0) emptyList() else null
        return (0 until count).map { values.share(it * 16L) }
    }

    private fun map(node: Pointer, maximum: Int): Map<String, Pointer>? {
        if (node.getInt(8) != MAP) return null
        val list = node.getPointer(0) ?: return null
        val count = list.getInt(0)
        if (count !in 0..maximum) return null
        val values = list.getPointer(8) ?: return if (count == 0) emptyMap() else null
        val keys = list.getPointer(16) ?: return null
        return (0 until count).associate { index -> boundedString(keys.getPointer(index * 8L)) to values.share(index * 16L) }
    }

    private fun string(node: Pointer): String? = if (node.getInt(8) == STRING) boundedString(node.getPointer(0)) else null
    private fun boundedString(pointer: Pointer?): String {
        if (pointer == null) return ""
        var length = 0
        while (length < 8_192 && pointer.getByte(length.toLong()) != 0.toByte()) length++
        return String(pointer.getByteArray(0, length), Charsets.UTF_8)
    }
}

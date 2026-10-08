package com.bilipai.desktop.player

import com.sun.nio.file.ExtendedOpenOption
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.util.Locale

@Volatile private var restoredVeyraSelection: List<String>? = null

/** Passive origin metadata for the loader fallback, not engine/source authority. */
internal fun desktopHasRestoredVeyraSelection(): Boolean {
    val selected = restoredVeyraSelection ?: return false
    return listOf("bilipai.veyra.profile", "bilipai.veyra.profile.sha256", "bilipai.mpv.path")
        .map(System::getProperty) == selected && System.getenv("BILIPAI_MPV_PATH").isNullOrBlank()
}

/** An explicitly authored local selection survives the ordinary EXE/restart path.
 * This is private local configuration, not a release-monitor or remote trust source.
 * The actual component verifier still runs on the sole native worker before load.
 */
internal fun desktopRestoreVeyraLocalSelection() {
    val explicit = listOf("bilipai.veyra.profile", "bilipai.veyra.profile.sha256", "bilipai.mpv.path", "bilipai.veyra.verifier")
    if (explicit.any { !System.getProperty(it).isNullOrBlank() } ||
        !System.getenv("BILIPAI_MPV_PATH").isNullOrBlank()) return
    runCatching {
        val local = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank) ?: return
        val root = Path.of(local, "BiliPaiWindows", "private-components", "veyra").toAbsolutePath().normalize()
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return
        fun noReparse(path: Path) {
            var cursor: Path? = path
            while (cursor != null) {
                val current = cursor
                require(Files.exists(current, NOFOLLOW_LINKS))
                // Windows junctions are reported as other; neither symlinks nor reparse
                // directories may redirect this fixed local selection hierarchy.
                val attrs = Files.readAttributes(current, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(!attrs.isSymbolicLink && !attrs.isOther)
                require(current.toRealPath() == current.toRealPath(NOFOLLOW_LINKS))
                cursor = current.parent
            }
        }
        noReparse(root)
        val selection = root.resolve("current.json")
        if (!Files.isRegularFile(selection, NOFOLLOW_LINKS)) return
        noReparse(selection)
        val bytes = FileChannel.open(selection, READ, ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE).use { channel ->
            val buffer = ByteBuffer.allocate(8193)
            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
            require(buffer.position() in 1..8192)
            buffer.array().copyOf(buffer.position())
        }
        val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")) as JsonObject
        fun text(key: String) = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(json["schema"]?.jsonPrimitive?.intOrNull == 1)
        require(text("selectionKind") == "EXPLICIT_PRIVATE_COMPONENT")
        require(text("variant") == "bilipai-veyra-core-v1")
        val component = requireNotNull(text("componentRelativePath"))
        require(component.matches(Regex("components/[0-9a-f]{32}")))
        val hash = requireNotNull(text("trustedProfileSha256"))
        require(hash.matches(Regex("[0-9a-fA-F]{64}")))
        val packageRoot = root.resolve(component).normalize()
        require(packageRoot.startsWith(root))
        val profile = packageRoot.resolve("profile.json")
        val mpv = packageRoot.resolve("mpv/libmpv-2.dll")
        noReparse(profile); noReparse(mpv)
        require(Files.isRegularFile(profile, NOFOLLOW_LINKS) && Files.isRegularFile(mpv, NOFOLLOW_LINKS))
        // These are paths and an external profile anchor, never authenticated engine
        // state. No DLL, script, network or SDK is executed by this restore step.
        val selected = listOf(profile.toString(), hash.lowercase(Locale.ROOT), mpv.toString())
        val properties = System.getProperties()
        synchronized(properties) {
            if (explicit.any { !properties.getProperty(it).isNullOrBlank() } ||
                !System.getenv("BILIPAI_MPV_PATH").isNullOrBlank()) return
            properties.setProperty("bilipai.veyra.profile", selected[0])
            properties.setProperty("bilipai.veyra.profile.sha256", selected[1])
            properties.setProperty("bilipai.mpv.path", selected[2])
            restoredVeyraSelection = selected
        }
    }
    // Missing, corrupt or unsupported local configuration keeps the old default.
}

package com.bilipai.desktop.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Cleanup is restricted to marked, immediate children of the updater's verified root. */
internal object UpdateStorage {
    private const val OWNER_FILE = ".bilipai-updater.json"
    private const val OWNER = "BiliPai.Windows.Updater.v1"
    private val json = Json { ignoreUnknownKeys = true }
    private val stageName = Regex("^staged-[0-9]+-[A-Za-z0-9-]+$")

    fun createStage(root: Path, repository: String, assetId: Long): Path {
        require(assetId > 0) { "更新资产标识无效" }
        val verified = verifiedRoot(root)
        val stage = Files.createTempDirectory(verified, "staged-$assetId-")
        Files.writeString(stage.resolve(OWNER_FILE), json.encodeToString(OwnerMarker.serializer(), OwnerMarker(repository, OWNER)))
        return stage
    }

    fun verifiedRoot(root: Path): Path {
        val absolute = root.toAbsolutePath().normalize()
        var existing: Path = absolute
        while (!Files.exists(existing, NOFOLLOW_LINKS)) existing = requireNotNull(existing.parent)
        // Reject a redirected ancestor before creating a missing root beneath it.
        existingPathWithoutLinks(existing)
        Files.createDirectories(absolute)
        val canonical = existingPathWithoutLinks(absolute)
        require(Files.isDirectory(canonical, NOFOLLOW_LINKS)) { "更新根路径必须是目录" }
        return canonical
    }

    /** Expand Windows 8.3 names without resolving links, then independently reject every link/reparse component. */
    fun existingPathWithoutLinks(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        var component: Path? = absolute
        while (component != null) {
            val attributes = Files.readAttributes(component, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            // On Windows a directory junction is isOther, although isSymbolicLink is false.
            require(!attributes.isSymbolicLink && !attributes.isOther) { "更新路径不能包含链接、junction 或其他特殊文件" }
            component = component.parent
        }
        val canonical = absolute.toRealPath(NOFOLLOW_LINKS)
        require(canonical.toRealPath() == canonical) { "更新路径不能通过链接重定向" }
        return canonical
    }

    fun ownedStage(root: Path, stage: Path, repository: String): Path? = runCatching {
        val absoluteRoot = existingPathWithoutLinks(root)
        if (!Files.isDirectory(absoluteRoot, NOFOLLOW_LINKS)) return@runCatching null
        val absoluteStage = existingPathWithoutLinks(stage)
        if (absoluteStage.parent != absoluteRoot || !stageName.matches(absoluteStage.fileName.toString())) return@runCatching null
        if (!Files.isDirectory(absoluteStage, NOFOLLOW_LINKS)) return@runCatching null
        val marker = absoluteStage.resolve(OWNER_FILE)
        if (!Files.isRegularFile(marker, NOFOLLOW_LINKS) || Files.isSymbolicLink(marker) || Files.size(marker) > 4096) return@runCatching null
        val owner = json.decodeFromString<OwnerMarker>(Files.readString(marker))
        absoluteStage.takeIf { owner.owner == OWNER && owner.repository == repository }
    }.getOrNull()

    fun stageContaining(root: Path, path: Path, repository: String): Path? = runCatching {
        val absoluteRoot = existingPathWithoutLinks(root)
        val absolute = existingPathWithoutLinks(path)
        if (!absolute.startsWith(absoluteRoot) || absolute == absoluteRoot) return@runCatching null
        ownedStage(absoluteRoot, absoluteRoot.resolve(absoluteRoot.relativize(absolute).getName(0)), repository)
    }.getOrNull()

    fun executableStage(root: Path, executable: Path, expectedName: String): Path? = runCatching {
        val absoluteRoot = existingPathWithoutLinks(root)
        val absolute = existingPathWithoutLinks(executable)
        if (!absolute.startsWith(absoluteRoot) || absolute == absoluteRoot || !Files.isRegularFile(absolute, NOFOLLOW_LINKS)) return@runCatching null
        val relative = absoluteRoot.relativize(absolute)
        if (relative.nameCount < 3 || !stageName.matches(relative.getName(0).toString()) || relative.getName(1).toString() != "app") return@runCatching null
        if (!absolute.fileName.toString().equals(expectedName, ignoreCase = true)) return@runCatching null
        absoluteRoot.resolve(relative.getName(0))
    }.getOrNull()

    fun deleteOwnedStage(root: Path, stage: Path, repository: String): Boolean = runCatching {
        val verified = ownedStage(root, stage, repository) ?: return@runCatching false
        // Validate the complete deletion set before removing anything. Walk never follows links.
        val paths = Files.walk(verified).use { stream -> stream.map(::existingPathWithoutLinks).toList() }
        if (paths.any { !it.startsWith(verified) }) return@runCatching false
        paths.sortedByDescending { it.nameCount }.forEach { Files.delete(it) }
        true
    }.getOrDefault(false)

    fun prune(root: Path, repository: String, preservedStages: Set<Path>) {
        val absolute = verifiedRoot(root)
        val preserved = preservedStages.map(::existingPathWithoutLinks).toSet()
        Files.list(absolute).use { children ->
            children.forEach { child -> if (child !in preserved) deleteOwnedStage(absolute, child, repository) }
        }
    }

    @Serializable
    private data class OwnerMarker(val repository: String, val owner: String)
}

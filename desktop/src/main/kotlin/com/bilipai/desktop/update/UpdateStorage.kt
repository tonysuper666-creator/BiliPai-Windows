package com.bilipai.desktop.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

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
        Files.createDirectories(absolute)
        require(Files.isDirectory(absolute, NOFOLLOW_LINKS) && absolute.toRealPath() == absolute) { "更新目录不能位于链接路径中" }
        return absolute
    }

    fun ownedStage(root: Path, stage: Path, repository: String): Path? = runCatching {
        val absoluteRoot = root.toAbsolutePath().normalize()
        if (!Files.isDirectory(absoluteRoot, NOFOLLOW_LINKS) || absoluteRoot.toRealPath() != absoluteRoot) return@runCatching null
        val absoluteStage = stage.toAbsolutePath().normalize()
        if (absoluteStage.parent != absoluteRoot || !stageName.matches(absoluteStage.fileName.toString())) return@runCatching null
        if (!Files.isDirectory(absoluteStage, NOFOLLOW_LINKS) || absoluteStage.toRealPath() != absoluteStage) return@runCatching null
        val marker = absoluteStage.resolve(OWNER_FILE)
        if (!Files.isRegularFile(marker, NOFOLLOW_LINKS) || Files.isSymbolicLink(marker) || Files.size(marker) > 4096) return@runCatching null
        val owner = json.decodeFromString<OwnerMarker>(Files.readString(marker))
        absoluteStage.takeIf { owner.owner == OWNER && owner.repository == repository }
    }.getOrNull()

    fun stageContaining(root: Path, path: Path, repository: String): Path? {
        val absoluteRoot = root.toAbsolutePath().normalize()
        val absolute = path.toAbsolutePath().normalize()
        if (!absolute.startsWith(absoluteRoot) || absolute == absoluteRoot) return null
        return ownedStage(absoluteRoot, absoluteRoot.resolve(absoluteRoot.relativize(absolute).getName(0)), repository)
    }

    fun deleteOwnedStage(root: Path, stage: Path, repository: String): Boolean = runCatching {
        val verified = ownedStage(root, stage, repository) ?: return@runCatching false
        // Validate the complete deletion set before removing anything. Walk never follows links.
        val paths = Files.walk(verified).use { it.toList() }
        if (paths.any { Files.isSymbolicLink(it) || !it.toRealPath().startsWith(verified) }) return@runCatching false
        paths.sortedByDescending { it.nameCount }.forEach { Files.delete(it) }
        true
    }.getOrDefault(false)

    fun prune(root: Path, repository: String, preservedStages: Set<Path>) {
        val absolute = verifiedRoot(root)
        val preserved = preservedStages.map { it.toAbsolutePath().normalize() }.toSet()
        Files.list(absolute).use { children ->
            children.forEach { child -> if (child !in preserved) deleteOwnedStage(absolute, child, repository) }
        }
    }

    @Serializable
    private data class OwnerMarker(val repository: String, val owner: String)
}

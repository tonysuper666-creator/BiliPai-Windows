package com.bilipai.desktop.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean

/** Cleanup is restricted to marked, immediate children of the updater's verified root. */
internal object UpdateStorage {
    private const val OWNER_FILE = ".bilipai-updater.json"
    private const val LEASE_FILE = ".bilipai-updater.lock"
    private const val ACTIVE_REGISTRY_LOCK_FILE = ".bilipai-active-install.lock"
    private const val OWNER = "BiliPai.Windows.Updater.v2"
    private const val LEGACY_OWNER = "BiliPai.Windows.Updater.v1"
    private val json = Json { ignoreUnknownKeys = true }
    private val stageName = Regex("^staged-[0-9]+-[A-Za-z0-9-]+$")

    /** The OS releases this lock on process death; neither timestamps nor PIDs grant ownership. */
    class StageLease internal constructor(val stage: Path, private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
        private val closed = AtomicBoolean()
        fun holds(stage: Path): Boolean = !closed.get() && channel.isOpen && lock.isValid && this.stage == stage
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try { runCatching { lock.release() } } finally { runCatching { channel.close() } }
        }
    }

    /** A short cross-process registry transaction; payload stages retain their independent leases.
     * Keep this zero-byte lock file after release so another process cannot lock a replacement inode. */
    class ActiveRegistryLease internal constructor(
        val root: Path, private val channel: FileChannel, private val lock: FileLock,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()
        fun holds(root: Path): Boolean = !closed.get() && channel.isOpen && lock.isValid && this.root == root
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try { runCatching { lock.release() } } finally { runCatching { channel.close() } }
        }
    }

    /** Busy or unknown registry custody never authorizes a write. No process wait holds this lease. */
    fun tryLeaseActiveRegistry(root: Path): ActiveRegistryLease? {
        var channel: FileChannel? = null
        var lock: FileLock? = null
        try {
            val verified = verifiedRoot(root)
            val file = verified.resolve(ACTIVE_REGISTRY_LOCK_FILE)
            if (Files.exists(file, NOFOLLOW_LINKS)) {
                require(existingPathWithoutLinks(file) == file && Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) == 0L)
            }
            val opened = FileChannel.open(file, CREATE, WRITE, NOFOLLOW_LINKS)
            channel = opened
            val acquired = opened.tryLock() ?: return null
            lock = acquired
            require(existingPathWithoutLinks(file) == file && Files.isRegularFile(file, NOFOLLOW_LINKS) &&
                Files.size(file) == 0L && opened.size() == 0L)
            return ActiveRegistryLease(verified, opened, acquired).also { channel = null; lock = null }
        } catch (_: Exception) {
            return null
        } finally {
            try { lock?.let { runCatching { it.release() } } } finally { channel?.let { runCatching { it.close() } } }
        }
    }

    /** Only actual v2 ownership and a live OS lease permit prepared-state IO. */
    internal fun requireLeasedStage(root: Path, lease: StageLease, repository: String): Path {
        val stage = requireNotNull(ownedStage(root, lease.stage, repository)) { "更新安装目录所有者无法确认" }
        require(lease.holds(stage) && readOwner(stage)?.owner == OWNER) { "更新安装目录 v2 租约已失效" }
        return stage
    }

    fun createStage(root: Path, repository: String, assetId: Long): Path =
        createLeasedStage(root, repository, assetId).use { it.stage }

    fun createLeasedStage(root: Path, repository: String, assetId: Long): StageLease {
        require(assetId > 0) { "更新资产标识无效" }
        val verified = verifiedRoot(root)
        val stage = Files.createTempDirectory(verified, "staged-$assetId-")
        var lease: StageLease? = null
        try {
            // Until the lease exists, no ownership marker is published and cleanup ignores this directory.
            val acquired = requireNotNull(openLease(stage, create = true)) { "更新安装目录无法取得独占租约" }
            lease = acquired
            Files.writeString(stage.resolve(OWNER_FILE), json.encodeToString(OwnerMarker.serializer(), OwnerMarker(repository, OWNER)), CREATE_NEW, WRITE)
            return acquired
        } catch (error: Throwable) {
            lease?.close()
            // Only this new, unpublished directory can be removed without an ownership marker.
            runCatching { Files.deleteIfExists(stage.resolve(OWNER_FILE)); Files.deleteIfExists(stage.resolve(LEASE_FILE)); Files.delete(stage) }
            throw error
        }
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
        val owner = readOwner(absoluteStage) ?: return@runCatching null
        absoluteStage.takeIf { owner.owner in setOf(OWNER, LEGACY_OWNER) && owner.repository == repository }
    }.getOrNull()

    private fun readOwner(stage: Path): OwnerMarker? {
        val marker = stage.resolve(OWNER_FILE)
        if (!Files.isRegularFile(marker, NOFOLLOW_LINKS) || Files.isSymbolicLink(marker) || Files.size(marker) > 4096) return null
        return json.decodeFromString<OwnerMarker>(Files.readString(marker))
    }

    /** A missing v2 lease file, a redirected path, and a busy/unknown lock are all left untouched. */
    fun tryLeaseStage(root: Path, stage: Path, repository: String): StageLease? = runCatching {
        val verified = ownedStage(root, stage, repository) ?: return@runCatching null
        val owner = readOwner(verified) ?: return@runCatching null
        val missing = !Files.exists(verified.resolve(LEASE_FILE), NOFOLLOW_LINKS)
        if (missing && owner.owner != LEGACY_OWNER) return@runCatching null
        val lease = openLease(verified, create = missing) ?: return@runCatching null
        try {
            require(ownedStage(root, verified, repository) == verified) { "更新安装目录所有者已改变" }
            lease
        } catch (error: Throwable) { lease.close(); throw error }
    }.getOrNull()

    private fun openLease(stage: Path, create: Boolean): StageLease? {
        val file = stage.resolve(LEASE_FILE)
        var channel: FileChannel? = null
        var lock: FileLock? = null
        try {
            if (!create) {
                require(existingPathWithoutLinks(file) == file && Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) == 0L)
            }
            val opened = if (create) FileChannel.open(file, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
                else FileChannel.open(file, WRITE, NOFOLLOW_LINKS)
            channel = opened
            val acquired = opened.tryLock() ?: return null
            lock = acquired
            require(existingPathWithoutLinks(file) == file && Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) == 0L)
            return StageLease(stage, opened, acquired).also { channel = null; lock = null }
        } catch (_: Exception) {
            // Includes an overlapping JVM lock and a lock owned by another process.
            return null
        } finally {
            try { lock?.let { runCatching { it.release() } } } finally { channel?.let { runCatching { it.close() } } }
        }
    }

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

    fun deleteOwnedStage(root: Path, stage: Path, repository: String,
        preserveAfterLease: (Path) -> Boolean = { false }): Boolean {
        // A legacy owner never held an OS lease. It may still be waiting for playback, so it is not reclaimable.
        val verified = runCatching {
            ownedStage(root, stage, repository)?.takeIf { readOwner(it)?.owner == OWNER }
        }.getOrNull() ?: return false
        val lease = tryLeaseStage(root, verified, repository) ?: return false
        try {
            // READY/CLAIMED and unknown receipt custody are durable, not generic
            // prune garbage. Explicit invalidation consumes its exact receipt first.
            if (DesktopPreparedUpdateStorage.hasRecord(root, lease, repository)) return false
            // A snapshot taken before lock acquisition may predate another owner's successful activation.
            // Unknown/failing fresh admission is preserved, before the first payload deletion.
            if (runCatching { preserveAfterLease(lease.stage) }.getOrDefault(true)) return false
            return deleteLeasedStage(root, lease, repository)
        } finally { lease.close() }
    }

    /** Incomplete downloads already own their lease and must not attempt to reacquire it. Consumes the lease. */
    fun deleteLeasedStage(root: Path, lease: StageLease, repository: String): Boolean {
        var unmarked = false
        val removed = try {
            runCatching {
                val verified = ownedStage(root, lease.stage, repository) ?: return@runCatching false
                require(lease.holds(verified)) { "更新安装目录租约已失效" }
                require(readOwner(verified)?.owner == OWNER) { "旧版更新安装目录不参与自动清理" }
                // Validate the complete deletion set before removing anything. Walk never follows links.
                val paths = Files.walk(verified).use { stream -> stream.map(::existingPathWithoutLinks).toList() }
                if (paths.any { !it.startsWith(verified) }) return@runCatching false
                val marker = verified.resolve(OWNER_FILE)
                val lock = verified.resolve(LEASE_FILE)
                paths.filter { it != verified && it != marker && it != lock }
                    .sortedByDescending { it.nameCount }.forEach { Files.delete(it) }
                // Retire ownership while still locked. A waiting cleaner cannot reclaim the release/delete gap.
                Files.delete(marker)
                unmarked = true
                true
            }.getOrDefault(false)
        } finally { lease.close() }
        if (!unmarked) return removed
        // Close our lock handle before removing the lock file. Once unmarked this stage cannot be newly claimed.
        return runCatching { Files.delete(lease.stage.resolve(LEASE_FILE)); Files.delete(lease.stage); true }.getOrDefault(false)
    }

    fun prune(root: Path, repository: String, preservedStages: Set<Path>,
        preserveAfterLease: (Path) -> Boolean = { false }) {
        val absolute = verifiedRoot(root)
        val preserved = preservedStages.map(::existingPathWithoutLinks).toSet()
        Files.list(absolute).use { children ->
            children.forEach { child -> if (child !in preserved) deleteOwnedStage(absolute, child, repository, preserveAfterLease) }
        }
    }

    @Serializable
    private data class OwnerMarker(val repository: String, val owner: String)
}

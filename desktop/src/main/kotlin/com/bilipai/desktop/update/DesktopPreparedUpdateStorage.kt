package com.bilipai.desktop.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Local recovery facts, never process/health/registry admission. Every operation
 * borrows the updater's existing v2 OS stage lease. A CLAIMED receipt is never
 * automatically converted to READY after another process or owner disappears. */
internal object DesktopPreparedUpdateStorage {
    private const val RECEIPT_FILE = ".bilipai-prepared.json"
    private const val ARCHIVE_FILE = "download.zip"
    private const val MAX_RECEIPT_BYTES = 64 * 1024
    private val json = Json { encodeDefaults = true }
    internal enum class Phase { READY, CLAIMED }

    internal class Receipt internal constructor(
        val repository: String,
        val channel: String,
        val preparedId: String,
        val update: WindowsUpdate,
        val archiveSha256: String,
        val executableName: String,
        val phase: Phase,
        val claimToken: String?,
        bytes: ByteArray,
    ) {
        private val capturedBytes = bytes.copyOf()
        internal fun matches(other: Receipt): Boolean = capturedBytes.contentEquals(other.capturedBytes)
    }

    /** Absence is null. Malformed, redirected, unknown-schema or unreadable data
     * throws, so callers cannot confuse it with an ordinary incomplete stage. */
    fun read(root: Path, lease: UpdateStorage.StageLease, repository: String): Receipt? {
        val stage = UpdateStorage.requireLeasedStage(root, lease, repository)
        val file = stage.resolve(RECEIPT_FILE)
        val attributes = try { Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
        catch (_: NoSuchFileException) { return null }
        require(attributes.isRegularFile && !attributes.isSymbolicLink && !attributes.isOther &&
            attributes.size() in 1..MAX_RECEIPT_BYTES.toLong()) { "更新准备记录不是可确认的普通文件" }
        require(UpdateStorage.existingPathWithoutLinks(file) == file) { "更新准备记录路径已改变" }
        val bytes = Files.newInputStream(file, NOFOLLOW_LINKS).use { it.readNBytes(MAX_RECEIPT_BYTES + 1) }
        require(bytes.size in 1..MAX_RECEIPT_BYTES) { "更新准备记录大小异常" }
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        return decode(json.decodeFromString<StoredReceipt>(text), bytes, repository)
    }

    /** Used only under the cleaner's actual stage lease. Unknown receipt custody
     * is preserved as well; neither READY nor CLAIMED is generic prune garbage. */
    fun hasRecord(root: Path, lease: UpdateStorage.StageLease, repository: String): Boolean = runCatching {
        val file = UpdateStorage.requireLeasedStage(root, lease, repository).resolve(RECEIPT_FILE)
        try { Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS); true }
        catch (_: NoSuchFileException) { false }
    }.getOrDefault(true)

    /** Call only after the existing checksum/catalog validation and SafeUpdateZip
     * extraction succeeded. Retain download.zip; recheck its exact bytes before
     * publishing the durable READY fact. The extracted executable is NOT trusted
     * on a later restart; that restart must re-extract the reverified archive. */
    fun writeReady(
        root: Path, lease: UpdateStorage.StageLease, repository: String, channel: String,
        update: WindowsUpdate, archiveSha256: String, executable: Path,
        expectedExecutableName: String, checkCancelled: () -> Unit,
    ): Receipt {
        val stage = UpdateStorage.requireLeasedStage(root, lease, repository)
        require(UpdateStorage.executableStage(root, executable, expectedExecutableName) == stage) {
            "更新准备启动文件不属于实际解压目录"
        }
        val stored = StoredReceipt(1, repository, channel, UUID.randomUUID().toString(),
            StoredUpdate.from(update), archiveSha256.lowercase(Locale.ROOT), expectedExecutableName,
            Phase.READY.name, null)
        val bytes = encode(stored)
        val receipt = decode(stored, bytes, repository)
        verifyArchive(root, lease, repository, receipt, checkCancelled)
        atomicWrite(root, lease, repository, bytes, expected = null, checkCancelled)
        return receipt
    }

    /** Does not grant launch. Hash/size are the prior accepted ZIP facts, not a
     * signature for arbitrary local files. Root also validates current build
     * repository/channel/newer-version policy before accepting this receipt. */
    fun verifyArchive(
        root: Path, lease: UpdateStorage.StageLease, repository: String,
        receipt: Receipt, checkCancelled: () -> Unit,
    ): Path {
        require(receipt.repository == repository)
        val stage = UpdateStorage.requireLeasedStage(root, lease, repository)
        val archive = stage.resolve(ARCHIVE_FILE)
        val before = Files.readAttributes(archive, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(before.isRegularFile && !before.isSymbolicLink && !before.isOther &&
            before.size() == receipt.update.size &&
            UpdateStorage.existingPathWithoutLinks(archive) == archive) { "保留的更新 ZIP 路径或大小已改变" }
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        Files.newInputStream(archive, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                received += count
                require(received <= receipt.update.size) { "保留的更新 ZIP 大小超限" }
                digest.update(buffer, 0, count)
            }
        }
        checkCancelled()
        require(received == receipt.update.size) { "保留的更新 ZIP 不完整" }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        DesktopUpdater.verifyChecksum(actual, receipt.archiveSha256)
        val after = Files.readAttributes(archive, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(after.isRegularFile && !after.isSymbolicLink && !after.isOther &&
            after.size() == before.size() && after.fileKey() == before.fileKey() &&
            after.lastModifiedTime() == before.lastModifiedTime() &&
            UpdateStorage.existingPathWithoutLinks(archive) == archive) { "校验期间更新 ZIP 已改变" }
        UpdateStorage.requireLeasedStage(root, lease, repository)
        return archive
    }

    /** Rebuild only the fixed app child, while keeping ZIP/receipt/owner/lease.
     * Validate the WHOLE deletion set first; interruption can leave an incomplete
     * app tree, which the next READY recovery will rebuild from the same ZIP. */
    fun recreateAppDirectory(
        root: Path, lease: UpdateStorage.StageLease, repository: String,
        ready: Receipt, checkCancelled: () -> Unit,
    ): Path {
        require(ready.phase == Phase.READY)
        requireCurrent(root, lease, repository, ready)
        val stage = UpdateStorage.requireLeasedStage(root, lease, repository)
        val app = stage.resolve("app")
        val attributes = try { Files.readAttributes(app, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
        catch (_: NoSuchFileException) { null }
        if (attributes != null) {
            require(attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther &&
                UpdateStorage.existingPathWithoutLinks(app) == app) { "旧更新解压目录不能包含链接或特殊文件" }
            val paths = Files.walk(app).use { stream ->
                stream.map { path -> checkCancelled(); UpdateStorage.existingPathWithoutLinks(path) }.toList()
            }
            require(paths.all { it == app || it.startsWith(app) }) { "旧更新解压文件越过 app 目录" }
            requireCurrent(root, lease, repository, ready)
            paths.sortedByDescending { it.nameCount }.forEach { path -> checkCancelled(); Files.delete(path) }
        }
        checkCancelled()
        requireCurrent(root, lease, repository, ready)
        return Files.createDirectory(app)
    }

    /** Root performs download idle/freeze first, then calls this before creating
     * launch/health files or entering ProcessBuilder.start. Exact generation and
     * exact raw disk bytes are checked under the same OS stage lease. */
    fun claim(
        root: Path, lease: UpdateStorage.StageLease, repository: String,
        ready: Receipt, healthToken: String,
    ): Receipt {
        require(ready.phase == Phase.READY && canonicalUuid(healthToken))
        return replace(root, lease, repository, ready, Phase.CLAIMED, healthToken)
    }

    /** ONLY the same live activation may call this after confirmed no-start OR
     * actual StartupHealth.terminate AND download rollback both succeeded.
     * An entered launch with no returned Process is unknown, not "no child".
     * A recovered CLAIMED record alone never proves this condition. */
    fun restoreReady(
        root: Path, lease: UpdateStorage.StageLease, repository: String, claimed: Receipt,
    ): Receipt {
        require(claimed.phase == Phase.CLAIMED && claimed.claimToken != null)
        return replace(root, lease, repository, claimed, Phase.READY, null)
    }

    /** After confirmed invalidation or actual healthy registry commit. This
     * consumes only the exact captured receipt; it never removes payload files. */
    fun retire(root: Path, lease: UpdateStorage.StageLease, repository: String, expected: Receipt) {
        requireCurrent(root, lease, repository, expected)
        val stage = UpdateStorage.requireLeasedStage(root, lease, repository)
        Files.delete(stage.resolve(RECEIPT_FILE))
    }

    private fun replace(
        root: Path, lease: UpdateStorage.StageLease, repository: String,
        expected: Receipt, phase: Phase, token: String?,
    ): Receipt {
        val stored = StoredReceipt(1, expected.repository, expected.channel, expected.preparedId,
            StoredUpdate.from(expected.update), expected.archiveSha256, expected.executableName, phase.name, token)
        val bytes = encode(stored)
        val next = decode(stored, bytes, repository)
        atomicWrite(root, lease, repository, bytes, expected) {}
        return next
    }

    private fun requireCurrent(
        root: Path, lease: UpdateStorage.StageLease, repository: String, expected: Receipt,
    ) {
        val current = requireNotNull(read(root, lease, repository)) { "更新准备记录已退役" }
        require(expected.repository == repository && expected.matches(current)) { "更新准备记录或认领世代已改变" }
    }

    private fun atomicWrite(
        root: Path, lease: UpdateStorage.StageLease, repository: String,
        bytes: ByteArray, expected: Receipt?, checkCancelled: () -> Unit,
    ) {
        val stage = UpdateStorage.requireLeasedStage(root, lease, repository)
        val temporary = Files.createTempFile(stage, ".bilipai-prepared-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) { checkCancelled(); channel.write(buffer) }
                channel.force(true)
            }
            checkCancelled()
            UpdateStorage.requireLeasedStage(root, lease, repository)
            if (expected == null) require(read(root, lease, repository) == null) { "更新准备记录已存在" }
            else requireCurrent(root, lease, repository, expected)
            // No non-atomic fallback: a crash-visible half claim may never grant READY.
            Files.move(temporary, stage.resolve(RECEIPT_FILE), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun encode(stored: StoredReceipt): ByteArray =
        json.encodeToString(StoredReceipt.serializer(), stored).toByteArray(Charsets.UTF_8).also {
            require(it.size in 1..MAX_RECEIPT_BYTES) { "更新准备记录大小异常" }
        }

    private fun decode(stored: StoredReceipt, bytes: ByteArray, repository: String): Receipt {
        require(stored.schema == 1 && stored.repository == repository && DesktopUpdater.validRepository(repository))
        require(stored.channel in setOf("stable", "prerelease", "dev") && canonicalUuid(stored.preparedId))
        val update = stored.update.toUpdate()
        require(DesktopVersion.parse(update.version) != null && update.releaseId > 0 && update.assetId > 0 &&
            DesktopUpdater.isWindowsZip(update.assetName) && update.size in 1..DesktopUpdater.MAX_DOWNLOAD_BYTES &&
            (if (update.signedManifest != null) update.signedManifest.toByteArray(Charsets.UTF_8).size in 1..32 * 1024 &&
                listOf(update.downloadUrl, update.checksumUrl).all { raw -> runCatching {
                    val uri = java.net.URI(raw)
                    uri.scheme == "https" && uri.userInfo == null && uri.host != null && uri.fragment == null
                }.getOrDefault(false) }
            else DesktopUpdater.trustedAssetUrl(update.downloadUrl, repository) &&
                DesktopUpdater.trustedAssetUrl(update.checksumUrl, repository))) { "更新准备目标字段无效" }
        require(Regex("^[0-9a-f]{64}$").matches(stored.archiveSha256))
        require(SafeUpdateZip.safeRelativeName(stored.executableName) == stored.executableName &&
            '/' !in stored.executableName) { "更新准备启动文件名无效" }
        val phase = Phase.valueOf(stored.phase)
        require(if (phase == Phase.READY) stored.claimToken == null else
            stored.claimToken?.let(::canonicalUuid) == true) { "更新认领阶段或令牌无效" }
        return Receipt(repository, stored.channel, stored.preparedId, update, stored.archiveSha256,
            stored.executableName, phase, stored.claimToken, bytes)
    }

    private fun canonicalUuid(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    @Serializable
    private data class StoredReceipt(
        val schema: Int, val repository: String, val channel: String, val preparedId: String,
        val update: StoredUpdate, val archiveSha256: String, val executableName: String,
        val phase: String, val claimToken: String?,
    )

    @Serializable
    private data class StoredUpdate(
        val version: String, val releaseId: Long, val assetId: Long, val assetName: String,
        val size: Long, val downloadUrl: String, val checksumUrl: String, val releaseUrl: String,
        val signedManifest: String? = null,
    ) {
        fun toUpdate() = WindowsUpdate(version, releaseId, assetId, assetName, size, downloadUrl, checksumUrl, releaseUrl, signedManifest)
        companion object {
            fun from(update: WindowsUpdate) = StoredUpdate(update.version, update.releaseId, update.assetId,
                update.assetName, update.size, update.downloadUrl, update.checksumUrl, update.releaseUrl, update.signedManifest)
        }
    }
}

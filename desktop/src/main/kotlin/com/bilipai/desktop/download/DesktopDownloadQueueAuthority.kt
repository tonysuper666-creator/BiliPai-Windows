package com.bilipai.desktop.download

import com.bilipai.desktop.update.DesktopUpdater
import com.bilipai.desktop.update.UpdateStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID

/** Platform ownership only. Original task JSON, IDs, directories and queue policy stay in Manager. */
internal object DesktopDownloadQueueAuthority {
    private const val OWNER = "BiliPai.Windows.DownloadQueue.v1"
    private val json = Json { encodeDefaults = true }

    @Serializable
    internal data class CommitTarget(val repository: String, val version: String,
        val executablePath: String, val registryFile: String)

    @Serializable
    private data class PendingTransfer(val schema: Int = 1, val owner: String = OWNER,
        val queueName: String, val token: String, val claimedBy: String? = null,
        val target: CommitTarget? = null)

    internal data class Attempt(val lease: Lease?, val reason: String?, val busy: Boolean = false)

    /** Keep the permanent zero-byte lock inode separate from atomically replaced task JSON. */
    internal class Lease internal constructor(private val inputStateFile: Path, val stateFile: Path,
        private val channel: FileChannel, private val fileLock: FileLock,
        private val ownerId: String, val startupToken: String?) : AutoCloseable {
        private var closed = false
        private val pendingFile get() = stateFile.resolveSibling(stateFile.fileName.toString() + ".bilipai-transfer.json")

        fun holds(path: Path): Boolean = !closed && channel.isOpen && fileLock.isValid &&
            inputStateFile == path.toAbsolutePath().normalize()

        private fun requireHeld() {
            check(holds(inputStateFile)) { "下载队列操作系统租约已失效" }
            check(UpdateStorage.existingPathWithoutLinks(stateFile.parent) == stateFile.parent)
        }

        private fun readPending(): PendingTransfer? {
            if (Files.notExists(pendingFile, NOFOLLOW_LINKS)) return null
            val checked = UpdateStorage.existingPathWithoutLinks(pendingFile)
            require(Files.isRegularFile(checked, NOFOLLOW_LINKS) && Files.size(checked) <= 8192)
            val bytes = Files.newInputStream(checked, NOFOLLOW_LINKS).use { it.readNBytes(8193) }
            require(bytes.size <= 8192)
            val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            return json.decodeFromString<PendingTransfer>(text).also {
                require(it.schema == 1 && it.owner == OWNER && it.queueName == stateFile.fileName.toString())
                require(canonicalToken(it.token) && (it.claimedBy == null || canonicalToken(it.claimedBy)))
                it.target?.let { target ->
                    require(Path.of(target.registryFile).toAbsolutePath().normalize() ==
                        stateFile.parent.resolve("updates/active-install.json"))
                }
            }
        }

        private fun writePending(value: PendingTransfer) {
            requireHeld()
            if (Files.exists(pendingFile, NOFOLLOW_LINKS)) readPending()
            val temporary = stateFile.parent.resolve(".download-transfer-${UUID.randomUUID()}.tmp")
            try {
                FileChannel.open(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
                    val bytes = ByteBuffer.wrap(json.encodeToString(PendingTransfer.serializer(), value).toByteArray())
                    while (bytes.hasRemaining()) output.write(bytes)
                    output.force(true)
                }
                Files.move(temporary, pendingFile, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }

        /** A matching registry generation proves actual successful CAS, never a PID or timestamp. */
        private fun committed(value: PendingTransfer): Boolean = value.target?.let {
            DesktopUpdater.downloadHandoffCommitMatches(it, value.token)
        } == true

        internal fun admitStartup(recoveryToken: String?) {
            requireHeld()
            val pending = readPending() ?: run {
                check(recoveryToken == null) { "下载交接记录已改变，不能恢复旧队列" }
                return
            }
            if (recoveryToken != null) {
                check(pending.token == recoveryToken) { "下载交接代次已经改变" }
                return // Confirmed parent still must strict-read and persist before clearing.
            }
            if (committed(pending)) {
                Files.delete(pendingFile) // Old committed token may differ from a new launch UUID.
                return
            }
            check(startupToken != null && pending.token == startupToken && pending.claimedBy == null) {
                "上次更新的下载交接尚未确认，下载队列保持只读"
            }
            writePending(pending.copy(claimedBy = ownerId))
        }

        fun prepareTransfer(token: String, target: CommitTarget?) {
            requireHeld()
            require(canonicalToken(token))
            val previous = readPending()
            if (previous != null) {
                check(previous.claimedBy == ownerId && committed(previous)) { "已有未完成的下载交接" }
                Files.delete(pendingFile)
            }
            target?.let {
                require(Path.of(it.registryFile).toAbsolutePath().normalize() ==
                    stateFile.parent.resolve("updates/active-install.json"))
            }
            writePending(PendingTransfer(queueName = stateFile.fileName.toString(), token = token, target = target))
        }

        fun hasPendingTransfer(token: String): Boolean {
            requireHeld()
            val pending = readPending() ?: return false
            check(pending.token == token) { "下载交接代次已经改变" }
            return true
        }

        /** Call only after confirmed-child retirement, exact reacquisition, strict reload and checkpoint. */
        fun finishRollback(token: String) {
            requireHeld()
            val pending = checkNotNull(readPending()) { "下载交接记录已改变" }
            check(pending.token == token) { "下载交接代次已经改变" }
            Files.delete(pendingFile)
        }

        /** Clean exit can release its own lease; unresolved transfer remains durable. */
        fun finishCleanExit() {
            requireHeld()
            val pending = readPending() ?: return
            if (pending.claimedBy == ownerId && committed(pending)) Files.delete(pendingFile)
        }

        override fun close() {
            if (closed) return
            try { if (fileLock.isValid) fileLock.release() }
            finally { channel.close(); closed = true }
            check(!channel.isOpen && !fileLock.isValid) { "下载队列租约释放状态无法确认" }
        }
    }

    private fun canonicalToken(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    /** One nonblocking attempt, outside queue/Store monitors. Busy/unknown never grants an owner. */
    fun tryAcquire(stateFile: Path, startupToken: String? = null, recoveryToken: String? = null): Attempt {
        var channel: FileChannel? = null
        var lock: FileLock? = null
        var lease: Lease? = null
        return try {
            if (startupToken != null) require(canonicalToken(startupToken))
            if (recoveryToken != null) require(canonicalToken(recoveryToken))
            val input = stateFile.toAbsolutePath().normalize()
            val parent = UpdateStorage.verifiedRoot(requireNotNull(input.parent))
            val canonicalState = parent.resolve(input.fileName)
            if (Files.exists(canonicalState, NOFOLLOW_LINKS)) {
                require(UpdateStorage.existingPathWithoutLinks(canonicalState) == canonicalState &&
                    Files.isRegularFile(canonicalState, NOFOLLOW_LINKS))
            }
            val lockFile = parent.resolve(input.fileName.toString() + ".bilipai-lock")
            if (Files.exists(lockFile, NOFOLLOW_LINKS)) {
                require(UpdateStorage.existingPathWithoutLinks(lockFile) == lockFile &&
                    Files.isRegularFile(lockFile, NOFOLLOW_LINKS) && Files.size(lockFile) == 0L)
            }
            val opened = FileChannel.open(lockFile, CREATE, WRITE, NOFOLLOW_LINKS)
            channel = opened
            val acquired = try { opened.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
            if (acquired == null) return Attempt(null, "另一个客户端正在使用下载队列，当前窗口暂为只读", busy = true)
            lock = acquired
            require(UpdateStorage.existingPathWithoutLinks(lockFile) == lockFile &&
                Files.isRegularFile(lockFile, NOFOLLOW_LINKS) && Files.size(lockFile) == 0L && opened.size() == 0L)
            val owned = Lease(input, canonicalState, opened, acquired, UUID.randomUUID().toString(), startupToken)
            lease = owned
            owned.admitStartup(recoveryToken)
            Attempt(owned, null).also { lease = null; channel = null; lock = null }
        } catch (failure: Exception) {
            Attempt(null, failure.message ?: "下载队列所有权无法确认，当前窗口暂为只读")
        } finally {
            if (lease != null) runCatching { lease!!.close() }
            else try { lock?.let { runCatching { it.release() } } } finally { channel?.let { runCatching { it.close() } } }
        }
    }
}

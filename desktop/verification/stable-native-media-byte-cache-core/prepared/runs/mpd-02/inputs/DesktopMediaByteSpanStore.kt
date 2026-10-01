package com.bilipai.desktop.player.cache

import com.android.purebilibili.core.player.resolvePlaybackMediaCacheMaxBytes

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Sole bounded media-cache index; files contain opaque identity/interval metadata and bytes.
 * All disk operations are outside the index monitor and outside account/entry/native admission. */
internal class DesktopMediaByteSpanStore(root: Path, val limit: Long = resolvePlaybackMediaCacheMaxBytes()) {
    private val directory = root.toAbsolutePath().normalize()
    private val monitor = Any()
    internal data class Span(val id: String, val start: Long, val length: Long,
        val path: Path, val dataOffset: Long, val diskSize: Long, var users: Int = 0,
        var used: Long = System.nanoTime())
    private val spans = mutableListOf<Span>()
    private val garbage = mutableMapOf<Path, Long>()
    private var diskBytes = 0L
    private var closed = false

    init {
        require(limit in 1024L..(128L * 1024 * 1024))
        Files.createDirectories(directory)
        require(!Files.isSymbolicLink(directory))
        Files.list(directory).use { paths -> paths.forEach { path ->
            if (Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
                val size = Files.size(path)
                val span = runCatching { readHeader(path, size) }.getOrNull()
                if (span != null && diskBytes <= limit - size) { spans += span; diskBytes += size }
                else {
                    diskBytes += size; garbage[path] = size
                }
            }
        } }
        cleanGarbage()
        if (diskBytes > limit) throw IOException("Media cache startup exceeds bounded capacity")
    }

    private fun readHeader(path: Path, size: Long): Span? {
        if (!path.fileName.toString().endsWith(".span")) return null
        RandomAccessFile(path.toFile(), "r").use { input ->
            require(input.readInt() == MAGIC)
            val id = input.readUTF(); require(id.matches(Regex("[a-f0-9]{64}")))
            val start = input.readLong(); val length = input.readLong()
            val persistent = input.readBoolean(); val offset = input.filePointer
            require(start >= 0 && length > 0 && start <= Long.MAX_VALUE - length)
            require(size == offset + length)
            return if (persistent) Span(id, start, length, path, offset, size) else null
        }
    }

    fun stats(): Pair<Long, Int> = synchronized(monitor) { diskBytes to spans.size }

    fun covers(id: String, start: Long, length: Long): Boolean = synchronized(monitor) {
        var cursor = start; val end = checkedEnd(start, length)
        while (cursor < end) {
            val span = spans.filter { it.id == id && it.start <= cursor && it.start + it.length > cursor }
                .maxByOrNull { it.start + it.length } ?: return@synchronized false
            cursor = minOf(end, span.start + span.length)
        }
        true
    }

    internal inner class Reader internal constructor(private val span: Span,
        private val file: RandomAccessFile, position: Long) : AutoCloseable {
        var remaining: Long = span.start + span.length - position; private set
        private val retired = AtomicBoolean(false)
        init { file.seek(span.dataOffset + position - span.start) }
        fun read(bytes: ByteArray, offset: Int, size: Int): Int {
            if (retired.get()) throw IOException("Media cache reader closed")
            if (remaining == 0L) return -1
            val count = file.read(bytes, offset, minOf(size.toLong(), remaining).toInt())
            if (count < 0) throw IOException("Committed media interval truncated")
            remaining -= count; return count
        }
        override fun close() {
            if (!retired.compareAndSet(false,true)) return
            try { file.close() } finally { synchronized(monitor) { span.users-- } }
        }
    }

    fun open(id: String, position: Long): Reader? {
        val span = synchronized(monitor) {
            if (closed) throw IOException("Media cache closed")
            spans.firstOrNull { it.id == id && position >= it.start && position < it.start + it.length }
                ?.also { it.users++; it.used = System.nanoTime() }
        } ?: return null
        try { return Reader(span, RandomAccessFile(span.path.toFile(), "r"), position) }
        catch (failure: Throwable) { synchronized(monitor) { span.users-- }; throw failure }
    }

    internal class Reservation internal constructor(val temp: Path, val charged: Long) {
        var released = false
    }

    fun reserve(length: Long): Reservation {
        require(length > 0 && length <= limit - HEADER_BUDGET)
        val charge = length + HEADER_BUDGET
        cleanGarbage()
        while (true) {
            val victim = synchronized(monitor) {
                if (closed) throw IOException("Media cache closed")
                if (diskBytes <= limit - charge) {
                    diskBytes += charge
                    return Reservation(directory.resolve(UUID.randomUUID().toString() + ".part"), charge)
                }
                spans.filter { it.users == 0 }.minByOrNull { it.used }?.also {
                    spans.remove(it); garbage[it.path] = it.diskSize
                }
            } ?: throw IOException("Media cache capacity pinned by active readers")
            deleteGarbage(victim.path)
        }
    }

    fun begin(reservation: Reservation, id: String, start: Long, length: Long,
        persistent: Boolean): DataOutputStream {
        require(id.matches(Regex("[a-f0-9]{64}"))); checkedEnd(start, length)
        return DataOutputStream(Files.newOutputStream(reservation.temp, java.nio.file.StandardOpenOption.CREATE_NEW)).also {
            it.writeInt(MAGIC); it.writeUTF(id); it.writeLong(start); it.writeLong(length); it.writeBoolean(persistent)
        }
    }

    fun publish(reservation: Reservation, id: String, start: Long, length: Long,
        admit: ((() -> Unit) -> Unit)) {
        val target = directory.resolve(UUID.randomUUID().toString() + ".span")
        try {
            val actualSize = Files.size(reservation.temp)
            require(actualSize <= reservation.charged)
            // No REPLACE_EXISTING or ATOMIC_MOVE overwrite ambiguity: UUID destination
            // plus no-clobber move; index remains unreachable until complete header check.
            Files.move(reservation.temp, target)
            val candidate = RandomAccessFile(target.toFile(), "r").use { input ->
                require(input.readInt() == MAGIC && input.readUTF() == id && input.readLong() == start && input.readLong() == length)
                input.readBoolean(); require(actualSize == input.filePointer + length)
                Span(id, start, length, target, input.filePointer, actualSize)
            }
            admit {
                synchronized(monitor) {
                    if (closed) throw IOException("Media cache closed")
                    check(!reservation.released)
                    diskBytes -= reservation.charged - actualSize
                    reservation.released = true; spans += candidate
                }
            }
        } catch (failure: Throwable) {
            // Rename may have completed before admission was rejected. Orphan never enters index.
            discard(reservation, target); throw failure
        }
    }

    fun discard(reservation: Reservation, renamed: Path? = null) {
        if (reservation.released) return
        var deleted = true
        for (path in listOfNotNull(reservation.temp, renamed)) {
            try { Files.deleteIfExists(path) } catch (_: IOException) { deleted = false }
        }
        val remainingPath = renamed?.takeIf { Files.exists(it) } ?: reservation.temp
        synchronized(monitor) {
            if (!reservation.released) {
                reservation.released = true
                if (deleted) diskBytes -= reservation.charged
                else {
                    garbage[remainingPath] = reservation.charged
                }
            }
        }
    }

    private fun cleanGarbage() {
        val paths = synchronized(monitor) { garbage.keys.toList() }
        paths.forEach(::deleteGarbage)
    }
    private fun deleteGarbage(path: Path) {
        try {
            Files.deleteIfExists(path)
            synchronized(monitor) { garbage.remove(path)?.let { diskBytes -= it } }
        } catch (_: IOException) { /* remains charged; do not exceed budget */ }
    }

    fun clear() {
        synchronized(monitor) {
            require(spans.none { it.users != 0 }) { "Media cache readers must close before clear" }
            spans.forEach { garbage[it.path] = it.diskSize }; spans.clear()
        }
        cleanGarbage()
    }
    fun close() { synchronized(monitor) { closed = true } }
    companion object {
        private const val MAGIC = 0x42504331
        private const val HEADER_BUDGET = 128L
        fun checkedEnd(start: Long, length: Long): Long {
            require(start >= 0 && length > 0 && start <= Long.MAX_VALUE - length)
            return start + length
        }
    }
}

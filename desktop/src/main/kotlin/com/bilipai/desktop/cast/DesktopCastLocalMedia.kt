package com.bilipai.desktop.cast

import com.bilipai.desktop.player.DesktopNativePlaybackPublication
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Immutable file registration owned by the existing cast and native offline publications. */
internal class DesktopCastLocalTarget private constructor(
    private val path: Path,
    val contentType: String,
    val length: Long,
    private val modified: java.nio.file.attribute.FileTime,
    private val fileKey: Any?,
    private val frame: DesktopCastPublicationFrame,
    private val nativePublication: DesktopNativePlaybackPublication,
) {
    private val lock = Any()
    private var retired = false
    private val streams = mutableSetOf<InputStream>()

    fun <T> admit(action: () -> T): T = frame.admit<T> {
        var result: Any? = null
        var applied = false
        check(nativePublication.admit {
            synchronized(lock) {
                check(!retired) { "Local cast target retired" }
                result = action(); applied = true
            }
        } && applied) { "Local cast source retired" }
        @Suppress("UNCHECKED_CAST")
        result as T
    }

    /** Disk operations precede the final short account -> entry -> native gate. */
    fun checkFile() {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(attributes.isRegularFile && attributes.size() == length && attributes.lastModifiedTime() == modified &&
            (fileKey == null || attributes.fileKey() == fileKey)) { "Local cast file changed" }
        admit { Unit }
    }

    fun open(start: Long, count: Long): InputStream {
        checkFile()
        val channel = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        try {
            channel.position(start)
            checkFile()
            val stream = LocalInputStream(channel, count)
            admit { check(streams.size < 32) { "Too many local cast readers" }; streams.add(stream) }
            return stream
        } catch (failure: Throwable) {
            channel.close()
            throw failure
        }
    }

    fun retire() {
        val opened = synchronized(lock) { retired = true; streams.toList().also { streams.clear() } }
        opened.forEach { runCatching { it.close() } }
    }

    private inner class LocalInputStream(private val channel: SeekableByteChannel, private var remaining: Long) : InputStream() {
        private val closed = java.util.concurrent.atomic.AtomicBoolean()
        private val readLock = Any()
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = synchronized(readLock) {
            require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
            if (length == 0) return@synchronized 0
            if (closed.get()) throw IOException("Local cast reader closed")
            try {
                admit { Unit }
                if (remaining == 0L) return@synchronized -1
                checkFile()
                // Read at most one bounded chunk outside all account/entry/native monitors.
                val bytes = ByteArray(minOf(length.toLong(), remaining, 65_536L).toInt())
                val read = channel.read(ByteBuffer.wrap(bytes))
                if (read < 0) throw EOFException("Local cast file truncated")
                // Only an admitted copy reaches NanoHTTPD's caller-owned output buffer.
                admit { check(!closed.get()); bytes.copyInto(buffer, offset, 0, read); remaining -= read }
                read
            } catch (failure: Exception) {
                close()
                throw IOException("Local cast source is no longer available", failure)
            }
        }
        override fun close() {
            // Do not wait for a reader that may be seeking account/native admission.
            if (closed.compareAndSet(false, true)) {
                synchronized(lock) { streams.remove(this) }
                channel.close()
            }
        }
    }

    companion object {
        fun capture(path: Path, contentType: String, frame: DesktopCastPublicationFrame,
            nativePublication: DesktopNativePlaybackPublication): DesktopCastLocalTarget {
            require(path.isAbsolute && contentType in setOf("video/mp4", "audio/mp4"))
            val absolute = path.toAbsolutePath().normalize()
            require(!Files.isSymbolicLink(absolute)) { "Local cast file must not be a symbolic link" }
            val actual = absolute.toRealPath()
            val attributes = Files.readAttributes(actual, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(attributes.isRegularFile) { "Local cast file is unavailable" }
            return DesktopCastLocalTarget(actual, contentType, attributes.size(), attributes.lastModifiedTime(),
                attributes.fileKey(), frame, nativePublication)
        }
    }
}

/** GET/HEAD and single byte ranges on an opaque registration, never a caller-supplied path. */
internal fun serveDesktopLocalCastFile(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
    fun error(status: NanoHTTPD.Response.Status, message: String) =
        NanoHTTPD.newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, message)
    if (session.method != NanoHTTPD.Method.GET && session.method != NanoHTTPD.Method.HEAD)
        return error(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "Only GET and HEAD are supported")
    val id = (session.uri ?: "").removePrefix("/local/")
    if (!Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id) || session.parameters.isNotEmpty())
        return error(NanoHTTPD.Response.Status.NOT_FOUND, "Local cast target not found")
    val target = DesktopCastProxySessions.findLocal(id)
        ?: return error(NanoHTTPD.Response.Status.NOT_FOUND, "Local cast target not found")
    return try {
        target.checkFile()
        val rangeHeader = if (session.method == NanoHTTPD.Method.GET) session.headers["range"] else null
        val range = if (rangeHeader == null) 0L to target.length else localCastByteRange(rangeHeader, target.length)
        if (range == null) error(NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE, "Invalid or unsatisfiable byte range").also {
            it.addHeader("Content-Range", "bytes */${target.length}"); it.addHeader("Accept-Ranges", "bytes")
        } else {
            val (start, count) = range
            val data = if (session.method == NanoHTTPD.Method.HEAD) ByteArrayInputStream(ByteArray(0)) else target.open(start, count)
            NanoHTTPD.newFixedLengthResponse(if (rangeHeader == null) NanoHTTPD.Response.Status.OK else NanoHTTPD.Response.Status.PARTIAL_CONTENT,
                target.contentType, data, count).also {
                it.addHeader("Accept-Ranges", "bytes")
                if (rangeHeader != null) it.addHeader("Content-Range", "bytes $start-${start + count - 1}/${target.length}")
                com.android.purebilibili.feature.cast.LocalProxyServer.corsHeaders().forEach(it::addHeader)
            }
        }
    } catch (_: Exception) {
        error(NanoHTTPD.Response.Status.NOT_FOUND, "Local cast source is no longer available")
    }
}

/** A range is (start, count); malformed/multiple/overflowing ranges are rejected. */
internal fun localCastByteRange(header: String, length: Long): Pair<Long, Long>? {
    if (length <= 0 || header.length > 128) return null
    val match = Regex("bytes=([0-9]*)-([0-9]*)").matchEntire(header.trim()) ?: return null
    val first = match.groupValues[1]; val last = match.groupValues[2]
    if (first.isEmpty()) {
        val suffix = last.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val count = minOf(suffix, length)
        return length - count to count
    }
    val start = first.toLongOrNull()?.takeIf { it < length } ?: return null
    val end = if (last.isEmpty()) length - 1 else last.toLongOrNull()?.takeIf { it >= start }?.coerceAtMost(length - 1) ?: return null
    return start to (end - start + 1)
}

package com.bilipai.desktop.player

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Loopback-only test media; no account, internet, production proxy or JDK httpserver module participates. */
internal class DesktopHttpMediaFixture(video: File) : AutoCloseable {
    private val bytes = video.readBytes()
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val closed = AtomicBoolean()
    val denied = AtomicInteger()
    val served = AtomicInteger()
    val suppliedFixtureCookie = AtomicBoolean()
    private val worker = Thread({
        while (!closed.get()) {
            try { server.accept().use(::serve) }
            catch (_: Exception) { if (closed.get()) break }
        }
    }, "BiliPai-native-loopback-fixture").apply { isDaemon = true; start() }
    val deniedUrl get() = "http://127.0.0.1:${server.localPort}/denied?sign=fixture-signature"
    val playableUrl get() = "http://127.0.0.1:${server.localPort}/video.avi?sign=fixture-signature"

    private fun serve(socket: Socket) {
        socket.soTimeout = 3_000
        val input = socket.getInputStream()
        val header = ByteArray(8_192)
        var count = 0
        while (count < header.size) {
            val value = input.read()
            if (value < 0) return
            header[count++] = value.toByte()
            if (count >= 4 && header[count - 4] == 13.toByte() && header[count - 3] == 10.toByte() &&
                header[count - 2] == 13.toByte() && header[count - 1] == 10.toByte()) break
        }
        require(count < header.size)
        val lines = String(header, 0, count, Charsets.ISO_8859_1).split("\r\n")
        val path = lines.first().split(' ').getOrNull(1).orEmpty().substringBefore('?')
        if (lines.any { it.startsWith("Cookie:", true) && it.contains("SESSDATA=fixture-cookie") }) suppliedFixtureCookie.set(true)
        val output = socket.getOutputStream()
        if (path == "/denied") {
            denied.incrementAndGet()
            output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush(); return
        }
        if (path != "/video.avi") {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush(); return
        }
        val range = lines.firstOrNull { it.startsWith("Range:", true) }?.substringAfter(':')?.trim()
        val parsed = range?.let { Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(it) }
        val start = parsed?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val end = parsed?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
        require(start in bytes.indices && end >= start)
        served.incrementAndGet()
        val length = end - start + 1
        val response = buildString {
            append(if (parsed == null) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 206 Partial Content\r\n")
            append("Content-Type: video/x-msvideo\r\nAccept-Ranges: bytes\r\nContent-Length: $length\r\nConnection: close\r\n")
            if (parsed != null) append("Content-Range: bytes $start-$end/${bytes.size}\r\n")
            append("\r\n")
        }
        output.write(response.toByteArray(Charsets.US_ASCII)); output.write(bytes, start, length); output.flush()
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) { server.close(); worker.join(4_000L) }
    }
}

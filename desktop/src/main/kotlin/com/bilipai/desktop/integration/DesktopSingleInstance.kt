package com.bilipai.desktop.integration

import java.awt.EventQueue
import java.awt.Frame
import java.awt.Window
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** A per-profile lock is the authority; the authenticated loopback endpoint only delivers activation. */
class DesktopSingleInstance private constructor(
    private val channel: FileChannel, private val lock: FileLock,
    private val server: ServerSocket, private val endpoint: Path, private val token: String,
) : AutoCloseable {
    private val closing = java.util.concurrent.atomic.AtomicBoolean()
    private val closed get() = closing.get()
    private val window = AtomicReference<Window?>()
    private val activation = AtomicReference<String?>()
    private var pendingFocus = false // EDT only
    private val worker = Thread({
        while (!closed) try {
            server.accept().use { client ->
                client.soTimeout = 2_000
                val input = DataInputStream(client.getInputStream())
                if (input.readUTF() != token) return@use
                val route = input.readUTF().take(4_000)
                activate(route)
                DataOutputStream(client.getOutputStream()).writeBoolean(true)
            }
        } catch (failure: Exception) {
            if (!closed) System.err.println("Single-instance activation failed: ${failure.javaClass.simpleName}")
        }
    }, "BiliPai-activation").apply { isDaemon = true; start() }

    fun bind(value: Window?) { EventQueue.invokeLater {
        window.set(value)
        if (value != null && pendingFocus) focus()
    } }
    fun activate(route: String = "") {
        if (route.isNotBlank()) activation.set(route)
        EventQueue.invokeLater { focus() }
    }
    private fun focus() {
        val host = window.get()
        pendingFocus = host == null
        if (host != null) {
            if (host is Frame) host.extendedState = host.extendedState and Frame.ICONIFIED.inv()
            host.toFront(); host.requestFocus()
        }
    }
    fun pendingRoute(): String? = activation.get()
    fun consumed(route: String) { activation.compareAndSet(route, null) }
    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        if (current === this) current = null
        server.close(); worker.join(2_500)
        Files.deleteIfExists(endpoint)
        lock.release(); channel.close()
    }

    companion object {
        @Volatile var current: DesktopSingleInstance? = null
            private set
        /** null means the existing owner acknowledged; failures are explicit, never a second owner. */
        fun acquire(directory: Path, route: String = ""): DesktopSingleInstance? {
            Files.createDirectories(directory)
            val endpoint = directory.resolve("activation.endpoint")
            val channel = FileChannel.open(directory.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock == null) {
                channel.close()
                var last: Exception? = null
                repeat(30) {
                    try {
                        val lines = Files.readAllLines(endpoint)
                        Socket(InetAddress.getLoopbackAddress(), lines[0].toInt()).use { client ->
                            client.soTimeout = 2_000
                            if (System.getProperty("os.name").startsWith("Windows") && lines.size > 2)
                                com.sun.jna.platform.win32.User32.INSTANCE.AllowSetForegroundWindow(lines[2].toInt())
                            val output = DataOutputStream(client.getOutputStream())
                            output.writeUTF(lines[1]); output.writeUTF(route.take(4_000)); output.flush()
                            check(DataInputStream(client.getInputStream()).readBoolean()) { "Activation was refused" }
                        }
                        return null
                    } catch (failure: Exception) { last = failure; Thread.sleep(100) }
                }
                throw IllegalStateException("已有客户端未响应，请稍后重试。", last)
            }
            try {
                val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
                val token = UUID.randomUUID().toString()
                Files.writeString(endpoint, "${server.localPort}\n$token\n${ProcessHandle.current().pid()}\n")
                return DesktopSingleInstance(channel, lock, server, endpoint, token).also {
                    current = it
                    if (route.isNotBlank()) it.activate(route)
                }
            } catch (failure: Throwable) { lock.release(); channel.close(); throw failure }
        }
    }
}

package com.bilipai.desktop.ui

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.atomic.AtomicBoolean

/** All playback hosts share one process-owned HANDLE. Windows drops it on process exit. */
internal object DesktopWindowsVideoWakeLease {
    enum class Mode { VIDEO, AUDIO }
    internal interface Request : AutoCloseable {
        fun setDisplay(required: Boolean)
        fun setSystem(required: Boolean)
    }

    /** Count logical borrowers, not HWNDs. Returning from PiP cannot clear another borrower's request. */
    internal class Pool(private val create: () -> Request) {
        private var request: Request? = null
        private var videos = 0
        private var users = 0

        @Synchronized fun acquire(mode: Mode, diagnostic: (Throwable) -> Unit): AutoCloseable {
            val current = request ?: create().also { request = it }
            var systemAdded = false
            try {
                if (users == 0) { current.setSystem(true); systemAdded = true }
                if (mode == Mode.VIDEO && videos == 0) current.setDisplay(true)
            } catch (failure: Throwable) {
                if (systemAdded) runCatching { current.setSystem(false) }.onFailure(diagnostic)
                if (users == 0) {
                    request = null
                    runCatching { current.close() }.onFailure(diagnostic)
                }
                throw failure
            }
            users++
            if (mode == Mode.VIDEO) videos++
            val closed = AtomicBoolean()
            return AutoCloseable {
                if (closed.compareAndSet(false, true)) synchronized(this) {
                    if (mode == Mode.VIDEO && --videos == 0)
                        runCatching { current.setDisplay(false) }.onFailure(diagnostic)
                    if (--users == 0) {
                        request = null
                        try { runCatching { current.setSystem(false) }.onFailure(diagnostic) }
                        finally { runCatching { current.close() }.onFailure(diagnostic) }
                    }
                }
            }
        }
    }

    private interface PowerApi : StdCallLibrary {
        fun PowerCreateRequest(context: Pointer): Pointer?
        fun PowerSetRequest(request: Pointer, type: Int): Int
        fun PowerClearRequest(request: Pointer, type: Int): Int
        fun CloseHandle(handle: Pointer): Int
    }
    private val api: PowerApi by lazy { Native.load("kernel32", PowerApi::class.java) }
    private val pool = Pool(::createRequest)

    fun acquire(stillOwned: () -> Boolean, diagnostic: (Throwable) -> Unit,
        mode: Mode = Mode.VIDEO): AutoCloseable {
        check(stillOwned()) { "Keep-awake entry retired" }
        if (!Platform.isWindows()) return AutoCloseable {}
        val lease = pool.acquire(mode, diagnostic)
        if (!stillOwned()) { lease.close(); error("Keep-awake entry retired") }
        return lease
    }

    private fun createRequest(): Request {
        val handle = Memory(32).use { context ->
            val text = "BiliPai 正在播放媒体".toByteArray(Charsets.UTF_16LE)
            Memory(text.size + 2L).use { reason ->
                reason.clear(); reason.write(0, text, 0, text.size)
                context.clear(); context.setInt(0, 0); context.setInt(4, 1); context.setPointer(8, reason)
                checkNotNull(api.PowerCreateRequest(context)).also {
                    check(Pointer.nativeValue(it) != -1L) { "Keep-awake request unavailable (${Native.getLastError()})" }
                }
            }
        }
        return object : Request {
            private fun change(type: Int, required: Boolean) {
                val result = if (required) api.PowerSetRequest(handle, type) else api.PowerClearRequest(handle, type)
                check(result != 0) { "Wake request $type/$required failed (${Native.getLastError()})" }
            }
            override fun setDisplay(required: Boolean) = change(0, required)
            override fun setSystem(required: Boolean) = change(1, required)
            override fun close() {
                check(api.CloseHandle(handle) != 0) { "Wake handle release failed (${Native.getLastError()})" }
            }
        }
    }
}

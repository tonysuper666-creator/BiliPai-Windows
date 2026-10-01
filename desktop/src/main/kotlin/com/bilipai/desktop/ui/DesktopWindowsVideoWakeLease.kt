package com.bilipai.desktop.ui

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.atomic.AtomicBoolean

/** One owned request HANDLE, independent of thread-local execution-state flags.
 * Taking both DisplayRequired and SystemRequired follows the Windows contract.
 * No polling worker, input injection or global power-policy change is needed. */
internal object DesktopWindowsVideoWakeLease {
    private interface PowerApi : StdCallLibrary {
        fun PowerCreateRequest(context: Pointer): Pointer?
        fun PowerSetRequest(request: Pointer, type: Int): Int
        fun PowerClearRequest(request: Pointer, type: Int): Int
        fun CloseHandle(handle: Pointer): Int
    }
    private val api: PowerApi by lazy { Native.load("kernel32", PowerApi::class.java) }

    fun acquire(stillOwned: () -> Boolean, diagnostic: (Throwable) -> Unit): AutoCloseable {
        check(stillOwned()) { "Keep-awake entry retired" }
        val handle = Memory(32).use { context -> // REASON_CONTEXT, x64 union size 24
            val text = "BiliPai 视频正在播放".toByteArray(Charsets.UTF_16LE)
            Memory(text.size + 2L).use { reason ->
                reason.clear(); reason.write(0, text, 0, text.size)
                context.clear(); context.setInt(0, 0); context.setInt(4, 1); context.setPointer(8, reason)
                checkNotNull(api.PowerCreateRequest(context)).also {
                    check(Pointer.nativeValue(it) != -1L) { "Keep-awake request unavailable (${Native.getLastError()})" }
                }
            }
        }
        var displaySet = false
        var systemSet = false
        try {
            check(stillOwned()) { "Keep-awake entry retired" }
            check(api.PowerSetRequest(handle, 0) != 0) { "Display wake request unavailable (${Native.getLastError()})" }
            displaySet = true
            check(api.PowerSetRequest(handle, 1) != 0) { "System wake request unavailable (${Native.getLastError()})" }
            systemSet = true
            check(stillOwned()) { "Keep-awake entry retired" }
        } catch (failure: Throwable) {
            if (systemSet) api.PowerClearRequest(handle, 1)
            if (displaySet) api.PowerClearRequest(handle, 0)
            api.CloseHandle(handle)
            throw failure
        }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                // Releasing one's own HANDLE remains valid after its source retires.
                try {
                    if (api.PowerClearRequest(handle, 1) == 0)
                        diagnostic(IllegalStateException("System wake release failed (${Native.getLastError()})"))
                    if (api.PowerClearRequest(handle, 0) == 0)
                        diagnostic(IllegalStateException("Display wake release failed (${Native.getLastError()})"))
                } finally {
                    if (api.CloseHandle(handle) == 0)
                        diagnostic(IllegalStateException("Wake request handle release failed (${Native.getLastError()})"))
                }
            }
        }
    }
}

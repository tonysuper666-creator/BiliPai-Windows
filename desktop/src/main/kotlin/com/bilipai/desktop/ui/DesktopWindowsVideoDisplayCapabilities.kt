package com.bilipai.desktop.ui

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.EventQueue
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface DesktopWindowsVideoDisplayCapability {
    data class Available(val hdrSupported: Boolean, val hdrUserEnabled: Boolean,
        val hdrActive: Boolean, val bitsPerChannel: Int) : DesktopWindowsVideoDisplayCapability
    data class Unavailable(val nativeCode: Int?, val reason: String) : DesktopWindowsVideoDisplayCapability
}

/** The monitor containing this actual Root HWND, not the default/first screen.
 * INFO_2 distinguishes HDR from WCG-only Advanced Color. Older API/driver/remote
 * sessions report unavailable; they never become a fabricated HDR capability.
 * Window events refresh metadata without a timer, second window or MPV core. */
internal class DesktopWindowsVideoDisplayCapabilities(private val root: Window) : AutoCloseable {
    private interface DisplayApi : StdCallLibrary {
        fun MonitorFromWindow(window: Pointer, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer, info: Pointer): Int
        fun GetDisplayConfigBufferSizes(flags: Int, paths: IntByReference, modes: IntByReference): Int
        fun QueryDisplayConfig(flags: Int, paths: IntByReference, pathBuffer: Pointer,
            modes: IntByReference, modeBuffer: Pointer, topology: Pointer?): Int
        fun DisplayConfigGetDeviceInfo(packet: Pointer): Int
    }
    private val api: DisplayApi by lazy { Native.load("user32", DisplayApi::class.java) }
    private val closed = AtomicBoolean()
    private val mutable = MutableStateFlow<DesktopWindowsVideoDisplayCapability>(
        DesktopWindowsVideoDisplayCapability.Unavailable(null, "Root window has not been inspected"))
    val state: StateFlow<DesktopWindowsVideoDisplayCapability> = mutable.asStateFlow()
    private val listener = object : ComponentAdapter() {
        override fun componentMoved(event: ComponentEvent) = refresh()
        override fun componentShown(event: ComponentEvent) = refresh()
    }
    init { onEdt { root.addComponentListener(listener); refresh() } }

    private fun onEdt(action: () -> Unit) {
        if (EventQueue.isDispatchThread()) action()
        else { val task = FutureTask(action); EventQueue.invokeAndWait(task); task.get() }
    }

    fun refresh() {
        check(EventQueue.isDispatchThread())
        if (closed.get()) return
        mutable.value = try { inspect() }
        catch (_: UnsatisfiedLinkError) {
            DesktopWindowsVideoDisplayCapability.Unavailable(null, "Display configuration API unavailable")
        } catch (_: IllegalStateException) {
            DesktopWindowsVideoDisplayCapability.Unavailable(null, "Display configuration contract unavailable")
        }
    }

    private fun inspect(): DesktopWindowsVideoDisplayCapability {
        if (!root.isDisplayable) return DesktopWindowsVideoDisplayCapability.Unavailable(null, "Root HWND unavailable")
        val monitor = api.MonitorFromWindow(Native.getWindowPointer(root), 2)
            ?: return DesktopWindowsVideoDisplayCapability.Unavailable(null, "Root monitor unavailable")
        val device = Memory(104).use { info -> // MONITORINFOEXW: 40-byte prefix + WCHAR[32]
            info.clear(); info.setInt(0, 104)
            if (api.GetMonitorInfoW(monitor, info) == 0)
                return DesktopWindowsVideoDisplayCapability.Unavailable(Native.getLastError(), "Root monitor info unavailable")
            info.getWideString(40)
        }
        repeat(3) {
            val pathCount = IntByReference(); val modeCount = IntByReference()
            val sizes = api.GetDisplayConfigBufferSizes(2, pathCount, modeCount)
            if (sizes != 0) return DesktopWindowsVideoDisplayCapability.Unavailable(sizes, "Active display paths unavailable")
            check(pathCount.value in 1..128 && modeCount.value in 0..512)
            Memory(pathCount.value * 72L).use { paths -> // DISPLAYCONFIG_PATH_INFO
                Memory((modeCount.value * 64L).coerceAtLeast(64L)).use { modes -> // DISPLAYCONFIG_MODE_INFO
                    val queried = api.QueryDisplayConfig(2, pathCount, paths, modeCount, modes, null)
                    if (queried == 122) return@repeat // Display topology changed; bounded retry.
                    if (queried != 0) return DesktopWindowsVideoDisplayCapability.Unavailable(queried, "Active display configuration unavailable")
                    repeat(pathCount.value) { index ->
                        val path = paths.share(index * 72L)
                        val matches = Memory(84).use { name -> // DISPLAYCONFIG_SOURCE_DEVICE_NAME
                            header(name, 1, 84, path, 0)
                            api.DisplayConfigGetDeviceInfo(name) == 0 && name.getWideString(20).equals(device, true)
                        }
                        if (matches) {
                            return Memory(36).use { color -> // DISPLAYCONFIG_GET_ADVANCED_COLOR_INFO_2
                                header(color, 15, 36, path, 20)
                                val status = api.DisplayConfigGetDeviceInfo(color)
                                if (status != 0) DesktopWindowsVideoDisplayCapability.Unavailable(status, "HDR-specific display capability unavailable")
                                else {
                                    val flags = color.getInt(20)
                                    DesktopWindowsVideoDisplayCapability.Available(
                                        hdrSupported = flags and 0x10 != 0,
                                        hdrUserEnabled = flags and 0x20 != 0,
                                        hdrActive = color.getInt(32) == 2,
                                        bitsPerChannel = color.getInt(28),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        return DesktopWindowsVideoDisplayCapability.Unavailable(null, "Root monitor path changed or was not found")
    }

    private fun header(packet: Memory, type: Int, size: Int, path: Pointer, infoOffset: Long) {
        packet.clear(); packet.setInt(0, type); packet.setInt(4, size)
        // LUID has 4-byte alignment; copy its bits, then the source/target ID.
        packet.setLong(8, path.getLong(infoOffset)); packet.setInt(16, path.getInt(infoOffset + 8))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) onEdt { root.removeComponentListener(listener) }
    }
}

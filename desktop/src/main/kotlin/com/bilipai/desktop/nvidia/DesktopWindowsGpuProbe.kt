package com.bilipai.desktop.nvidia

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary

// Read-only Windows probes for the NVIDIA auto-detection: the adapter identity, the power state and
// the foreground window. JNA core only (no jna-platform), the same style as the display state reader
// of the player package. No device is created and no setting is changed; every failure is "unknown",
// never an exception into the caller, and unknown never counts as a pause reason.
//
// The adapter name request is DISPLAYCONFIG_ADAPTER_NAME: a 20 byte header followed by the
// 256 byte WCHAR adapterDevicePath, 276 bytes in all.

private fun windowsProbeGateOpen(): Boolean =
    Platform.isWindows() && Native.POINTER_SIZE == 8 && Native.WCHAR_SIZE == 2

/**
 * Maps a DXGI adapter LUID to its PnP device path, which is the only thing that tells a physical
 * card from a virtual twin carrying its exact name, memory and driver. A path that starts with the
 * PCI NVIDIA prefix and carries the same device id is physical (see classifyNvidiaAdapterPath).
 */
internal object DesktopWindowsAdapterIdentity {
    /** DISPLAYCONFIG_DEVICE_INFO_GET_ADAPTER_NAME. */
    const val DEVICE_INFO_GET_ADAPTER_NAME: Int = 4

    /** Header (type, size, LUID, id) is 20 bytes; the path is a WCHAR[128]. */
    const val ADAPTER_NAME_PACKET_SIZE: Int = 276
    const val ADAPTER_NAME_LUID_LOW_OFFSET: Long = 8L
    const val ADAPTER_NAME_LUID_HIGH_OFFSET: Long = 12L
    const val ADAPTER_NAME_PATH_OFFSET: Long = 20L
    const val ADAPTER_NAME_PATH_BYTES: Int = 256

    private interface IdentityApi : StdCallLibrary {
        fun DisplayConfigGetDeviceInfo(packet: Pointer): Int
    }

    private val api: IdentityApi by lazy { Native.load("user32", IdentityApi::class.java) }

    /** Fills the request header for one LUID. The caller clears nothing: this clears the packet. */
    internal fun encodeAdapterNameRequest(packet: Memory, luidHigh: Int, luidLow: Int) {
        packet.clear()
        packet.setInt(0, DEVICE_INFO_GET_ADAPTER_NAME)
        packet.setInt(4, ADAPTER_NAME_PACKET_SIZE)
        packet.setInt(ADAPTER_NAME_LUID_LOW_OFFSET, luidLow)
        packet.setInt(ADAPTER_NAME_LUID_HIGH_OFFSET, luidHigh)
        packet.setInt(16, 0)
    }

    /** The path text up to the first NUL, or null when it is empty. Pure over the packet memory. */
    internal fun decodeAdapterNamePath(packet: Pointer): String? {
        val text = String(packet.getByteArray(ADAPTER_NAME_PATH_OFFSET, ADAPTER_NAME_PATH_BYTES), Charsets.UTF_16LE)
            .substringBefore('\u0000')
        return if (text.isEmpty()) null else text
    }

    /** The PnP device path of the adapter with this LUID, or null when Windows could not say. */
    fun adapterDevicePath(luidHigh: Int, luidLow: Int): String? {
        if (!Platform.isWindows() || Native.POINTER_SIZE != 8 || Native.WCHAR_SIZE != 2) return null
        return try {
            Memory(ADAPTER_NAME_PACKET_SIZE.toLong()).use { packet ->
                encodeAdapterNameRequest(packet, luidHigh, luidLow)
                if (api.DisplayConfigGetDeviceInfo(packet) != 0) return@use null
                decodeAdapterNamePath(packet)
            }
        } catch (_: LinkageError) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** PHYSICAL, VIRTUAL, or UNKNOWN when the query itself failed. */
    fun physicalVerdict(luidHigh: Int, luidLow: Int, dxgiDeviceId: Int): NvidiaPhysicalVerdict =
        classifyNvidiaAdapterPath(adapterDevicePath(luidHigh, luidLow), dxgiDeviceId)
}

/**
 * Battery state from kernel32 GetSystemPowerStatus. SYSTEM_POWER_STATUS is four BYTE (AC line status,
 * battery flag, battery percent, system status flag) followed by two DWORD: 12 bytes. The decision is the
 * pure desktopNvidiaOnBattery; this object only reads the two bytes it needs.
 */
internal object DesktopWindowsPowerState {
    const val STATUS_BYTES: Int = 12
    const val AC_LINE_STATUS_OFFSET: Long = 0L
    const val BATTERY_FLAG_OFFSET: Long = 1L

    private interface PowerApi : StdCallLibrary {
        fun GetSystemPowerStatus(status: Pointer): Int
    }

    private val api: PowerApi by lazy { Native.load("kernel32", PowerApi::class.java) }

    /** True only when the machine runs on battery (AC offline and a battery present). Every failure is false. */
    fun onBattery(): Boolean {
        if (!windowsProbeGateOpen()) return false
        return try {
            Memory(STATUS_BYTES.toLong()).use { status ->
                status.clear()
                if (api.GetSystemPowerStatus(status) == 0) {
                    false
                } else {
                    desktopNvidiaOnBattery(
                        status.getByte(AC_LINE_STATUS_OFFSET).toInt(),
                        status.getByte(BATTERY_FLAG_OFFSET).toInt(),
                    )
                }
            }
        } catch (_: LinkageError) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }
}

/**
 * What the foreground window says about a game: the shell notification state, the window's process,
 * class and rectangle, and the rectangle of its monitor. Read-only. The facts go through the pure
 * desktopNvidiaForegroundSnapshot, and the application's own process id is compared by the pure game rule.
 */
internal object DesktopWindowsForegroundBusy {
    /** MONITOR_DEFAULTTONEAREST. */
    const val MONITOR_DEFAULT_TO_NEAREST: Int = 2

    /** MONITORINFO: cbSize at 0, rcMonitor at 4 (four LONG), rcWork at 20, dwFlags at 36. */
    const val MONITOR_INFO_BYTES: Int = 40
    const val MONITOR_INFO_RECT_OFFSET: Long = 4L

    /** RECT: left, top, right, bottom as four LONG. */
    const val RECT_BYTES: Int = 16
    const val CLASS_NAME_CHARS: Int = 64

    private interface ForegroundApi : StdCallLibrary {
        fun GetForegroundWindow(): Pointer?
        fun GetShellWindow(): Pointer?
        fun GetDesktopWindow(): Pointer?
        fun GetWindowThreadProcessId(window: Pointer, processId: IntByReference): Int
        fun GetClassNameW(window: Pointer, buffer: Pointer, maxCount: Int): Int
        fun GetWindowRect(window: Pointer, rect: Pointer): Int
        fun MonitorFromWindow(window: Pointer, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer, info: Pointer): Int
    }

    private interface ShellApi : StdCallLibrary {
        fun SHQueryUserNotificationState(state: IntByReference): Int
    }

    private val windows: ForegroundApi by lazy { Native.load("user32", ForegroundApi::class.java) }
    private val shell: ShellApi by lazy { Native.load("shell32", ShellApi::class.java) }

    /** One read of the foreground window, or null when it could not be read. Unknown is never a game. */
    fun snapshot(ownProcessId: Int): DesktopNvidiaForegroundSnapshot? {
        if (!windowsProbeGateOpen()) return null
        return try {
            desktopNvidiaForegroundSnapshot(readFacts(), ownProcessId)
        } catch (_: LinkageError) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun readFacts(): DesktopNvidiaForegroundFacts {
        val state = notificationState()
        val window = windows.GetForegroundWindow()
            ?: return DesktopNvidiaForegroundFacts(null, state, false, null, null, null)
        val processId = IntByReference()
        val processOwner = windows.GetWindowThreadProcessId(window, processId)
        val foregroundProcessId: Int? = if (processOwner != 0) processId.value else null
        val windowValue = Pointer.nativeValue(window)
        val shellWindow = windows.GetShellWindow()
        val desktopWindow = windows.GetDesktopWindow()
        val shellHandle = (shellWindow != null && Pointer.nativeValue(shellWindow) == windowValue) ||
            (desktopWindow != null && Pointer.nativeValue(desktopWindow) == windowValue)
        return DesktopNvidiaForegroundFacts(
            foregroundProcessId = foregroundProcessId,
            notificationState = state,
            windowIsShellOrDesktopHandle = shellHandle,
            windowClassName = windowClassName(window),
            windowRect = windowRect(window),
            monitorRect = monitorRect(window),
        )
    }

    /** SHQueryUserNotificationState: S_OK gives the state, anything else is unknown. */
    private fun notificationState(): Int? = try {
        val state = IntByReference()
        if (shell.SHQueryUserNotificationState(state) == 0) state.value else null
    } catch (_: LinkageError) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun windowClassName(window: Pointer): String? =
        Memory((CLASS_NAME_CHARS * 2).toLong()).use { buffer ->
            buffer.clear()
            val length = windows.GetClassNameW(window, buffer, CLASS_NAME_CHARS)
            if (length <= 0) {
                null
            } else {
                String(buffer.getByteArray(0L, length.coerceAtMost(CLASS_NAME_CHARS - 1) * 2), Charsets.UTF_16LE)
            }
        }

    private fun windowRect(window: Pointer): DesktopNvidiaScreenRect? =
        Memory(RECT_BYTES.toLong()).use { rect ->
            rect.clear()
            if (windows.GetWindowRect(window, rect) == 0) {
                null
            } else {
                DesktopNvidiaScreenRect(rect.getInt(0L), rect.getInt(4L), rect.getInt(8L), rect.getInt(12L))
            }
        }

    private fun monitorRect(window: Pointer): DesktopNvidiaScreenRect? {
        val monitor = windows.MonitorFromWindow(window, MONITOR_DEFAULT_TO_NEAREST) ?: return null
        return Memory(MONITOR_INFO_BYTES.toLong()).use { info ->
            info.clear()
            info.setInt(0L, MONITOR_INFO_BYTES)
            if (windows.GetMonitorInfoW(monitor, info) == 0) {
                null
            } else {
                DesktopNvidiaScreenRect(
                    info.getInt(MONITOR_INFO_RECT_OFFSET),
                    info.getInt(MONITOR_INFO_RECT_OFFSET + 4L),
                    info.getInt(MONITOR_INFO_RECT_OFFSET + 8L),
                    info.getInt(MONITOR_INFO_RECT_OFFSET + 12L),
                )
            }
        }
    }
}

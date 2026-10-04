package com.bilipai.desktop.player

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary

/** A read-only observation of the monitor hosting the actual native video HWND. */
data class WindowsHdrDisplayState(
    val known: Boolean = false,
    val hdrSupported: Boolean = false,
    val hdrUserEnabled: Boolean = false,
    val hdrActive: Boolean = false,
    val hdrEnabled: Boolean = false,
    val bitsPerChannel: Int = 0,
    val statusText: String = "等待显示器 HDR 状态",
    val nativeCode: Int? = null,
)

/** INFO_2 distinguishes user-enabled HDR from wide-color-only Advanced Color. */
internal fun decodeWindowsHdrDisplay(flags: Int, activeColorMode: Int, bitsPerChannel: Int): WindowsHdrDisplayState {
    val supported = flags and 0x10 != 0
    val userEnabled = flags and 0x20 != 0
    val active = flags and 0x2 != 0 && activeColorMode == 2
    val enabled = supported && userEnabled && active
    return WindowsHdrDisplayState(true, supported, userEnabled, active, enabled, bitsPerChannel,
        when {
            !supported -> "当前显示器未报告 HDR 支持"
            !userEnabled -> "当前显示器的 Windows HDR 未开启"
            !active -> "当前显示器尚未进入 HDR 输出模式"
            else -> "当前显示器已开启 Windows HDR"
        })
}

/** No display/driver setting is changed. Unsupported APIs conservatively keep SDR output. */
internal object DesktopWindowsHdrDisplay {
    private interface DisplayApi : StdCallLibrary {
        fun MonitorFromWindow(window: Pointer, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer, info: Pointer): Int
        fun GetDisplayConfigBufferSizes(flags: Int, paths: IntByReference, modes: IntByReference): Int
        fun QueryDisplayConfig(flags: Int, paths: IntByReference, pathBuffer: Pointer,
            modes: IntByReference, modeBuffer: Pointer, topology: Pointer?): Int
        fun DisplayConfigGetDeviceInfo(packet: Pointer): Int
    }
    private val api: DisplayApi by lazy { Native.load("user32", DisplayApi::class.java) }
    private fun unavailable(text: String, code: Int? = null) = WindowsHdrDisplayState(statusText = text, nativeCode = code)

    fun query(windowId: Long): WindowsHdrDisplayState {
        if (!Platform.isWindows() || windowId == 0L) return unavailable("尚无可检查的 Windows 视频窗口")
        return try { inspect(Pointer(windowId)) }
        catch (_: LinkageError) { unavailable("当前系统无法查询显示器 HDR 状态") }
        catch (_: RuntimeException) { unavailable("当前显示器 HDR 状态不可用") }
    }

    private fun inspect(window: Pointer): WindowsHdrDisplayState {
        val monitor = api.MonitorFromWindow(window, 2) ?: return unavailable("无法确定视频窗口所在显示器")
        val device = Memory(104).use { info -> // MONITORINFOEXW
            info.clear(); info.setInt(0, 104)
            if (api.GetMonitorInfoW(monitor, info) == 0) return unavailable("无法读取视频显示器", Native.getLastError())
            wideName(info, 40)
        }
        repeat(3) {
            val pathCount = IntByReference(); val modeCount = IntByReference()
            val sizes = api.GetDisplayConfigBufferSizes(2, pathCount, modeCount)
            if (sizes != 0) return unavailable("无法查询活动显示器", sizes)
            val pathCapacity = pathCount.value; val modeCapacity = modeCount.value
            check(pathCapacity in 1..128 && modeCapacity in 0..512)
            Memory(pathCapacity * 72L).use { paths -> // DISPLAYCONFIG_PATH_INFO
                Memory((modeCapacity * 64L).coerceAtLeast(64L)).use { modes -> // DISPLAYCONFIG_MODE_INFO
                    val queried = api.QueryDisplayConfig(2, pathCount, paths, modeCount, modes, null)
                    if (queried == 122) return@repeat // Topology changed between sizing and query.
                    if (queried != 0) return unavailable("无法读取活动显示配置", queried)
                    check(pathCount.value in 0..pathCapacity && modeCount.value in 0..modeCapacity)
                    val matched = mutableListOf<WindowsHdrDisplayState>()
                    repeat(pathCount.value) { index ->
                        val path = paths.share(index * 72L)
                        val matches = Memory(84).use { name -> // DISPLAYCONFIG_SOURCE_DEVICE_NAME
                            header(name, 1, 84, path, 0)
                            api.DisplayConfigGetDeviceInfo(name) == 0 && wideName(name, 20).equals(device, true)
                        }
                        if (matches) matched += Memory(36).use { color -> // GET_ADVANCED_COLOR_INFO_2
                            header(color, 15, 36, path, 20)
                            val status = api.DisplayConfigGetDeviceInfo(color)
                            if (status != 0) unavailable("当前系统或显示器未提供明确的 HDR 状态", status)
                            else decodeWindowsHdrDisplay(color.getInt(20), color.getInt(32), color.getInt(28))
                        }
                    }
                    if (matched.isNotEmpty()) {
                        // A cloned source can have more than one physical target. Do not
                        // send synthetic HDR unless every target of that source supports it.
                        return matched.firstOrNull { !it.hdrEnabled } ?: matched.first()
                    }
                }
            }
        }
        return unavailable("视频显示器正在切换或尚未就绪")
    }

    private fun wideName(memory: Memory, offset: Long): String =
        String(memory.getByteArray(offset, 64), Charsets.UTF_16LE).substringBefore('\u0000')

    private fun header(packet: Memory, type: Int, size: Int, path: Pointer, infoOffset: Long) {
        packet.clear(); packet.setInt(0, type); packet.setInt(4, size)
        packet.setLong(8, path.getLong(infoOffset)); packet.setInt(16, path.getInt(infoOffset + 8))
    }
}

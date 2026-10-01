package com.bilipai.desktop.ui

import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary

/** A fresh native OS observation; original nullable battery semantics are retained. */
internal class DesktopOriginalPlayerControlsWindows(
    private val isCurrent: () -> Boolean,
    private val reportCapabilityUnavailable: (Throwable) -> Unit,
) : DesktopOriginalPlayerControlsPlatform {
    override fun readBatteryPercent(): Int? {
        if (!isCurrent()) return null
        return try {
            val status = DesktopPlayerSystemPowerStatus()
            check(DesktopPlayerPowerKernel32.api.GetSystemPowerStatus(status) != 0) { "Windows GetSystemPowerStatus failed" }
            if (!isCurrent()) return null
            val flags = status.batteryFlag.toInt() and 255
            val percent = status.batteryLifePercent.toInt() and 255
            if (flags and 128 != 0 || percent == 255) null else percent.coerceIn(0,100)
        } catch (failure: Exception) {
            if (isCurrent()) reportCapabilityUnavailable(failure)
            null
        }
    }
}

/** WinBase.h SYSTEM_POWER_STATUS: four BYTE then two DWORD, size12, no handle ownership. */
@Structure.FieldOrder("acLineStatus", "batteryFlag", "batteryLifePercent", "reserved1", "batteryLifeTime", "batteryFullLifeTime")
internal class DesktopPlayerSystemPowerStatus : Structure() {
    @JvmField var acLineStatus: Byte = 0
    @JvmField var batteryFlag: Byte = 0
    @JvmField var batteryLifePercent: Byte = 0
    @JvmField var reserved1: Byte = 0
    @JvmField var batteryLifeTime: Int = 0
    @JvmField var batteryFullLifeTime: Int = 0
}
private interface DesktopPlayerPowerKernelApi : StdCallLibrary {
    fun GetSystemPowerStatus(status: DesktopPlayerSystemPowerStatus): Int
}
private object DesktopPlayerPowerKernel32 {
    val api: DesktopPlayerPowerKernelApi by lazy { Native.load("kernel32", DesktopPlayerPowerKernelApi::class.java) }
}

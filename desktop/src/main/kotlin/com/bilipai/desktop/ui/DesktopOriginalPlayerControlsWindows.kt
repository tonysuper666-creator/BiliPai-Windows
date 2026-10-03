package com.bilipai.desktop.ui

import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary

/** A fresh native OS observation; original nullable battery semantics are retained. */
internal class DesktopOriginalPlayerControlsWindows(
    private val isCurrent: () -> Boolean,
    private val reportCapabilityUnavailable: (Throwable) -> Unit,
) : DesktopOriginalPlayerControlsPlatform {
    override fun readAmbientEnvironment(): com.android.purebilibili.feature.video.ambient.AmbientEnvironment {
        if (!isCurrent()) return com.android.purebilibili.feature.video.ambient.AmbientEnvironment()
        return try {
            val status = DesktopPlayerSystemPowerStatus()
            check(DesktopPlayerPowerKernel32.api.GetSystemPowerStatus(status) != 0)
            if (!isCurrent()) return com.android.purebilibili.feature.video.ambient.AmbientEnvironment()
            val flags = status.batteryFlag.toInt() and 255
            val percent = status.batteryLifePercent.toInt() and 255
            val lowBattery = flags and 128 == 0 && percent != 255 && percent <= 15
            // SYSTEM_POWER_STATUS.SystemStatusFlag is the original struct's fourth BYTE.
            // Windows has no equivalent portable Android severe-thermal listener.
            com.android.purebilibili.feature.video.ambient.AmbientEnvironment(status.reserved1.toInt() and 255 == 1 || lowBattery, false)
        } catch (failure: Exception) {
            if (isCurrent()) reportCapabilityUnavailable(failure)
            com.android.purebilibili.feature.video.ambient.AmbientEnvironment()
        }
    }
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

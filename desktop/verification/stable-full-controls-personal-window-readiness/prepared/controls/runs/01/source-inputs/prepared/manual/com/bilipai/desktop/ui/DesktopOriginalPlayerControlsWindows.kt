package com.bilipai.desktop.ui

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase

/** A fresh native OS observation; original nullable battery semantics are retained. */
internal class DesktopOriginalPlayerControlsWindows(
    private val isCurrent: () -> Boolean,
    private val reportCapabilityUnavailable: (Throwable) -> Unit,
) : DesktopOriginalPlayerControlsPlatform {
    override fun readBatteryPercent(): Int? {
        if (!isCurrent()) return null
        return try {
            val status = WinBase.SYSTEM_POWER_STATUS()
            check(Kernel32.INSTANCE.GetSystemPowerStatus(status)) { "Windows GetSystemPowerStatus failed" }
            if (!isCurrent()) return null
            val flags = status.BatteryFlag.toInt() and 255
            val percent = status.BatteryLifePercent.toInt() and 255
            if (flags and 128 != 0 || percent == 255) null else percent.coerceIn(0,100)
        } catch (failure: Exception) {
            if (isCurrent()) reportCapabilityUnavailable(failure)
            null
        }
    }
}

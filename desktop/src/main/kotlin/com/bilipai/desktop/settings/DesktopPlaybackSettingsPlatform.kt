package com.bilipai.desktop.settings

import androidx.compose.runtime.staticCompositionLocalOf
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import kotlinx.coroutines.CancellationException

/** Uses the mounted Root's existing account readbacks and setting-change callback. */
internal class DesktopPlaybackSettingsPlatform(
    private val owns: () -> Boolean,
    private val loggedIn: () -> Boolean,
    private val vip: () -> Boolean,
    private val settingChange: (String, String) -> Unit,
) {
    private var lastLoggedIn: Boolean? = null
    private var lastVip: Boolean? = null

    fun isPlaybackLoggedIn(): Boolean {
        if (owns()) return loggedIn().also { lastLoggedIn = it }
        return lastLoggedIn ?: throw CancellationException("Original settings owner retired before account was read")
    }

    fun isPlaybackVip(): Boolean {
        if (owns()) return vip().also { lastVip = it }
        return lastVip ?: throw CancellationException("Original settings owner retired before VIP was read")
    }

    fun logSettingChange(name: String, value: String) {
        if (owns()) settingChange(name, value)
    }

}

internal val LocalDesktopPlaybackSettingsPlatform = staticCompositionLocalOf<DesktopPlaybackSettingsPlatform> {
    throw UnsupportedDesktopCapability("Playback settings platform", "the mounted Root must provide its account and logging callbacks")
}

package com.bilipai.desktop.appearance

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Windows AppsUseLightTheme is the app-theme preference, independent of the taskbar theme. */
@Composable
internal fun rememberDesktopSystemDarkTheme(): Boolean {
    val fallback = isSystemInDarkTheme()
    if (!System.getProperty("os.name").startsWith("Windows")) return fallback
    var dark by remember { mutableStateOf(readWindowsDarkTheme()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            dark = withContext(Dispatchers.IO) { readWindowsDarkTheme() }
            delay(250)
        }
    }
    return dark
}

private fun readWindowsDarkTheme(): Boolean {
    val key = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"
    return if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, key, "AppsUseLightTheme"))
        Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, key, "AppsUseLightTheme") == 0 else false
}

package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** The original screen reads the same snapshot-backed Root Window state.
 * It owns no second fullscreen flag and never forces phone orientation. */
internal class DesktopOfflineFullscreenValue(private val window: DesktopOfflineWindowEffects) : ReadWriteProperty<Any?, Boolean> {
    var value: Boolean
        get() = window.fullscreen
        set(value) { window.applyFullscreen(value) }
    override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) { this.value = value }
}

@Composable
internal fun rememberDesktopOfflineFullscreen(window: DesktopOfflineWindowEffects): DesktopOfflineFullscreenValue =
    remember(window) { DesktopOfflineFullscreenValue(window) }

package com.android.purebilibili.core.store.player
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPreferenceKey
import com.android.purebilibili.core.store.DEFAULT_LONG_PRESS_SPEED
import com.android.purebilibili.core.store.normalizeLongPressSpeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
private fun floatPreferencesKey(name:String)=DesktopPreferenceKey(name) { value -> (value as? JsonPrimitive)?.floatOrNull }
private val longPressSpeedPreferenceKey = floatPreferencesKey("long_press_speed")
object DesktopOriginalLongPressSpeedSettings {
fun getLongPressSpeed(context: DesktopPluginContext): Flow<Float> =
    context.store.snapshot("settings").map { preferences ->
        normalizeLongPressSpeed(preferences[longPressSpeedPreferenceKey] ?: DEFAULT_LONG_PRESS_SPEED)
    }
}

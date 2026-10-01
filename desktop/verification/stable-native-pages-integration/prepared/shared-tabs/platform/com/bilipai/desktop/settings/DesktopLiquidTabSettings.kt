package com.bilipai.desktop.settings

import androidx.compose.runtime.*
import com.android.purebilibili.core.store.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.*

/** Read-only subset view; source keys/defaults come from original getHomeSettings.
 * The caller supplies Root's one applicationPluginStore. No setter, MID, account,
 * HomeSettings, SettingsManager, preferences cache or second store is created. */
internal data class DesktopLiquidTabHomePreferences(
    val isBottomBarLiquidGlassEnabled:Boolean,
    val androidNativeLiquidGlassEnabled:Boolean,
    val liquidGlassProgress:Float,
    val liquidGlassAdvancedSettings:LiquidGlassAdvancedSettings,
    val liquidGlassReadabilityMode:LiquidGlassReadabilityMode,
    val navigationIconCrossScaleEnabled:Boolean,
    val bottomBarLiquidGlassPreset:BottomBarLiquidGlassPreset=BottomBarLiquidGlassPreset.BILIPAI_TUNED,
)

internal class DesktopLiquidTabSettings(store:DesktopPluginStore) {
    val homeSettings=store.snapshot("settings").map{snapshot->
        decodeDesktopLiquidTabHomePreferences(snapshot.valuesForLiquidTabs())
    }.distinctUntilChanged()
}

internal val LocalDesktopLiquidTabSettings=staticCompositionLocalOf<DesktopLiquidTabSettings> {
    error("Provide Root's shared DesktopLiquidTabSettings from applicationPluginStore")
}

// DesktopPreferenceSnapshot is read through its original typed-key API below;
// it is not serialized or copied into a second settings authority.
private fun com.bilipai.desktop.plugins.DesktopPreferenceSnapshot.valuesForLiquidTabs():DesktopLiquidTabHomePreferences {
    fun bool(key:String)=this[com.bilipai.desktop.plugins.DesktopPreferenceKey<Boolean>(key){(it as? JsonPrimitive)?.booleanOrNull}]
    fun int(key:String)=this[com.bilipai.desktop.plugins.DesktopPreferenceKey<Int>(key){(it as? JsonPrimitive)?.intOrNull}]
    fun float(key:String)=this[com.bilipai.desktop.plugins.DesktopPreferenceKey<Float>(key){(it as? JsonPrimitive)?.floatOrNull}]
    val legacyLiquidGlassEnabled=bool("liquid_glass_enabled")?:false
    val liquidGlassProgress=resolveStoredLiquidGlassProgress(
        progress=float("liquid_glass_material_progress_v2"),legacyModeValue=int("liquid_glass_mode"),
        legacyStrength=float("liquid_glass_strength"),legacyStyleValue=int("liquid_glass_style"))
    val liquidGlassReadabilityMode=LiquidGlassReadabilityMode.fromValue(int("liquid_glass_readability_mode")?:LiquidGlassReadabilityMode.STABLE.value)
    val liquidGlassAdvancedSettings=resolveLiquidGlassAdvancedSettings(
        presetValue=int("liquid_glass_advanced_preset"),progressiveBlurRadius=float("liquid_glass_progressive_blur_radius"),
        progressiveBlurExtent=float("liquid_glass_progressive_blur_extent"),progressiveBlurCurve=float("liquid_glass_progressive_blur_curve"),
        contentReadability=float("liquid_glass_content_readability"),chromaticAberration=float("liquid_glass_chromatic_aberration"),contentDistortion=float("liquid_glass_content_distortion"))
    return DesktopLiquidTabHomePreferences(
        isBottomBarLiquidGlassEnabled=bool("bottom_bar_liquid_glass_enabled")?:legacyLiquidGlassEnabled,
        androidNativeLiquidGlassEnabled=bool("android_native_liquid_glass_enabled")?:false,
        liquidGlassProgress=liquidGlassProgress,liquidGlassAdvancedSettings=liquidGlassAdvancedSettings,
        liquidGlassReadabilityMode=liquidGlassReadabilityMode,navigationIconCrossScaleEnabled=bool("navigation_icon_cross_scale_enabled")?:true)
}
private fun decodeDesktopLiquidTabHomePreferences(value:DesktopLiquidTabHomePreferences)=value

package com.bilipai.desktop.settings
import com.android.purebilibili.core.store.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.plugins.DesktopPreferenceKey
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import com.bilipai.desktop.settings.dynamicIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
/** Original getHomeSettings 10-field projection. Root supplies its sole application store context. */
data class DesktopOriginalFrostedHomePreferences(
    val navigationIconCrossScaleEnabled:Boolean,
    val isBottomBarSearchEnabled:Boolean,
    val linkedDockMergeOnScrollEnabled:Boolean,
    val bottomBarSearchAutoExpandMode:BottomBarSearchAutoExpandMode,
    val bottomBarSearchLayoutMode:BottomBarSearchLayoutMode,
    val androidNativeLiquidGlassEnabled:Boolean,
    val liquidGlassProgress:Float,
    val liquidGlassReadabilityMode:LiquidGlassReadabilityMode,
    val liquidGlassAdvancedSettings:LiquidGlassAdvancedSettings,
    val bottomBarLiquidGlassPreset:BottomBarLiquidGlassPreset,
)
private fun floatPreferencesKey(name:String)=DesktopPreferenceKey(name){(it as? JsonPrimitive)?.floatOrNull}
internal class DesktopOriginalFrostedSettings(context:DesktopPluginContext) {
    val preferences=context.settingsDataStore.data.map { decodeOriginalFrostedHomePreferences(it) }.distinctUntilChanged()
}
    private val KEY_NAVIGATION_ICON_CROSS_SCALE_ENABLED =
        booleanPreferencesKey("navigation_icon_cross_scale_enabled")
    //  [新增] 底栏显示模式 (0=图标+文字, 1=仅图标, 2=仅文字)
    private val KEY_BOTTOM_BAR_SEARCH_ENABLED = booleanPreferencesKey("bottom_bar_search_enabled")
    private val KEY_LINKED_DOCK_MERGE_ON_SCROLL_ENABLED =
        booleanPreferencesKey("linked_dock_merge_on_scroll_enabled")
    private val KEY_BOTTOM_BAR_SEARCH_AUTO_EXPAND_MODE =
        intPreferencesKey("bottom_bar_search_auto_expand_mode")
    private val KEY_BOTTOM_BAR_SEARCH_LAYOUT_MODE =
        intPreferencesKey("bottom_bar_search_layout_mode")
    private val KEY_ANDROID_NATIVE_LIQUID_GLASS_ENABLED =
        booleanPreferencesKey("android_native_liquid_glass_enabled")
    private val KEY_LIQUID_GLASS_MODE = intPreferencesKey("liquid_glass_mode")
    private val KEY_LIQUID_GLASS_STRENGTH = floatPreferencesKey("liquid_glass_strength")
    // V2 stores the continuous material position; reads migrate the complete legacy selection
    // when this key is absent so upgrades preserve the user's clear/balanced/frosted intent.
    private val KEY_LIQUID_GLASS_PROGRESS = floatPreferencesKey("liquid_glass_material_progress_v2")
    private val KEY_LIQUID_GLASS_ADVANCED_PRESET =
        intPreferencesKey("liquid_glass_advanced_preset")
    private val KEY_LIQUID_GLASS_PROGRESSIVE_BLUR_RADIUS =
        floatPreferencesKey("liquid_glass_progressive_blur_radius")
    private val KEY_LIQUID_GLASS_PROGRESSIVE_BLUR_EXTENT =
        floatPreferencesKey("liquid_glass_progressive_blur_extent")
    private val KEY_LIQUID_GLASS_PROGRESSIVE_BLUR_CURVE =
        floatPreferencesKey("liquid_glass_progressive_blur_curve")
    private val KEY_LIQUID_GLASS_CONTENT_READABILITY =
        floatPreferencesKey("liquid_glass_content_readability")
    private val KEY_LIQUID_GLASS_CHROMATIC_ABERRATION =
        floatPreferencesKey("liquid_glass_chromatic_aberration")
    private val KEY_LIQUID_GLASS_CONTENT_DISTORTION =
        floatPreferencesKey("liquid_glass_content_distortion")
    private val KEY_LIQUID_GLASS_STYLE = intPreferencesKey("liquid_glass_style")
private fun decodeOriginalFrostedHomePreferences(preferences:DesktopPreferenceSnapshot):DesktopOriginalFrostedHomePreferences {
        val liquidGlassProgress = resolveStoredLiquidGlassProgress(
            progress = preferences[KEY_LIQUID_GLASS_PROGRESS],
            legacyModeValue = preferences[KEY_LIQUID_GLASS_MODE],
            legacyStrength = preferences[KEY_LIQUID_GLASS_STRENGTH],
            legacyStyleValue = preferences[KEY_LIQUID_GLASS_STYLE],
        )
        val liquidGlassReadabilityMode = LiquidGlassReadabilityMode.fromValue(
            preferences[intPreferencesKey("liquid_glass_readability_mode")]
                ?: LiquidGlassReadabilityMode.STABLE.value
        )
        val liquidGlassAdvancedSettings = resolveLiquidGlassAdvancedSettings(
            presetValue = preferences[KEY_LIQUID_GLASS_ADVANCED_PRESET],
            progressiveBlurRadius = preferences[KEY_LIQUID_GLASS_PROGRESSIVE_BLUR_RADIUS],
            progressiveBlurExtent = preferences[KEY_LIQUID_GLASS_PROGRESSIVE_BLUR_EXTENT],
            progressiveBlurCurve = preferences[KEY_LIQUID_GLASS_PROGRESSIVE_BLUR_CURVE],
            contentReadability = preferences[KEY_LIQUID_GLASS_CONTENT_READABILITY],
            chromaticAberration = preferences[KEY_LIQUID_GLASS_CHROMATIC_ABERRATION],
            contentDistortion = preferences[KEY_LIQUID_GLASS_CONTENT_DISTORTION],
        )

        return DesktopOriginalFrostedHomePreferences(
            navigationIconCrossScaleEnabled =
                preferences[KEY_NAVIGATION_ICON_CROSS_SCALE_ENABLED] ?: true,
            isBottomBarSearchEnabled = preferences[KEY_BOTTOM_BAR_SEARCH_ENABLED] ?: false,
            linkedDockMergeOnScrollEnabled = preferences[KEY_LINKED_DOCK_MERGE_ON_SCROLL_ENABLED] ?: true,
            bottomBarSearchAutoExpandMode = BottomBarSearchAutoExpandMode.fromValue(
                preferences[KEY_BOTTOM_BAR_SEARCH_AUTO_EXPAND_MODE]
                    ?: BottomBarSearchAutoExpandMode.EXPAND_AT_HOME_TOP.value
            ),
            bottomBarSearchLayoutMode = BottomBarSearchLayoutMode.fromValue(
                preferences[KEY_BOTTOM_BAR_SEARCH_LAYOUT_MODE]
                    ?: BottomBarSearchLayoutMode.FULL_DOCK.value
            ),
            androidNativeLiquidGlassEnabled =
                preferences[KEY_ANDROID_NATIVE_LIQUID_GLASS_ENABLED]
                    ?: false,
            liquidGlassProgress = liquidGlassProgress,
            liquidGlassReadabilityMode = liquidGlassReadabilityMode,
            liquidGlassAdvancedSettings = liquidGlassAdvancedSettings,
            bottomBarLiquidGlassPreset = BottomBarLiquidGlassPreset.BILIPAI_TUNED,
        )
}

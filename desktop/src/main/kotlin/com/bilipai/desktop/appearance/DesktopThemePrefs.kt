package com.bilipai.desktop.appearance

import com.android.purebilibili.core.ui.blur.BlurIntensity
import com.android.purebilibili.core.store.HomeHeaderBlurMode
import com.android.purebilibili.core.store.resolveHomeHeaderBlurModePreference
import com.android.purebilibili.core.store.ThemeModeRoleOverrides
import com.android.purebilibili.core.store.ThemeRoleOverrides
import com.android.purebilibili.core.store.parseThemeSelectionString
import com.android.purebilibili.core.theme.*
import com.android.purebilibili.core.ui.AppIconStyle
import com.android.purebilibili.core.ui.AppListItemStyle
import com.android.purebilibili.core.ui.resolveAppIconStylePreference
import com.android.purebilibili.core.ui.resolveAppListItemStylePreference
import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Windows platform adapter for the original appearance keys and policy types. */
data class DesktopThemeSettings(
    val uiStyle: AppUiStyle = AppUiStyle.MATERIAL3,
    val themeMode: AppThemeMode = AppThemeMode.FOLLOW_SYSTEM,
    val darkThemeStyle: DarkThemeStyle = DarkThemeStyle.DEFAULT,
    val appLanguage: AppLanguage = AppLanguage.FOLLOW_SYSTEM,
    val md3ColorSource: Md3ColorSource = Md3ColorSource.FOLLOW_WALLPAPER,
    val md3CustomColorHex: String = "#007AFF",
    val themeRoleOverrides: ThemeRoleOverrides = ThemeRoleOverrides(),
    val colorStyle: PaletteStyle = PaletteStyle.TonalSpot,
    val colorSpec: ColorSpec.SpecVersion = ColorSpec.SpecVersion.SPEC_2025,
    val themeColorIndex: Int = 0,
    val appFontSizePreset: AppFontSizePreset = AppFontSizePreset.DEFAULT,
    val appFontWeightPreset: AppFontWeightPreset = AppFontWeightPreset.FOLLOW_THEME,
    val appUiScalePreset: AppUiScalePreset = AppUiScalePreset.STANDARD,
    val appDpiOverridePercent: Int = 0,
    val appIconStyle: AppIconStyle = AppIconStyle.AUTO,
    val appListItemStyle: AppListItemStyle = AppListItemStyle.AUTO,
    val hapticFeedbackEnabled: Boolean = true,
    val globalTextTapCopyEnabled: Boolean = false,
    val uiEntranceAnimationEnabled: Boolean = true,
    val runtimeVisualGuardEnabled: Boolean = true,
    val blurIntensity: BlurIntensity = BlurIntensity.THIN,
    val headerBlurEnabled: Boolean = true,
    val bottomBarBlurEnabled: Boolean = false,
    val progressiveTopBlurEnabled: Boolean = false,
    val progressiveTopFadeEnabled: Boolean = true,
    val liquidGlassEnabled: Boolean = false,
)

class DesktopThemePrefs(
    private val store: DesktopPluginStore,
    private val legacyWindowsDark: Boolean? = null,
) {
    val settings: Flow<DesktopThemeSettings> = store.snapshot(NAMESPACE)
        .map { initialSettings() }.distinctUntilChanged()

    fun initialSettings(): DesktopThemeSettings = decodeDesktopThemeSettings(
        store.preferences(NAMESPACE), store.preferences("theme_cache"), legacyWindowsDark,
    )

    /** Same atomic new-key + old-key removal semantics as ThemeSelectionStore. */
    suspend fun ensureMigrated() = withContext(Dispatchers.IO) {
        val caller = kotlinx.coroutines.currentCoroutineContext()
        caller.ensureActive()
        store.updateFromSnapshot(NAMESPACE) {
        val current = store.preferences(NAMESPACE)
        val edits = linkedMapOf<String, JsonElement?>()
        val state = initialSettings()
        if (current.string("theme_selection_v1")?.let(::parseThemeSelectionString) == null) {
            edits["theme_selection_v1"] = JsonPrimitive(state.uiStyle.name)
        }
        if ("ui_preset" in current) edits["ui_preset"] = null
        if ("android_native_variant_v1" in current) edits["android_native_variant_v1"] = null
        // Read the Android startup cache only for missing canonical settings; Windows uses one namespace thereafter.
        if (current.int("theme_mode_v2") == null) edits["theme_mode_v2"] = JsonPrimitive(state.themeMode.value)
        if (current.int("dark_theme_style_v1") == null) edits["dark_theme_style_v1"] = JsonPrimitive(state.darkThemeStyle.value)
        if (current.int("app_language_v1") == null) edits["app_language_v1"] = JsonPrimitive(state.appLanguage.value)
        // Windows v5 selects original glass ON once. The mark and preference
        // share one atomic Store write; subsequent manual OFF is never reset.
        if (current.boolean("windows_liquid_glass_default_v1") != true) {
            edits["android_native_liquid_glass_enabled"] = JsonPrimitive(true)
            edits["windows_liquid_glass_default_v1"] = JsonPrimitive(true)
        }
        caller.ensureActive()
        edits
        }
    }

    suspend fun setUiStyle(style: AppUiStyle) = save(mapOf(
        "theme_selection_v1" to JsonPrimitive(style.name), "ui_preset" to null, "android_native_variant_v1" to null,
    ))

    suspend fun setThemeMode(mode: AppThemeMode) = withContext(Dispatchers.IO) {
        val caller = kotlinx.coroutines.currentCoroutineContext()
        caller.ensureActive()
        writeOriginalThemeMode(mode) { caller.ensureActive() }
    }

    suspend fun setThemeModeOwned(mode: AppThemeMode, owns: () -> Boolean,
        withOwnedAdmission: ((() -> Unit) -> Boolean)) = withContext(Dispatchers.IO) {
        val caller = kotlinx.coroutines.currentCoroutineContext()
        caller.ensureActive()
        if (!owns()) throw kotlinx.coroutines.CancellationException("Profile theme entry retired")
        if (!withOwnedAdmission { caller.ensureActive();writeOriginalThemeMode(mode) { caller.ensureActive();if(!owns())throw kotlinx.coroutines.CancellationException("Profile theme entry retired") } })
            throw kotlinx.coroutines.CancellationException("Profile theme entry retired")
    }

    private fun writeOriginalThemeMode(mode: AppThemeMode, checkPublication: () -> Unit) {
        store.updateThemeFromSnapshot(checkPublication) { current, _ ->
            val darkStyle = resolveDarkThemeStylePreference(current.int("dark_theme_style_v1"), current.int("theme_mode_v2"))
            val canonical = linkedMapOf<String, JsonElement?>("theme_mode_v2" to JsonPrimitive(mode.value))
            if (current.int("dark_theme_style_v1") == null) canonical["dark_theme_style_v1"] = JsonPrimitive(darkStyle.value)
            mapOf("settings" to canonical, "theme_cache" to mapOf("theme_mode" to JsonPrimitive(mode.value), "dark_theme_style" to JsonPrimitive(darkStyle.value)))
        }
    }

    suspend fun setDarkThemeStyle(style: DarkThemeStyle) = withContext(Dispatchers.IO) {
        val caller = kotlinx.coroutines.currentCoroutineContext()
        caller.ensureActive()
        store.updateThemeFromSnapshot({caller.ensureActive()}) { _, _ -> mapOf("settings" to mapOf("dark_theme_style_v1" to JsonPrimitive(style.value)),
            "theme_cache" to mapOf("dark_theme_style" to JsonPrimitive(style.value))) }
    }
    suspend fun setAppLanguage(language: AppLanguage) = save("app_language_v1", JsonPrimitive(language.value))
    suspend fun setColorSource(source: Md3ColorSource) = save(mapOf(
        "md3_color_source" to JsonPrimitive(source.name), "dynamic_color" to JsonPrimitive(source == Md3ColorSource.FOLLOW_WALLPAPER),
    ))
    suspend fun setCustomColor(hex: String) = save(mapOf(
        "md3_color_source" to JsonPrimitive(Md3ColorSource.CUSTOM.name),
        "dynamic_color" to JsonPrimitive(false), "md3_custom_color_hex" to JsonPrimitive(normalizeMd3CustomColorHex(hex)),
    ))
    suspend fun setColorStyle(style: PaletteStyle) = save("theme_color_style", JsonPrimitive(style.name))
    suspend fun setColorSpec(spec: ColorSpec.SpecVersion) = save("theme_color_spec", JsonPrimitive(spec.name))
    suspend fun setThemeColorIndex(index: Int) = save("theme_color_index", JsonPrimitive(normalizeThemeColorIndex(index)))
    suspend fun setFontSize(preset: AppFontSizePreset) = save("app_font_size_preset", JsonPrimitive(preset.value))
    suspend fun setFontWeight(preset: AppFontWeightPreset) = save("app_font_weight", JsonPrimitive(preset.value))
    suspend fun setUiScale(preset: AppUiScalePreset) = save("app_ui_scale_preset", JsonPrimitive(preset.value))
    suspend fun setDpiOverride(percent: Int) = save("app_dpi_override_percent", JsonPrimitive(if (percent == 0) 0 else percent.coerceIn(90, 115)))
    suspend fun setIconStyle(style: AppIconStyle) = save("app_icon_style", JsonPrimitive(style.name))
    suspend fun setListItemStyle(style: AppListItemStyle) = save("app_list_item_style", JsonPrimitive(style.name))
    suspend fun setLiquidGlassEnabled(value: Boolean) = save(mapOf(
        "android_native_liquid_glass_enabled" to JsonPrimitive(value),
        "windows_liquid_glass_default_v1" to JsonPrimitive(true),
    ))
    suspend fun setHapticFeedback(value: Boolean) = save("haptic_feedback_enabled", JsonPrimitive(value))
    suspend fun setGlobalTextTapCopy(value: Boolean) = save("global_text_tap_copy_enabled", JsonPrimitive(value))
    suspend fun setUiEntranceAnimation(value: Boolean) = save("ui_entrance_animation_enabled", JsonPrimitive(value))
    suspend fun setRuntimeVisualGuard(value: Boolean) = save("runtime_visual_guard_enabled", JsonPrimitive(value))
    suspend fun setRoleOverrides(overrides: ThemeRoleOverrides) = save(buildMap {
        put("theme_role_overrides_enabled", JsonPrimitive(overrides.enabled))
        val defaults = ThemeRoleOverrides()
        for ((mode, roles, fallback) in listOf(Triple("light", overrides.light, defaults.light), Triple("dark", overrides.dark, defaults.dark))) {
            put("theme_${mode}_background", JsonPrimitive(normalizeMd3CustomColorHex(roles.backgroundHex, fallback.backgroundHex)))
            put("theme_${mode}_primary_text", JsonPrimitive(normalizeMd3CustomColorHex(roles.primaryTextHex, fallback.primaryTextHex)))
            put("theme_${mode}_secondary_text", JsonPrimitive(normalizeMd3CustomColorHex(roles.secondaryTextHex, fallback.secondaryTextHex)))
            put("theme_${mode}_control_accent", JsonPrimitive(normalizeMd3CustomColorHex(roles.controlAccentHex, fallback.controlAccentHex)))
        }
    })
    private suspend fun save(key: String, value: JsonElement) = save(mapOf(key to value))
    private suspend fun save(edits: Map<String, JsonElement?>) = withContext(Dispatchers.IO) { store.update(NAMESPACE, edits) }
    companion object { const val NAMESPACE = "settings" }
}

internal fun decodeDesktopThemeSettings(
    values: JsonObject,
    startupCache: JsonObject = JsonObject(emptyMap()),
    legacyWindowsDark: Boolean? = null,
): DesktopThemeSettings {
    val rawMode = values.int("theme_mode_v2") ?: startupCache.int("theme_mode")
        ?: legacyWindowsDark?.let { if (it) AppThemeMode.DARK.value else AppThemeMode.LIGHT.value }
    val defaults = ThemeRoleOverrides()
    fun roles(mode: String, fallback: ThemeModeRoleOverrides) = ThemeModeRoleOverrides(
        backgroundHex = normalizeMd3CustomColorHex(values.string("theme_${mode}_background"), fallback.backgroundHex),
        primaryTextHex = normalizeMd3CustomColorHex(values.string("theme_${mode}_primary_text"), fallback.primaryTextHex),
        secondaryTextHex = normalizeMd3CustomColorHex(values.string("theme_${mode}_secondary_text"), fallback.secondaryTextHex),
        controlAccentHex = normalizeMd3CustomColorHex(values.string("theme_${mode}_control_accent"), fallback.controlAccentHex),
    )
    val dpi = values.int("app_dpi_override_percent") ?: 0
    return DesktopThemeSettings(
        uiStyle = values.string("theme_selection_v1")?.let(::parseThemeSelectionString)
            ?: AppUiStyle.fromLegacyValues(values.int("ui_preset"), values.int("android_native_variant_v1")),
        themeMode = resolveThemeModePreference(rawMode ?: AppThemeMode.FOLLOW_SYSTEM.value),
        darkThemeStyle = resolveDarkThemeStylePreference(values.int("dark_theme_style_v1") ?: startupCache.int("dark_theme_style"), rawMode),
        appLanguage = resolveAppLanguagePreference(values.int("app_language_v1") ?: startupCache.int("app_language")),
        md3ColorSource = resolveMd3ColorSourcePreference(values.string("md3_color_source"), values.boolean("dynamic_color")),
        md3CustomColorHex = normalizeMd3CustomColorHex(values.string("md3_custom_color_hex")),
        themeRoleOverrides = ThemeRoleOverrides(values.boolean("theme_role_overrides_enabled") ?: false, roles("light", defaults.light), roles("dark", defaults.dark)),
        colorStyle = resolvePaletteStylePreference(values.string("theme_color_style")),
        colorSpec = resolveColorSpecPreference(values.string("theme_color_spec")),
        themeColorIndex = normalizeThemeColorIndex(values.int("theme_color_index") ?: 0),
        appFontSizePreset = AppFontSizePreset.fromValue(values.int("app_font_size_preset") ?: AppFontSizePreset.DEFAULT.value),
        appFontWeightPreset = AppFontWeightPreset.fromValue(values.int("app_font_weight") ?: AppFontWeightPreset.FOLLOW_THEME.value),
        appUiScalePreset = AppUiScalePreset.fromValue(values.int("app_ui_scale_preset") ?: AppUiScalePreset.STANDARD.value),
        appDpiOverridePercent = if (dpi == 0) 0 else dpi.coerceIn(90, 115),
        appIconStyle = resolveAppIconStylePreference(values.string("app_icon_style")),
        appListItemStyle = resolveAppListItemStylePreference(values.string("app_list_item_style")),
        hapticFeedbackEnabled = values.boolean("haptic_feedback_enabled") ?: true,
        globalTextTapCopyEnabled = values.boolean("global_text_tap_copy_enabled") ?: false,
        uiEntranceAnimationEnabled = values.boolean("ui_entrance_animation_enabled") ?: true,
        runtimeVisualGuardEnabled = values.boolean("runtime_visual_guard_enabled") ?: true,
        blurIntensity = runCatching { BlurIntensity.valueOf(values.string("blur_intensity") ?: BlurIntensity.THIN.name) }.getOrDefault(BlurIntensity.THIN),
        headerBlurEnabled = resolveHomeHeaderBlurModePreference(values.int("home_header_blur_mode"), values.boolean("header_blur_enabled")) != HomeHeaderBlurMode.ALWAYS_OFF,
        bottomBarBlurEnabled = values.boolean("bottom_bar_blur_enabled") ?: false,
        progressiveTopBlurEnabled = values.boolean("progressive_top_blur_enabled") ?: false,
        progressiveTopFadeEnabled = values.boolean("progressive_top_fade_enabled") ?: true,
        liquidGlassEnabled = values.boolean("android_native_liquid_glass_enabled") ?: false,
    )
}
private fun JsonObject.int(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
private fun JsonObject.boolean(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

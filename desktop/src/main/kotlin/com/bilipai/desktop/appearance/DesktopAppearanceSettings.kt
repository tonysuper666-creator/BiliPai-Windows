package com.bilipai.desktop.appearance

import com.bilipai.desktop.ui.LocalDesktopWindowsGlassMaterial
import com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext
import com.bilipai.desktop.brand.DesktopMaidMotionPreferences
import com.bilipai.desktop.settings.DARK_THEME_SCHEDULE_FOCUS_ID
import com.bilipai.desktop.settings.desktopSettingsSearchFocusAnchor
import com.android.purebilibili.feature.settings.SettingsSearchTarget
import com.android.purebilibili.feature.settings.SettingsSearchFocusIds

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.ThemeModeRoleOverrides
import com.android.purebilibili.core.store.ThemeRoleOverrides
import com.android.purebilibili.core.theme.*
import com.android.purebilibili.core.ui.AppIconStyle
import com.android.purebilibili.core.ui.AppListItemStyle
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.core.ui.components.AppChoiceOption
import com.android.purebilibili.core.ui.components.AppSegmentOption
import com.android.purebilibili.core.ui.components.AppSingleChoicePreference
import com.android.purebilibili.core.ui.components.AppSwitchPreference
import com.android.purebilibili.core.ui.components.AppSwitch
import com.android.purebilibili.core.ui.components.AppOutlinedTextField
import com.android.purebilibili.feature.settings.*
import com.android.purebilibili.feature.settings.screen.DarkThemeSchedulePreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DesktopAppearanceSettings(
    prefs: DesktopThemePrefs,
    onRestartRequested: () -> Unit,
    onNavigateToIconSettings: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    val settings by prefs.settings.collectAsState(prefs.initialSettings())
    val strings = LocalDesktopStrings.current
    val glassMaterial = LocalDesktopWindowsGlassMaterial.current
    val settingsContext = LocalDesktopOriginalPlayerSettingsContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var requestedLanguage by remember { mutableStateOf<AppLanguage?>(null) }
    var showCustomColorPicker by remember { mutableStateOf(false) }
    fun update(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action(); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
            finally { busy = false }
        }
    }
    LaunchedEffect(prefs) {
        try { prefs.ensureMigrated() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
        finally { busy = false }
    }
    Column(modifier.verticalScroll(scrollState).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DesktopAppearanceText(strings["appearance_settings_title"], style = MaterialTheme.typography.headlineMedium)
        error?.let { DesktopAppearanceText(it, color = MaterialTheme.colorScheme.error) }
        DesktopAppearanceCard(Modifier.fillMaxWidth().desktopSettingsSearchFocusAnchor(SettingsSearchTarget.APPEARANCE, SettingsSearchFocusIds.APPEARANCE_THEME)) {
            AppearanceChoice(strings["appearance_ui_preset_title"], settings.uiStyle,
                resolveThemeSelectionOptions("Material 3", "Miuix"), !busy) { update { prefs.setUiStyle(it) } }
            AppSwitchPreference(
                title = strings.desktop("原版液态玻璃", "原版液態玻璃", "Original liquid glass"),
                subtitle = strings.desktop("复用原版折射与材质，跟随应用背景。", "重用原版折射與材質，跟隨應用背景。", "Original refraction and material, using the app background."),
                checked = settings.liquidGlassEnabled, enabled = !busy,
                onCheckedChange = { update { prefs.setLiquidGlassEnabled(it) } },
            )
            val glassStatus = when {
                !settings.liquidGlassEnabled -> strings.desktop("普通材质", "一般材質", "Standard material")
                glassMaterial == null -> strings.desktop("玻璃背景正在准备", "玻璃背景準備中", "Preparing glass background")
                !glassMaterial.renderer.supported -> strings.desktop("当前渲染器不支持玻璃，使用普通材质", "目前渲染器不支援玻璃，使用一般材質", "Glass is unsupported; standard material is in use")
                !glassMaterial.sourceReady -> strings.desktop("玻璃渲染器可用，背景正在准备", "玻璃渲染器可用，背景準備中", "Glass renderer available; preparing background")
                glassMaterial.sourceHasWallpaper -> strings.desktop("原版玻璃：渲染器可用，背景已就绪（已配置壁纸）", "原版玻璃：渲染器可用，背景已就緒（已設定桌布）", "Original glass: renderer available, background ready (wallpaper configured)")
                else -> strings.desktop("原版玻璃：渲染器可用，原版纯色背景已就绪", "原版玻璃：渲染器可用，原版純色背景已就緒", "Original glass: renderer available, original solid background ready")
            }
            DesktopAppearanceText(glassStatus, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            if (settings.liquidGlassEnabled && glassMaterial?.renderer?.supported == false) {
                glassMaterial.renderer.error?.let { DesktopAppearanceText(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            }
            AppearanceChoice(strings["appearance_theme_mode_title"], settings.themeMode,
                resolveThemeModeSegmentOptions(strings["theme_mode_follow_system"], strings["theme_mode_light"], strings["theme_mode_dark"]), !busy) { update { prefs.setThemeMode(it) } }
            // The three schedule rows stay in one vertically measured child so the search anchor scrolls to all of them.
            DarkThemeSchedulePreference(settings.darkThemeSchedule, { schedule -> update { prefs.setDarkThemeSchedule(schedule) } },
                Modifier.desktopSettingsSearchFocusAnchor(SettingsSearchTarget.APPEARANCE, DARK_THEME_SCHEDULE_FOCUS_ID))
            AppearanceChoice(strings["appearance_dark_theme_style_title"], settings.darkThemeStyle,
                resolveDarkThemeStyleSegmentOptions(strings["dark_theme_style_default"], strings["dark_theme_style_amoled"]), !busy) { update { prefs.setDarkThemeStyle(it) } }
            AppearanceChoice(strings["appearance_app_language_title"], settings.appLanguage,
                resolveAppLanguageSegmentOptions(strings["app_language_follow_system"], "简体中文", "繁體中文", "English"), !busy) {
                if (shouldPromptAppRestartForLanguageChange(settings.appLanguage, it)) requestedLanguage = it
            }
        }
        DesktopAppearanceCard(Modifier.fillMaxWidth()) {
            AppearanceChoice(strings.desktop("主题颜色来源", "主題顏色來源", "Theme color source"), settings.md3ColorSource,
                listOf(AppSegmentOption(Md3ColorSource.FOLLOW_WALLPAPER, strings.desktop("上游预设色", "上游預設色", "Preset color")),
                    AppSegmentOption(Md3ColorSource.CUSTOM, strings.desktop("自定义颜色", "自訂顏色", "Custom color"))), !busy) { update { prefs.setColorSource(it) } }
            // The colour every preview below is computed from: the preset, the custom hex or the stored index.
            val seedColor = remember(settings.md3ColorSource, settings.md3CustomColorHex, settings.themeColorIndex) {
                resolveMd3ThemeSeedColor(settings.md3ColorSource, settings.md3CustomColorHex, settings.themeColorIndex)
            }
            val previewDark = resolveColorSchemeIsDark(MaterialTheme.colorScheme)
            if (settings.md3ColorSource == Md3ColorSource.FOLLOW_WALLPAPER) {
                DesktopAppearanceText(strings.desktop("预设颜色不会随桌面壁纸变化。", "預設顏色不會隨桌布變更。", "Preset colors do not change with your desktop wallpaper."),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            } else {
                var custom by remember(settings.md3CustomColorHex) { mutableStateOf(settings.md3CustomColorHex) }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppOutlinedTextField(custom, { custom = it }, Modifier.weight(1f), singleLine = true,
                        isError = !isValidMd3CustomColorHex(custom), enabled = !busy, labelText = "#RRGGBB")
                    DesktopAppearanceButton(strings.desktop("选色", "選色", "Choose color"),
                        { showCustomColorPicker = true }, enabled = !busy)
                    DesktopAppearanceButton(strings["common_save"], { update { prefs.setCustomColor(custom) } },
                        enabled = !busy && isValidMd3CustomColorHex(custom))
                }
            }
            // Preset colours as swatch cells. The 19 seed presets replace the original list while a seed style is on.
            DesktopAppearanceText(strings.desktop("预设颜色", "預設顏色", "Preset color"),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            DesktopThemePresetPalette(settings.seedPaletteStyle, settings.colorStyle, settings.colorSpec,
                settings.colorContrast, seedColor, !busy) { index, color ->
                when (resolveDesktopPresetPick(settings.seedPaletteStyle.enabled, settings.md3ColorSource)) {
                    DesktopPresetPick.THEME_COLOR_INDEX -> update { prefs.setThemeColorIndex(index) }
                    DesktopPresetPick.CUSTOM_COLOR -> update { prefs.setCustomColor(formatMd3CustomColorHex(color)) }
                }
            }
            val seedStyleSwatches = rememberSeedStyleSwatches(seedColor, previewDark, settings.colorSpec)
            AppearanceChoice(strings.desktop("配色预设", "配色預設", "Color preset"), settings.seedPaletteStyle,
                resolveSeedPaletteStyleOptions(), !busy,
                optionLeading = { style -> seedStyleSwatches[style]?.let { ThemeStyleSwatch(it) } }) { update { prefs.setSeedPaletteStyle(it) } }
            DesktopAppearanceText(resolveSeedPaletteStyleDescription(settings.seedPaletteStyle),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            if (!settings.seedPaletteStyle.enabled) {
                val colorStyleOptions = remember { resolveColorStyleOptions() }
                val colorStyles = remember(colorStyleOptions) { colorStyleOptions.map { it.value } }
                val styleSwatches = rememberThemeStylePaletteSwatches(seedColor, previewDark, colorStyles,
                    settings.colorSpec, settings.colorContrast, settings.seedPaletteStyle)
                val styleSwatchByStyle = remember(styleSwatches) { colorStyles.zip(styleSwatches).toMap() }
                AppearanceChoice(strings.desktop("调色板风格", "調色盤風格", "Palette style"), settings.colorStyle,
                    colorStyleOptions, !busy,
                    optionLeading = { style -> styleSwatchByStyle[style]?.let { ThemeStyleSwatch(it) } }) { update { prefs.setColorStyle(it) } }
                AppearanceChoice(strings.desktop("对比度", "對比度", "Contrast"), settings.colorContrast,
                    resolveColorContrastOptions(), !busy) { update { prefs.setColorContrast(it) } }
            }
            if (shouldShowColorSpecControl(settings.seedPaletteStyle)) {
                AppearanceChoice(strings.desktop("颜色规范", "顏色規範", "Color specification"),
                    resolveSelectedColorSpec(settings.colorSpec, settings.colorStyle, settings.seedPaletteStyle),
                    resolveColorSpecOptions(settings.colorStyle, settings.seedPaletteStyle), !busy) { update { prefs.setColorSpec(it) } }
            }
        }
        DesktopAppearanceCard(Modifier.fillMaxWidth().desktopSettingsSearchFocusAnchor(SettingsSearchTarget.APPEARANCE, SettingsSearchFocusIds.APPEARANCE_DISPLAY)) {
            AppearanceChoice(strings.desktop("字体大小", "字體大小", "Font size"), settings.appFontSizePreset,
                AppFontSizePreset.entries.map { AppSegmentOption(it, "${(it.multiplier * 100).toInt()}%") }, !busy) { update { prefs.setFontSize(it) } }
            AppearanceChoice(strings.desktop("字体粗细", "字體粗細", "Font weight"), settings.appFontWeightPreset,
                AppFontWeightPreset.entries.map { AppSegmentOption(it, when (it) {
                    AppFontWeightPreset.FOLLOW_THEME -> strings.desktop("跟随默认", "跟隨預設", "Follow Theme")
                    else -> it.fontWeight?.weight.toString()
                }) }, !busy) { update { prefs.setFontWeight(it) } }
            // Whole-window Windows zoom is separate from the retained original font choices.
        }
        DesktopWindowsDisplayScaleSettings()
        DesktopAppearanceButton(strings["icon_settings_title"], onNavigateToIconSettings,
            modifier = Modifier.fillMaxWidth(), enabled = !busy)
        if (!settings.seedPaletteStyle.enabled) {
            RoleOverridesEditor(settings, !busy) { update { prefs.setRoleOverrides(it) } }
        }
        DesktopAppearanceCard(Modifier.fillMaxWidth()) {
            AppearanceChoice(strings.desktop("图标样式", "圖示樣式", "Icon style"), settings.appIconStyle,
                listOf(AppSegmentOption(AppIconStyle.AUTO, strings["theme_mode_follow_system"]),
                    AppSegmentOption(AppIconStyle.THEME_CONTAINER, strings.desktop("主题色容器", "主題色容器", "Theme container")),
                    AppSegmentOption(AppIconStyle.MD3_STANDARD, strings.desktop("Material 标准", "Material 標準", "Material standard"))), !busy) { update { prefs.setIconStyle(it) } }
            AppearanceChoice(strings.desktop("列表样式", "清單樣式", "List style"), settings.appListItemStyle,
                listOf(AppSegmentOption(AppListItemStyle.AUTO, strings["theme_mode_follow_system"]),
                    AppSegmentOption(AppListItemStyle.NATIVE, strings.desktop("原生组件", "原生元件", "Native components")),
                    AppSegmentOption(AppListItemStyle.CUSTOM, strings.desktop("自定义组件", "自訂元件", "Custom components"))), !busy) { update { prefs.setListItemStyle(it) } }
            AppSwitchPreference(title = strings.desktop("点击文字复制", "點擊文字複製", "Tap text to copy"),
                checked = settings.globalTextTapCopyEnabled, enabled = !busy,
                onCheckedChange = { update { prefs.setGlobalTextTapCopy(it) } })
            AppSwitchPreference(title = strings.desktop("入场动画", "入場動畫", "Entrance animations"),
                checked = settings.uiEntranceAnimationEnabled, enabled = !busy,
                onCheckedChange = { update { prefs.setUiEntranceAnimation(it) } })
            AppSwitchPreference(
                title = strings.desktop("女仆动画", "女僕動畫", "Maid animations"),
                subtitle = strings.desktop("关闭后隐藏女仆动画和静态图", "關閉後隱藏女僕動畫和靜態圖", "Hide maid animations and still images when disabled"),
                checked = settings.maidLottieAnimationEnabled, enabled = !busy,
                onCheckedChange = { enabled ->
                    update { DesktopMaidMotionPreferences.setMaidLottieAnimationEnabled(settingsContext, enabled) }
                },
            )
        }
        DesktopAppearanceCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DesktopAppearanceText(strings.desktop("外观预览", "外觀預覽", "Appearance preview"), style = MaterialTheme.typography.titleLarge)
                DesktopAppearanceText(strings.desktop("标题、正文、控件与卡片会随当前选择变化。", "標題、內文、控制項與卡片會隨目前選擇變更。", "Titles, text, controls and cards follow the selected appearance."))
                DesktopAppearanceButton(strings.desktop("示例按钮", "範例按鈕", "Example button"), {}, enabled = false)
                // The original home page colour preview; it reads the live theme, so it follows every choice above.
                ThemeHomePreview(Modifier.widthIn(max = 440.dp))
            }
        }
    }
    if (showCustomColorPicker && settings.md3ColorSource == Md3ColorSource.CUSTOM) {
        ThemeCustomColorPickerDialog(initialHex = settings.md3CustomColorHex,
            paletteStyle = settings.colorStyle, colorSpec = settings.colorSpec,
            onDismiss = { showCustomColorPicker = false },
            onConfirm = { selected -> showCustomColorPicker = false; update { prefs.setCustomColor(selected) } })
    }
    requestedLanguage?.let { requested ->
        AppAlertDialog(onDismissRequest = { if (!busy) requestedLanguage = null },
            title = { DesktopAppearanceText(strings["app_language_restart_dialog_title"]) },
            text = { DesktopAppearanceText(strings["app_language_restart_dialog_message"]) },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                update {
                    persistAndApplyAppLanguageBeforeRestart(requested, prefs::setAppLanguage,
                        restart = { requestedLanguage = null; onRestartRequested() })
                }
            }) { DesktopAppearanceText(strings["app_language_restart_dialog_confirm"]) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { requestedLanguage = null }) { DesktopAppearanceText(strings["common_cancel"]) } })
    }
}

/** [optionLeading] puts a swatch before each option (the original colour style and seed style previews). */
@Composable
private fun <T> AppearanceChoice(title: String, value: T, options: List<AppSegmentOption<T>>, enabled: Boolean,
    optionLeading: (@Composable (T) -> Unit)? = null, onSelect: (T) -> Unit) {
    AppSingleChoicePreference(title = title, selectedValue = value,
        options = options.map { AppChoiceOption(it.value, it.label) }, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), optionLeading = optionLeading, onValueChange = onSelect)
}

@Composable
private fun RoleOverridesEditor(settings: DesktopThemeSettings, enabled: Boolean, onSave: (ThemeRoleOverrides) -> Unit) {
    val strings = LocalDesktopStrings.current
    var draft by remember(settings.themeRoleOverrides) { mutableStateOf(settings.themeRoleOverrides) }
    val baseRoles = LocalBaseThemeRoleOverrides.current
    var colorTarget by remember { mutableStateOf<ThemeRoleColorTarget?>(null) }
    val applicable = settings.uiStyle == AppUiStyle.MATERIAL3 && settings.md3ColorSource == Md3ColorSource.CUSTOM
    DesktopAppearanceCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DesktopAppearanceText(strings.desktop("自定义颜色角色", "自訂顏色角色", "Custom color roles"))
                AppSwitch(draft.enabled, { checked ->
                    draft = if (checked) enableThemeRoleOverrides(draft, baseRoles, settings.md3CustomColorHex)
                        else draft.copy(enabled = false)
                }, enabled = enabled && applicable)
            }
            if (draft.enabled) {
                for ((dark, roles) in listOf(false to draft.light, true to draft.dark)) {
                    DesktopAppearanceText(strings[if (dark) "theme_mode_dark" else "theme_mode_light"])
                    fun replace(value: ThemeModeRoleOverrides) { draft = if (dark) draft.copy(dark = value) else draft.copy(light = value) }
                    for ((index, row) in listOf(
                        Triple(strings.desktop("背景", "背景", "Background"), roles.backgroundHex, { text: String -> replace(roles.copy(backgroundHex = text)) }),
                        Triple(strings.desktop("主要文字", "主要文字", "Primary text"), roles.primaryTextHex, { text: String -> replace(roles.copy(primaryTextHex = text)) }),
                        Triple(strings.desktop("次要文字", "次要文字", "Secondary text"), roles.secondaryTextHex, { text: String -> replace(roles.copy(secondaryTextHex = text)) }),
                        Triple(strings.desktop("控件强调色", "控制項強調色", "Control accent"), roles.controlAccentHex, { text: String -> replace(roles.copy(controlAccentHex = text)) }),
                    ).withIndex()) {
                        val (label, value, action) = row
                        val target = ThemeRoleColorTarget.entries[(if (dark) 4 else 0) + index]
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppOutlinedTextField(value, action, Modifier.weight(1f), labelText = label,
                                singleLine = true, enabled = enabled && applicable, isError = !isValidMd3CustomColorHex(value))
                            DesktopAppearanceButton(strings.desktop("选色", "選色", "Choose color"),
                                { colorTarget = target }, enabled = enabled && applicable)
                        }
                    }
                }
            }
            val valid = listOf(draft.light, draft.dark).all { roles -> listOf(roles.backgroundHex, roles.primaryTextHex, roles.secondaryTextHex, roles.controlAccentHex).all(::isValidMd3CustomColorHex) }
            DesktopAppearanceButton(strings["common_save"], { onSave(draft) }, enabled = enabled && applicable && valid)
        }
    }
    colorTarget?.takeIf { applicable }?.let { target ->
        ThemeCustomColorPickerDialog(initialHex = target.read(draft), roleTarget = target,
            paletteStyle = settings.colorStyle, colorSpec = settings.colorSpec,
            onDismiss = { colorTarget = null },
            onConfirm = { selected -> draft = target.write(draft, selected); colorTarget = null })
    }
}

private fun DesktopStrings.desktop(simplified: String, traditional: String, english: String): String = when (languageTag) {
    "en" -> english; "zh-TW" -> traditional; else -> simplified
}

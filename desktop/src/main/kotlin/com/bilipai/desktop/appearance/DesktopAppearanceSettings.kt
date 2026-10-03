package com.bilipai.desktop.appearance

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
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var requestedLanguage by remember { mutableStateOf<AppLanguage?>(null) }
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
            AppearanceChoice(strings["appearance_theme_mode_title"], settings.themeMode,
                resolveThemeModeSegmentOptions(strings["theme_mode_follow_system"], strings["theme_mode_light"], strings["theme_mode_dark"]), !busy) { update { prefs.setThemeMode(it) } }
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
            if (settings.md3ColorSource == Md3ColorSource.FOLLOW_WALLPAPER) {
                DesktopAppearanceText(strings.desktop("预设颜色不会随桌面壁纸变化。", "預設顏色不會隨桌布變更。", "Preset colors do not change with your desktop wallpaper."),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                AppearanceChoice(strings.desktop("预设颜色", "預設顏色", "Preset color"), settings.themeColorIndex,
                    ThemeColors.indices.map { AppSegmentOption(it, "${it + 1} · ${formatMd3CustomColorHex(ThemeColors[it])}") }, !busy) { update { prefs.setThemeColorIndex(it) } }
            } else {
                var custom by remember(settings.md3CustomColorHex) { mutableStateOf(settings.md3CustomColorHex) }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppOutlinedTextField(custom, { custom = it }, Modifier.weight(1f), singleLine = true,
                        isError = !isValidMd3CustomColorHex(custom), enabled = !busy, labelText = "#RRGGBB")
                    DesktopAppearanceButton(strings["common_save"], { update { prefs.setCustomColor(custom) } },
                        enabled = !busy && isValidMd3CustomColorHex(custom))
                }
            }
            AppearanceChoice(strings.desktop("调色板风格", "調色盤風格", "Palette style"), settings.colorStyle,
                resolveColorStyleOptions(), !busy && settings.uiStyle == AppUiStyle.MATERIAL3) { update { prefs.setColorStyle(it) } }
            AppearanceChoice(strings.desktop("颜色规范", "顏色規範", "Color specification"), settings.colorSpec,
                resolveColorSpecOptions(), !busy && settings.uiStyle == AppUiStyle.MATERIAL3) { update { prefs.setColorSpec(it) } }
            if (settings.uiStyle == AppUiStyle.MIUIX) DesktopAppearanceText(
                strings.desktop("高级调色配置会保留，切换到 Material 3 后生效。", "進階調色設定會保留，切換到 Material 3 後生效。", "Advanced palette settings are retained and apply in Material 3."),
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        }
        DesktopAppearanceCard(Modifier.fillMaxWidth().desktopSettingsSearchFocusAnchor(SettingsSearchTarget.APPEARANCE, SettingsSearchFocusIds.APPEARANCE_DISPLAY)) {
            AppearanceChoice(strings.desktop("字体大小", "字體大小", "Font size"), settings.appFontSizePreset,
                AppFontSizePreset.entries.map { AppSegmentOption(it, "${(it.multiplier * 100).toInt()}%") }, !busy) { update { prefs.setFontSize(it) } }
            AppearanceChoice(strings.desktop("字体粗细", "字體粗細", "Font weight"), settings.appFontWeightPreset,
                AppFontWeightPreset.entries.map { AppSegmentOption(it, when (it) {
                    AppFontWeightPreset.FOLLOW_THEME -> strings.desktop("跟随默认", "跟隨預設", "Follow Theme")
                    else -> it.fontWeight?.weight.toString()
                }) }, !busy) { update { prefs.setFontWeight(it) } }
            AppearanceChoice(strings.desktop("界面缩放", "介面縮放", "UI scale"), settings.appUiScalePreset,
                AppUiScalePreset.entries.map { AppSegmentOption(it, "${(it.densityMultiplier * 100).toInt()}%") }, !busy) { update { prefs.setUiScale(it) } }
            AppearanceChoice(strings.desktop("DPI 覆盖", "DPI 覆寫", "DPI override"), settings.appDpiOverridePercent,
                listOf(AppSegmentOption(0, strings["theme_mode_follow_system"])) + (90..115).map { AppSegmentOption(it, "$it%") }, !busy) { update { prefs.setDpiOverride(it) } }
        }
        DesktopAppearanceButton(strings["icon_settings_title"], onNavigateToIconSettings,
            modifier = Modifier.fillMaxWidth(), enabled = !busy)
        RoleOverridesEditor(settings, !busy) { update { prefs.setRoleOverrides(it) } }
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
        }
        DesktopAppearanceCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DesktopAppearanceText(strings.desktop("外观预览", "外觀預覽", "Appearance preview"), style = MaterialTheme.typography.titleLarge)
                DesktopAppearanceText(strings.desktop("标题、正文、控件与卡片会随当前选择变化。", "標題、內文、控制項與卡片會隨目前選擇變更。", "Titles, text, controls and cards follow the selected appearance."))
                DesktopAppearanceButton(strings.desktop("示例按钮", "範例按鈕", "Example button"), {}, enabled = false)
            }
        }
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

@Composable
private fun <T> AppearanceChoice(title: String, value: T, options: List<AppSegmentOption<T>>, enabled: Boolean, onSelect: (T) -> Unit) {
    AppSingleChoicePreference(title = title, selectedValue = value,
        options = options.map { AppChoiceOption(it.value, it.label) }, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), onValueChange = onSelect)
}

@Composable
private fun RoleOverridesEditor(settings: DesktopThemeSettings, enabled: Boolean, onSave: (ThemeRoleOverrides) -> Unit) {
    val strings = LocalDesktopStrings.current
    var draft by remember(settings.themeRoleOverrides) { mutableStateOf(settings.themeRoleOverrides) }
    val applicable = settings.uiStyle == AppUiStyle.MATERIAL3 && settings.md3ColorSource == Md3ColorSource.CUSTOM
    DesktopAppearanceCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DesktopAppearanceText(strings.desktop("自定义颜色角色", "自訂顏色角色", "Custom color roles"))
                AppSwitch(draft.enabled, { draft = draft.copy(enabled = it) }, enabled = enabled && applicable)
            }
            if (draft.enabled) {
                for ((dark, roles) in listOf(false to draft.light, true to draft.dark)) {
                    DesktopAppearanceText(strings[if (dark) "theme_mode_dark" else "theme_mode_light"])
                    fun replace(value: ThemeModeRoleOverrides) { draft = if (dark) draft.copy(dark = value) else draft.copy(light = value) }
                    for ((label, value, action) in listOf(
                        Triple(strings.desktop("背景", "背景", "Background"), roles.backgroundHex, { text: String -> replace(roles.copy(backgroundHex = text)) }),
                        Triple(strings.desktop("主要文字", "主要文字", "Primary text"), roles.primaryTextHex, { text: String -> replace(roles.copy(primaryTextHex = text)) }),
                        Triple(strings.desktop("次要文字", "次要文字", "Secondary text"), roles.secondaryTextHex, { text: String -> replace(roles.copy(secondaryTextHex = text)) }),
                        Triple(strings.desktop("控件强调色", "控制項強調色", "Control accent"), roles.controlAccentHex, { text: String -> replace(roles.copy(controlAccentHex = text)) }),
                    )) AppOutlinedTextField(value, action, Modifier.fillMaxWidth(), labelText = label,
                        singleLine = true, enabled = enabled && applicable, isError = !isValidMd3CustomColorHex(value))
                }
            }
            val valid = listOf(draft.light, draft.dark).all { roles -> listOf(roles.backgroundHex, roles.primaryTextHex, roles.secondaryTextHex, roles.controlAccentHex).all(::isValidMd3CustomColorHex) }
            DesktopAppearanceButton(strings["common_save"], { onSave(draft) }, enabled = enabled && applicable && valid)
        }
    }
}

private fun DesktopStrings.desktop(simplified: String, traditional: String, english: String): String = when (languageTag) {
    "en" -> english; "zh-TW" -> traditional; else -> simplified
}

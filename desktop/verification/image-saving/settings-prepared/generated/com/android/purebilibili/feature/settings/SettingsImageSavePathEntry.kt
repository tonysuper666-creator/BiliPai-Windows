// GENERATED from app/src/main/java/com/android/purebilibili/feature/settings/ui/SettingsSections.kt; do not edit.
// LF-normalized SHA-256: c8178e7a048512348678555b529180f7a969a658eca4019d484aff8603581a1b
package com.android.purebilibili.feature.settings
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.android.purebilibili.core.ui.components.AppPreference as SettingClickableItem
import com.android.purebilibili.core.ui.components.rememberAdaptiveListVisualCapabilities
import com.bilipai.desktop.settings.DesktopSettingsVectors
@Composable
internal fun SettingsImageSavePathEntry(customImageSavePath: String?, onImageSavePathClick: () -> Unit) {
    val imageSavePathVisual = rememberSettingsEntryVisual(SettingsSearchTarget.IMAGE_SAVE_PATH)
    val siblingTints = remember { resolveSettingsSiblingIconTints(7, paletteOffset = 2) }
    val showExplicitActionChevron =
        rememberAdaptiveListVisualCapabilities().showExplicitActionChevron
    SettingsCardGroup {
        SettingClickableItem(
            icon = imageSavePathVisual.icon,
            iconPainter = imageSavePathVisual.iconResId?.let { rememberVectorPainter(DesktopSettingsVectors.vector(it)) },
            title = settingsDestinationCopy(SettingsSearchTarget.IMAGE_SAVE_PATH).title,
            subtitle = if (customImageSavePath != null) "已选择目录" else "默认",
            onClick = onImageSavePathClick,
            iconTint = siblingTints[3],
            showChevron = showExplicitActionChevron
        )
    }
}

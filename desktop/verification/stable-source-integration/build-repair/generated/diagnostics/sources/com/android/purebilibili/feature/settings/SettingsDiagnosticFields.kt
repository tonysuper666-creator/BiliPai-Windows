// GENERATED from app/src/main/java/com/android/purebilibili/feature/settings/ui/SettingsSections.kt; do not edit.
// LF-normalized SHA-256: dad065eba76c2bb51ce9c5775b4e8bf86f21b8230be0ee2606fe53fa77b64f25
package com.android.purebilibili.feature.settings
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.android.purebilibili.core.theme.*
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.ui.components.AppSwitchPreference as SettingSwitchItem
import com.android.purebilibili.core.ui.components.AppPreference as SettingClickableItem
import com.bilipai.desktop.settings.*

@Composable
internal fun DesktopDiagnosticFields(
    enhancedDiagnosticLoggingEnabled:Boolean,
    onEnhancedDiagnosticLoggingChange:(Boolean)->Unit,
    onExportLogsClick:()->Unit,
) {
    val siblingTints = remember { resolveSettingsSiblingIconTints(6, paletteOffset = 5) }
    val exportLogsVisual = rememberSettingsEntryVisual(SettingsSearchTarget.EXPORT_LOGS)
    val useMd3ExportLogsDescription = LocalAppUiStyle.current == AppUiStyle.MATERIAL3
    val exportLogsDescription = "崩溃摘要优先；原始回溯需单独选择"
    var showEnhancedDiagnosticConsent by remember { mutableStateOf(false) }
    SettingsCardGroup {
        SettingSwitchItem(
            icon = DesktopDiagnosticSettingsVectors.vector(DesktopDiagnosticSettingsSymbols.ms_pest_control_24),
            title = "增强诊断日志",
            subtitle = if (enhancedDiagnosticLoggingEnabled) {
                "正在采集脱敏后的详细运行信息；关闭会清除详细日志，保留基础错误日志"
            } else {
                "基础错误日志默认保留；开启后记录更多排障信息，不含凭据与观看内容"
            },
            checked = enhancedDiagnosticLoggingEnabled,
            onCheckedChange = { enabled ->
                if (enabled) showEnhancedDiagnosticConsent = true
                else onEnhancedDiagnosticLoggingChange(false)
            },
            iconTint = siblingTints[2],
        )
        SettingsAdaptiveDivider()
        SettingClickableItem(
            icon = exportLogsVisual.icon,
            iconPainter = exportLogsVisual.iconResId?.let { androidx.compose.ui.graphics.vector.rememberVectorPainter(DesktopSettingsVectors.vector(it)) },
            title = settingsDestinationCopy(SettingsSearchTarget.EXPORT_LOGS).title,
            subtitle = exportLogsDescription.takeIf { useMd3ExportLogsDescription },
            value = exportLogsDescription.takeUnless { useMd3ExportLogsDescription },
            onClick = onExportLogsClick,
            iconTint = siblingTints[5],
        )
    }
    if (showEnhancedDiagnosticConsent) {
        AppAlertDialog(
            onDismissRequest = { showEnhancedDiagnosticConsent = false },
            title = { AppText("开启增强诊断日志？", fontWeight = FontWeight.Bold) },
            text = {
                AppText(
                    text = "将记录应用版本、设备与系统环境、请求结果、播放器状态和错误堆栈。" +
                        "账号凭据、用户标识、视频标识、搜索词和用户输入会在写入前脱敏。" +
                        "详细日志仅保存在应用私有目录（最多 256KB），不会自动上传；关闭后会清除详细日志。" +
                        "基础错误与启动诊断另行保留（最多 64KB），崩溃快照单独保存，可在诊断设置中清理。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                AppDialogAction(
                    onClick = {
                        showEnhancedDiagnosticConsent = false
                        onEnhancedDiagnosticLoggingChange(true)
                    }
                ) { AppText("同意并开启") }
            },
            dismissButton = {
                AppDialogAction(onClick = { showEnhancedDiagnosticConsent = false }) {
                    AppText("取消")
                }
            },
        )
    }
}

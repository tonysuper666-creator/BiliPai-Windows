package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.AppSurface
import com.android.purebilibili.core.ui.components.AppSwitchPreference
import com.android.purebilibili.core.ui.components.AppButtonDefaults
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton
import com.bilipai.desktop.player.DesktopVideoEnhancementState
import com.bilipai.desktop.plugins.DesktopVideoEnhancementConfiguration
import kotlinx.coroutines.flow.StateFlow

/** Every Windows entrance borrows this exact Root configuration/native session. */
internal class DesktopWindowsVideoEnhancementUiBinding(
    val configuration: DesktopVideoEnhancementConfiguration,
    val state: StateFlow<DesktopVideoEnhancementState>,
)
internal val LocalDesktopWindowsVideoEnhancement = staticCompositionLocalOf<DesktopWindowsVideoEnhancementUiBinding> {
    error("NVIDIA enhancement requires the actual Windows Root session")
}

@Composable
fun DesktopVideoEnhancementSettingsDialog(configuration: DesktopVideoEnhancementConfiguration, onDismiss: () -> Unit) {
    DesktopWindowsPlayerDialog("NVIDIA 自动增强", onDismiss, preferredHeightDp = 360) {
        AppSurface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AppText("NVIDIA 自动增强", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    DesktopVideoEnhancementSettingsContent(configuration)
                }
                AppTextButton(onClick = onDismiss, modifier = Modifier.align(androidx.compose.ui.Alignment.End)) {
                    AppText("完成")
                }
            }
        }
    }
}

internal val LocalDesktopVideoEnhancementCompact = staticCompositionLocalOf { false }
private val LocalDesktopVideoEnhancementCompactStatus = staticCompositionLocalOf { true }

/** Compact presentation only. All switch and detail operations still use the original Root binding. */
@Composable
internal fun DesktopVideoEnhancementCompactSlot(showStatus: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDesktopVideoEnhancementCompact provides true,
        LocalDesktopVideoEnhancementCompactStatus provides showStatus, content = content)
}

internal fun desktopVideoEnhancementCompactLabel(state: DesktopVideoEnhancementState,
    enabled: Boolean, configurationError: String?): String {
    return when {
        configurationError != null || state.error != null -> "异常"
        !enabled -> "关闭"
        state.unavailableReason != null -> "不可用"
        state.active && state.driverVsrAccepted && state.hdrConversionActive -> "VSR · HDR"
        state.active && state.hdrConversionActive -> "HDR"
        state.active && state.driverVsrAccepted -> "VSR"
        state.pending -> "处理中"
        else -> "原画"
    }
}

@Composable
fun DesktopVideoEnhancementControls(state: DesktopVideoEnhancementState,
    configuration: DesktopVideoEnhancementConfiguration, onToggle: (Boolean) -> Unit, onSettings: () -> Unit) {
    if (LocalDesktopVideoEnhancementCompact.current) {
        val enabled by configuration.automaticEnabled.collectAsState()
        val configurationError by configuration.error.collectAsState()
        val label = desktopVideoEnhancementCompactLabel(state, enabled, configurationError)
        val showStatus = LocalDesktopVideoEnhancementCompactStatus.current
        AppTextButton(onClick = onSettings, modifier = (if (showStatus) Modifier.heightIn(min = 44.dp) else Modifier.size(44.dp))
            .semantics { contentDescription = "NVIDIA 增强详情" },
            contentPadding = if (showStatus) AppButtonDefaults.TextButtonContentPadding else PaddingValues(4.dp)) {
            AppText(if (showStatus) "NVIDIA · $label" else "RTX", style = MaterialTheme.typography.labelMedium,
                maxLines = 1, softWrap = false,
                color = if (configurationError != null || state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        return
    }
    DesktopWindowsVideoEnhancementBody(configuration, state, onToggle)
    AppTextButton(onClick = onSettings) { AppText("NVIDIA 增强详情") }
}

@Composable
internal fun DesktopWindowsVideoEnhancementSettingsContent() {
    val binding = LocalDesktopWindowsVideoEnhancement.current
    DesktopWindowsVideoEnhancementSettingsContent(binding.configuration)
}

@Composable
internal fun DesktopWindowsVideoEnhancementSettingsContent(configuration: DesktopVideoEnhancementConfiguration) {
    val binding = LocalDesktopWindowsVideoEnhancement.current
    check(binding.configuration === configuration) { "NVIDIA settings must use the actual Root configuration" }
    val state by binding.state.collectAsState()
    DesktopWindowsVideoEnhancementBody(configuration, state, { configuration.setAutomaticEnabled(it) })
}

@Composable
private fun DesktopWindowsVideoEnhancementBody(configuration: DesktopVideoEnhancementConfiguration,
    state: DesktopVideoEnhancementState, onToggle: (Boolean) -> Unit) {
    val enabled by configuration.automaticEnabled.collectAsState()
    val configurationError by configuration.error.collectAsState()
    var actionError by remember(configuration) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppSwitchPreference(title = "NVIDIA 自动增强",
            subtitle = "所有视频统一使用 NVIDIA 视频增强；按当前视频、显卡和显示器条件自动处理。",
            checked = enabled, onCheckedChange = { value ->
                actionError = null
                runCatching { onToggle(value) }.onFailure { actionError = "保存 NVIDIA 增强设置失败，请重试" }
            })
        AppText(state.statusText, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Native status distinguishes accepted driver requests and actual output
        // conditions. The switch itself is never proof of VSR/Tensor/HDR activity.
        state.error?.let { AppText(it, color = MaterialTheme.colorScheme.error) }
        configurationError?.takeIf { it != state.error }?.let { AppText(it, color = MaterialTheme.colorScheme.error) }
        actionError?.let { AppText(it, color = MaterialTheme.colorScheme.error) }
    }
}

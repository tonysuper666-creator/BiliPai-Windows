package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.core.ui.components.AppSwitchPreference
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
    AppAlertDialog(onDismissRequest = onDismiss, title = { AppText("NVIDIA 自动增强") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            DesktopVideoEnhancementSettingsContent(configuration)
        }
    }, confirmButton = { AppTextButton(onClick = onDismiss) { AppText("完成") } })
}

@Composable
fun DesktopVideoEnhancementControls(state: DesktopVideoEnhancementState,
    configuration: DesktopVideoEnhancementConfiguration, onToggle: (Boolean) -> Unit, onSettings: () -> Unit) {
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

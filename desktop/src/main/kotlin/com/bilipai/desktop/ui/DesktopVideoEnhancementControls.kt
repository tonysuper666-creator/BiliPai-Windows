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
import com.bilipai.desktop.player.DesktopNvidiaVideoContent
import com.bilipai.desktop.player.DesktopNvidiaVideoQuality
import com.bilipai.desktop.player.DesktopNvidiaVideoIntensity
import com.bilipai.desktop.player.NvidiaVideoBackend
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
                    DesktopWindowsVideoEnhancementSettingsContent(configuration, showProcessingQuality = false)
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
private val LocalDesktopVideoEnhancementMenuEnabled = staticCompositionLocalOf { true }
private val LocalDesktopVideoEnhancementMenuExpanded = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

/** Compact presentation only. All switch and detail operations still use the original Root binding. */
@Composable
internal fun DesktopVideoEnhancementCompactSlot(showStatus: Boolean = true, menuEnabled: Boolean = true,
    onMenuExpandedChanged: (Boolean) -> Unit = {}, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDesktopVideoEnhancementCompact provides true,
        LocalDesktopVideoEnhancementCompactStatus provides showStatus,
        LocalDesktopVideoEnhancementMenuEnabled provides menuEnabled,
        LocalDesktopVideoEnhancementMenuExpanded provides onMenuExpandedChanged, content = content)
}

internal fun desktopVideoEnhancementCompactLabel(state: DesktopVideoEnhancementState,
    enabled: Boolean, configurationError: String?): String {
    return when {
        configurationError != null || state.error != null -> "异常"
        !enabled -> "关闭"
        state.unavailableReason != null -> "不可用"
        state.active && state.backend == NvidiaVideoBackend.VEYRA_CORE && state.srEnabledRequested && state.hdrConversionActive -> "清晰度＋HDR"
        state.active && state.backend == NvidiaVideoBackend.VEYRA_CORE && state.srEnabledRequested -> "清晰度"
        state.active && state.driverVsrAccepted && state.hdrConversionActive -> "VSR · HDR"
        state.active && state.hdrConversionActive -> "HDR"
        state.active && state.driverVsrAccepted -> "VSR"
        state.active -> "增强"
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
        val binding = LocalDesktopWindowsVideoEnhancement.current
        check(binding.configuration === configuration) { "NVIDIA settings must use the actual Root configuration" }
        val owner = LocalDesktopWindowsPlayerWindow.current
        val menuEnabled = LocalDesktopVideoEnhancementMenuEnabled.current
        // The Root recreates its binding wrapper on recompose; the session flow is stable.
        // A changed source or retired source (version zero) never revives an old popover.
        var menuOpen by remember(configuration, binding.state, owner, state.sourceVersion, state.identity, menuEnabled) {
            mutableStateOf(false)
        }
        val canExpand = menuEnabled && state.sourceVersion > 0 && owner?.isShowing == true
        val expanded = menuOpen && canExpand
        val reportExpanded by rememberUpdatedState(LocalDesktopVideoEnhancementMenuExpanded.current)
        SideEffect { reportExpanded(expanded) }
        DisposableEffect(configuration, binding.state, owner) {
            onDispose { reportExpanded(false) }
        }
        Box {
            AppTextButton(onClick = {
                    val actualState = binding.state.value
                    if (canExpand && actualState.sourceVersion == state.sourceVersion && actualState.identity == state.identity) menuOpen = true
                }, enabled = canExpand, modifier = (if (showStatus) Modifier.heightIn(min = 44.dp) else Modifier.size(44.dp))
                .semantics { contentDescription = "NVIDIA 增强详情" },
                contentPadding = if (showStatus) AppButtonDefaults.TextButtonContentPadding else PaddingValues(4.dp)) {
                AppText(if (showStatus) "NVIDIA · $label" else "RTX", style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, softWrap = false,
                    color = if (configurationError != null || state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            DesktopWindowsPlayerMenu(expanded, onDismissRequest = { menuOpen = false }, preferredHeight = 400.dp) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    DesktopWindowsVideoEnhancementSettingsContent(configuration, showProcessingQuality = false)
                }
            }
        }
        return
    }
    DesktopWindowsVideoEnhancementBody(configuration, state, onToggle)
    AppTextButton(onClick = onSettings) { AppText("NVIDIA 增强详情") }
}

@Composable
internal fun DesktopWindowsVideoEnhancementSettingsContent(showProcessingQuality: Boolean = true) {
    val binding = LocalDesktopWindowsVideoEnhancement.current
    DesktopWindowsVideoEnhancementSettingsContent(binding.configuration, showProcessingQuality)
}

@Composable
internal fun DesktopWindowsVideoEnhancementSettingsContent(configuration: DesktopVideoEnhancementConfiguration,
    showProcessingQuality: Boolean = true) {
    val binding = LocalDesktopWindowsVideoEnhancement.current
    check(binding.configuration === configuration) { "NVIDIA settings must use the actual Root configuration" }
    val state by binding.state.collectAsState()
    DesktopWindowsVideoEnhancementBody(configuration, state, { configuration.setAutomaticEnabled(it) }, showProcessingQuality)
}

@Composable
private fun DesktopWindowsVideoEnhancementBody(configuration: DesktopVideoEnhancementConfiguration,
    state: DesktopVideoEnhancementState, onToggle: (Boolean) -> Unit, showProcessingQuality: Boolean = false) {
    val preferences by configuration.preferences.collectAsState()
    val enabled = preferences.enabled
    val configurationError by configuration.error.collectAsState()
    var actionError by remember(configuration) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppSwitchPreference(title = "NVIDIA 自动增强",
            subtitle = "按当前视频和显示尺寸增强清晰度，可选 SDR 转 HDR。播放时可直接调整。",
            checked = enabled, onCheckedChange = { value ->
                actionError = null
                runCatching { onToggle(value) }.onFailure { actionError = "保存 NVIDIA 增强设置失败，请重试" }
            })
        DesktopWindowsSettingsChoice("增强内容", preferences.content,
            DesktopNvidiaVideoContent.entries.map { it to it.label }, enabled = enabled) { content ->
            actionError = null
            runCatching { configuration.setContent(content) }.onFailure { actionError = "保存增强内容失败，可重试" }
        }
        DesktopWindowsSettingsChoice("效果强度", preferences.intensity,
            DesktopNvidiaVideoIntensity.entries.map { it to it.label }, enabled = enabled) { intensity ->
            actionError = null
            runCatching { configuration.setIntensity(intensity) }.onFailure { actionError = "保存效果强度失败，可重试" }
        }
        if (showProcessingQuality) DesktopWindowsSettingsChoice("处理质量", preferences.quality,
            DesktopNvidiaVideoQuality.entries.map { it to it.label }, enabled = enabled && preferences.srEnabled) { quality ->
            actionError = null
            runCatching { configuration.setQuality(quality) }.onFailure { actionError = "保存 NVIDIA 处理质量失败，可重试" }
        }
        if (showProcessingQuality) {
            AppText("HDR 减弱也会减少亮度扩展。计算质量由完整应用设置中的处理质量决定。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppText("HDR 自动仅在 HDR 显示目标可用时转换 SDR；原生 HDR 保持原样。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (enabled && !preferences.srEnabled) AppText("仅 HDR 自动使用原尺寸转换，不使用清晰度处理质量。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (showProcessingQuality && enabled && preferences.srEnabled && (!state.veyraAvailable || state.backend != NvidiaVideoBackend.VEYRA_CORE)) AppText(
                if (!state.veyraAvailable) "当前无法应用处理质量，选择已保存，可用时自动应用。"
                else "当前视频暂不支持质量调节，选择已保存，支持时自动应用。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (enabled && (!state.veyraAvailable || state.backend != NvidiaVideoBackend.VEYRA_CORE ||
                    state.error != null || state.unavailableReason != null)) AppText(
                "当前效果强度未应用，选择已保存；可用时自动应用。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppText(state.statusText, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val label = desktopVideoEnhancementCompactLabel(state, enabled, configurationError)
            val status = state.unavailableReason ?: when {
                !enabled -> "已关闭，使用原画播放。"
                state.error != null || configurationError != null -> "增强异常，详情见下方。"
                state.pending -> "正在准备增强。"
                state.active -> "当前状态：$label。"
                else -> "当前使用原画，满足条件后自动增强。"
            }
            val intensityNotApplied = enabled && (!state.veyraAvailable || state.backend != NvidiaVideoBackend.VEYRA_CORE ||
                state.error != null || state.unavailableReason != null)
            AppText(if (intensityNotApplied) "$status 强度选择已保存，当前未应用。" else status,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Native status distinguishes accepted driver requests and actual output
        // conditions. The switch itself is never proof of VSR/Tensor/HDR activity.
        state.error?.let { AppText(it, color = MaterialTheme.colorScheme.error) }
        configurationError?.takeIf { it != state.error }?.let { AppText(it, color = MaterialTheme.colorScheme.error) }
        actionError?.let { AppText(it, color = MaterialTheme.colorScheme.error) }
    }
}

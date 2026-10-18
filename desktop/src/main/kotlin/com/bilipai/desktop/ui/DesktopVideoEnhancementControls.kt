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
import com.bilipai.desktop.nvidia.DesktopNvidiaStatusText
import com.bilipai.desktop.nvidia.desktopNvidiaRequestedDetail
import com.bilipai.desktop.nvidia.desktopNvidiaSwitchSubtitle
import com.bilipai.desktop.player.DesktopVideoEnhancementActivation
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
    /** Re-detects the GPU through the owning session. Null where no session exists, so no button is offered. */
    val redetectGpu: (() -> Unit)? = null,
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

/** The compact word for what the activation gate decided, or null when the live pipeline decides. Detection off never has one. */
private fun desktopVideoEnhancementGateLabel(state: DesktopVideoEnhancementState): String? = when (state.activation) {
    DesktopVideoEnhancementActivation.PROBING -> "检测中"
    DesktopVideoEnhancementActivation.NO_NVIDIA, DesktopVideoEnhancementActivation.UNSUPPORTED,
    DesktopVideoEnhancementActivation.UNIDENTIFIED -> "不可用"
    DesktopVideoEnhancementActivation.PAUSED_GAME, DesktopVideoEnhancementActivation.PAUSED_BATTERY,
    DesktopVideoEnhancementActivation.PAUSED_REMOTE -> "已暂停"
    DesktopVideoEnhancementActivation.FAILED_REMEMBERED, DesktopVideoEnhancementActivation.FAILED_THIS_VIDEO,
    DesktopVideoEnhancementActivation.FAILED_EXTENDED_RANGE -> "已回退"
    else -> if (state.steppedDown) "已回退" else null
}

/** False only when no usable NVIDIA GPU is known. Probing and detection off still count as capable. */
internal fun desktopVideoEnhancementCapable(state: DesktopVideoEnhancementState): Boolean = when (state.activation) {
    DesktopVideoEnhancementActivation.NO_NVIDIA, DesktopVideoEnhancementActivation.UNSUPPORTED,
    DesktopVideoEnhancementActivation.UNIDENTIFIED -> false
    else -> true
}

internal fun desktopVideoEnhancementCompactLabel(state: DesktopVideoEnhancementState,
    enabled: Boolean, configurationError: String?): String {
    val gateLabel = desktopVideoEnhancementGateLabel(state)
    return when {
        configurationError != null || state.error != null -> "异常"
        !enabled -> "关闭"
        gateLabel != null -> gateLabel
        state.unavailableReason != null -> "不可用"
        state.active && state.backend == NvidiaVideoBackend.VEYRA_CORE && state.srEnabledRequested && state.hdrConversionActive -> "清晰度＋HDR"
        state.active && state.backend == NvidiaVideoBackend.VEYRA_CORE && state.srEnabledRequested -> "清晰度"
        state.active && state.driverVsrAccepted && state.hdrConversionActive -> "VSR · HDR"
        state.active && state.hdrConversionActive -> "HDR"
        state.active && state.driverVsrAccepted -> "VSR"
        state.active -> "增强"
        !state.active && state.backend == NvidiaVideoBackend.VEYRA_CORE && state.veyraSubmitted -> "准备中"
        state.pending -> "处理中"
        else -> "原画"
    }
}

@Composable
fun DesktopVideoEnhancementControls(state: DesktopVideoEnhancementState,
    configuration: DesktopVideoEnhancementConfiguration, onToggle: (Boolean) -> Unit, onSettings: (() -> Unit)? = null) {
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
    onSettings?.let { openSettings ->
        AppTextButton(onClick = openSettings) { AppText("NVIDIA 增强详情") }
    }
}

/** All original player bars use this same compact view of the existing Root enhancement session. */
@Composable
internal fun DesktopWindowsNvidiaPlayerSlot(menuEnabled: Boolean, onMenuExpandedChanged: (Boolean) -> Unit) {
    val binding = LocalDesktopWindowsVideoEnhancement.current
    val state by binding.state.collectAsState()
    DesktopVideoEnhancementCompactSlot(showStatus = false, menuEnabled = menuEnabled,
        onMenuExpandedChanged = onMenuExpandedChanged) {
        DesktopVideoEnhancementControls(state, binding.configuration, binding.configuration::setAutomaticEnabled)
    }
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
    DesktopWindowsVideoEnhancementBody(configuration, state, { configuration.setAutomaticEnabled(it) }, showProcessingQuality,
        binding.redetectGpu)
}

@Composable
private fun DesktopWindowsVideoEnhancementBody(configuration: DesktopVideoEnhancementConfiguration,
    state: DesktopVideoEnhancementState, onToggle: (Boolean) -> Unit, showProcessingQuality: Boolean = false,
    redetectGpu: (() -> Unit)? = null) {
    val preferences by configuration.preferences.collectAsState()
    val enabled = preferences.enabled
    val configurationError by configuration.error.collectAsState()
    var actionError by remember(configuration) { mutableStateOf<String?>(null) }
    // With detection off these are today's controls and today's wording. With detection on, the controls that only
    // the shared core can apply stay editable on that route alone; the saved choices are kept either way.
    val detecting = state.activation != DesktopVideoEnhancementActivation.LEGACY
    val componentMissing = !state.veyraAvailable
    val routeNotCore = (if (detecting) state.route else state.backend) != NvidiaVideoBackend.VEYRA_CORE
    val strengthEditable = enabled && (!detecting || (!componentMissing && !routeNotCore))
    val qualityEditable = enabled && preferences.srEnabled && (!detecting || (!componentMissing && !routeNotCore))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppSwitchPreference(title = "NVIDIA 自动增强",
            subtitle = if (detecting) desktopNvidiaSwitchSubtitle(desktopVideoEnhancementCapable(state))
                else "按当前视频和显示尺寸增强清晰度，可选 SDR 转 HDR。播放时可直接调整。",
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
            DesktopNvidiaVideoIntensity.entries.map { it to it.label }, enabled = strengthEditable) { intensity ->
            actionError = null
            runCatching { configuration.setIntensity(intensity) }.onFailure { actionError = "保存效果强度失败，可重试" }
        }
        // The stored level is the ceiling; the automatic mode picks the real level per GPU and never exceeds it.
        if (showProcessingQuality) DesktopWindowsSettingsChoice("质量上限", preferences.quality,
            DesktopNvidiaVideoQuality.entries.map { it to it.label }, enabled = qualityEditable) { quality ->
            actionError = null
            runCatching { configuration.setQuality(quality) }.onFailure { actionError = "保存 NVIDIA 处理质量失败，可重试" }
        }
        if (showProcessingQuality) {
            AppText(DesktopNvidiaStatusText.QUALITY_CEILING_HELPER,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppText("HDR 自动仅在 HDR 显示目标可用时转换 SDR；原生 HDR 保持原样。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (enabled && !preferences.srEnabled) AppText("仅 HDR 自动使用原尺寸转换，不使用清晰度处理质量。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (showProcessingQuality && enabled && preferences.srEnabled && (componentMissing || routeNotCore)) AppText(
                if (componentMissing) "当前无法应用处理质量，选择已保存，可用时自动应用。"
                else "当前视频暂不支持质量调节，选择已保存，支持时自动应用。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (enabled && (componentMissing || routeNotCore ||
                    state.error != null || state.unavailableReason != null)) AppText(
                "当前效果强度未应用，选择已保存；可用时自动应用。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // What was asked for, never what was applied, and only where the shared core can take quality and strength.
            val intensityLabel = DesktopNvidiaVideoIntensity.entries
                .firstOrNull { it.nativePercent == state.requestedIntensityPercent }?.label ?: preferences.intensity.label
            val requestedDetail = if (detecting && (state.pending || state.veyraSubmitted || state.active)) {
                state.requestedQualityLevel?.let { level -> desktopNvidiaRequestedDetail(state.backend, level, intensityLabel) }
            } else null
            requestedDetail?.let { AppText(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            // The single status line. With detection on, the session builds it from the shared status texts.
            AppText(state.statusText, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val label = desktopVideoEnhancementCompactLabel(state, enabled, configurationError)
            val status = if (detecting) state.statusText else state.unavailableReason ?: when {
                !enabled -> "已关闭，使用原画播放。"
                state.error != null || configurationError != null -> "增强异常，详情见下方。"
                !state.active && state.backend == NvidiaVideoBackend.VEYRA_CORE && state.veyraSubmitted -> "正在准备增强画面。"
                state.pending -> "正在准备增强。"
                state.active -> "当前状态：$label。"
                else -> "当前使用原画，满足条件后自动增强。"
            }
            val intensityNotApplied = enabled && (componentMissing || routeNotCore ||
                state.error != null || state.unavailableReason != null)
            AppText(if (intensityNotApplied) "$status 强度选择已保存，当前未应用。" else status,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.notice?.let { AppText(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        // The one contextual action. It only re-reads the GPU; it changes no GPU, driver or system setting.
        if (redetectGpu != null && detecting && (showProcessingQuality ||
                state.activation == DesktopVideoEnhancementActivation.FAILED_REMEMBERED ||
                state.activation == DesktopVideoEnhancementActivation.UNIDENTIFIED)) {
            AppTextButton(onClick = redetectGpu) { AppText("重新检测显卡") }
        }
        // Native status distinguishes accepted driver requests and actual output
        // conditions. The switch itself is never proof of VSR or HDR activity.
        state.error?.let { AppText(it, color = MaterialTheme.colorScheme.error) }
        state.failureDiagnostic?.takeIf { state.error != null }?.let {
            AppText(it.panelText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        configurationError?.takeIf { it != state.error }?.let { AppText(it, color = MaterialTheme.colorScheme.error) }
        actionError?.let { AppText(it, color = MaterialTheme.colorScheme.error) }
    }
}

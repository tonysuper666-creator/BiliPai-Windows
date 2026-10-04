package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.core.ui.AppDialogAction
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.feature.settings.SettingsSingleChoicePreference
import com.bilipai.desktop.player.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** The physical Root supplies its sole Store/native-video/native-listening controller.
 * Each original page supplies its existing Context; no settings or player are created here. */
internal val LocalDesktopWindowsAudioOutputController = staticCompositionLocalOf<DesktopWindowsAudioOutputController> {
    error("Windows audio output requires the actual Root native audio controller")
}

@Composable
internal fun DesktopWindowsAudioOutputSettings(context: DesktopOriginalPlayerSettingsContext) {
    DesktopWindowsAudioOutputContent(rememberDesktopWindowsAudioOutputVisibleContext(context))
}

/** The retained source's Context remains valid in background. Narrow its existing
 * admission to this actual visible Compose leaf; share its Store and delegate its gate. */
@Composable
private fun rememberDesktopWindowsAudioOutputVisibleContext(parent: DesktopOriginalPlayerSettingsContext): DesktopOriginalPlayerSettingsContext {
    val foreground = LocalDesktopDetailForeground.current
    val foregroundNow by rememberUpdatedState(foreground)
    // Hide -> restore creates a fresh leaf identity; an old hidden request never
    // borrows the new foreground lifetime even when its media source is retained.
    val alive = remember(parent, foreground) { AtomicBoolean(true) }
    DisposableEffect(alive) { onDispose { alive.set(false) } }
    return remember(parent, alive) {
        val owns = { alive.get() && foregroundNow && parent.isCurrentForOriginalWrite() }
        DesktopOriginalPlayerSettingsContext(parent.pluginContext, owns, { action ->
            var applied = false
            parent.commit { if (owns()) { action(); applied = true } }
            applied
        })
    }
}

@Composable
private fun DesktopWindowsAudioOutputContent(context: DesktopOriginalPlayerSettingsContext) {
    val controller = LocalDesktopWindowsAudioOutputController.current
    val preferences by controller.preferences.collectAsState()
    val configurationError by controller.configurationError.collectAsState()
    val video by controller.video.collectAsState()
    val listening by controller.listening.collectAsState()
    val devices by controller.devices.collectAsState()
    val compositionScope = rememberCoroutineScope()
    val pageJob = remember(controller, context) { SupervisorJob(compositionScope.coroutineContext[Job]) }
    val pageScope = remember(pageJob) { CoroutineScope(compositionScope.coroutineContext + pageJob) }
    var current by remember(context, controller) { mutableStateOf(context.isCurrentForOriginalWrite()) }
    var saving by remember(context, controller) { mutableStateOf(false) }
    var operationFailure by remember(context, controller) { mutableStateOf<String?>(null) }
    DisposableEffect(pageJob) { onDispose { pageJob.cancel() } }
    LaunchedEffect(context, controller, pageJob) {
        while (isActive && pageJob.isActive) {
            current = context.isCurrentForOriginalWrite()
            if (!current) { pageJob.cancel(); return@LaunchedEffect }
            delay(100)
        }
    }
    fun launchWrite(action: suspend () -> Unit) {
        if (!current || !pageScope.isActive || saving || !context.isCurrentForOriginalWrite()) return
        saving = true
        operationFailure = null
        pageScope.launch {
            try {
                action()
                ensureActive()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (context.isCurrentForOriginalWrite()) operationFailure = "音频输出设置未保存，请重试。"
            } finally {
                if (pageScope.isActive && context.isCurrentForOriginalWrite()) saving = false
            }
        }
    }
    LaunchedEffect(controller, context, pageJob) {
        try { controller.refreshDevices(context) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (pageScope.isActive && context.isCurrentForOriginalWrite())
                operationFailure = "无法读取音频设备，请刷新设备列表。"
        }
    }
    val enabled = current && pageScope.isActive && !saving
    val options = remember(devices.devices) {
        listOf(
            AppSegmentOption("auto", "跟随 Windows 默认输出"),
            AppSegmentOption("wasapi", "Windows 默认 WASAPI"),
        ) + devices.devices.filter { it.name.startsWith("wasapi/") && it.name.length > "wasapi/".length }
            .map { AppSegmentOption(it.name, it.description.ifBlank { it.name }) }
    }
    val savedDevice = when (preferences.deviceId) {
        "auto" -> "跟随 Windows 默认输出"
        "wasapi" -> "Windows 默认 WASAPI"
        else -> devices.devices.singleOrNull { it.name == preferences.deviceId }?.description
            ?.takeIf { it.isNotBlank() } ?: "已保存的设备当前未枚举：${preferences.deviceId}"
    }
    Column {
        AppPreferenceSectionTitle("Windows 音频输出")
        AppPreferenceGroup {
            AppSwitchPreference(
                title = "WASAPI 独占输出",
                subtitle = "绕过系统混音，更改从下一次播放生效。独占时其他应用可能无法使用同一设备。",
                checked = preferences.exclusive,
                enabled = enabled,
                onCheckedChange = { value -> launchWrite { controller.updateExclusive(context, value) } },
            )
            AppPreferenceDivider()
            SettingsSingleChoicePreference(
                title = "输出设备",
                subtitle = "当前选择：$savedDevice。默认设备随 Windows 设置变化。",
                options = options,
                selectedValue = preferences.deviceId,
                enabled = enabled && !devices.querying,
                onSelectionChange = { value -> launchWrite { controller.selectDevice(context, value) } },
            )
            AppPreferenceDivider()
            AppPreference(
                title = if (devices.querying) "正在读取设备…" else "刷新设备列表",
                subtitle = devices.error ?: if (!devices.available) "当前播放器尚无法枚举设备。" else "读取实际 WASAPI 设备，不会切换输出。",
                onClick = if (enabled && !devices.querying) ({ launchWrite { controller.refreshDevices(context) } }) else null,
                showChevron = false,
            )
            configurationError?.takeIf { it.isNotBlank() }?.let { message ->
                AppText(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                AppText("配置异常时独占偏好回退为关闭、设备回退为自动；请重新选择输出设备与独占开关。",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            operationFailure?.let { message ->
                AppText(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            if (saving) AppText("正在保存…", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        DesktopWindowsAudioOutputObservation("视频 / BV 听视频输出", video)
        DesktopWindowsAudioOutputObservation("独立音频播放器输出", listening)
        AppText(
            "音量、静音和播放速度沿用播放器现有设置。显示的格式来自播放器读回；本界面不据此认证位完美输出或光纤链路格式。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun DesktopWindowsAudioOutputObservation(title: String, status: DesktopWindowsAudioOutputStatus) {
    AppPreferenceSectionTitle(title)
    AppPreferenceGroup {
        AppPreference(title = "本次播放状态", subtitle = desktopWindowsAudioOutputPhaseLabel(status), showChevron = false)
        status.error?.takeIf { it.isNotBlank() }?.let { message ->
            AppText(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
        status.previousOutputError?.takeIf { it.isNotBlank() }?.let { message ->
            AppText("上次输出错误：$message", color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp))
        }
        AppPreferenceDivider()
        AppPreference(title = "实际音频后端", value = status.activeDriver ?: "尚未读回", showChevron = false)
        AppPreferenceDivider()
        AppPreference(
            title = "播放器设备配置读回",
            subtitle = "这是播放器配置值；默认设备的实际端点 ID 尚未确认。",
            value = status.configuredDeviceId ?: "尚未读回", showChevron = false,
        )
        AppPreferenceDivider()
        AppPreference(title = "源音频格式", subtitle = desktopWindowsAudioPcmLabel(status.sourcePcm), showChevron = false)
        AppPreferenceDivider()
        AppPreference(title = "播放器输出格式", subtitle = desktopWindowsAudioPcmLabel(status.outputPcm), showChevron = false)
    }
}

internal fun desktopWindowsAudioOutputPhaseLabel(status: DesktopWindowsAudioOutputStatus): String = when (status.phase) {
    DesktopWindowsAudioOutputPhase.IDLE -> "尚未播放，未验证输出模式。"
    DesktopWindowsAudioOutputPhase.PENDING_NEXT_PLAY -> "已保存，等待下一次播放应用；当前输出不会被自动重载。"
    DesktopWindowsAudioOutputPhase.OPENING -> "正在打开音频输出，独占状态尚未确认。"
    DesktopWindowsAudioOutputPhase.ACTIVE_SHARED -> "播放器已确认共享输出。"
    DesktopWindowsAudioOutputPhase.ACTIVE_EXCLUSIVE -> "播放器已确认 WASAPI 独占输出。"
    DesktopWindowsAudioOutputPhase.ERROR -> "音频输出失败。请检查设备占用、设备状态或格式支持，并在下次播放重试。"
    DesktopWindowsAudioOutputPhase.UNAVAILABLE -> "当前播放器的音频输出能力不可用。"
}

internal fun desktopWindowsAudioPcmLabel(pcm: DesktopWindowsAudioPcm?): String {
    if (pcm == null) return "尚未读回"
    val rate = pcm.sampleRate?.takeIf { it > 0 }?.let { "$it Hz" } ?: "采样率未知"
    val channels = pcm.channels?.takeIf { it.isNotBlank() }
        ?: pcm.channelCount?.takeIf { it > 0 }?.let { "$it 声道" } ?: "声道未知"
    val format = pcm.format?.takeIf { it.isNotBlank() } ?: "样本格式未知"
    return "$rate · $channels · $format"
}

/** Mounted in the complete original video settings panel, with the same current source/page Context. */
@Composable
internal fun DesktopWindowsAudioOutputPanelEntry(context: DesktopOriginalPlayerSettingsContext) {
    val foreground = LocalDesktopDetailForeground.current
    var show by remember(context) { mutableStateOf(false) }
    AppPreference(
        title = "Windows 音频输出",
        subtitle = "输出设备、WASAPI 独占偏好及实际输出状态",
        onClick = { if (foreground && context.isCurrentForOriginalWrite()) show = true },
    )
    if (show) DesktopWindowsAudioOutputDialog(context, onDismiss = { show = false })
}

/** Original video/audio popup owns this dialog through its unchanged existing Context. */
@Composable
internal fun DesktopWindowsAudioOutputDialog(context: DesktopOriginalPlayerSettingsContext, onDismiss: () -> Unit) {
    val pageContext = rememberDesktopWindowsAudioOutputVisibleContext(context)
    val dismissNow by rememberUpdatedState(onDismiss)
    LaunchedEffect(pageContext) {
        while (isActive) {
            if (!pageContext.isCurrentForOriginalWrite()) { dismissNow(); return@LaunchedEffect }
            delay(100)
        }
    }
    if (!pageContext.isCurrentForOriginalWrite()) return
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { AppText("Windows 音频输出") },
        text = {
            Column(Modifier.widthIn(max = 640.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                DesktopWindowsAudioOutputContent(pageContext)
            }
        },
        confirmButton = { AppDialogAction(onClick = onDismiss) { AppText("关闭") } },
    )
}

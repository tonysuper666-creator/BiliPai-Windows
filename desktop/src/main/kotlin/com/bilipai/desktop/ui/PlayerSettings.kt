package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import com.bilipai.desktop.settings.desktopSettingsSearchFocusAnchor
import com.android.purebilibili.feature.settings.SettingsSearchTarget
import com.android.purebilibili.feature.settings.SettingsSearchFocusIds
import androidx.compose.runtime.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.android.purebilibili.core.store.DesktopOriginalReplySettings
import com.android.purebilibili.core.ui.LocalDetailedCommentTimeEnabled
import com.android.purebilibili.feature.settings.DesktopOriginalDetailedCommentTimeSetting
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.parseNewPlaybackSpeedOption
import com.android.purebilibili.feature.settings.resolveDefaultAudioQualityOptions
import com.android.purebilibili.feature.video.subtitle.SubtitleAutoPreference
import com.android.purebilibili.feature.video.danmaku.parseDanmakuBlockRules
import com.bilipai.desktop.danmaku.DanmakuSettings
import com.bilipai.desktop.player.PlaybackMode
import com.bilipai.desktop.player.PlayerPreferences
import java.util.Locale
import kotlinx.coroutines.launch

fun playbackModeLabel(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.STOP_AFTER_CURRENT -> "播完停止"
    PlaybackMode.SEQUENTIAL -> "顺序播放"
    PlaybackMode.SHUFFLE -> "随机播放"
    PlaybackMode.REPEAT_ONE -> "单曲循环"
    PlaybackMode.REPEAT_ALL -> "列表循环"
}

fun playbackSpeedLabel(speed: Double): String = String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.')

/** Audio selection can finish while this dialog's editable draft remains open. */
internal fun resolvePlaybackSettingsDraftSave(draft: PlayerPreferences, latest: PlayerPreferences): PlayerPreferences =
    draft.copy(
        lastSelectedAudioQuality = latest.lastSelectedAudioQuality,
        speed = if (draft.rememberLastSpeed) draft.speed else draft.defaultSpeed,
    ).normalized()

internal val LocalDesktopDetailedCommentTimeContext = staticCompositionLocalOf<DesktopPluginContext> {
    error("Root same global comment settings context is required")
}

@Composable
fun PlaybackSettingsDialog(preferences: PlayerPreferences, onPreferencesChange: (PlayerPreferences) -> Unit, onDismiss: () -> Unit) {
    val latestPreferences by rememberUpdatedState(preferences)
    val commentContext = LocalDesktopDetailedCommentTimeContext.current
    val commentScope = rememberCoroutineScope()
    val detailedCommentTimeEnabled = LocalDetailedCommentTimeEnabled.current
    val initialDraft = remember { preferences }
    var draft by remember { mutableStateOf(preferences) }
    var hardwareDecodeDraftChanged by remember { mutableStateOf(false) }
    var newSpeed by remember { mutableStateOf("") }
    var speedError by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("播放设置") }, text = {
        Column(Modifier.width(520.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("播放结束后", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PlaybackMode.entries.forEach { mode ->
                    FilterChip(selected = draft.playbackMode == mode, onClick = { draft = draft.copy(playbackMode = mode) }, label = { Text(playbackModeLabel(mode)) })
                }
            }
            PlayerSwitch("记住上次播放倍速", draft.rememberLastSpeed) { draft = draft.copy(rememberLastSpeed = it) }
            com.android.purebilibili.feature.settings.DesktopOriginalVideoAmbientSettingsContent()
            Column(Modifier.desktopSettingsSearchFocusAnchor(SettingsSearchTarget.PLAYBACK, SettingsSearchFocusIds.PLAYBACK_DECODER), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PlayerSwitch("启用硬件解码", draft.hardwareDecodeEnabled) { hardwareDecodeDraftChanged = true; draft = draft.copy(hardwareDecodeEnabled = it) }
                val codecOptions = listOf("avc1" to "AVC", "hev1" to "HEVC", "av01" to "AV1")
                Text("首选视频编码", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    codecOptions.forEach { (value, label) -> FilterChip(selected = draft.videoCodecPreference == value,
                        onClick = { draft = draft.copy(videoCodecPreference = value) }, label = { Text(label) }) }
                }
                Text("无法播放时改用", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    codecOptions.forEach { (value, label) -> FilterChip(selected = draft.videoSecondCodecPreference == value,
                        onClick = { draft = draft.copy(videoSecondCodecPreference = value) }, label = { Text(label) }) }
                }
            }
            Column(Modifier.desktopSettingsSearchFocusAnchor(SettingsSearchTarget.PLAYBACK, SettingsSearchFocusIds.PLAYBACK_NETWORK), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("默认音质", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    resolveDefaultAudioQualityOptions().forEach { option -> FilterChip(selected = draft.defaultAudioQuality == option.value,
                        onClick = { draft = draft.copy(defaultAudioQuality = option.value) }, label = { Text(option.label) }) }
                }
            }
            Column(Modifier.desktopSettingsSearchFocusAnchor(SettingsSearchTarget.PLAYBACK, SettingsSearchFocusIds.PLAYBACK_INTERACTION), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DesktopOriginalDetailedCommentTimeSetting(detailedCommentTimeEnabled) { enabled ->
                    commentScope.launch { DesktopOriginalReplySettings.setDetailedCommentTimeEnabled(commentContext, enabled) }
                }
                Text("自动启用字幕", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(SubtitleAutoPreference.OFF to "关闭", SubtitleAutoPreference.ON to "开启",
                        SubtitleAutoPreference.WITHOUT_AI to "无 AI", SubtitleAutoPreference.AUTO to "自动").forEach { (value, label) ->
                        FilterChip(selected = draft.subtitleAutoPreference == value,
                            onClick = { draft = draft.copy(subtitleAutoPreference = value) }, label = { Text(label) })
                    }
                }
            }
            Column(Modifier.desktopSettingsSearchFocusAnchor(SettingsSearchTarget.PLAYBACK, SettingsSearchFocusIds.PLAYBACK_SPEED), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("默认倍速", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    draft.speedOptions.forEach { speed ->
                        FilterChip(selected = draft.defaultSpeed == speed, onClick = { draft = draft.copy(defaultSpeed = speed) }, label = { Text("${playbackSpeedLabel(speed)}×") })
                    }
                }
            }
            Text("倍速菜单", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                draft.speedOptions.forEach { speed ->
                    InputChip(selected = false, enabled = speed != 1.0, onClick = {
                        draft = draft.copy(speedOptions = draft.speedOptions.filter { it != speed }).normalized()
                    }, label = { Text("${playbackSpeedLabel(speed)}×") }, trailingIcon = { if (speed != 1.0) Text("×") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = newSpeed, onValueChange = { newSpeed = it; speedError = null }, label = { Text("新倍速（0.1–8，最多两位小数）") },
                    singleLine = true, modifier = Modifier.weight(1f), isError = speedError != null)
                Button(onClick = {
                    val parsed = parseNewPlaybackSpeedOption(newSpeed, draft.speedOptions.map(Double::toFloat))
                    if (parsed == null) speedError = "请输入尚未添加的有效倍速。"
                    else {
                        draft = draft.copy(speedOptions = draft.speedOptions + parsed.toDouble()).normalized()
                        newSpeed = ""; speedError = null
                    }
                }) { Text("添加") }
            }
            speedError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            PlayerSwitch("仅播放音频", draft.audioOnly) { draft = draft.copy(audioOnly = it) }
            TextButton(onClick = { hardwareDecodeDraftChanged = true; draft = PlayerPreferences(danmaku = draft.danmaku) }) { Text("恢复播放默认设置") }
        }
    }, confirmButton = { Button(onClick = {
        val saved = resolvePlaybackSettingsDraftSave(draft, latestPreferences)
        val speedIntent = draft.speed != initialDraft.speed || draft.defaultSpeed != initialDraft.defaultSpeed ||
            draft.rememberLastSpeed != initialDraft.rememberLastSpeed || draft.speedOptions != initialDraft.speedOptions
        onPreferencesChange(saved.copy(
            speed = if (speedIntent) saved.speed else latestPreferences.speed,
            hardwareDecodeEnabled = if (hardwareDecodeDraftChanged) draft.hardwareDecodeEnabled else latestPreferences.hardwareDecodeEnabled))
        onDismiss()
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun PlayerSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f)); Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PlayerSettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, formatted: String, steps: Int = 0, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Text(formatted, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps)
    }
}

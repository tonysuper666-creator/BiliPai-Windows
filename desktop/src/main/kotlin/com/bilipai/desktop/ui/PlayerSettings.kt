package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import com.bilipai.desktop.settings.desktopSettingsSearchFocusAnchor
import com.android.purebilibili.feature.settings.SettingsSearchTarget
import com.android.purebilibili.feature.settings.SettingsSearchFocusIds
import androidx.compose.runtime.*
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

@Composable
fun PlaybackSettingsDialog(preferences: PlayerPreferences, onPreferencesChange: (PlayerPreferences) -> Unit, onDismiss: () -> Unit) {
    val latestPreferences by rememberUpdatedState(preferences)
    var draft by remember { mutableStateOf(preferences) }
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
            Column(Modifier.desktopSettingsSearchFocusAnchor(SettingsSearchTarget.PLAYBACK, SettingsSearchFocusIds.PLAYBACK_DECODER), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PlayerSwitch("启用硬件解码", draft.hardwareDecodeEnabled) { draft = draft.copy(hardwareDecodeEnabled = it) }
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
            TextButton(onClick = { draft = PlayerPreferences(danmaku = draft.danmaku) }) { Text("恢复播放默认设置") }
        }
    }, confirmButton = { Button(onClick = {
        onPreferencesChange(resolvePlaybackSettingsDraftSave(draft, latestPreferences)); onDismiss()
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun DanmakuSettingsDialog(preferences: PlayerPreferences, onPreferencesChange: (PlayerPreferences) -> Unit, onDismiss: () -> Unit) {
    val latestPreferences by rememberUpdatedState(preferences)
    var draft by remember { mutableStateOf(preferences.danmaku) }
    var rules by remember { mutableStateOf((preferences.danmaku.blockedKeywords + preferences.danmaku.blockedRules).distinct().joinToString("\n")) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("弹幕设置") }, text = {
        Column(Modifier.width(540.dp).heightIn(max = 640.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PlayerSwitch("显示弹幕", draft.enabled) { draft = draft.copy(enabled = it) }
            PlayerSettingSlider("不透明度", draft.opacity, 0.1f..1f, "${(draft.opacity * 100).toInt()}%") { draft = draft.copy(opacity = it) }
            PlayerSettingSlider("字号", draft.fontScale, 0.5f..2f, "${(draft.fontScale * 100).toInt()}%") { draft = draft.copy(fontScale = it) }
            PlayerSettingSlider("显示区域", draft.displayAreaRatio, 0.25f..1f, "${(draft.displayAreaRatio * 100).toInt()}%") { draft = draft.copy(displayAreaRatio = it) }
            PlayerSettingSlider("滚动停留时间", draft.scrollDurationSeconds, 2f..20f, "${playbackSpeedLabel(draft.scrollDurationSeconds.toDouble())}秒") { draft = draft.copy(scrollDurationSeconds = it) }
            PlayerSettingSlider("速度系数（越大越慢）", draft.speedFactor, 0.25f..4f, playbackSpeedLabel(draft.speedFactor.toDouble())) { draft = draft.copy(speedFactor = it) }
            PlayerSettingSlider("固定弹幕停留", draft.staticDurationSeconds, 1f..20f, "${playbackSpeedLabel(draft.staticDurationSeconds.toDouble())}秒") { draft = draft.copy(staticDurationSeconds = it) }
            PlayerSettingSlider("行距", draft.lineHeight, 1f..3f, playbackSpeedLabel(draft.lineHeight.toDouble())) { draft = draft.copy(lineHeight = it) }
            PlayerSettingSlider("字重", draft.fontWeight.toFloat(), 1f..9f, draft.fontWeight.toString(), steps = 7) { draft = draft.copy(fontWeight = it.toInt()) }
            PlayerSwitch("文字描边", draft.strokeEnabled) { draft = draft.copy(strokeEnabled = it) }
            if (draft.strokeEnabled) PlayerSettingSlider("描边宽度", draft.strokeWidth, 0f..5f, playbackSpeedLabel(draft.strokeWidth.toDouble())) { draft = draft.copy(strokeWidth = it) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(draft.allowScroll, { draft = draft.copy(allowScroll = !draft.allowScroll) }, label = { Text("滚动") })
                FilterChip(draft.allowTop, { draft = draft.copy(allowTop = !draft.allowTop) }, label = { Text("顶部") })
                FilterChip(draft.allowBottom, { draft = draft.copy(allowBottom = !draft.allowBottom) }, label = { Text("底部") })
                FilterChip(draft.allowColorful, { draft = draft.copy(allowColorful = !draft.allowColorful) }, label = { Text("彩色") })
                FilterChip(draft.allowSpecial, { draft = draft.copy(allowSpecial = !draft.allowSpecial) }, label = { Text("高级 / BAS") })
            }
            PlayerSwitch("合并重复弹幕", draft.mergeDuplicates) { draft = draft.copy(mergeDuplicates = it) }
            if (draft.mergeDuplicates) {
                PlayerSettingSlider("合并时间窗口", draft.duplicateMergeWindowMs.toFloat(), 50f..5_000f, "${draft.duplicateMergeWindowMs}毫秒") {
                    draft = draft.copy(duplicateMergeWindowMs = it.toInt())
                }
                PlayerSettingSlider("显示重复次数的阈值", draft.duplicateMergeCountThreshold.toFloat(), 2f..20f, draft.duplicateMergeCountThreshold.toString(), steps = 17) {
                    draft = draft.copy(duplicateMergeCountThreshold = it.toInt())
                }
            }
            OutlinedTextField(value = rules, onValueChange = { rules = it.take(150_000) }, label = { Text("屏蔽规则，每行一条") },
                placeholder = { Text("关键词\nregex:正则表达式\nuid:用户弹幕哈希") }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8)
            TextButton(onClick = { draft = DanmakuSettings(); rules = "" }) { Text("恢复弹幕默认设置") }
        }
    }, confirmButton = { Button(onClick = {
        onPreferencesChange(latestPreferences.copy(danmaku = draft.copy(blockedKeywords = emptyList(), blockedRules = parseDanmakuBlockRules(rules))).normalized()); onDismiss()
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

package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlayerPreferences
import com.bilipai.desktop.player.PlayerTrack
import com.android.purebilibili.feature.video.playback.audio.AudioSelectionDecision
import com.android.purebilibili.feature.video.playback.audio.resolveAudioQualityControlPresentation
import com.android.purebilibili.feature.video.subtitle.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.SwingUtilities

/** Native video surface plus real transport controls; navigation and the playback queue stay with the caller. */
@Composable
fun PlayerPanel(
    player: MpvPlayer,
    preferences: PlayerPreferences,
    onPreferencesChange: (PlayerPreferences) -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    onPreviousPart: (() -> Unit)? = null,
    onNextPart: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    onScreenshot: (() -> Unit)? = null,
    onOnlineSubtitles: (() -> Unit)? = null,
    onMessage: (String) -> Unit = {},
    renderSurface: Boolean = true,
    onPictureInPicture: (() -> Unit)? = null,
    onSeekTo: ((Double) -> Unit)? = null,
    onManualSubtitleSelection: () -> Unit = {},
    audioSelection: AudioSelectionDecision? = null,
    onAudioQualityChange: ((Int) -> Unit)? = null,
    automaticSubtitleMode: SubtitleDisplayMode = SubtitleDisplayMode.OFF,
    automaticSubtitleTracks: List<SubtitleTrackMeta> = emptyList(),
    onAutomaticSubtitleMode: ((SubtitleDisplayMode) -> Unit)? = null,
    surfaceOnly: Boolean = false,
    viewPoints: List<com.android.purebilibili.data.model.response.ViewPoint> = emptyList(),
    commandOverlay: (@Composable () -> Unit)? = null,
    onOriginalDanmakuSettings: (() -> Unit)? = null,
    onOriginalDanmakuToggle: (() -> Unit)? = null,
) {
    val state by player.state.collectAsState()
    var videoSurfaceSize by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val latestPreferences by rememberUpdatedState(preferences)
    var seeking by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var playbackSettings by remember { mutableStateOf(false) }
    var busyScreenshot by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var audioQualityMenu by remember { mutableStateOf(false) }
    var automaticSubtitleMenu by remember { mutableStateOf(false) }
    val duration = state.durationSeconds.takeIf { it.isFinite() && it > 0 }?.toFloat() ?: 1f
    fun update(next: PlayerPreferences) { onPreferencesChange(next.normalized()) }
    fun message(text: String) { status = text; onMessage(text) }
    fun seekTo(seconds: Double) { (onSeekTo ?: player::seekTo)(seconds.coerceAtLeast(0.0)) }

    if (surfaceOnly) {
        Box(modifier.fillMaxSize().background(Color.Black).onSizeChanged { videoSurfaceSize = it }) {
            if (renderSurface) SwingPanel(factory = { player.surface }, background = Color.Black, modifier = Modifier.fillMaxSize())
            else Text("正在浮窗播放", color = Color.White, modifier = Modifier.align(Alignment.Center))
            if (renderSurface && commandOverlay != null) DesktopVideoCommandPopup(videoSurfaceSize, commandOverlay, player.surface)
        }
        return
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black).onSizeChanged { videoSurfaceSize = it }) {
            if (renderSurface) SwingPanel(factory = { player.surface }, background = Color.Black, modifier = Modifier.fillMaxSize())
            else Text("正在浮窗播放", color = Color.White, modifier = Modifier.align(Alignment.Center))
            if (renderSurface && commandOverlay != null) DesktopVideoCommandPopup(videoSurfaceSize, commandOverlay, player.surface)
        }
        DesktopSkinPlayerProgress(value = (seeking ?: state.positionSeconds.toFloat()).let { if (it.isFinite()) it.coerceIn(0f, duration) else 0f },
            onValueChange = { seeking = it }, onValueChangeFinished = {
                seeking?.let { seekTo(it.toDouble()) }; seeking = null
            }, duration = duration, enabled = state.durationSeconds > 0 && state.error == null,
            bufferedFraction = state.bufferedForwardSeconds?.takeIf { it.isFinite() && it >= 0 }?.let {
                ((state.positionSeconds + it) / duration).toFloat().coerceIn(0f, 1f)
            }, dragging = seeking != null, onError = ::message)
        if (state.ready && state.error == null && state.durationSeconds.isFinite() && state.durationSeconds > 0) {
            com.android.purebilibili.feature.video.ui.overlay.ViewPointSegmentBar(
                viewPoints = viewPoints,
                durationMs = (state.durationSeconds * 1000.0).toLong(),
                currentPositionMs = ((seeking?.toDouble() ?: state.positionSeconds)
                    .takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0).times(1000.0).toLong(),
                onSeek = { seekTo(it / 1000.0) },
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalButton(onClick = { player.togglePause() }, enabled = state.ready && (state.durationSeconds > 0 || state.ended)) {
                Text(if (state.paused || state.ended) "播放" else "暂停")
            }
            TextButton(onClick = { seekTo(state.positionSeconds - 5.0) }, enabled = state.ready) { Text("−5秒") }
            TextButton(onClick = { seekTo(state.positionSeconds + 5.0) }, enabled = state.ready) { Text("+5秒") }
            if (onPreviousPart != null) TextButton(onClick = onPreviousPart) { Text("上一集") }
            if (onNextPart != null) TextButton(onClick = onNextPart) { Text("下一集") }
            Text("${playerTime(state.positionSeconds)} / ${playerTime(state.durationSeconds)}",
                modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium)
            Box {
                TextButton(onClick = { speedMenu = true }) { Text("${playbackSpeedLabel(state.speed)}×") }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    preferences.speedOptions.forEach { speed ->
                        DropdownMenuItem(text = { Text("${playbackSpeedLabel(speed)}×") }, onClick = {
                            player.setSpeed(speed); update(latestPreferences.copy(speed = speed)); speedMenu = false
                        })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("编辑倍速与默认值…") }, onClick = { speedMenu = false; playbackSettings = true })
                }
            }
            TextButton(onClick = { player.setMuted(!state.muted); update(latestPreferences.copy(muted = !state.muted)) }) {
                Text(if (state.muted) "取消静音" else "静音")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("音量", style = MaterialTheme.typography.labelSmall)
                Slider(value = state.volume.toFloat().coerceIn(0f, 100f), onValueChange = {
                    player.setVolume(it.toDouble()); update(latestPreferences.copy(volume = it.toDouble()))
                }, valueRange = 0f..100f, modifier = Modifier.width(120.dp))
            }
            TextButton(onClick = onFullscreen) { Text("全屏") }
            if (onPictureInPicture != null) TextButton(onClick = onPictureInPicture, enabled = state.videoCodec != null && !state.audioOnly) { Text("浮窗") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = state.audioOnly, onClick = {
                player.setAudioOnly(!state.audioOnly); update(latestPreferences.copy(audioOnly = !state.audioOnly))
            }, label = { Text("仅音频") })
            FilterChip(selected = preferences.danmaku.enabled, onClick = { onOriginalDanmakuToggle?.invoke() },
                enabled = onOriginalDanmakuToggle != null, label = { Text("弹幕") })
            TextButton(onClick = { onOriginalDanmakuSettings?.invoke() }, enabled = onOriginalDanmakuSettings != null) { Text("弹幕设置") }
            TextButton(onClick = { playbackSettings = true }) { Text(playbackModeLabel(preferences.playbackMode)) }
            TextButton(enabled = state.ready && state.videoCodec != null && !state.audioOnly && !state.ended && !busyScreenshot, onClick = {
                if (onScreenshot != null) onScreenshot() else scope.launch {
                    busyScreenshot = true
                    try { savePlayerScreenshot(player)?.let { message("截图已保存：$it") } }
                    catch (failure: Exception) { if (failure is CancellationException) throw failure; message(failure.message ?: "截图失败") }
                    finally { busyScreenshot = false }
                }
            }) { Text(if (busyScreenshot) "截图中…" else "截图") }
            TextButton(enabled = state.ready && state.durationSeconds > 0, onClick = {
                val owner = player.currentSourceVersion
                scope.launch {
                    try {
                        chooseSubtitleFile(player)?.let { path ->
                            if (player.currentSourceVersion != owner) return@launch
                            onManualSubtitleSelection()
                            player.addSubtitle(path)
                            player.setSubtitlesVisible(true)
                            message("正在载入字幕：${path.fileName}")
                        }
                    } catch (failure: Exception) { if (failure is CancellationException) throw failure; message(failure.message ?: "字幕加载失败") }
                }
            }) { Text("载入字幕") }
            if (onOnlineSubtitles != null) TextButton(onClick = onOnlineSubtitles) { Text("在线视频字幕") }
            if (audioSelection != null && onAudioQualityChange != null) Box {
                val presentation = resolveAudioQualityControlPresentation(audioSelection.availableOptions, audioSelection.selectedPreferenceId)
                TextButton(onClick = { audioQualityMenu = true }) { Text(presentation.label) }
                DropdownMenu(expanded = audioQualityMenu, onDismissRequest = { audioQualityMenu = false }) {
                    audioSelection.availableOptions.forEach { option ->
                        DropdownMenuItem(text = { Text((if (option.preferenceId == audioSelection.requestedPreferenceId) "✓ " else "") + option.label) },
                            onClick = { audioQualityMenu = false; onAudioQualityChange(option.preferenceId) })
                    }
                }
            }
            if (onAutomaticSubtitleMode != null && automaticSubtitleTracks.isNotEmpty()) Box {
                val languages = resolveDefaultSubtitleLanguages(automaticSubtitleTracks)
                val primary = automaticSubtitleTracks.firstOrNull { it.lan == languages.primaryLanguage }
                val secondary = automaticSubtitleTracks.firstOrNull { it.lan == languages.secondaryLanguage }
                val options = resolveSubtitleDisplayOptions(primary?.lanDoc.orEmpty(), secondary?.lanDoc.orEmpty(), primary != null, secondary != null)
                TextButton(onClick = { automaticSubtitleMenu = true }) { Text("字幕：${options.firstOrNull { it.mode == automaticSubtitleMode }?.label ?: "关闭"}") }
                DropdownMenu(expanded = automaticSubtitleMenu, onDismissRequest = { automaticSubtitleMenu = false }) {
                    options.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, enabled = option.enabled,
                        onClick = { automaticSubtitleMenu = false; onAutomaticSubtitleMode(option.mode) }) }
                }
            }
            state.nativeTrackIdentity?.let { identity -> key(identity) {
                val audioTracks = state.tracks.filter { it.type == "audio" }
                if (audioTracks.isNotEmpty()) PlayerTrackMenu("音轨", audioTracks, { it.selected }, { player.selectAudioTrackForIdentity(identity, it) })
                val subtitleTracks = state.tracks.filter { it.type == "sub" }
                if (subtitleTracks.isNotEmpty()) {
                    PlayerTrackMenu("主字幕", subtitleTracks, { it.selected && it.mainSelection != 1 }, {
                        if (player.selectSubtitleTrackForIdentity(identity, it) && player.isNativeTrackIdentityCurrent(identity)) onManualSubtitleSelection()
                    })
                    PlayerTrackMenu("副字幕", subtitleTracks, { it.selected && it.mainSelection == 1 }, {
                        if (player.selectSecondarySubtitleTrackForIdentity(identity, it) && player.isNativeTrackIdentityCurrent(identity)) onManualSubtitleSelection()
                    })
                    FilterChip(selected = state.subtitlesVisible, onClick = { onManualSubtitleSelection(); player.setSubtitlesVisible(!state.subtitlesVisible) }, label = { Text("显示字幕") })
                }
            } }
            if (onClose != null) TextButton(onClick = onClose) { Text("关闭播放") }
        }
        when {
            state.error != null -> Text(state.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            state.loading -> DesktopLoadingIndicator(Modifier.fillMaxWidth())
            state.audioOnly -> Text("仅播放音频", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        state.operationError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    if (playbackSettings) PlaybackSettingsDialog(preferences, onPreferencesChange) { playbackSettings = false }
}

@Composable
private fun PlayerTrackMenu(label: String, tracks: List<PlayerTrack>, selected: (PlayerTrack) -> Boolean, onSelect: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = tracks.firstOrNull(selected)
    Box {
        TextButton(onClick = { expanded = true }) { Text(if (current == null) "$label：关闭" else "$label：${current.language ?: current.id}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("关闭") }, onClick = { onSelect(null); expanded = false })
            tracks.forEach { track ->
                DropdownMenuItem(text = { Text((if (selected(track)) "✓ " else "") +
                    listOfNotNull(track.title?.takeIf(String::isNotBlank), track.language?.takeIf(String::isNotBlank))
                        .joinToString(" · ").ifBlank { "$label ${track.id}" }) },
                    onClick = { onSelect(track.id); expanded = false })
            }
        }
    }
}

/** Also used by the caller's keyboard screenshot action. Null means the user canceled the save dialog. */
suspend fun savePlayerScreenshot(player: MpvPlayer): Path? {
    val directory = Path.of(System.getProperty("user.home"), "Pictures", "BiliPai")
    withContext(Dispatchers.IO) { Files.createDirectories(directory) }
    val filename = "BiliPai-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))}.png"
    val selected = choosePlayerFile(player, "保存视频截图", FileDialog.SAVE, directory, filename) ?: return null
    val target = if (selected.fileName.toString().endsWith(".png", ignoreCase = true)) selected
        else selected.resolveSibling(selected.fileName.toString() + ".png")
    return player.captureScreenshot(target)
}

private suspend fun chooseSubtitleFile(player: MpvPlayer): Path? {
    val selected = choosePlayerFile(player, "选择字幕文件（SRT / ASS / SSA / VTT / SUB）", FileDialog.LOAD) ?: return null
    require(Files.isRegularFile(selected)) { "字幕文件不存在。" }
    require(selected.fileName.toString().substringAfterLast('.', "").lowercase() in setOf("srt", "ass", "ssa", "vtt", "sub")) {
        "请选择 SRT、ASS、SSA、VTT 或 SUB 字幕文件。"
    }
    return selected
}

private suspend fun choosePlayerFile(player: MpvPlayer, title: String, mode: Int, directory: Path? = null, filename: String? = null): Path? =
    withContext(Dispatchers.Swing) {
        val owner = SwingUtilities.getWindowAncestor(player.surface)
        val dialog = if (owner is Dialog) FileDialog(owner, title, mode) else FileDialog(owner as? Frame, title, mode)
        try {
            directory?.let { dialog.directory = it.toString() }
            filename?.let { dialog.file = it }
            dialog.isMultipleMode = false
            dialog.isVisible = true
            val file = dialog.file ?: return@withContext null
            Path.of(dialog.directory ?: ".", file).toAbsolutePath().normalize()
        } finally { dialog.dispose() }
    }

private fun playerTime(seconds: Double): String {
    val total = if (seconds.isFinite()) seconds.coerceAtLeast(0.0).toLong() else 0
    return if (total >= 3_600) "%d:%02d:%02d".format(total / 3_600, total / 60 % 60, total % 60)
        else "%d:%02d".format(total / 60, total % 60)
}

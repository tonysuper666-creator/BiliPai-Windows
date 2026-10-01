package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.download.DownloadTask
import com.bilipai.desktop.player.MpvPlayer

/** Windows taskId binding surface. Full original OfflineVideoPlayerScreen controls are a separate source closure. */
@Composable
fun DesktopOfflineTaskPlayerHost(
    initialTaskId: String,
    bindings: DesktopOfflineTaskPlayerBinding,
    onBack: () -> Unit,
    onOnlinePlay: (DownloadTask) -> Unit,
    onToggleFullscreen: () -> Unit,
    playerContent: @Composable (MpvPlayer) -> Unit,
) {
    val latestOnline by rememberUpdatedState(onOnlinePlay)
    val tasks by bindings.manager.tasks.collectAsState()
    val memory = bindings.memory
    val player = bindings.player
    LaunchedEffect(bindings, initialTaskId) { bindings.open(initialTaskId) { latestOnline(it) } }
    LaunchedEffect(bindings, tasks, memory.current) {
        if (bindings.ownsAcceptedSource() && tasks.none { it.id == memory.current }) bindings.close()
    }
    DisposableEffect(bindings) { onDispose { bindings.close() } }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val task = tasks.firstOrNull { it.id == memory.current }
        if (task != null && player != null && memory.loaded && bindings.isOwned() && bindings.ownsAcceptedSource()) {
            MediaPlaybackHeader(task.title, { if (bindings.isOwned()) onBack() }, { if (bindings.isOwned()) onToggleFullscreen() })
            bindings.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Box(Modifier.fillMaxWidth().weight(1f)) { playerContent(player) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.item.localDanmakuSegmentPaths.isNotEmpty() && !task.item.isAudioOnly) {
                    Checkbox(memory.danmakuEnabled, bindings::setDanmakuEnabled)
                    Text("离线弹幕")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { memory.previous?.invoke() }, enabled = memory.previous != null) { Text("上一集") }
                TextButton(onClick = { memory.next?.invoke() }, enabled = memory.next != null) { Text("下一集") }
            }
        } else {
            Text("离线播放", style = MaterialTheme.typography.headlineMedium)
            if (bindings.opening && bindings.isOwned()) CircularProgressIndicator()
            bindings.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { if (bindings.isOwned()) onBack() }) { Text("返回") }
        }
    }
}

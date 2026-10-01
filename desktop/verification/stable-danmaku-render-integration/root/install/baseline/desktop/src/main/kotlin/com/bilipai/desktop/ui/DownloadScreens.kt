package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.purebilibili.feature.download.DownloadStatus
import com.android.purebilibili.feature.download.DownloadAssetStatus
import com.android.purebilibili.feature.download.shouldPauseAllInclude
import com.android.purebilibili.feature.download.shouldContinueAllInclude
import com.android.purebilibili.feature.download.resolveDownloadTaskClickTarget
import com.android.purebilibili.feature.download.DownloadTaskClickTarget
import com.bilipai.desktop.download.DesktopDownloadManager
import com.bilipai.desktop.download.DownloadTask
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.danmaku.DanmakuOverlay
import com.bilipai.desktop.danmaku.DanmakuDocument
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun DownloadBrowserScreen(
    manager: DesktopDownloadManager, player: MpvPlayer?, playerError: String?,
    onPlaybackActive: (Boolean) -> Unit, onToggleFullscreen: () -> Unit = {},
    playerContent: @Composable (MpvPlayer) -> Unit = { NativeMediaPlayer(it) },
    sharedDanmaku: DanmakuOverlay? = null, retained: DesktopRetainedMedia? = null,
    onOnlinePlay: ((DownloadTask) -> Unit)? = null,
) {
    val pageScope = rememberCoroutineScope()
    val memory = retained?.offline ?: remember(player) { DesktopOfflinePageMemory(pageScope, player) }
    val tasks by manager.tasks.collectAsState()
    var current by memory::current
    var error by memory::error
    var deleting by memory::deleting
    var deleteFiles by memory::deleteFiles
    var loaded by memory::loaded
    var sourceVersion by memory::sourceVersion
    var danmakuEnabled by memory::danmakuEnabled
    val ownedOverlay = remember(player, current, sharedDanmaku) { if (sharedDanmaku == null && player != null && current != null) DanmakuOverlay(player) else null }
    val overlay = sharedDanmaku ?: ownedOverlay
    val emptyOverlayError = remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
    val overlayError by (overlay?.loadError ?: emptyOverlayError).collectAsState()
    var downloadSpeeds by remember(manager) { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(manager) {
        var previous = emptyMap<String, Long>()
        var sampledAt = System.nanoTime()
        while (true) {
            delay(1_000L)
            val now = System.nanoTime()
            val elapsed = (now - sampledAt).coerceAtLeast(1L)
            val active = manager.tasks.value.filter { it.status == DownloadStatus.DOWNLOADING }
            downloadSpeeds = active.associate { task ->
                val bytes = task.downloadedBytes.coerceAtLeast(0L)
                val before = previous[task.id]
                val rate = if (before != null && bytes >= before)
                    ((bytes - before).toDouble() * 1_000_000_000.0 / elapsed).toLong().coerceAtLeast(0L) else 0L
                task.id to rate
            }
            previous = active.associate { it.id to it.downloadedBytes.coerceAtLeast(0L) }
            sampledAt = now
        }
    }
    fun savePosition() { if (sourceVersion == null || sourceVersion != player?.currentSourceVersion) return; current?.let { id -> player?.state?.value?.let { state ->
        runCatching { manager.savePlaybackPosition(id, (state.positionSeconds * 1000).toLong(), (state.durationSeconds * 1000).toLong()) }
    } } }
    memory.checkpoint = ::savePosition
    memory.onBeforeStop = { overlay?.setDocument(DanmakuDocument()) }
    memory.release = { memory.assetsJob?.cancel(); memory.checkpointJob?.cancel() }
    if (retained == null) PlaybackLifecycle(player, loaded, onPlaybackActive,
        { savePosition(); overlay?.setDocument(DanmakuDocument()) }, sourceVersion)
    val latestVersion by rememberUpdatedState(sourceVersion)
    DisposableEffect(overlay) { onDispose {
        if (retained == null && latestVersion != null && latestVersion == player?.currentSourceVersion) overlay?.setDocument(DanmakuDocument())
        ownedOverlay?.close()
    } }
    suspend fun loadOfflineDanmaku() {
        val id = current ?: return
        val version = sourceVersion
        try {
            val files = manager.offlineDanmaku(id)
            val task = tasks.firstOrNull { it.id == id }
            if (current != id || version == null || version != player?.currentSourceVersion) return
            overlay?.enabled = danmakuEnabled
            if (files != null && task != null && !task.item.isAudioOnly)
                overlay?.loadOffline(files.standardSegments, files.specialSegments, task.item.duration.toDouble())
            else overlay?.setDocument(DanmakuDocument())
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { error = failure.message ?: "离线弹幕读取失败" }
    }
    if (retained == null) LaunchedEffect(current, overlay) { loadOfflineDanmaku() }
    if (retained == null) LaunchedEffect(current, loaded) { if (current != null && loaded) while (isActive) { delay(5000); savePosition() } }
    fun stopOwned() {
        savePosition()
        if (sourceVersion != null && sourceVersion == player?.currentSourceVersion) {
            overlay?.setDocument(DanmakuDocument()); player?.stopIfSourceVersion(sourceVersion!!)
        }
        sourceVersion = null; loaded = false
    }
    fun stop() { stopOwned(); current = null; error = null }
    fun play(task: DownloadTask) {
        // Original routing policy; Windows interface state needs no HTTP probe.
        when (resolveDownloadTaskClickTarget(task.item, desktopDownloadNetworkAvailable())) {
            DownloadTaskClickTarget.OnlinePlayer -> {
                if (onOnlinePlay != null) onOnlinePlay(task)
                else error = "缓存文件已失效，请从视频页重新播放"
                return
            }
            null -> { error = "缓存文件不可用，连接网络后可回退在线播放"; return }
            DownloadTaskClickTarget.OfflinePlayer -> Unit
        }
        retained?.acquire(memory)
        stopOwned(); current = null
        try {
            val initialized = player ?: throw IllegalStateException(playerError ?: "播放器未能初始化")
            val source = manager.offlinePlayback(task.id)
            sourceVersion = initialized.loadVersioned(source); current = task.id; loaded = true; error = null
            val queue = manager.offlineEpisodeQueue(task.id)
            val index = queue.indexOfFirst { it.id == task.id }
            memory.previous = if (index > 0) ({ queue.getOrNull(index - 1)?.let(::play) }) else null
            memory.next = if (index >= 0 && index + 1 < queue.size) ({ queue.getOrNull(index + 1)?.let(::play) }) else null
            if (retained != null) {
                memory.assetsJob?.cancel(); memory.assetsJob = memory.scope.launch { loadOfflineDanmaku() }
                memory.checkpointJob?.cancel(); memory.checkpointJob = memory.scope.launch {
                    while (isActive && memory.ownsNativeSource) { delay(5000); savePosition() }
                }
            }
        } catch (failure: Exception) { error = failure.message ?: "离线播放失败"; loaded = false }
    }
    LaunchedEffect(tasks, current) { if (current != null && tasks.none { it.id == current }) stop() }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val playing = tasks.firstOrNull { it.id == current }
        if (playing != null && player != null && loaded && memory.ownsNativeSource) {
            MediaPlaybackHeader(playing.title, ::stop, onToggleFullscreen)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Box(Modifier.fillMaxWidth().weight(1f)) { playerContent(player) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playing.item.localDanmakuSegmentPaths.isNotEmpty() && !playing.item.isAudioOnly) {
                    Checkbox(danmakuEnabled, { danmakuEnabled = it; overlay?.enabled = it }); Text("离线弹幕")
                }
                Spacer(Modifier.weight(1f))
                val queue = manager.offlineEpisodeQueue(playing.id)
                val index = queue.indexOfFirst { it.id == playing.id }
                TextButton(onClick = { queue.getOrNull(index - 1)?.let(::play) }, enabled = index > 0) { Text("上一集") }
                TextButton(onClick = { queue.getOrNull(index + 1)?.let(::play) }, enabled = index >= 0 && index + 1 < queue.size) { Text("下一集") }
            }
            overlayError?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            Text("下载", style = MaterialTheme.typography.headlineMedium)
            Text("${tasks.size} 个任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val hasActive = tasks.any { shouldPauseAllInclude(it.item) }
            val hasResumable = tasks.any { shouldContinueAllInclude(it.item) }
            if (hasActive || hasResumable) TextButton(onClick = {
                if (hasActive) tasks.filter { shouldPauseAllInclude(it.item) }.forEach { manager.pause(it.id) }
                else tasks.filter { shouldContinueAllInclude(it.item) }.forEach { manager.resume(it.id) }
            }) { Text(if (hasActive) "暂停全部" else "继续全部") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (tasks.isEmpty()) Text("在视频或番剧播放页加入下载后，任务会显示在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(tasks, key = { it.id }) { task ->
                    DownloadTaskCard(task, onPause = { manager.pause(task.id) }, onResume = { manager.resume(task.id) },
                        onPlay = { play(task) }, onRemove = { deleting = task; deleteFiles = false },
                        speedBytesPerSecond = downloadSpeeds[task.id] ?: 0L)
                }
            }
        }
    }
    deleting?.let { task ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("移除下载任务") },
            text = { Column {
                Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(deleteFiles, { deleteFiles = it }); Text("同时删除此任务的本地文件")
                }
            } }, confirmButton = { TextButton(onClick = {
                try { if (current == task.id) stop(); manager.remove(task.id, deleteFiles) }
                catch (failure: Exception) { error = failure.message ?: "移除下载失败" }
                deleting = null
            }) { Text("移除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("保留") } })
    }
}

@Composable
private fun DownloadTaskCard(task: DownloadTask, onPause: () -> Unit, onResume: () -> Unit, onPlay: () -> Unit, onRemove: () -> Unit,
    speedBytesPerSecond: Long = 0L) {
    val status = when (task.status) {
        DownloadStatus.QUEUED, DownloadStatus.PENDING -> "等待下载"
        DownloadStatus.DOWNLOADING -> "正在下载"
        DownloadStatus.MERGING -> "正在合并音视频"
        DownloadStatus.COMPLETED -> "已完成"
        DownloadStatus.FAILED -> "下载失败"
        DownloadStatus.PAUSED -> "已暂停"
    }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(task.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(status)
                if (task.status == DownloadStatus.DOWNLOADING && speedBytesPerSecond > 0L)
                    Text("${downloadBytes(speedBytesPerSecond)}/秒")
                Text("${(task.progress * 100).toInt()}% · ${downloadBytes(task.downloadedBytes)}${if (task.totalBytes > 0) " / ${downloadBytes(task.totalBytes)}" else ""}")
            }
            LinearProgressIndicator(progress = { task.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            task.error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 5, overflow = TextOverflow.Ellipsis) }
            task.item.assets.filter { it.status == DownloadAssetStatus.FAILED }.forEach { asset ->
                Text("${when (asset.kind.name) { "COVER" -> "封面"; "DANMAKU" -> "弹幕"; "AUDIO" -> "音频"; else -> "视频" }}：${asset.errorMessage.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (task.status == DownloadStatus.COMPLETED) Text(task.outputFile.orEmpty(), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (task.status) {
                    DownloadStatus.QUEUED, DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.MERGING ->
                        OutlinedButton(onClick = onPause, modifier = Modifier.heightIn(min = 48.dp)) { Text("暂停") }
                    DownloadStatus.FAILED, DownloadStatus.PAUSED ->
                        FilledTonalButton(onClick = onResume, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (task.status == DownloadStatus.FAILED) "重试" else "继续") }
                    DownloadStatus.COMPLETED ->
                        FilledTonalButton(onClick = onPlay, modifier = Modifier.heightIn(min = 48.dp)) { Text("播放") }
                }
                OutlinedButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (task.status in setOf(DownloadStatus.QUEUED, DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.MERGING)) "取消任务" else "移除")
                }
            }
        }
    }
}

private fun downloadBytes(value: Long): String {
    val safe = value.coerceAtLeast(0).toDouble()
    return when {
        safe >= 1_073_741_824 -> "%.1f GB".format(safe / 1_073_741_824)
        safe >= 1_048_576 -> "%.1f MB".format(safe / 1_048_576)
        safe >= 1024 -> "%.1f KB".format(safe / 1024)
        else -> "${safe.toLong()} B"
    }
}

/** Windows link availability only; the existing online player reports server failures. */
private fun desktopDownloadNetworkAvailable(): Boolean = runCatching {
    java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).any { network ->
        network.isUp && !network.isLoopback && java.util.Collections.list(network.inetAddresses).any { !it.isLoopbackAddress }
    }
}.getOrDefault(false)

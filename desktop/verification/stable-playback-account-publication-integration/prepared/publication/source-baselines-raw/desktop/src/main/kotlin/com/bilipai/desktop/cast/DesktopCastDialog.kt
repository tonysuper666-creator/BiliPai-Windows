package com.bilipai.desktop.cast

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.plugin.CastPluginMediaRequest
import kotlinx.coroutines.launch

/** Discovery begins only when the user opens this dialog; dismissal keeps a successful cast playing. */
@Composable
fun DesktopCastDialog(controller: DesktopCastController, media: suspend () -> CastPluginMediaRequest?, onDismiss: () -> Unit) {
    val routes by controller.routes.collectAsState()
    val state by controller.playbackState.collectAsState()
    val discovering by controller.isDiscovering.collectAsState()
    val busy by controller.isBusy.collectAsState()
    val error by controller.error.collectAsState()
    val discoveryError by controller.discoveryError.collectAsState()
    val latestMedia by rememberUpdatedState(media)
    val scope = rememberCoroutineScope()
    var selectedInterface by remember(controller) { mutableStateOf<String?>(null) }
    var interfaces by remember(controller) { mutableStateOf(emptyList<DesktopCastInterface>()) }
    var pendingSeek by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(controller) { interfaces = controller.interfaces(); controller.startDiscovery() }
    DisposableEffect(controller) { onDispose { controller.stopDiscovery() } }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("DLNA 投屏") }, text = {
        Column(Modifier.width(640.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("请选择与电脑位于同一局域网的电视或播放设备。")
            if (interfaces.size > 1) {
                Text("本地网卡")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    interfaces.forEach { network -> FilterChip(selectedInterface == network.label,
                        onClick = { selectedInterface = network.label; controller.selectInterface(network) },
                        enabled = !busy && !state.isActive, label = { Text(network.label) }) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = controller::refreshDiscovery, enabled = !busy && !discovering) { Text("刷新设备") }
                if (discovering) Text("正在搜索…")
            }
            if (discovering || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            discoveryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (routes.isEmpty() && !discovering) Text("未发现可播放设备。请开启电视的 DLNA/媒体接收功能，并确认防火墙允许局域网通信。")
            routes.forEach { route ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(route.name, style = MaterialTheme.typography.titleMedium)
                        route.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Button(onClick = { scope.launch { controller.cast(route) { latestMedia() } } }, enabled = !busy) { Text("投屏到此设备") }
                    }
                }
            }
            if (state.isActive) {
                HorizontalDivider()
                Text("正在 ${state.deviceLabel} 上播放", style = MaterialTheme.typography.titleMedium)
                Text(state.title)
                if (state.isBuffering) Text("设备缓冲中…")
                Text("${castTime(state.currentPositionMs)} / ${castTime(state.durationMs)}")
                if (state.canSeek && state.durationMs > 0) {
                    Slider(value = pendingSeek ?: (state.currentPositionMs.toDouble() / state.durationMs).toFloat().coerceIn(0f, 1f),
                        onValueChange = { pendingSeek = it }, enabled = !busy,
                        onValueChangeFinished = {
                            val fraction = pendingSeek
                            pendingSeek = null
                            if (fraction != null) scope.launch { controller.seek((fraction * state.durationMs).toLong()) }
                        })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { if (state.isPlaying) controller.pause() else controller.play() } }, enabled = !busy) {
                        Text(if (state.isPlaying) "暂停" else "播放")
                    }
                    OutlinedButton(onClick = { scope.launch { controller.stop() } }, enabled = !busy) { Text("停止投屏") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") } })
}

internal fun castTime(positionMs: Long): String {
    val total = positionMs.coerceAtLeast(0) / 1000
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
        else "%d:%02d".format(total / 60, total % 60)
}

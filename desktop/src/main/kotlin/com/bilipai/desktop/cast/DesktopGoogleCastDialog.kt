package com.bilipai.desktop.cast

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.plugin.CastPluginMediaRequest
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.launch

@Composable
fun DesktopGoogleCastDialog(context: DesktopPluginContext, plugin: DesktopGoogleCastPlugin,
    media: suspend () -> DesktopCastMediaPublication?, onDismiss: () -> Unit,
    controlSource: DesktopCastMediaPublication? = null) =
    DesktopGoogleCastDialogContent(context, plugin, media, null, onDismiss, controlSource)

@Composable
internal fun DesktopGoogleCastDialog(context: DesktopPluginContext, plugin: DesktopGoogleCastPlugin,
    onRouteSelected: (com.android.purebilibili.core.plugin.CastPluginApi, com.android.purebilibili.core.plugin.CastPluginRoute) -> Unit,
    onDismiss: () -> Unit) = DesktopGoogleCastDialogContent(context, plugin, null, onRouteSelected, onDismiss)

@Composable
private fun DesktopGoogleCastDialogContent(context: DesktopPluginContext, plugin: DesktopGoogleCastPlugin,
    media: (suspend () -> DesktopCastMediaPublication?)?,
    onRouteSelected: ((com.android.purebilibili.core.plugin.CastPluginApi, com.android.purebilibili.core.plugin.CastPluginRoute) -> Unit)?,
    onDismiss: () -> Unit, controlSource: DesktopCastMediaPublication? = null) {
    val latestSelection by rememberUpdatedState(onRouteSelected)
    val scope = rememberCoroutineScope()
    val routes by plugin.routes.collectAsState()
    val playback by plugin.playbackState.collectAsState()
    val pluginBusy by plugin.isBusy.collectAsState()
    var preparing by remember { mutableStateOf(false) }
    var sourceAccepted by remember(plugin, controlSource) { mutableStateOf(controlSource == null) }
    val busy = pluginBusy || preparing
    val discovering by plugin.isDiscovering.collectAsState()
    val error by plugin.error.collectAsState()
    var seekSeconds by remember { mutableStateOf("") }
    var mediaError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(plugin, context) { plugin.startRouteDiscovery(context); onDispose { plugin.stopRouteDiscovery() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Google Cast") }, text = {
        Column(Modifier.widthIn(min = 420.dp, max = 620.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            mediaError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = { plugin.refreshRouteDiscovery(context) }) { Text("刷新设备") }
                if (discovering) Text("正在发现局域网设备…")
            }
            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                items(routes, key = { it.routeId }) { route ->
                    OutlinedButton(enabled = !busy, onClick = {
                    val selection = latestSelection
                    if (selection != null) { preparing = true; selection(plugin, route) }
                    else scope.launch {
                        val preparation = DesktopCastProxySessions.acquirePreparation()
                        preparing = true
                        mediaError = null
                        try {
                            val request = checkNotNull(media).invoke()
                            if (request == null) mediaError = "当前媒体没有可投屏的播放地址"
                            else {
                                if (controlSource != null) check(request === controlSource)
                                val result = request.use { plugin.cast(context, route, it) }
                                if (result.isSuccess) sourceAccepted = true
                            }
                        }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { mediaError = "获取投屏媒体失败，请重新选择播放源" }
                        finally { preparing = false; preparation.close(); com.android.purebilibili.feature.cast.LocalProxyServer.stopAndClear() }
                    } }, modifier = Modifier.fillMaxWidth()) {
                        Column { Text(route.name); route.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
                    }
                }
            }
            if (routes.isEmpty()) Text("未发现设备。请让电脑与 Chromecast / Google Cast 设备连接同一局域网。")
            if (playback.isActive && (controlSource == null || sourceAccepted)) {
                Text("${playback.deviceLabel} · ${playback.title}")
                Text("${playback.currentPositionMs/1000} / ${playback.durationMs/1000} 秒")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { scope.launch {
                        if (controlSource == null) { if (playback.isPlaying) plugin.pause() else plugin.play() }
                        else controlSource.onSource { if (playback.isPlaying) plugin.pause() else plugin.play() }
                    } }) { Text(if (playback.isPlaying) "暂停" else "播放") }
                    OutlinedButton(enabled = !busy, onClick = { scope.launch {
                        if (controlSource == null) plugin.stop() else controlSource.onSource { plugin.stop() }
                    } }) { Text("停止投屏") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(seekSeconds, { seekSeconds = it }, label = { Text("跳转秒数") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = !busy && seekSeconds.toLongOrNull()?.let { it >= 0 && it <= Long.MAX_VALUE/1000 } == true,
                        onClick = { scope.launch {
                             if (controlSource == null) plugin.seek(seekSeconds.toLong()*1000)
                             else controlSource.onSource { plugin.seek(seekSeconds.toLong()*1000) }
                         } }) { Text("跳转") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

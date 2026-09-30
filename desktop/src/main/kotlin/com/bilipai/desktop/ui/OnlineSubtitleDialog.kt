package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.feature.video.subtitle.*
import com.bilipai.desktop.data.DesktopCommunityRepository
import com.bilipai.desktop.player.DesktopSubtitleAssets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.nio.file.Path

@Composable
fun OnlineSubtitleDialog(bvid: String, cid: Long, community: DesktopCommunityRepository, assets: DesktopSubtitleAssets,
    onImport: (Path, SubtitleTrackMeta, Boolean) -> Unit, onDismiss: () -> Unit) {
    var tracks by remember(bvid, cid) { mutableStateOf(emptyList<SubtitleTrackMeta>()) }
    var loading by remember(bvid, cid) { mutableStateOf(true) }
    var error by remember(bvid, cid) { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(bvid, cid) {
        try { tracks = mapPlayerInfoSubtitleTracks(community.playerMetadata(bvid, cid).subtitle?.subtitles.orEmpty()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "字幕信息加载失败" }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("B 站在线字幕") }, text = {
        Column(Modifier.width(550.dp).heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!loading && error == null && tracks.isEmpty()) Text("服务器没有返回字幕，部分字幕需要登录后查看。")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(tracks, key = { it.trackKey }) { track ->
                    Column {
                        Text(track.lanDoc.ifBlank { track.lan } + if (isLikelyAiSubtitleTrack(track)) " · AI" else "")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            fun import(select: Boolean) {
                                importing = track.trackKey
                                scope.launch {
                                    try { onImport(assets.import(track), track, select); error = null }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { error = failure.message ?: "字幕导入失败" }
                                    finally { importing = null }
                                }
                            }
                            TextButton(onClick = { import(true) }, enabled = importing == null) { Text("设为主字幕") }
                            TextButton(onClick = { import(false) }, enabled = importing == null) { Text("添加到音轨列表") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

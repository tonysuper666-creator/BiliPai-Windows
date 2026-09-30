package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.data.model.response.PlayerInfoData
import com.android.purebilibili.feature.audio.player.MusicPlaybackSource
import com.android.purebilibili.feature.video.ui.section.resolveDisplayBgmList
import com.bilipai.desktop.audio.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Real player metadata and current selected part; constructing the component sends no request. */
@Composable
internal fun DesktopVideoMusicEntries(details: VideoDetails, target: DesktopMusicVideoTarget,
    repository: DesktopRepository, community: DesktopCommunityRepository,
    currentTarget: (DesktopMusicVideoTarget) -> Boolean, positionSeconds: () -> Double,
    onMusic: (MusicPlaybackSource, Double) -> Unit, onExternalUrl: (String) -> Unit,
    modifier: Modifier = Modifier) {
    val epoch by repository.sessionEpochFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var metadata by remember(target, epoch) { mutableStateOf<PlayerInfoData?>(null) }
    var loading by remember(target, epoch) { mutableStateOf(false) }
    var error by remember(target, epoch) { mutableStateOf<String?>(null) }
    val current by rememberUpdatedState(currentTarget)
    val music by rememberUpdatedState(onMusic)
    val openWeb by rememberUpdatedState(onExternalUrl)
    val position by rememberUpdatedState(positionSeconds)
    fun authorized() = repository.sessionEpoch == target.sessionEpoch && current(target)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = target.cid > 0 && epoch == target.sessionEpoch && authorized(), onClick = {
                if (authorized()) music(MusicPlaybackSource.VideoAudio(details.bvid, target.cid, details.title),
                    position().takeIf { it.isFinite() && it >= 0 } ?: 0.0)
            }) { Text("听当前分 P") }
            TextButton(enabled = target.cid > 0 && !loading && epoch == target.sessionEpoch && authorized(), onClick = {
                if (!authorized()) return@TextButton
                loading = true; error = null
                scope.launch {
                    try {
                        val reply = community.playerMetadata(details.bvid, target.cid)
                        if (authorized() && (reply.bvid.isBlank() || reply.bvid == target.bvid) && (reply.cid == 0L || reply.cid == target.cid)) metadata = reply
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        if (authorized()) error = failure.message ?: "背景音乐加载失败"
                    } finally { loading = false }
                }
            }) { Text(if (loading) "加载背景音乐…" else "背景音乐") }
        }
        metadata?.let { info ->
            val rows = resolveDisplayBgmList(info.bgmInfo, emptyList())
            if (rows.isEmpty()) Text("当前分 P 没有背景音乐信息", color = MaterialTheme.colorScheme.onSurfaceVariant)
            rows.forEach { bgm ->
                val destination = resolveDesktopBgmMusicTarget(bgm, target.bvid, target.cid)
                TextButton(enabled = destination != null && epoch == target.sessionEpoch && authorized(), onClick = {
                    if (!authorized()) return@TextButton
                    when (destination) {
                        is DesktopBgmMusicTarget.Web -> openWeb(destination.url)
                        is DesktopBgmMusicTarget.Native -> music(destination.source,
                            if (destination.source is MusicPlaybackSource.VideoAudio) position().takeIf { it.isFinite() && it >= 0 } ?: 0.0 else 0.0)
                        null -> Unit
                    }
                }) { Text(listOf(bgm.musicTitle.ifBlank { "背景音乐" }, bgm.actor).filter(String::isNotBlank).joinToString(" · ")) }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

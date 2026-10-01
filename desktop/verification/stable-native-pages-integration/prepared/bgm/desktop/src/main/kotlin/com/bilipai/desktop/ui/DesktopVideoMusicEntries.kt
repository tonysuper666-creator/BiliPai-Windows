package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.audio.player.MusicPlaybackSource
import com.android.purebilibili.feature.video.ui.section.DesktopOriginalInlineBgmSection
import com.android.purebilibili.feature.video.ui.section.resolveDisplayBgmList
import com.bilipai.desktop.audio.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Same real player/part owner; original InlineBgmSection owns every BGM renderer. */
@Composable internal fun DesktopVideoMusicEntries(
    details: VideoDetails,
    target: DesktopMusicVideoTarget,
    repository: DesktopRepository,
    community: DesktopCommunityRepository,
    currentTarget: (DesktopMusicVideoTarget) -> Boolean,
    positionSeconds: () -> Double,
    onMusic: (MusicPlaybackSource, Double) -> Unit,
    onBgm: (DesktopBgmMusicTarget.Detail) -> Unit,
    operations: DesktopDynamicCardOperations,
    onRelatedVideo: (String, Long) -> Unit,
    onExternalUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val epoch by repository.sessionEpochFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var metadata by remember(target, epoch) { mutableStateOf<PlayerInfoData?>(null) }
    var bgmInfoList by remember(target, epoch) { mutableStateOf<List<BgmInfo>>(emptyList()) }
    var loading by remember(target, epoch) { mutableStateOf(false) }
    var error by remember(target, epoch) { mutableStateOf<String?>(null) }
    val current by rememberUpdatedState(currentTarget)
    val music by rememberUpdatedState(onMusic)
    val openBgm by rememberUpdatedState(onBgm)
    val openWeb by rememberUpdatedState(onExternalUrl)
    val relatedVideo by rememberUpdatedState(onRelatedVideo)
    val position by rememberUpdatedState(positionSeconds)
    fun authorized() = operations.isOwned() && repository.sessionEpoch == target.sessionEpoch && current(target)
    suspend fun loadMetadata() {
        if (!authorized() || loading) return
        loading = true; error = null
        try {
            val reply = community.playerMetadata(details.bvid, target.cid)
            if (!authorized()) return
            if ((reply.bvid.isBlank() || reply.bvid == target.bvid) && (reply.cid == 0L || reply.cid == target.cid)) metadata = reply
            // Original VideoPlaybackViewModel's positive-aid, multi-song source.
            if (details.aid > 0) {
                val songs = operations.getBgmList(details.aid, details.bvid, target.cid)
                if (authorized() && songs.isSuccess && !songs.getOrNull().isNullOrEmpty()) bgmInfoList = songs.getOrThrow()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (authorized()) error = failure.message ?: "背景音乐加载失败" }
        finally { if (authorized()) loading = false }
    }
    LaunchedEffect(target, epoch) { if (authorized()) loadMetadata() }
    val discovery = remember(operations, target, epoch) { DesktopBgmDiscoveryOperationsBinding(operations, ::authorized) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = target.cid > 0 && epoch == target.sessionEpoch && authorized(), onClick = {
                if (authorized()) music(MusicPlaybackSource.VideoAudio(details.bvid, target.cid, details.title),
                    position().takeIf { it.isFinite() && it >= 0 } ?: 0.0)
            }) { Text("听当前分 P") }
            if (loading) Text("加载背景音乐…")
        }
        val rows = resolveDisplayBgmList(metadata?.bgmInfo, bgmInfoList)
        if (rows.isNotEmpty() && authorized()) CompositionLocalProvider(LocalDesktopBgmDiscoveryRequests provides discovery) {
            DesktopOriginalInlineBgmSection(rows, onBgmClick = { bgm ->
                if (authorized()) when (val destination = resolveDesktopBgmMusicTarget(bgm, target.bvid, target.cid)) {
                    is DesktopBgmMusicTarget.Web -> openWeb(destination.url)
                    is DesktopBgmMusicTarget.Detail -> openBgm(destination)
                    null -> Unit
                }
            }, onRelatedVideoClick = { bvid, cid -> if (authorized()) relatedVideo(bvid, cid) })
        }
        error?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
            TextButton(enabled = !loading && authorized(), onClick = { scope.launch { loadMetadata() } }) { Text("重试") }
        }
    }
}

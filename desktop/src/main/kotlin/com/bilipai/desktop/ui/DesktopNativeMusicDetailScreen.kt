package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.purebilibili.feature.audio.player.MusicPlaybackSource
import com.bilipai.desktop.audio.*
import com.bilipai.desktop.data.VideoCard
import com.bilipai.desktop.player.PlayerPreferences

/** Separate AU / NativeMusic route. Playback belongs to Root's retained audio session, not this screen. */
@Composable
internal fun DesktopNativeMusicDetailScreen(
    source: MusicPlaybackSource,
    session: ListenAudioSession,
    preferences: PlayerPreferences,
    onPreferencesChange: (PlayerPreferences) -> Unit,
    onBack: () -> Unit,
    onOpenSpace: (Long) -> Unit,
    onOpenVideo: (VideoCard) -> Unit,
    modifier: Modifier = Modifier,
    startPositionSeconds: Double = 0.0,
) {
    val audio by session.state.collectAsState()
    val native by session.player.state.collectAsState()
    val ownsNative = session.ownedPlaybackSourceVersion != null
    val state = projectNativeMusicState(source, audio, native, ownsNative)
    val music = projectNativeMusicPlayerState(state, preferences.playbackMode, native.speed)
    val matching = source.matches(audio.current)

    LaunchedEffect(source.stableId, session) {
        if (shouldStartNativeMusic(source, session.state.value, session.ownedPlaybackSourceVersion != null)) session.openNativeMusic(source, startPositionSeconds)
    }
    // Deliberately no screen-disposal release: original AudioSong is MiniPlayerManager-owned.
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(when(source) {
                is MusicPlaybackSource.AudioSong -> "音乐详情 · AU${source.sid}"
                is MusicPlaybackSource.VideoAudio -> "视频音频 · ${source.bvid} · CID ${source.cid}"
            }, style = MaterialTheme.typography.titleMedium)
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(Modifier.widthIn(min = 240.dp, max = 360.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(music.coverUrl.let { if(it.startsWith("//")) "https:$it" else it }, music.title,
                    Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop)
                Text(music.title, style = MaterialTheme.typography.headlineSmall)
                if(music.artist.isNotBlank()) Text(music.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.songInfo?.let { song ->
                    if(song.uid > 0) TextButton(onClick = { onOpenSpace(song.uid) }) { Text("上传者：${song.uname.ifBlank { song.uid.toString() }}") }
                    song.statistic?.let { stats ->
                        Text("播放 ${stats.play} · 收藏 ${stats.collect} · 评论 ${stats.comment} · 分享 ${stats.share}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(song.intro.isNotBlank()) Text(song.intro, style = MaterialTheme.typography.bodyMedium)
                }
                music.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if(matching && ownsNative) {
                    ListenTransportPanel(session, preferences, onPreferencesChange, onOpenVideo)
                } else {
                    if(matching && audio.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(onClick = { session.openNativeMusic(source) }, enabled = !audio.loading || !matching) { Text("重新播放") }
                }
            }
            if(matching && ownsNative) {
                ListenLyricsPanel(session, Modifier.weight(1f).fillMaxHeight())
            } else Column(Modifier.weight(1f)) {
                Text(if(state.isLoading) "正在加载歌曲和音频…" else "播放后可查看歌词。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

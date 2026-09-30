package com.bilipai.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.purebilibili.data.model.response.FavFolder
import com.android.purebilibili.data.model.response.FavoriteData
import com.android.purebilibili.feature.audio.library.*
import com.android.purebilibili.feature.audio.lyrics.*
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.subtitle.SubtitleTrackMeta
import com.android.purebilibili.feature.video.subtitle.resolveSubtitleTrackDisplayLabel
import com.bilipai.desktop.audio.ListenAudioSession
import com.bilipai.desktop.data.VideoCard
import com.bilipai.desktop.player.PlayerPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities

private enum class ListenTab(val title: String) { PLAYLISTS("播放列表"), ALBUMS("合集"), ARTISTS("UP 主"), RECENT("最近播放"), FAVORITES("听视频收藏"), SEARCH("搜索"), QUEUE("播放队列") }

@Composable
internal fun ListenBrowserScreen(session: ListenAudioSession, preferences: PlayerPreferences,
    onPreferencesChange: (PlayerPreferences) -> Unit, onOpenVideo: (VideoCard) -> Unit,
    onLogin: () -> Unit, modifier: Modifier = Modifier) {
    val account by session.audio.repository.account.collectAsState()
    val audioState by session.state.collectAsState()
    val native by session.player.state.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(ListenTab.PLAYLISTS) }
    var owned by remember { mutableStateOf(emptyList<FavFolder>()) }
    var collected by remember { mutableStateOf(emptyList<FavFolder>()) }
    var indexed by remember { mutableStateOf(emptyList<FavoriteData>()) }
    var playlists by remember { mutableStateOf(emptyList<ListenVideoPlaylist>()) }
    var albums by remember { mutableStateOf(emptyList<ListenVideoAlbum>()) }
    var artists by remember { mutableStateOf(emptyList<ListenVideoArtist>()) }
    var selectedTitle by remember { mutableStateOf<String?>(null) }
    var selectedTracks by remember { mutableStateOf(emptyList<PlaylistItem>()) }
    var loading by remember { mutableStateOf(false) }
    var indexing by remember { mutableStateOf(false) }
    var indexProgress by remember { mutableStateOf("") }
    var indexedComplete by remember { mutableStateOf(false) }
    var failedFolders by remember { mutableStateOf(emptySet<Long>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var libraryRevision by remember { mutableIntStateOf(0) }
    val loader = remember(session, account?.mid, libraryRevision) { ListenVideoLibraryLoader(session.audio.librarySource) }
    var libraryJob by remember { mutableStateOf<Job?>(null) }
    var detailJob by remember { mutableStateOf<Job?>(null) }
    var indexJob by remember { mutableStateOf<Job?>(null) }
    var query by remember { mutableStateOf("") }
    var searchPage by remember { mutableIntStateOf(1) }
    var searchResults by remember { mutableStateOf(emptyList<PlaylistItem>()) }
    var searching by remember { mutableStateOf(false) }
    var searchHasMore by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var searchGeneration by remember { mutableLongStateOf(0) }
    LaunchedEffect(preferences) { session.updatePreferences(preferences) }

    fun update(next: PlayerPreferences) { session.updatePreferences(next); onPreferencesChange(next.normalized()) }
    fun indexLibrary(onlyFailed: Boolean = false) {
        if (indexing || owned.isEmpty()) return
        val targets = if (onlyFailed) owned.filter { it.id in failedFolders } else owned
        indexJob?.cancel()
        indexJob = scope.launch {
            indexing = true; error = null
            try {
                val result = loader.indexFolders(targets) { done, total -> indexProgress = "索引收藏夹 $done / $total" }
                indexed = (if (onlyFailed) indexed + result.resources else result.resources).distinctBy { "${it.type}:${it.bvid}:${it.id}" }
                failedFolders = result.failedFolderIds
                albums = mapListenVideoAlbums(collected, indexed)
                artists = mapListenVideoArtists(indexed)
                indexedComplete = true
                if (result.failedFolderIds.isNotEmpty()) error = if (result.haltedByRiskControl) "收藏接口暂时限流，已保留可用内容。" else "部分收藏夹索引失败，可以重试。"
            } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message }
            finally { indexing = false }
        }
    }

    fun openCollection(title: String, load: suspend () -> Result<List<FavoriteData>>) {
        libraryJob?.cancel(); indexJob?.cancel(); indexing = false; detailJob?.cancel()
        selectedTitle = title; selectedTracks = emptyList(); loading = true; error = null
        detailJob = scope.launch {
            try {
                val tracks = load().getOrThrow().mapNotNull(FavoriteData::toListenVideoTrackOrNull)
                selectedTracks = resolveListenVideoPlaybackSelection(tracks, "").items
            } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message ?: "播放列表加载失败。" }
            finally { loading = false }
        }
    }

    fun search(page: Int) {
        val term = query.trim()
        if (term.isEmpty()) return
        val identifier = Regex("(?i)BV[0-9A-Za-z]{10}|\\bau[1-9][0-9]*\\b|\\bav[1-9][0-9]*\\b").find(term)?.value
        if (identifier != null) {
            val item = PlaylistItem(identifier, title = identifier, cover = "", owner = "")
            session.play(listOf(item)); return
        }
        searchJob?.cancel(); searchGeneration++
        val generation = searchGeneration
        searchJob = scope.launch {
            searching = true; error = null
            try {
                val results = session.audio.repository.search(term, page).map { PlaylistItem(it.bvid, it.preferredCid, it.title, it.cover, it.author, duration = it.duration.toLong()) }
                if (generation != searchGeneration) return@launch
                searchResults = (if (page == 1) results else searchResults + results).distinctBy { it.bvid }
                searchPage = page; searchHasMore = results.size >= 24
            } catch (failure: Exception) { if (failure is CancellationException) throw failure; if (generation == searchGeneration) error = failure.message }
            finally { if (generation == searchGeneration) searching = false }
        }
    }

    LaunchedEffect(account?.mid, libraryRevision) {
        libraryJob?.cancel(); detailJob?.cancel(); indexJob?.cancel()
        owned = emptyList(); collected = emptyList(); indexed = emptyList(); playlists = emptyList(); albums = emptyList(); artists = emptyList()
        selectedTitle = null; selectedTracks = emptyList(); indexedComplete = false; failedFolders = emptySet(); indexing = false; error = null
        val mid = account?.mid ?: return@LaunchedEffect
        libraryJob = scope.launch {
            loading = true
            try {
                owned = session.audio.librarySource.ownedFolders(mid).getOrThrow()
                playlists = mapListenVideoPlaylists(owned)
                val subscribed = loader.loadCollectedFolders(mid)
                collected = subscribed.getOrDefault(emptyList())
                albums = mapListenVideoAlbums(collected, emptyList())
                error = subscribed.exceptionOrNull()?.message
                loading = false
                val covers = loader.loadPlaylistPreviewCovers(owned)
                playlists = playlists.map { item -> covers[item.mediaId]?.let { item.copy(coverUrl = it) } ?: item }
            } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message }
            finally { loading = false }
        }
    }
    LaunchedEffect(tab, owned, indexedComplete) { if (tab in setOf(ListenTab.ARTISTS, ListenTab.ALBUMS) && !indexedComplete) indexLibrary() }

    BoxWithConstraints(modifier.fillMaxSize().padding(16.dp)) {
        val narrow = maxWidth < 820.dp
        var showNowPlaying by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("听视频", style = MaterialTheme.typography.headlineSmall)
                if (narrow) TextButton(onClick = { showNowPlaying = !showNowPlaying }) { Text(if (showNowPlaying) "音乐库" else "当前播放 / 歌词") }
                else TextButton(onClick = { libraryRevision++ }, enabled = !loading) { Text("刷新音乐库") }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (!narrow || !showNowPlaying) Column(Modifier.weight(0.46f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        ListenTab.entries.forEach { section -> FilterChip(tab == section, {
                            detailJob?.cancel(); selectedTitle = null; selectedTracks = emptyList(); loading = false; tab = section
                        }, label = { Text(section.title) }) }
                    }
                    if (tab == ListenTab.SEARCH) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(query, { query = it }, label = { Text("歌曲、视频、BV 或 au 编号") }, singleLine = true, modifier = Modifier.weight(1f))
                        Button(onClick = { search(1) }, enabled = !searching && query.isNotBlank()) { Text("搜索") }
                    }
                    if (selectedTitle != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { detailJob?.cancel(); selectedTitle = null; selectedTracks = emptyList(); loading = false }) { Text("返回音乐库") }
                        Text(selectedTitle!!, modifier = Modifier.weight(1f))
                        TextButton(onClick = { session.play(selectedTracks) }, enabled = selectedTracks.isNotEmpty()) { Text("播放全部") }
                    }
                    if (loading || searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (indexing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(indexProgress, style = MaterialTheme.typography.labelSmall) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (failedFolders.isNotEmpty() && !indexing) TextButton(onClick = { indexLibrary(true) }) { Text("重试失败的收藏夹") }
                    val requiresLogin = tab in setOf(ListenTab.PLAYLISTS, ListenTab.ALBUMS, ListenTab.ARTISTS)
                    if (requiresLogin && account == null) {
                        Text("登录后读取你的收藏播放列表、合集和 UP 主音乐库。")
                        Button(onClick = onLogin) { Text("扫码登录") }
                    } else if (selectedTitle != null) ListenTrackList(selectedTracks, session, onOpenVideo, Modifier.weight(1f))
                    else when (tab) {
                        ListenTab.PLAYLISTS -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (playlists.isEmpty() && !loading) item { Text("还没有收藏播放列表。") }
                            items(playlists, key = { it.mediaId }) { item -> ListenCollectionRow(item.title, item.coverUrl, "${item.trackCount} 个视频") {
                                openCollection(item.title) { loader.loadFolder(item.mediaId) }
                            } }
                        }
                        ListenTab.ALBUMS -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (albums.isEmpty() && !indexing && !loading) item { Text("没有已收藏的合集。") }
                            items(albums, key = { it.seasonId }) { item -> ListenCollectionRow(item.title, item.coverUrl, "${item.trackCount} 个视频 · ${item.artistName}") {
                                openCollection(item.title) { loader.loadAlbum(item.seasonId) }
                            } }
                        }
                        ListenTab.ARTISTS -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (artists.isEmpty() && !indexing && !loading) item { Text("收藏视频按 UP 主分类后会显示在这里。") }
                            items(artists, key = { it.mid }) { item -> ListenCollectionRow(item.name, item.avatarUrl, "${item.tracks.size} 个收藏视频") {
                                selectedTitle = item.name; selectedTracks = resolveListenVideoPlaybackSelection(item.tracks, "").items
                            } }
                        }
                        ListenTab.RECENT -> {
                            TextButton(onClick = session::clearRecent, enabled = audioState.recent.isNotEmpty()) { Text("清空最近播放") }
                            ListenTrackList(audioState.recent, session, onOpenVideo, Modifier.weight(1f))
                        }
                        ListenTab.FAVORITES -> ListenTrackList(audioState.favorites, session, onOpenVideo, Modifier.weight(1f))
                        ListenTab.SEARCH -> {
                            ListenTrackList(searchResults, session, onOpenVideo, Modifier.weight(1f))
                            if (searchHasMore) TextButton(onClick = { search(searchPage + 1) }, enabled = !searching) { Text("加载更多") }
                        }
                        ListenTab.QUEUE -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${audioState.queue.size} 首", modifier = Modifier.weight(1f))
                                TextButton(onClick = session::clearQueue, enabled = audioState.queue.isNotEmpty()) { Text("清空队列") }
                            }
                            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                itemsIndexed(audioState.queue, key = { _, item -> item.bvid }) { index, item ->
                                    Column {
                                        ListenTrackRow(item, session, index == audioState.currentIndex, audioState.favorites.any { it.bvid == item.bvid },
                                            { session.playAt(index) }, { session.toggleFavorite(item) }, { session.enqueue(listOf(item)) }, onOpenVideo)
                                        Row {
                                            TextButton(onClick = { session.moveQueueItem(index, index - 1) }, enabled = index > 0) { Text("上移") }
                                            TextButton(onClick = { session.moveQueueItem(index, index + 1) }, enabled = index < audioState.queue.lastIndex) { Text("下移") }
                                            TextButton(onClick = { session.removeQueueItem(index) }) { Text("移出队列") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (!narrow || showNowPlaying) Column(Modifier.weight(if (narrow) 1f else 0.54f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val current = audioState.current
                    if (current != null) Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(imageUrl(current.cover), current.title, Modifier.size(90.dp), contentScale = ContentScale.Crop)
                        Column(Modifier.weight(1f)) { Text(current.title, style = MaterialTheme.typography.titleLarge); Text(current.owner, style = MaterialTheme.typography.bodyMedium) }
                        TextButton(onClick = { session.toggleFavorite(current) }) { Text(if (audioState.favorites.any { it.bvid == current.bvid }) "取消收藏" else "收藏") }
                    } else Text("选择视频或输入 au 编号开始听。", style = MaterialTheme.typography.titleMedium)
                    ListenTransportPanel(session, preferences, ::update, onOpenVideo)
                    audioState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    native.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    ListenLyricsPanel(session, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ListenCollectionRow(title: String, cover: String, description: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(imageUrl(cover), title, Modifier.size(68.dp), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(description, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ListenTrackList(tracks: List<PlaylistItem>, session: ListenAudioSession, onVideo: (VideoCard) -> Unit, modifier: Modifier) {
    val state by session.state.collectAsState()
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (tracks.isEmpty()) item { Text("这里还没有内容。", style = MaterialTheme.typography.bodyMedium) }
        itemsIndexed(tracks, key = { _, item -> item.bvid }) { index, item ->
            ListenTrackRow(item, session, state.current?.bvid == item.bvid, state.favorites.any { it.bvid == item.bvid },
                { session.play(tracks, index) }, { session.toggleFavorite(item) }, { session.enqueue(listOf(item)) }, onVideo)
        }
    }
}

@Composable
private fun ListenTrackRow(item: PlaylistItem, session: ListenAudioSession, selected: Boolean, favorite: Boolean, onPlay: () -> Unit,
    onFavorite: () -> Unit, onEnqueue: () -> Unit, onVideo: (VideoCard) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(imageUrl(item.cover), item.title, Modifier.size(54.dp).clickable(onClick = onPlay), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f).clickable(onClick = onPlay)) {
                Text((if (selected) "▶ " else "") + item.title, style = MaterialTheme.typography.titleSmall)
                Text(item.owner + " · " + listenTime(item.duration.toDouble()), style = MaterialTheme.typography.bodySmall)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(horizontal = 6.dp)) {
            TextButton(onClick = onPlay) { Text("播放") }
            TextButton(onClick = onEnqueue) { Text("加入队列") }
            TextButton(onClick = onFavorite) { Text(if (favorite) "取消收藏" else "收藏") }
            if (!item.bvid.startsWith("au", true)) TextButton(onClick = { onVideo(listenVideoCard(item, session)) }) { Text("看视频") }
        }
    }
}

@Composable
private fun ListenTransportPanel(session: ListenAudioSession, preferences: PlayerPreferences,
    onPreferencesChange: (PlayerPreferences) -> Unit, onVideo: (VideoCard) -> Unit) {
    val state by session.state.collectAsState()
    val native by session.player.state.collectAsState()
    val latestPreferences by rememberUpdatedState(preferences)
    var seek by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var sleep by remember { mutableStateOf(false) }
    val duration = native.durationSeconds.toFloat().takeIf { it.isFinite() && it > 0 } ?: 1f
    Slider((seek ?: native.positionSeconds.toFloat()).coerceIn(0f, duration), { seek = it }, onValueChangeFinished = {
        seek?.let { session.player.seekTo(it.toDouble()) }; seek = null
    }, valueRange = 0f..duration, enabled = native.durationSeconds > 0 && !state.loading, modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(listenTime(native.positionSeconds), style = MaterialTheme.typography.labelSmall)
        Text(listenTime(native.durationSeconds), style = MaterialTheme.typography.labelSmall)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        TextButton(onClick = session::previous, enabled = state.queue.size > 1) { Text("上一首") }
        FilledTonalButton(onClick = session::togglePause, enabled = state.current != null && !state.loading) { Text(if (state.active && !native.paused && !native.ended) "暂停" else "播放") }
        TextButton(onClick = session::next, enabled = state.queue.size > 1) { Text("下一首") }
        Box {
            TextButton(onClick = { speedMenu = true }) { Text("${playbackSpeedLabel(native.speed)}×") }
            DropdownMenu(speedMenu, { speedMenu = false }) {
                preferences.speedOptions.forEach { speed -> DropdownMenuItem(text = { Text("${playbackSpeedLabel(speed)}×") }, onClick = {
                    onPreferencesChange(latestPreferences.copy(speed = speed)); speedMenu = false
                }) }
            }
        }
        TextButton(onClick = { settings = true }) { Text(playbackModeLabel(preferences.playbackMode)) }
        TextButton(onClick = { sleep = true }) { Text(when {
            state.sleepAfterTrack -> "本曲结束后暂停"
            state.sleepRemainingMs != null -> "定时 ${listenTime(state.sleepRemainingMs!!.toDouble() / 1_000)}"
            else -> "睡眠定时"
        }) }
        state.current?.takeUnless { it.bvid.startsWith("au", true) }?.let { item -> TextButton(onClick = { onVideo(listenVideoCard(item, session)) }) { Text("看视频") } }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onPreferencesChange(latestPreferences.copy(muted = !native.muted)) }) { Text(if (native.muted) "取消静音" else "静音") }
        Slider(native.volume.toFloat().coerceIn(0f, 100f), { onPreferencesChange(latestPreferences.copy(volume = it.toDouble())) },
            valueRange = 0f..100f, modifier = Modifier.weight(1f))
    }
    if (state.loading || native.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (settings) PlaybackSettingsDialog(preferences, onPreferencesChange) { settings = false }
    if (sleep) ListenSleepDialog(session) { sleep = false }
}

@Composable
private fun ListenSleepDialog(session: ListenAudioSession, onDismiss: () -> Unit) {
    val state by session.state.collectAsState()
    var minutes by remember { mutableStateOf("30") }
    val parsed = minutes.toIntOrNull()?.takeIf { it in 1..1_440 }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("睡眠定时") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(10, 20, 30, 60, 90).forEach { value -> TextButton(onClick = { session.setSleepMinutes(value); onDismiss() }) { Text("$value 分钟") } }
            }
            OutlinedTextField(minutes, { minutes = it.take(4) }, label = { Text("自定义分钟（1–1440）") }, singleLine = true)
            TextButton(onClick = { session.setSleepAfterCurrentTrack(); onDismiss() }, enabled = state.current != null) { Text("当前一曲结束后暂停") }
            if (state.sleepRemainingMs != null || state.sleepAfterTrack) TextButton(onClick = { session.cancelSleepTimer(); onDismiss() }) { Text("取消定时") }
        }
    }, confirmButton = { Button(onClick = { parsed?.let(session::setSleepMinutes); onDismiss() }, enabled = parsed != null) { Text("开始计时") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
private fun ListenLyricsPanel(session: ListenAudioSession, modifier: Modifier) {
    val state by session.state.collectAsState()
    val native by session.player.state.collectAsState()
    val document = state.lyrics
    val scope = rememberCoroutineScope()
    var importError by remember { mutableStateOf<String?>(null) }
    var showTranslation by remember { mutableStateOf(true) }
    var autoScroll by remember { mutableStateOf(true) }
    var sourceMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val adjustedPosition = (native.positionSeconds * 1_000).toLong() - (document?.offsetMs ?: 0)
    var stableIndex by remember(document) { mutableIntStateOf(-1) }
    val activeIndex = document?.let { resolveStableLyricIndex(it.lines, adjustedPosition, stableIndex) } ?: -1
    LaunchedEffect(activeIndex, autoScroll, document) {
        stableIndex = activeIndex
        if (autoScroll && activeIndex >= 0 && !listState.isScrollInProgress) listState.animateScrollToItem((activeIndex - 1).coerceAtLeast(0))
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = session::searchLyrics, enabled = state.current != null && !state.lyricsLoading) { Text("搜索歌词") }
            TextButton(onClick = { sourceMenu = true }, enabled = state.candidates.isNotEmpty()) { Text("选择歌词来源") }
            DropdownMenu(sourceMenu, { sourceMenu = false }) {
                state.candidates.forEach { candidate -> DropdownMenuItem(text = { Text("${candidate.title} · ${candidate.artist} (${candidate.source})") }, onClick = {
                    session.selectLyrics(candidate); sourceMenu = false
                }) }
            }
            TextButton(onClick = {
                scope.launch {
                    try {
                        val text = pickListenLyricsFile(session) ?: return@launch
                        session.importLyrics(text); importError = null
                    } catch (failure: Exception) { if (failure is CancellationException) throw failure; importError = failure.message }
                }
            }, enabled = state.current != null) { Text("导入本地歌词") }
            FilterChip(autoScroll, { autoScroll = !autoScroll }, label = { Text("跟随播放") })
            FilterChip(showTranslation, { showTranslation = !showTranslation }, label = { Text("翻译 / 双语") })
        }
        if (state.subtitles.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ListenSubtitleMenu("主字幕", state.subtitles, state.primarySubtitleKey) { key -> session.selectSubtitles(key, state.secondarySubtitleKey) }
            ListenSubtitleMenu("副字幕", state.subtitles, state.secondarySubtitleKey) { key -> session.selectSubtitles(state.primarySubtitleKey, key) }
        }
        if (state.lyricsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        (importError ?: state.lyricsError)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        if (document != null) {
            Text(BiliSubtitleLyricsPolicy.resolveSourceLabel(document), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("时间偏移 ${document.offsetMs} 毫秒", style = MaterialTheme.typography.labelSmall)
                Slider(document.offsetMs.toFloat(), { session.setLyricsOffset(it.toLong()) }, valueRange = -10_000f..10_000f, modifier = Modifier.weight(1f))
                TextButton(onClick = { session.setLyricsOffset(0) }) { Text("重置") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 18.dp)) {
                itemsIndexed(document.lines, key = { index, line -> "$index:${line.startTimeMs}" }) { index, line ->
                    val active = index == activeIndex
                    val base = MaterialTheme.colorScheme.onSurface
                    val highlight = MaterialTheme.colorScheme.primary
                    Column(Modifier.fillMaxWidth().clickable { session.player.seekTo((line.startTimeMs + document.offsetMs).coerceAtLeast(0).toDouble() / 1_000) }) {
                        val lyrics = buildAnnotatedString {
                            if (active && line.spans.isNotEmpty() && line.spans.joinToString("") { it.text } == line.text) {
                                line.spans.forEach { span ->
                                    val progress = resolveSpanHighlightProgress(span, adjustedPosition)
                                    span.text.forEachIndexed { charIndex, char ->
                                        withStyle(SpanStyle(color = highlight.copy(alpha = resolveCharHighlightAlpha(charIndex, span.text.length, progress)))) { append(char) }
                                    }
                                }
                            } else withStyle(SpanStyle(color = if (active) highlight else base.copy(alpha = 0.45f))) { append(line.text) }
                        }
                        Text(lyrics, style = if (active) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge)
                        val rows = resolveDisplaySecondaryRows(line.text, line.translations.joinToString("\n"), line.romanization, showTranslation)
                        rows.first?.let { Text(it, color = base.copy(alpha = if (active) 0.8f else 0.4f)) }
                        rows.second?.let { Text(it, color = base.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        } else Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ListenSubtitleMenu(title: String, tracks: List<SubtitleTrackMeta>, selected: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        val current = tracks.firstOrNull { it.trackKey == selected }
        TextButton(onClick = { expanded = true }) { Text("$title：${current?.let(::resolveSubtitleTrackDisplayLabel) ?: "关闭"}") }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("关闭") }, onClick = { onSelect(null); expanded = false })
            tracks.forEach { track -> DropdownMenuItem(text = { Text((if (track.trackKey == selected) "✓ " else "") + resolveSubtitleTrackDisplayLabel(track)) },
                onClick = { onSelect(track.trackKey); expanded = false }) }
        }
    }
}

/** The caller places this in the persistent application shell when the listening page is not shown. */
@Composable
internal fun ListenNowPlayingBar(session: ListenAudioSession, onOpenListen: () -> Unit, modifier: Modifier = Modifier) {
    val state by session.state.collectAsState()
    val native by session.player.state.collectAsState()
    val current = state.current ?: return
    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AsyncImage(imageUrl(current.cover), current.title, Modifier.size(42.dp).clickable(onClick = onOpenListen), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f).clickable(onClick = onOpenListen)) { Text(current.title, maxLines = 1); Text(current.owner, style = MaterialTheme.typography.labelSmall) }
            Text("${listenTime(native.positionSeconds)} / ${listenTime(native.durationSeconds)}", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = session::previous, enabled = state.queue.size > 1) { Text("上一首") }
            TextButton(onClick = session::togglePause, enabled = !state.loading) { Text(if (state.active && !native.paused) "暂停" else "播放") }
            TextButton(onClick = session::next, enabled = state.queue.size > 1) { Text("下一首") }
            TextButton(onClick = onOpenListen) { Text("歌词 / 队列") }
        }
    }
}

private suspend fun pickListenLyricsFile(session: ListenAudioSession): String? {
    val path = withContext(Dispatchers.Swing) {
        val owner = SwingUtilities.getWindowAncestor(session.player.surface) as? Frame
        FileDialog(owner, "导入本地带时间轴歌词（LRC / SPL / TXT）", FileDialog.LOAD).let { dialog ->
            try {
                dialog.isVisible = true
                dialog.file?.let { Path.of(dialog.directory ?: ".", it).toAbsolutePath().normalize() }
            } finally { dialog.dispose() }
        }
    } ?: return null
    return withContext(Dispatchers.IO) {
        require(Files.isRegularFile(path) && Files.size(path) <= 2 * 1024 * 1024) { "请选择不超过 2 MiB 的歌词文件。" }
        Files.readString(path)
    }
}

private fun listenTime(seconds: Double): String {
    val value = if (seconds.isFinite()) seconds.coerceAtLeast(0.0).toLong() else 0L
    return if (value >= 3_600) "%d:%02d:%02d".format(value / 3_600, value / 60 % 60, value % 60) else "%d:%02d".format(value / 60, value % 60)
}

private fun listenVideoCard(item: PlaylistItem, session: ListenAudioSession): VideoCard {
    val current = session.state.value.current
    val native = session.player.state.value
    val sameContent = current?.bvid == item.bvid && (item.cid <= 0 || current?.cid == item.cid)
    val progress = if (sameContent && native.durationSeconds > 0 && native.positionSeconds.isFinite()) native.positionSeconds.coerceAtLeast(0.0).toInt() else null
    return VideoCard(item.bvid, item.title, item.cover, item.owner, 0L, item.duration.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
        progressSeconds = progress, preferredCid = if (sameContent && item.cid <= 0) current?.cid ?: 0 else item.cid)
}

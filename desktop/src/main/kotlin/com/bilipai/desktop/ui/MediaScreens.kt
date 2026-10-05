package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.bilipai.desktop.data.*
import com.bilipai.desktop.download.DesktopDownloadManager
import com.bilipai.desktop.download.DownloadMetadata
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.ownedSource
import com.bilipai.desktop.danmaku.DanmakuOverlay
import com.bilipai.desktop.danmaku.DanmakuDocument
import com.bilipai.desktop.player.PlaybackSource as NativePlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job

@Composable
fun LiveBrowserScreen(
    repository: DesktopRepository, player: MpvPlayer?, playerError: String?,
    onPlaybackActive: (Boolean) -> Unit, onToggleFullscreen: () -> Unit = {},
    playerContent: @Composable (MpvPlayer) -> Unit = { NativeMediaPlayer(it) },
    initialRoomId: Long = 0, sharedDanmaku: DanmakuOverlay? = null,
    retained: DesktopRetainedMedia? = null,
) {
    val media = remember(repository) { DesktopMediaRepository(repository) }
    val pageScope = rememberCoroutineScope()
    val memory = retained?.live ?: remember(player) { DesktopLivePageMemory(pageScope, player) }
    val scope = memory.scope
    val account by repository.account.collectAsState()
    var sessionAccount by remember { mutableStateOf(account) }
    val accountEpoch by repository.sessionEpochFlow.collectAsState()
    var sessionEpoch by remember { mutableLongStateOf(accountEpoch) }
    var sourcePicker by remember(memory) { mutableStateOf<DesktopLiveSourceSelection?>(null) }
    var section by memory::section
    var query by memory::query
    var submitted by memory::submitted
    var areas by memory::areas
    var parent by memory::parent
    var area by memory::area
    var page by memory::page
    var generation by memory::generation
    var cards by memory::cards
    var hasMore by memory::hasMore
    var loading by memory::loading
    var error by memory::error
    var room by memory::room
    var stream by memory::stream
    var loaded by memory::loaded
    var sourceVersion by memory::sourceVersion
    var opening by memory::opening
    var playJob by memory::playJob
    var quality by memory::quality
    var onlyAudio by memory::onlyAudio
    val ownedOverlay = remember(player, repository, sharedDanmaku) { if (sharedDanmaku == null) player?.let { DanmakuOverlay(it, renderPlatform = com.bilipai.desktop.danmaku.DesktopWindowsDanmakuRenderPlatform { requireNotNull(javax.swing.SwingUtilities.getWindowAncestor(it.surface)) }, httpClient = repository.httpClient) } else null }
    val overlay = sharedDanmaku ?: ownedOverlay
    var danmakuEnabled by memory::danmakuEnabled
    if (retained == null) PlaybackLifecycle(player, loaded, onPlaybackActive, sourceVersion = sourceVersion)
    DisposableEffect(ownedOverlay) { onDispose { ownedOverlay?.close() } }

    fun stopOwned() { memory.stopPlayback() }
    fun connectChat() {
        val current = room ?: return
        val chatEpoch = repository.sessionEpoch
        val session = DesktopLiveSession(repository, media, current.roomId)
        memory.connectChat(session, overlay) actionHandler@ { action ->
            if (memory.chat !== session || repository.sessionEpoch != chatEpoch || !memory.scope.isActive) return@actionHandler
            val token = memory.liveToken
        when (action) {
            is com.android.purebilibili.feature.live.LiveRealtimeAction.EmitChat -> token?.let { overlay?.emitLive(action.item, it) }
            is com.android.purebilibili.feature.live.LiveRealtimeAction.EmitSuperChat -> token?.let { overlay?.emitLive(action.item, it) }
            is com.android.purebilibili.feature.live.LiveRealtimeAction.RemoveSuperChats -> token?.let { overlay?.removeLiveSuperChats(action.ids, it) }
            is com.android.purebilibili.feature.live.LiveRealtimeAction.UpdateVote -> token?.let { overlay?.emitLive(action.announcement, it) }
            is com.android.purebilibili.feature.live.LiveRealtimeAction.UpdateRoomTitle -> room = room?.copy(title = action.title)
            is com.android.purebilibili.feature.live.LiveRealtimeAction.UpdateOnlineRankCount -> room = room?.copy(online = action.count)
            is com.android.purebilibili.feature.live.LiveRealtimeAction.RoomBlocked -> { stopOwned(); error = action.message }
            is com.android.purebilibili.feature.live.LiveRealtimeAction.RoomUnavailable -> { stopOwned(); room = room?.copy(liveStatus = action.liveStatus); error = action.message }
            is com.android.purebilibili.feature.live.LiveRealtimeAction.RefreshPlayback -> {
                val binding = memory.captureLiveSourceBinding() ?: return@actionHandler
                val current = room ?: return@actionHandler
                val caller = currentCoroutineContext().job
                val requestedQuality = quality
                val audioOnly = onlyAudio
                val owned = { caller.isActive && memory.chat === session && binding.current() }
                try {
                    // Realtime video URL payloads cannot silently replace an audio-only request.
                    val info = if (!audioOnly) action.playUrlData?.let {
                        DesktopMediaRepository.selectLive(it, current, requestedQuality)
                    } else null
                    val refreshed = info ?: media.livePlaybackInfo(current.copy(liveStatus = 1),
                        requestedQuality, audioOnly, chatEpoch, owned)
                    currentCoroutineContext().ensureActive()
                    if (owned()) binding.refresh(refreshed, caller)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: Exception) {
                    desktopLiveAdmission(repository, chatEpoch, owned) {
                        if (owned()) error = failure.message ?: "直播流刷新失败"
                    }
                }
            }
            else -> Unit
        }
        }
    }
    fun closeRoom() {
        sourcePicker?.close(); sourcePicker = null
        playJob?.cancel(); stopOwned(); room = null; stream = null; opening = false; error = null
    }
    fun playRoom(id: Long, selectedQuality: Int = quality) {
        // Capture user intent before IO; a server downgrade is only actual stream state.
        quality = selectedQuality
        sourcePicker?.close(); sourcePicker = null
        retained?.acquire(memory)
        playJob?.cancel(); stopOwned(); opening = true; error = null
        val requestEpoch = repository.sessionEpoch
        val audioOnly = onlyAudio
        val nativeVersion = player?.currentSourceVersion
        memory.launchRequest {
            val caller = currentCoroutineContext().job
            val owned = { caller.isActive && memory.playJob === caller && memory.scope.isActive &&
                repository.sessionEpoch == requestEpoch && player?.currentSourceVersion == nativeVersion }
            try {
                val initialized = player ?: throw IllegalStateException(playerError ?: "播放器未能初始化")
                val details = room?.takeIf { it.roomId == id } ?: media.liveRoom(id, requestEpoch, owned)
                currentCoroutineContext().ensureActive()
                if (!desktopLiveAdmission(repository, requestEpoch, owned) { room = details }) return@launchRequest
                val info = media.livePlaybackInfo(details, selectedQuality, audioOnly, requestEpoch, owned)
                currentCoroutineContext().ensureActive()
                desktopLiveAdmission(repository, requestEpoch, owned) {
                    val version = initialized.loadVersioned(info.source.toNativePlayback())
                    check(version == initialized.currentSourceVersion)
                    memory.installLivePlayback(info, requireNotNull(initialized.currentSourceSnapshot()),
                        desktopLiveRecoveryPorts(repository, media, requestEpoch))
                    loaded = true
                    connectChat()
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                desktopLiveAdmission(repository, requestEpoch, owned) {
                    error = failure.message ?: "直播播放失败"; loaded = false
                }
            } finally { if (memory.playJob === caller) opening = false }
        }
    }
    LaunchedEffect(account, accountEpoch) {
        if (sessionAccount != account || sessionEpoch != accountEpoch) {
            sessionAccount = account; sessionEpoch = accountEpoch
            closeRoom(); cards = emptyList(); generation++
        }
    }
    DisposableEffect(memory) { onDispose { sourcePicker?.close() } }
    sourcePicker?.let { selection ->
        DisposableEffect(selection) { onDispose { selection.close() } }
        if (selection.current()) {
            DesktopWindowsPlayerDialog("直播线路", onDismissRequest = {
                selection.close(); if (sourcePicker === selection) sourcePicker = null
            }, preferredHeightDp = 640) {
                com.android.purebilibili.feature.live.components.LiveStreamSourceSheet(
                    candidates = selection.binding.info.resolvedPlayback?.candidates.orEmpty(),
                    activeCandidateIndex = selection.binding.info.candidateIndex,
                    activeUrlIndex = selection.binding.info.urlIndex,
                    onSelect = { candidate, url ->
                        if (selection.switch(candidate, url)) {
                            selection.close(); if (sourcePicker === selection) sourcePicker = null
                        }
                    },
                    onDismiss = { selection.close(); if (sourcePicker === selection) sourcePicker = null },
                )
            }
        }
    }
    val chat = memory.chat
    LaunchedEffect(initialRoomId) { if (initialRoomId > 0 && (memory.initialRoomRequest != initialRoomId || room == null)) {
        memory.initialRoomRequest = initialRoomId; playRoom(initialRoomId)
    } }
    LaunchedEffect(section, submitted, parent?.id, area?.id, page, generation, room?.roomId, account) {
        if (room != null) return@LaunchedEffect
        loading = true; error = null
        try {
            val result = when (section) {
                "搜索" -> if (submitted.isBlank()) MediaPage(emptyList(), page, false) else media.searchLive(submitted, page)
                "关注" -> media.followedLive(page)
                "分区" -> {
                    if (areas.isEmpty()) areas = media.liveAreas()
                    media.liveRooms(page, parent?.id ?: 0, area?.id ?: 0)
                }
                else -> if (page == 1) {
                    val recommendations = media.liveRecommendations()
                    if (recommendations.isEmpty()) media.liveRooms(page) else MediaPage(recommendations, page, false)
                } else media.liveRooms(page)
            }
            cards = result.items; hasMore = result.hasMore
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { error = failure.message ?: "获取直播列表失败"; cards = emptyList()
        } finally { loading = false }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (room == null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("热门", "分区", "关注", "搜索").forEach { label ->
                    if (label == section) FilledTonalButton(onClick = {}, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
                    else OutlinedButton(onClick = { section = label; page = 1; error = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
                }
                OutlinedButton(onClick = { generation++ }, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp)) { Text("刷新") }
            }
            if (section == "搜索") MediaSearch(query, { query = it }, { submitted = query.trim(); page = 1; generation++ }, "搜索直播间")
            if (section == "分区") {
                MediaSelector(listOf("全部") + areas.map { it.name }, parent?.name ?: "全部") { label ->
                    parent = areas.firstOrNull { it.name == label }; area = null; page = 1
                }
                parent?.let { chosen -> MediaSelector(listOf("全部") + chosen.children.map { it.name }, area?.name ?: "全部") { label ->
                    area = chosen.children.firstOrNull { it.name == label }; page = 1
                } }
            }
            MediaMessage(loading, error, cards.isEmpty(), if (section == "搜索" && submitted.isBlank()) "输入关键词搜索直播间" else "暂无直播间")
            LazyVerticalGrid(GridCells.Adaptive(230.dp), modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(cards, key = { it.roomId }) { card ->
                    MediaCard(card.cover, card.title, "${card.author} · ${card.online} 人观看", card.area) { playRoom(card.roomId) }
                }
            }
            MediaPagination(page, hasMore, loading) { page = it }
        } else {
            val current = room!!
            MediaPlaybackHeader(current.title, ::closeRoom, onToggleFullscreen)
            Text("${current.author} · ${current.area} · ${current.online} 人观看", color = MaterialTheme.colorScheme.onSurfaceVariant)
            MediaMessage(opening, error, false, "")
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (player != null && loaded && memory.ownsNativeSource) Box(Modifier.weight(3f).fillMaxHeight()) { playerContent(player) }
                chat?.let { LiveChatPanel(it, account != null, Modifier.weight(1f).fillMaxHeight()) }
            }
            MediaSelector(stream?.qualities.orEmpty().map { it.label }, stream?.qualities?.firstOrNull { it.id == stream?.source?.quality }?.label.orEmpty()) { label ->
                stream?.qualities?.firstOrNull { it.label == label }?.let { playRoom(current.roomId, it.id) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(onlyAudio, { onlyAudio = it; playRoom(current.roomId) })
                Text("仅音频")
                Checkbox(danmakuEnabled, { danmakuEnabled = it; overlay?.enabled = it }); Text("弹幕")
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = {
                    memory.captureLiveSourceBinding()?.let { binding ->
                        sourcePicker?.close(); sourcePicker = DesktopLiveSourceSelection(binding)
                    }
                }, enabled = memory.captureLiveSourceBinding() != null) { Text("直播线路") }
                OutlinedButton(onClick = { playRoom(current.roomId) }, enabled = !opening) { Text("重新连接") }
            }
        }
    }
}

@Composable
fun BangumiBrowserScreen(
    repository: DesktopRepository, player: MpvPlayer?, playerError: String?,
    onPlaybackActive: (Boolean) -> Unit, downloadManager: DesktopDownloadManager? = null,
    onToggleFullscreen: () -> Unit = {}, playerContent: @Composable (MpvPlayer) -> Unit = { NativeMediaPlayer(it) },
    initialSeasonId: Long = 0, sharedDanmaku: DanmakuOverlay? = null,
    initialIsCourse: Boolean = false, initialEpisodeId: Long = 0, initialProgressSeconds: Double = 0.0,
    initialSeasonType: Int = 1,
    retained: DesktopRetainedMedia? = null,
    community: DesktopCommunityRepository,
) {
    val media = remember(repository) { DesktopMediaRepository(repository) }
    val pageScope = rememberCoroutineScope()
    val memory = retained?.bangumi ?: remember(player) { DesktopBangumiPageMemory(pageScope, player) }
    val scope = memory.scope
    val account by repository.account.collectAsState()
    var sessionAccount by remember { mutableStateOf(account) }
    var section by memory::section
    var courseUrl by memory::courseUrl
    var timetable by memory::timetable
    var seasonType by memory::seasonType
    var query by memory::query
    var submitted by memory::submitted
    var page by memory::page
    var generation by memory::generation
    var cards by memory::cards
    var hasMore by memory::hasMore
    var season by memory::season
    var episode by memory::episode
    var playback by memory::playback
    var loading by memory::loading
    var opening by memory::opening
    var error by memory::error
    var notice by memory::notice
    var loaded by memory::loaded
    var sourceVersion by memory::sourceVersion
    var quality by memory::quality
    var playJob by memory::playJob
    val ownedOverlay = remember(player, repository, sharedDanmaku) { if (sharedDanmaku == null) player?.let { DanmakuOverlay(it, renderPlatform = com.bilipai.desktop.danmaku.DesktopWindowsDanmakuRenderPlatform { requireNotNull(javax.swing.SwingUtilities.getWindowAncestor(it.surface)) }, httpClient = repository.httpClient) } else null }
    val overlay = sharedDanmaku ?: ownedOverlay
    if (retained == null) PlaybackLifecycle(player, loaded, onPlaybackActive,
        onBeforeStop = { overlay?.setDocument(DanmakuDocument()) }, sourceVersion = sourceVersion)
    memory.onBeforeStop = { overlay?.setDocument(DanmakuDocument()) }
    val emptyOverlayError = remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
    val overlayError by (overlay?.loadError ?: emptyOverlayError).collectAsState()
    var danmakuEnabled by memory::danmakuEnabled
    val latestVersion by rememberUpdatedState(sourceVersion)
    DisposableEffect(overlay) { onDispose {
        if (retained == null && latestVersion != null && latestVersion == player?.currentSourceVersion) overlay?.setDocument(DanmakuDocument())
        ownedOverlay?.close()
    } }
    val types = remember { linkedMapOf("番剧" to 1, "国创" to 4, "电影" to 2, "纪录片" to 3, "电视剧" to 5, "综艺" to 7) }

    fun stopOwned() {
        if (sourceVersion != null && sourceVersion == player?.currentSourceVersion) {
            overlay?.setDocument(DanmakuDocument()); player?.stopIfSourceVersion(sourceVersion!!)
        }
        sourceVersion = null; loaded = false
    }
    fun closeSeason() { playJob?.cancel(); stopOwned(); season = null; episode = null; playback = null; opening = false; error = null; notice = null }
    fun playEpisode(selected: BangumiEpisode, selectedQuality: Int = quality, startSeconds: Double? = null) {
        val current = season ?: return
        val startPosition = startSeconds ?: if (episode?.id == selected.id) player?.state?.value?.positionSeconds ?: 0.0 else 0.0
        retained?.acquire(memory)
        playJob?.cancel(); stopOwned(); opening = true; error = null; notice = null
        val nativeBaseline = player?.currentSourceVersion
        memory.launchRequest {
            try {
                val initialized = player ?: throw IllegalStateException(playerError ?: "播放器未能初始化")
                val info = media.bangumiPlaybackInfo(current, selected, selectedQuality)
                currentCoroutineContext().ensureActive()
                val callerJob = currentCoroutineContext()[Job]
                val maskEpoch = repository.sessionEpoch
                val episodeSourceVersion = repository.withPlaybackSourceAdmission(info.source, { callerJob?.isActive == true &&
                    memory.scope.isActive && memory.playJob === callerJob && season === current && initialized.currentSourceVersion == nativeBaseline }) {
                    initialized.loadVersioned(com.bilipai.desktop.player.DesktopRepositoryPlaybackPublication(repository).ownedSource(
                        info.source.toNativePlayback().copy(startPositionSeconds = startPosition),
                        { callerJob?.isCancelled != true && memory.scope.isActive && season === current && repository.sessionEpoch == maskEpoch }))
                }
                episode = selected; playback = info; quality = info.source.quality
                sourceVersion = episodeSourceVersion; loaded = true
                val index = current.episodes.indexOfFirst { it.id == selected.id }
                memory.previous = if (index > 0) ({ current.episodes.getOrNull(index - 1)?.let { playEpisode(it) } }) else null
                memory.next = if (index >= 0 && index + 1 < current.episodes.size)
                    ({ current.episodes.getOrNull(index + 1)?.let { playEpisode(it) } }) else null
                overlay?.setDocument(DanmakuDocument())
                overlay?.enabled = danmakuEnabled
                if (selected.cid > 0 && current.danmakuAllowed) launch {
                    overlay?.load(selected.cid, selected.aid, selected.durationSeconds.toDouble(),
                        expectedSourceVersion = episodeSourceVersion,
                        maskSource = selected.bvid.takeIf { it.isNotBlank() }?.let { bvid ->
                            com.bilipai.desktop.danmaku.DesktopOwnedWebMaskSource(bvid, selected.cid,
                                episodeSourceVersion, maskEpoch,
                                stillOwned = { memory.scope.isActive && memory.sourceVersion == episodeSourceVersion &&
                                    memory.episode?.id == selected.id && memory.season?.seasonId == current.seasonId &&
                                    repository.sessionEpoch == maskEpoch && initialized.ownsSourceVersion(episodeSourceVersion) },
                                metadata = community::playerMetadata)
                        })
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { if (memory.isCurrentRequest()) { error = failure.message ?: "番剧播放失败"; loaded = false }
            } finally { if (memory.isCurrentRequest()) opening = false }
        }
    }
    fun openSeasonId(id: Long, epId: Long = 0, isCourse: Boolean = false, startSeconds: Double = 0.0) {
        playJob?.cancel(); error = null; loading = true; opening = epId > 0; notice = null
        memory.launchRequest {
            try {
                val detail = media.bangumiSeason(id, epId, isCourse)
                currentCoroutineContext().ensureActive()
                season = detail
                if (epId > 0) {
                    val selected = detail.episodes.firstOrNull { it.id == epId } ?: throw IllegalStateException("此媒体详情没有指定剧集")
                    loading = false; playEpisode(selected, startSeconds = startSeconds)
                }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (memory.isCurrentRequest()) error = failure.message ?: "番剧详情加载失败" }
            finally { if (memory.isCurrentRequest()) { loading = false; opening = false } }
        }
    }
    LaunchedEffect(account) { if (sessionAccount != account) { sessionAccount = account; closeSeason(); cards = emptyList(); timetable = emptyList(); generation++ } }
    LaunchedEffect(initialSeasonId, initialEpisodeId, initialIsCourse, initialProgressSeconds, initialSeasonType) {
        val request = listOf(initialSeasonId, initialEpisodeId, initialIsCourse, initialProgressSeconds, initialSeasonType)
        if (memory.initialRequest != request) {
            memory.initialRequest = request
            seasonType = initialSeasonType.takeIf { it in setOf(1, 2, 3, 4, 5, 7) } ?: 1
            if (initialSeasonId > 0 || initialEpisodeId > 0) openSeasonId(initialSeasonId, initialEpisodeId, initialIsCourse, initialProgressSeconds)
        }
    }
    LaunchedEffect(seasonType, submitted, page, generation, season?.seasonId, section, account) {
        if (season != null) return@LaunchedEffect
        loading = true; error = null
        try {
            if (section == "时间表") { timetable = media.bangumiTimeline(seasonType); cards = emptyList(); hasMore = false }
            else if (section == "课程") { cards = emptyList(); hasMore = false }
            else {
                val result = when (section) {
                    "追番" -> media.followedBangumi(page, 1)
                    "追剧" -> media.followedBangumi(page, 2)
                    else -> if (submitted.isBlank()) media.bangumiIndex(page, seasonType) else media.searchBangumi(submitted, page, seasonType)
                }
                cards = result.items; hasMore = result.hasMore
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { error = failure.message ?: "获取番剧列表失败"; cards = emptyList()
        } finally { loading = false }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (season == null) {
            MediaSelector(listOf("索引", "时间表", "追番", "追剧", "课程"), section) { section = it; page = 1; submitted = "" }
            MediaSelector(types.keys.toList(), types.entries.first { it.value == seasonType }.key) { label -> seasonType = types.getValue(label); page = 1 }
            if (section == "课程") MediaSearch(courseUrl, { courseUrl = it }, {
                val target = com.android.purebilibili.feature.bangumi.policy.parseCourseNavigation(courseUrl)
                if (target == null) error = "请输入包含真实 ss/ep 编号的课程链接"
                else openSeasonId(target.seasonId, target.epId, true)
            }, "打开 Bilibili 课程链接")
            else if (section == "索引") MediaSearch(query, { query = it }, { submitted = query.trim(); page = 1; generation++ }, "搜索番剧、影视")
            MediaMessage(loading, error, if (section == "时间表") timetable.all { it.episodes.isNullOrEmpty() } else section != "课程" && cards.isEmpty(), "暂无内容")
            if (section == "时间表") LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                timetable.forEach { day ->
                    item { Text("${day.date} · 星期${day.dayOfWeek}${if (day.isToday == 1) " · 今天" else ""}", style = MaterialTheme.typography.titleMedium) }
                    items(day.episodes.orEmpty(), key = { "${day.date}:${it.seasonId}:${it.episodeId}" }) { entry ->
                        OutlinedButton(onClick = { openSeasonId(entry.seasonId, entry.episodeId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text("${entry.pubTime} · ${entry.title} ${entry.pubIndex}${entry.delayReason.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}")
                        }
                    }
                }
            } else if (section == "课程") Spacer(Modifier.weight(1f))
            else {
            LazyVerticalGrid(GridCells.Adaptive(190.dp), modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(cards, key = { it.seasonId }) { card -> MediaCard(card.cover, card.title, card.subtitle, listOf(card.badge, card.score).filter { it.isNotBlank() }.joinToString(" · "), portrait = true) { openSeasonId(card.seasonId) } }
            }
            MediaPagination(page, hasMore, loading) { page = it }
            }
        } else {
            val current = season!!
            MediaPlaybackHeader(current.title, ::closeSeason, onToggleFullscreen)
            Text((current.metaChips + current.restrictionLabels).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { try {
                    val identity = account
                    val result = media.setBangumiFollowing(current, !current.following)
                    if (season?.seasonId == current.seasonId && repository.account.value == identity) season = result
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "追番/收藏操作失败" } } }) {
                    Text(if (current.following) "${current.followLabel} · 取消" else current.followLabel)
                }
                if (current.following && !current.isCourse) com.android.purebilibili.feature.bangumi.BANGUMI_FOLLOW_STATUS_OPTIONS.forEach { option ->
                    TextButton(onClick = { scope.launch { try {
                        val identity = account
                        val result = media.updateBangumiFollowStatus(current, option.status)
                        if (season?.seasonId == current.seasonId && repository.account.value == identity) season = result
                    }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "追番状态更新失败" } } }) { Text(option.label) }
                }
                if (current.isCourse) Text(if (current.upstreamDetail?.hasPaid == true) "已购课程" else "课程可观看内容以账号权限为准")
            }
            MediaMessage(loading || opening, error, false, "")
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (episode != null && player != null && loaded && memory.ownsNativeSource) Box(Modifier.weight(1f).fillMaxWidth()) { playerContent(player) }
                    else {
                        AsyncImage(current.cover, current.title, Modifier.fillMaxWidth().heightIn(max = 230.dp), contentScale = ContentScale.Fit)
                        Text(current.description, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                    episode?.let { selected ->
                        Text("${selected.title} ${selected.subtitle}", style = MaterialTheme.typography.titleMedium)
                        if (current.danmakuAllowed) Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(danmakuEnabled, { danmakuEnabled = it; overlay?.enabled = it }); Text("弹幕")
                        }
                        overlayError?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                        if (playback?.preview == true) Text("当前播放为试看内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MediaSelector(playback?.qualities.orEmpty().map { it.label }, playback?.qualities?.firstOrNull { it.id == quality }?.label.orEmpty()) { label ->
                            playback?.qualities?.firstOrNull { it.label == label }?.let { playEpisode(selected, it.id) }
                        }
                        if (downloadManager != null && playback?.downloadAllowed == true) OutlinedButton(onClick = {
                            try {
                                val info = playback ?: return@OutlinedButton
                                downloadManager.enqueue(info.source.toNativePlayback(), metadata = DownloadMetadata(
                                    bvid = selected.bvid, cid = selected.cid, aid = selected.aid, cover = selected.cover.ifBlank { current.cover },
                                    durationSeconds = selected.durationSeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), quality = info.source.quality,
                                    qualityLabel = info.qualities.firstOrNull { it.id == info.source.quality }?.label.orEmpty(),
                                    seasonId = current.seasonId, episodeId = selected.id, isCourse = current.isCourse, episodeLabel = selected.title,
                                    downloadAllowed = info.downloadAllowed, groupKey = "season:${current.seasonId}", groupTitle = current.title,
                                    episodeSortIndex = current.episodes.indexOf(selected) + 1, episodeCount = current.episodes.size,
                                ), stillOwned = { memory.scope.isActive && season === current && episode === selected && playback === info })
                                notice = "已加入下载队列"
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = failure.message ?: "添加下载失败" }
                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("下载此集") }
                    }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(current.episodes, key = { it.id }) { selected ->
                        OutlinedButton(onClick = { playEpisode(selected) }, enabled = !opening, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text("${selected.title} ${selected.subtitle}", maxLines = 2)
                                if (selected.badge.isNotBlank() || selected.section.isNotBlank()) Text(listOf(selected.section, selected.badge).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (current.episodes.isEmpty()) item { Text("此季度尚无可播放剧集") }
                }
            }
        }
    }
}

internal fun com.bilipai.desktop.data.PlaybackSource.toNativePlayback() = NativePlaybackSource(videoUrl, audioUrl,
    referer, cookieHeader = cookieHeader, title = title, progressiveSegments = progressiveSegments, authorizationReceipt = authorizationReceipt)

@Composable
private fun LiveChatPanel(session: DesktopLiveSession, isLoggedIn: Boolean, modifier: Modifier = Modifier) {
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var message by remember(session) { mutableStateOf("") }
    var color by remember(session) { mutableIntStateOf(16777215) }
    var mode by remember(session) { mutableIntStateOf(1) }
    var reply by remember(session) { mutableStateOf<com.android.purebilibili.feature.live.LiveDanmakuItem?>(null) }
    var sendJob by remember(session) { mutableStateOf<Job?>(null) }
    DisposableEffect(session) { onDispose { sendJob?.cancel() } }
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    val permission = state.permission
    val canSend = isLoggedIn && permission?.canSend == true && !state.sending && message.isNotBlank() &&
        (permission.maxLength <= 0 || message.length <= permission.maxLength)
    fun send() {
        if (!canSend || sendJob?.isActive == true) return
        val text = message
        sendJob = scope.launch {
            try { session.send(text, color, mode, reply); if (message == text) message = ""; reply = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The session exposes the real API error below. */ }
        }
    }
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.status, style = MaterialTheme.typography.labelMedium,
                color = if (state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.messages) { item ->
                    Column(Modifier.fillMaxWidth().clickable(enabled = isLoggedIn && item.uid > 0) { reply = item }) {
                        Text(buildList {
                            if (item.isSuperChat) add("SC ${item.superChatPrice}")
                            if (item.medalName.isNotBlank()) add("${item.medalName} ${item.medalLevel}")
                            add(item.uname.ifBlank { "用户 ${item.uid}" })
                        }.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                            color = if (item.isSelf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (item.replyToName.isNotBlank()) Text("回复 ${item.replyToName}", style = MaterialTheme.typography.labelSmall)
                        Text(item.text.ifBlank { if (item.emoticonUrl != null) "[表情]" else "" },
                            color = Color(item.color or (0xff shl 24)))
                    }
                }
            }
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text(if (!isLoggedIn) "登录后可发送弹幕" else permission?.statusText ?: "正在读取弹幕权限", style = MaterialTheme.typography.labelSmall)
            reply?.let { target -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("回复 ${target.uname}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { reply = null }) { Text("取消") }
            } }
            if (isLoggedIn && permission != null) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    permission.availableModes.forEach { option -> FilterChip(mode == option.mode, { mode = option.mode }, label = { Text(option.name) }) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    permission.availableColors.forEach { option -> FilterChip(color == option.color, { color = option.color }, label = { Text(option.name) }) }
                }
            }
            OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth(), enabled = isLoggedIn && permission?.canSend == true,
                singleLine = true, label = { Text("发送直播弹幕") },
                supportingText = { if (permission != null) Text("${message.length}/${permission.maxLength}") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
            FilledTonalButton(onClick = ::send, enabled = canSend, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (state.sending) "发送中" else "发送")
            }
        }
    }
}

/** List browsing does not hold the updater. Loaded, paused and buffering media do. */
@Composable
internal fun PlaybackLifecycle(player: MpvPlayer?, loaded: Boolean, onPlaybackActive: (Boolean) -> Unit,
    onBeforeStop: () -> Unit = {}, sourceVersion: Long? = null) {
    val callback by rememberUpdatedState(onPlaybackActive)
    val beforeStop by rememberUpdatedState(onBeforeStop)
    val version by rememberUpdatedState(sourceVersion)
    DisposableEffect(player) { onDispose {
        val token = version
        if (token != null && token == player?.currentSourceVersion) {
            beforeStop(); player?.stopIfSourceVersion(token); callback(false)
        }
    } }
    LaunchedEffect(player, loaded, sourceVersion) {
        if (!loaded || player == null || sourceVersion == null) callback(false)
        else player.state.collect { state ->
            callback(sourceVersion == player.currentSourceVersion && state.error == null && !state.ended && (state.loading || state.videoCodec != null || state.audioCodec != null))
        }
    }
}

@Composable
fun NativeMediaPlayer(player: MpvPlayer) {
    val state by player.state.collectAsState()
    var seek by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwingPanel(factory = { player.surface }, modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black))
        if (state.durationSeconds > 0) Slider(value = (seek ?: state.positionSeconds.toFloat()).coerceIn(0f, state.durationSeconds.toFloat()),
            valueRange = 0f..state.durationSeconds.toFloat(), onValueChange = { seek = it },
            onValueChangeFinished = { seek?.let { player.seekTo(it.toDouble()) }; seek = null })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = { player.togglePause() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.paused) "播放" else "暂停") }
            TextButton(onClick = { player.toggleMuted() }) { Text(if (state.muted) "取消静音" else "静音") }
            Text("音量")
            Slider(state.volume.toFloat().coerceIn(0f, 100f), { player.setVolume(it.toDouble()) }, valueRange = 0f..100f, modifier = Modifier.width(150.dp))
            Text("${state.positionSeconds.toInt() / 60}:${(state.positionSeconds.toInt() % 60).toString().padStart(2, '0')}")
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
internal fun MediaPlaybackHeader(title: String, onClose: () -> Unit, onToggleFullscreen: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回列表") }
        Text(title, Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick = onToggleFullscreen, modifier = Modifier.heightIn(min = 48.dp)) { Text("全屏") }
    }
}

@Composable
private fun MediaCard(cover: String, title: String, subtitle: String, badge: String, portrait: Boolean = false, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AsyncImage(cover, title, Modifier.fillMaxWidth().aspectRatio(if (portrait) 0.75f else 16f / 9f), contentScale = ContentScale.Crop)
            Text(title, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            if (badge.isNotBlank()) Text(badge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MediaSearch(value: String, onValueChange: (String) -> Unit, onSearch: () -> Unit, label: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value, onValueChange, Modifier.weight(1f), singleLine = true, label = { Text(label) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }))
        FilledTonalButton(onClick = onSearch, modifier = Modifier.heightIn(min = 48.dp)) { Text("搜索") }
    }
}

@Composable
private fun MediaSelector(labels: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.distinct().forEach { label ->
            if (label == selected) FilledTonalButton(onClick = {}, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
            else OutlinedButton(onClick = { onSelect(label) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
        }
    }
}

@Composable
internal fun MediaMessage(loading: Boolean, error: String?, empty: Boolean, emptyLabel: String) {
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (!loading && error == null && empty) Text(emptyLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MediaPagination(page: Int, hasMore: Boolean, loading: Boolean, onPage: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onPage(page - 1) }, enabled = page > 1 && !loading) { Text("上一页") }
        Text("第 $page 页")
        TextButton(onClick = { onPage(page + 1) }, enabled = hasMore && !loading) { Text("下一页") }
    }
}

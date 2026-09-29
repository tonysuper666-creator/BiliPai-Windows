package com.bilipai.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.purebilibili.core.store.DEFAULT_PLAYBACK_SPEED_OPTIONS
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.update.DesktopUpdater
import com.bilipai.desktop.update.UpdateState
import com.bilipai.desktop.update.WindowsUpdate
import com.bilipai.desktop.danmaku.DanmakuOverlay
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.image.BufferedImage
import java.net.URI

private enum class Section(val label: String, val symbol: String) {
    HOME("推荐", "⌂"), POPULAR("热门", "◉"), HISTORY("观看历史", "◷"), FAVORITES("本地收藏", "♡"), SEARCH("搜索", "⌕")
}
private val accent = Color(0xFF256D77)

@Composable
fun DesktopApp(repository: DesktopRepository, player: MpvPlayer?, playerError: String?, initialVideo: String?,
    onExit: () -> Unit, onToggleFullscreen: () -> Unit) {
    val library = remember { DesktopLibrary() }
    var dark by remember { mutableStateOf(library.dark) }
    val scheme = if (dark) darkColorScheme(primary = Color(0xFF83CDD1), primaryContainer = Color(0xFF294A4F), background = Color(0xFF101719),
        surface = Color(0xFF182226), surfaceVariant = Color(0xFF233034))
    else lightColorScheme(primary = accent, primaryContainer = Color(0xFFD8E7E9), background = Color(0xFFF4F8F9),
        surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFEAF0F2))
    MaterialTheme(colorScheme = scheme, shapes = Shapes(medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp))) {
        val scope = rememberCoroutineScope()
        val updater = remember { DesktopUpdater() }
        val updateState by updater.state.collectAsState()
        var updatesDialog by remember { mutableStateOf(false) }
        var automaticUpdates by remember { mutableStateOf(library.automaticUpdates) }
        var updateJob by remember { mutableStateOf<Job?>(null) }
        var updateRequestedManually by remember { mutableStateOf(false) }
        var activatingUpdate by remember { mutableStateOf(false) }
        val danmaku = remember(player) { player?.let { DanmakuOverlay(it) } }
        var danmakuEnabled by remember { mutableStateOf(true) }
        DisposableEffect(danmaku) { onDispose { danmaku?.close() } }
        var section by remember { mutableStateOf(Section.HOME) }
        var query by remember { mutableStateOf("") }
        var submitted by remember { mutableStateOf("") }
        var page by remember { mutableIntStateOf(1) }
        var cards by remember { mutableStateOf(emptyList<VideoCard>()) }
        var feedLoading by remember { mutableStateOf(false) }
        var details by remember { mutableStateOf<VideoDetails?>(null) }
        var related by remember { mutableStateOf(emptyList<VideoCard>()) }
        var comments by remember { mutableStateOf(emptyList<Comment>()) }
        var opening by remember { mutableStateOf(initialVideo != null) }
        var error by remember { mutableStateOf<String?>(null) }
        var loginDialog by remember { mutableStateOf(false) }
        var favorite by remember { mutableStateOf(false) }
        var currentPart by remember { mutableIntStateOf(0) }
        var quality by remember { mutableIntStateOf(80) }
        var openedJob by remember { mutableStateOf<Job?>(null) }
        val account by repository.account.collectAsState()
        fun prepareUpdate(update: WindowsUpdate, manuallyRequested: Boolean) {
            if (activatingUpdate || updateJob?.isActive == true) return
            if (manuallyRequested) updateRequestedManually = true
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (updater.prepareUpdate(update) == null) updateRequestedManually = false
                } finally { updateJob = null }
            }
            updateJob = job
            job.start()
        }
        fun leaveVideo() { openedJob?.cancel(); details = null; opening = false; danmaku?.enabled = false; player?.stop(); error = null }
        fun playPart(info: VideoDetails, index: Int) {
            if (activatingUpdate) return
            openedJob?.cancel()
            currentPart = index
            error = null
            openedJob = scope.launch {
                try {
                    danmaku?.enabled = danmakuEnabled
                    launch { danmaku?.load(info.pages.getOrNull(index)?.cid ?: 0L) }
                    val source = repository.playback(info, index, quality)
                    player?.load(com.bilipai.desktop.player.PlaybackSource(
                        videoUrl = source.videoUrl, audioUrl = source.audioUrl,
                        title = source.title, referer = source.referer, cookieHeader = source.cookieHeader))
                        ?: throw IllegalStateException(playerError ?: "播放器未能初始化")
                } catch (exception: Exception) {
                    if (exception is CancellationException) throw exception
                    error = exception.message ?: "播放失败"
                }
            }
        }
        fun openVideo(bvid: String) {
            if (activatingUpdate) return
            openedJob?.cancel()
            player?.stop()
            opening = true
            error = null
            related = emptyList(); comments = emptyList()
            openedJob = scope.launch {
                try {
                    val info = repository.videoDetails(bvid)
                    details = info
                    currentPart = 0
                    favorite = library.isFavorite(info.bvid)
                    library.record(info.toCard())
                    opening = false
                    danmaku?.enabled = danmakuEnabled
                    launch { danmaku?.load(info.pages.firstOrNull()?.cid ?: 0L) }
                    launch { runCatching { repository.related(info.bvid) }.onSuccess { related = it } }
                    launch { runCatching { repository.comments(info.aid) }.onSuccess { comments = it } }
                    val source = repository.playback(info, quality = quality)
                    player?.load(com.bilipai.desktop.player.PlaybackSource(
                        videoUrl = source.videoUrl, audioUrl = source.audioUrl,
                        title = source.title, referer = source.referer, cookieHeader = source.cookieHeader))
                        ?: throw IllegalStateException(playerError ?: "播放器未能初始化")
                } catch (exception: Exception) {
                    if (exception is CancellationException) throw exception
                    error = exception.message ?: "无法打开视频"
                    opening = false
                }
            }
        }
        fun submitSearch() {
            val text = query.trim()
            if (text.isEmpty()) return
            val bvid = Regex("BV[0-9A-Za-z]{10}", RegexOption.IGNORE_CASE).find(text)?.value
            if (bvid != null) { openVideo(bvid); return }
            leaveVideo(); submitted = text; section = Section.SEARCH; page = 1
        }
        LaunchedEffect(section, submitted, page, account?.mid) {
            feedLoading = true; error = null
            try {
                cards = when (section) {
                    Section.HOME -> repository.recommendations(page)
                    Section.POPULAR -> repository.popular(page)
                    Section.SEARCH -> repository.search(submitted, page)
                    Section.HISTORY -> library.history()
                    Section.FAVORITES -> library.favorites()
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                error = exception.message ?: "加载失败"
            } finally { feedLoading = false }
        }
        LaunchedEffect(Unit) {
            runCatching { repository.refreshAccount() }
            initialVideo?.let { openVideo(it) }
        }
        LaunchedEffect(Unit) {
            updater.autoCheck()
            while (true) { delay(6 * 60 * 60 * 1000L); updater.autoCheck() }
        }
        LaunchedEffect(updateState, automaticUpdates, updateRequestedManually, details, opening, updateJob, activatingUpdate) {
            if (updateJob?.isActive == true || activatingUpdate) return@LaunchedEffect
            when (val status = updateState) {
                is UpdateState.Available -> if (automaticUpdates) prepareUpdate(status.update, manuallyRequested = false)
                is UpdateState.Prepared -> {
                    // This recheck and flag run together on the UI thread. Video
                    // opens cannot interleave with the subsequent startup handshake.
                    if ((automaticUpdates || updateRequestedManually) && details == null && !opening) {
                        activatingUpdate = true
                        val job = scope.launch(start = CoroutineStart.LAZY) {
                            try {
                                if (updater.activatePreparedUpdate(status.prepared)) onExit()
                            } finally {
                                activatingUpdate = false
                                updateRequestedManually = false
                                updateJob = null
                            }
                        }
                        updateJob = job
                        job.start()
                    }
                }
                else -> Unit
            }
        }
        Surface(Modifier.fillMaxSize().onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.F11) { onToggleFullscreen(); true } else false
        }, color = scheme.background) {
            Row(Modifier.fillMaxSize().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.width(170.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("BiliPai", Modifier.padding(14.dp), style = MaterialTheme.typography.headlineSmall,
                        color = scheme.primary, fontWeight = FontWeight.Bold)
                    listOf(Section.HOME, Section.POPULAR, Section.HISTORY, Section.FAVORITES).forEach { item ->
                        Surface(Modifier.fillMaxWidth().height(52.dp).clickable {
                            leaveVideo(); section = item; page = 1
                            if (item == Section.HISTORY) cards = library.history()
                            if (item == Section.FAVORITES) cards = library.favorites()
                        }, shape = RoundedCornerShape(26.dp), color = if (section == item) scheme.primaryContainer else Color.Transparent) {
                            Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(item.symbol, style = MaterialTheme.typography.titleLarge)
                                Text(item.label, fontWeight = if (section == item) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { dark = !dark; library.setDark(dark) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(if (dark) "☀ 浅色外观" else "☾ 深色外观")
                    }
                    TextButton(onClick = { loginDialog = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(account?.name ?: "扫码登录", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { updatesDialog = true; scope.launch { updater.check() } }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(when (updateState) {
                            is UpdateState.Available -> "↑ 新版本可用"
                            is UpdateState.Downloading -> "正在下载更新…"
                            is UpdateState.Prepared -> "更新已下载"
                            UpdateState.Verifying, UpdateState.Launching -> "正在安装更新…"
                            else -> "检查更新"
                        })
                    }
                    val version = remember { Thread.currentThread().contextClassLoader.getResourceAsStream("upstream-version.txt")?.bufferedReader()?.use { it.readLine() } ?: "alpha.9" }
                    Text("Windows · $version", Modifier.padding(12.dp), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (details != null || opening) OutlinedButton(onClick = { leaveVideo() }, modifier = Modifier.height(52.dp)) { Text("‹ 返回") }
                        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                            placeholder = { Text("搜索视频，或粘贴 BV 号 / 视频链接") },
                            shape = RoundedCornerShape(28.dp), modifier = Modifier.weight(1f).onPreviewKeyEvent {
                                if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) { submitSearch(); true } else false
                            }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { submitSearch() }))
                        Button(onClick = { submitSearch() }, modifier = Modifier.height(52.dp)) { Text("搜索") }
                    }
                    if (error != null) Surface(color = scheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error!!, Modifier.weight(1f), color = scheme.onErrorContainer)
                            TextButton(onClick = { error = null }) { Text("收起") }
                        }
                    }
                    when {
                        opening -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        details != null -> VideoPage(details!!, related, comments, player, currentPart, quality, favorite,
                            onVideo = { openVideo(it) }, onPart = { playPart(details!!, it) },
                            onQuality = { quality = it; playPart(details!!, currentPart) },
                            onFavorite = { library.toggleFavorite(details!!.toCard()); favorite = library.isFavorite(details!!.bvid) },
                            danmakuEnabled = danmakuEnabled, onDanmaku = {
                                danmakuEnabled = !danmakuEnabled; danmaku?.enabled = danmakuEnabled
                            }, onFullscreen = onToggleFullscreen)
                        else -> {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (section == Section.SEARCH) "搜索 · $submitted" else section.label,
                                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (section in listOf(Section.HOME, Section.POPULAR, Section.SEARCH)) {
                                    TextButton(onClick = { page = (page - 1).coerceAtLeast(1) }, enabled = page > 1 && !feedLoading) { Text("上一页") }
                                    Text("$page", color = scheme.onSurfaceVariant)
                                    TextButton(onClick = { page++ }, enabled = !feedLoading) { Text("下一页") }
                                }
                            }
                            if (feedLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (cards.isEmpty() && !feedLoading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(if (error == null) "这里暂时还没有视频" else "加载失败，请切换栏目后重试", color = scheme.onSurfaceVariant)
                            }
                            LazyVerticalGrid(columns = GridCells.Adaptive(240.dp), modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(cards, key = { it.bvid }) { card -> FeedCard(card) { openVideo(card.bvid) } }
                            }
                        }
                    }
                }
            }
        }
        if (loginDialog) LoginDialog(repository, account, onDismiss = { loginDialog = false })
        if (updatesDialog) AlertDialog(onDismissRequest = { updatesDialog = false }, title = { Text("Windows 更新") },
            text = { Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(when (val status = updateState) {
                    is UpdateState.Disabled -> status.reason
                    UpdateState.Idle -> "等待检查更新"
                    UpdateState.Checking -> "正在检查…"
                    is UpdateState.UpToDate -> "已是最新版本 ${status.version}"
                    is UpdateState.Available -> "新版本 ${status.update.version}，${status.update.size / 1024 / 1024} MB"
                    is UpdateState.Downloading -> "正在下载：${status.receivedBytes / 1024 / 1024} / ${status.totalBytes / 1024 / 1024} MB"
                    UpdateState.Verifying -> "正在校验更新包"
                    is UpdateState.Prepared -> if (details != null || opening) "更新已下载，退出播放后安装" else "更新已下载，等待安装"
                    UpdateState.Launching -> "正在启动新版本"
                    UpdateState.Launched -> "新版本已启动"
                    is UpdateState.Failed -> status.message
                })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("自动更新，播放时延后", Modifier.weight(1f))
                    Switch(checked = automaticUpdates, enabled = !activatingUpdate, onCheckedChange = {
                        automaticUpdates = it; library.setAutomaticUpdates(it)
                    })
                }
                Text("更新失败时继续使用当前版本。", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = {
                val available = updateState as? UpdateState.Available
                if (available != null) TextButton(onClick = {
                    prepareUpdate(available.update, manuallyRequested = true)
                }, enabled = updateJob?.isActive != true && !activatingUpdate) { Text("下载并更新") }
                else if (updateState is UpdateState.Prepared) TextButton(onClick = {
                    updateRequestedManually = true
                }, enabled = !activatingUpdate) { Text(if (details != null || opening) "退出播放后更新" else "安装更新") }
                else TextButton(onClick = { updatesDialog = false }) { Text("完成") }
            })
    }
}

private fun VideoDetails.toCard() = VideoCard(bvid, title, cover, author, playCount, pages.firstOrNull()?.duration?.toInt() ?: 0)
private fun formatCount(count: Long) = if (count >= 10000) "%.1f万".format(count / 10000.0) else count.toString()
private fun time(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toInt()
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
        else "%02d:%02d".format(total / 60, total % 60)
}

@Composable
private fun FeedCard(card: VideoCard, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column {
            Box {
                AsyncImage(card.cover, card.title, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant))
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.48f)).padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("▷ ${formatCount(card.playCount)}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    Text(time(card.duration.toDouble()), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(card.title, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(card.author, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun VideoPage(info: VideoDetails, related: List<VideoCard>, comments: List<Comment>, player: MpvPlayer?,
    part: Int, quality: Int, favorite: Boolean, onVideo: (String) -> Unit, onPart: (Int) -> Unit,
    onQuality: (Int) -> Unit, onFavorite: () -> Unit, danmakuEnabled: Boolean, onDanmaku: () -> Unit,
    onFullscreen: () -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                if (player != null) SwingPanel(factory = { player.surface }, background = Color.Black, modifier = Modifier.fillMaxSize())
            }
            if (player != null) PlayerControls(player)
            Text(info.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(info.author, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f), maxLines = 1)
                Text("${formatCount(info.playCount)} 播放 · ${formatCount(info.likeCount)} 赞", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onFavorite) { Text(if (favorite) "♥ 已收藏" else "♡ 收藏") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("画质", style = MaterialTheme.typography.bodySmall)
                listOf(32 to "480P", 64 to "720P", 80 to "1080P").forEach { (qn, label) ->
                    FilterChip(selected = quality == qn, onClick = { onQuality(qn) }, label = { Text(label) })
                }
                FilterChip(selected = danmakuEnabled, onClick = onDanmaku, label = { Text("弹幕") })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onFullscreen) { Text("全屏 F11") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (info.pages.size > 1) {
                    Text("选集", fontWeight = FontWeight.SemiBold)
                    info.pages.forEachIndexed { index, item ->
                        Surface(Modifier.fillMaxWidth().clickable { onPart(index) }, shape = RoundedCornerShape(12.dp),
                            color = if (part == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                            Text("P${index + 1} · ${item.title}", Modifier.padding(14.dp))
                        }
                    }
                }
                Text(info.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
        var showComments by remember(info.bvid) { mutableStateOf(false) }
        Column(Modifier.width(300.dp).fillMaxHeight()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showComments, onClick = { showComments = false }, label = { Text("相关推荐") })
                FilterChip(selected = showComments, onClick = { showComments = true }, label = { Text("评论") })
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                if (showComments) {
                    if (comments.isEmpty()) item { Text("暂无可展示的评论", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(comments, key = { it.id }) { comment ->
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(comment.author, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                                Text(comment.text, style = MaterialTheme.typography.bodyMedium)
                                Text("${comment.likeCount} 赞", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else items(related, key = { it.bvid }) { card -> FeedCard(card) { onVideo(card.bvid) } }
            }
        }
    }
}

@Composable
private fun PlayerControls(player: MpvPlayer) {
    val state by player.state.collectAsState()
    var seeking by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    val duration = state.durationSeconds.coerceAtLeast(1.0).toFloat()
    Column {
        Slider(value = (seeking ?: state.positionSeconds.toFloat()).coerceIn(0f, duration),
            onValueChange = { seeking = it }, valueRange = 0f..duration,
            onValueChangeFinished = { seeking?.let { player.seekTo(it.toDouble()) }; seeking = null }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { player.togglePause() }, modifier = Modifier.height(48.dp)) { Text(if (state.paused) "▷ 播放" else "Ⅱ 暂停") }
            Text("${time(state.positionSeconds)} / ${time(state.durationSeconds)}", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(onClick = { speedMenu = true }) { Text("${state.speed}×") }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    DEFAULT_PLAYBACK_SPEED_OPTIONS.forEach { speed ->
                        DropdownMenuItem(text = { Text("${speed}×") }, onClick = { player.setSpeed(speed.toDouble()); speedMenu = false })
                    }
                }
            }
            Text("音量", style = MaterialTheme.typography.labelSmall)
            Slider(value = state.volume.toFloat().coerceIn(0f, 100f), onValueChange = { player.setVolume(it.toDouble()) },
                valueRange = 0f..100f, modifier = Modifier.width(100.dp))
        }
        if (state.error != null) Text(state.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        else if (state.loading) Text("正在加载视频…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LoginDialog(repository: DesktopRepository, account: AccountSummary?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var qr by remember { mutableStateOf<QrLogin?>(null) }
    var status by remember { mutableStateOf("正在获取二维码…") }
    var error by remember { mutableStateOf<String?>(null) }
    var cookie by remember { mutableStateOf("") }
    var cookieMode by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(generation, cookieMode) {
        if (account != null || cookieMode) return@LaunchedEffect
        try {
            qr = repository.beginQrLogin()
            while (true) {
                delay(1500)
                when (val result = repository.pollQrLogin(qr!!.key)) {
                    QrLoginState.Waiting -> status = "请使用哔哩哔哩手机客户端扫码"
                    QrLoginState.Scanned -> status = "已扫码，请在手机上确认登录"
                    QrLoginState.Expired -> { status = "二维码已过期，请刷新"; break }
                    is QrLoginState.Complete -> { onDismiss(); break }
                }
            }
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            error = exception.message ?: "获取二维码失败"
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (account != null) "账号" else "登录 BiliPai") },
        text = {
            Column(Modifier.width(360.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (account != null) {
                    Text(account.name, style = MaterialTheme.typography.titleLarge)
                    Text("UID ${account.mid}")
                    Text("登录信息保存在这台电脑。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (cookieMode) {
                    Text("粘贴你自己的 B 站 Cookie，包含 SESSDATA。", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(value = cookie, onValueChange = { cookie = it }, label = { Text("Cookie") },
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), minLines = 3)
                } else {
                    qr?.let { login ->
                        val bitmap = remember(login.url) {
                            val matrix = MultiFormatWriter().encode(login.url, BarcodeFormat.QR_CODE, 256, 256)
                            BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB).apply {
                                for (y in 0 until 256) for (x in 0 until 256) setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
                            }.toComposeImageBitmap()
                        }
                        Image(BitmapPainter(bitmap), "登录二维码", Modifier.size(256.dp))
                    }
                    Text(status)
                    TextButton(onClick = { error = null; qr = null; generation++ }) { Text("刷新二维码") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            if (account != null) TextButton(onClick = { repository.logout(); onDismiss() }) { Text("退出登录") }
            else if (cookieMode) TextButton(onClick = {
                scope.launch { try { repository.importCookies(cookie); onDismiss() }
                    catch (exception: Exception) { error = exception.message ?: "Cookie 登录失败" } }
            }, enabled = cookie.isNotBlank()) { Text("登录") }
            else TextButton(onClick = onDismiss) { Text("完成") }
        }, dismissButton = {
            if (account == null) TextButton(onClick = { cookieMode = !cookieMode; error = null }) {
                Text(if (cookieMode) "扫码登录" else "Cookie 登录")
            }
        })
}

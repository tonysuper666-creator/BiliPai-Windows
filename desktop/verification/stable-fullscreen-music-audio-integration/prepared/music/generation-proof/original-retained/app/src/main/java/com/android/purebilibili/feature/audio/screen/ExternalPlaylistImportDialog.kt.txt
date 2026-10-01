package com.android.purebilibili.feature.audio.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.android.purebilibili.core.store.LocalPlaylist
import com.android.purebilibili.core.store.LocalPlaylistItem
import com.android.purebilibili.core.store.LocalPlaylistStore
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.data.repository.ExternalPlaylistRepository
import com.android.purebilibili.data.repository.SearchRepository
import com.android.purebilibili.feature.home.components.LiquidGlassTuning
import com.android.purebilibili.feature.home.components.biliPaiFloatingDockShell
import com.android.purebilibili.feature.home.components.resolveLiquidGlassTuning
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.Backdrop as MiuixBackdrop
import java.util.UUID

/**
 * Import an external playlist, match its tracks to Bilibili videos, and save the local playlist.
 * The dialog and its controls use Compose Material 3; the result list is lazy for large playlists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalPlaylistImportDialog(
    onDismiss: () -> Unit,
    onSaved: ((LocalPlaylist) -> Unit)? = null,
    backdrop: MiuixBackdrop? = null,
    glassEnabled: Boolean = false,
    liquidGlassTuning: LiquidGlassTuning = resolveLiquidGlassTuning(progress = 0.5f),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var inputText by remember { mutableStateOf("") }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var playlist by remember { mutableStateOf<ExternalPlaylistRepository.ExternalPlaylistMeta?>(null) }

    var matching by remember { mutableStateOf(false) }
    var matchCompleted by remember { mutableIntStateOf(0) }
    var matchTotal by remember { mutableIntStateOf(0) }
    var matchingTrackTitle by remember { mutableStateOf("") }
    var matchResults by remember {
        mutableStateOf<List<ExternalPlaylistRepository.MatchOutcome>>(emptyList())
    }
    var matchJob by remember { mutableStateOf<Job?>(null) }

    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var manualKeyword by remember { mutableStateOf("") }
    var manualSearching by remember { mutableStateOf(false) }
    var manualResults by remember {
        mutableStateOf<List<ExternalPlaylistRepository.MatchedVideo>>(emptyList())
    }

    LaunchedEffect(context) {
        ExternalPlaylistRepository.loadImportCheckpoint(context)?.let { checkpoint ->
            playlist = checkpoint.playlist
            matchResults = checkpoint.outcomes.map { ExternalPlaylistRepository.MatchOutcome(it.track, it.video) }
            matchTotal = checkpoint.playlist.tracks.size
            matchCompleted = checkpoint.completedCount
        }
    }

    fun dismissEditing() {
        editingIndex = null
        manualResults = emptyList()
        manualKeyword = ""
    }

    fun startMatching(resume: Boolean = false) {
        val meta = playlist ?: return
        matching = true
        matchTotal = meta.tracks.size
        val startIndex = if (resume) matchCompleted.coerceIn(0, meta.tracks.size) else 0
        if (!resume || matchResults.size != meta.tracks.size) {
            matchCompleted = 0
            matchResults = meta.tracks.map { ExternalPlaylistRepository.MatchOutcome(it, null) }
        }
        scope.launch {
            ExternalPlaylistRepository.saveImportCheckpoint(
                context,
                ExternalPlaylistRepository.ImportCheckpoint(meta, matchResults, matchCompleted),
            )
        }
        matchJob = scope.launch {
            ExternalPlaylistRepository.matchTracks(meta.tracks, startIndex) { completed, _, outcome ->
                matchCompleted = completed
                matchingTrackTitle = outcome.track.title
                matchResults = matchResults.toMutableList().also { list ->
                    if (completed - 1 in list.indices) list[completed - 1] = outcome
                }
                ExternalPlaylistRepository.saveImportCheckpoint(
                    context,
                    ExternalPlaylistRepository.ImportCheckpoint(meta, matchResults, completed),
                )
            }
            matching = false
        }
    }

    fun savePlaylist() {
        val meta = playlist ?: return
        val items = matchResults.mapNotNull { it.video }.map { video ->
            LocalPlaylistItem(
                bvid = video.bvid,
                title = video.title,
                cover = video.cover,
                owner = video.author,
                durationSec = video.durationSec,
            )
        }
        if (items.isEmpty()) return
        scope.launch {
            val local = LocalPlaylist(
                id = UUID.randomUUID().toString(),
                name = meta.name,
                coverUrl = meta.coverUrl,
                source = meta.source.name.lowercase(),
                createdAtMs = System.currentTimeMillis(),
                items = items,
            )
            LocalPlaylistStore.savePlaylist(context, local)
            ExternalPlaylistRepository.clearImportCheckpoint(context)
            onSaved?.invoke(local)
            onDismiss()
        }
    }

    val panelShape = RoundedCornerShape(24.dp)
    val glassSurface = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.9f)
                .biliPaiFloatingDockShell(
                    backdrop = backdrop,
                    containerColor = glassSurface,
                    pressProgress = 0f,
                    shape = panelShape,
                    enabled = glassEnabled && backdrop != null,
                    blurEnabled = glassEnabled && backdrop == null,
                    liquidGlassTuning = liquidGlassTuning,
                ),
            shape = panelShape,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "导入外部歌单",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                }
                Spacer(Modifier.height(8.dp))

                val meta = playlist
                if (meta == null) {
                    Text(
                        text = "支持网易云音乐和 QQ 音乐歌单。复制公开歌单的分享链接，粘贴到下面。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("歌单链接或 ID") },
                        singleLine = true,
                    )
                    fetchError?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (fetching) return@Button
                            fetching = true
                            fetchError = null
                            scope.launch {
                                val parsed = ExternalPlaylistRepository.parsePlaylistInput(inputText)
                                val sourceAndId = when {
                                    parsed != null -> parsed
                                    inputText.trim().matches(Regex("\\d{4,}")) -> {
                                        ExternalPlaylistRepository.Source.NETEASE to inputText.trim()
                                    }
                                    else -> null
                                }
                                if (sourceAndId == null) {
                                    fetchError = "无法识别链接，请粘贴网易云或 QQ 音乐的完整分享链接"
                                } else {
                                    ExternalPlaylistRepository.fetchPlaylist(sourceAndId.first, sourceAndId.second)
                                        .onSuccess { fetched ->
                                            if (fetched.tracks.isEmpty()) fetchError = "歌单为空或为私密歌单"
                                            else {
                                                playlist = fetched
                                                matchResults = fetched.tracks.map {
                                                    ExternalPlaylistRepository.MatchOutcome(it, null)
                                                }
                                                matchCompleted = 0
                                                matchTotal = fetched.tracks.size
                                                ExternalPlaylistRepository.saveImportCheckpoint(
                                                    context,
                                                    ExternalPlaylistRepository.ImportCheckpoint(
                                                        fetched,
                                                        matchResults,
                                                        completedCount = 0,
                                                    ),
                                                )
                                            }
                                        }
                                        .onFailure { fetchError = it.message ?: "获取歌单失败" }
                                }
                                fetching = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !fetching,
                    ) {
                        Text(if (fetching) "正在获取…" else "获取歌单信息")
                    }
                } else {
                    val completed = !matching && matchResults.isNotEmpty() && matchCompleted >= matchTotal
                    val outcomes = matchResults.ifEmpty {
                        meta.tracks.map { ExternalPlaylistRepository.MatchOutcome(it, null) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = meta.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${meta.author} · ${meta.tracks.size} 首",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (completed) {
                            Button(onClick = { savePlaylist() }, enabled = matchResults.any { it.video != null }) {
                                Text("保存")
                            }
                        } else {
                            Button(onClick = {
                                if (matching) {
                                    matchJob?.cancel()
                                    matching = false
                                } else {
                                    startMatching(resume = matchCompleted in 1 until meta.tracks.size)
                                }
                            }) {
                                Text(
                                    when {
                                        matching -> "停止"
                                        matchCompleted in 1 until meta.tracks.size -> "继续匹配"
                                        matchCompleted >= meta.tracks.size -> "重新匹配"
                                        else -> "开始匹配"
                                    },
                                )
                            }
                        }
                    }

                    if (matchTotal > 0 && !completed) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { (matchCompleted.toFloat() / matchTotal.coerceAtLeast(1)).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = if (matching) {
                                "正在匹配：$matchingTrackTitle ($matchCompleted/$matchTotal)"
                            } else {
                                "已匹配 $matchCompleted/$matchTotal 首"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                        )
                    } else {
                        Text(
                            text = "匹配 ${matchResults.count { it.video != null }}/${matchResults.size} 首",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                        )
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(outcomes) { index, outcome ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Card(
                                    modifier = Modifier.clickable(enabled = matchResults.isNotEmpty()) {
                                        if (matchResults.isNotEmpty()) {
                                            if (editingIndex == index) {
                                                dismissEditing()
                                            } else {
                                                editingIndex = index
                                                manualKeyword = ExternalPlaylistRepository
                                                    .buildSearchQueryForManualMatch(outcome.track)
                                                manualResults = emptyList()
                                            }
                                        }
                                    },
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.62f),
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${index + 1}. ${outcome.track.title}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = outcome.video?.let { "${it.author} · ${it.title}" }
                                                    ?: outcome.track.artists.joinToString("/").ifBlank { "未匹配" },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (outcome.video != null) MaterialTheme.colorScheme.onSurfaceVariant
                                                else MaterialTheme.colorScheme.error.copy(alpha = 0.86f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        outcome.video?.cover?.takeIf { it.isNotBlank() }?.let { cover ->
                                            AsyncImage(
                                                model = cover,
                                                contentDescription = null,
                                                modifier = Modifier.size(42.dp).clip(AppShapes.container(ContainerLevel.Chip)),
                                                contentScale = ContentScale.Crop,
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                if (editingIndex == index) dismissEditing() else {
                                                    editingIndex = index
                                                    manualKeyword = ExternalPlaylistRepository
                                                        .buildSearchQueryForManualMatch(outcome.track)
                                                    manualResults = emptyList()
                                                }
                                            },
                                            enabled = matchResults.isNotEmpty(),
                                        ) {
                                            Icon(Icons.Outlined.Edit, contentDescription = "手动修正")
                                        }
                                    }
                                }

                                if (editingIndex == index) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = manualKeyword,
                                            onValueChange = { manualKeyword = it },
                                            modifier = Modifier.weight(1f),
                                            label = { Text("搜索 B 站视频") },
                                            singleLine = true,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Button(
                                            onClick = {
                                                if (manualKeyword.isNotBlank() && !manualSearching) {
                                                    scope.launch {
                                                        manualSearching = true
                                                        SearchRepository.search(keyword = manualKeyword)
                                                            .onSuccess { (items, _) ->
                                                                manualResults = items.take(8).map {
                                                                    ExternalPlaylistRepository.MatchedVideo(
                                                                        bvid = it.bvid,
                                                                        title = it.title,
                                                                        cover = it.pic,
                                                                        author = it.owner.name,
                                                                        durationSec = it.duration.toLong(),
                                                                    )
                                                                }
                                                            }
                                                        manualSearching = false
                                                    }
                                                }
                                            },
                                            enabled = manualKeyword.isNotBlank() && !manualSearching,
                                        ) {
                                            Text(if (manualSearching) "搜索中" else "搜索")
                                        }
                                    }
                                    manualResults.forEach { video ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                matchResults = matchResults.toMutableList().also { list ->
                                                    list[index] = ExternalPlaylistRepository.MatchOutcome(list[index].track, video)
                                                }
                                                scope.launch {
                                                    ExternalPlaylistRepository.saveImportCheckpoint(
                                                        context,
                                                        ExternalPlaylistRepository.ImportCheckpoint(
                                                            meta,
                                                            matchResults,
                                                            matchCompleted,
                                                        ),
                                                    )
                                                }
                                                dismissEditing()
                                            },
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f),
                                            ),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                AsyncImage(
                                                    model = video.cover,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(width = 64.dp, height = 42.dp)
                                                        .clip(AppShapes.container(ContainerLevel.Chip)),
                                                    contentScale = ContentScale.Crop,
                                                )
                                                Spacer(Modifier.width(10.dp))
                                                Column {
                                                    Text(video.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(video.author, style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (completed) {
                        Text(
                            text = "未匹配的曲目将被跳过，保存后可稍后再添加。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

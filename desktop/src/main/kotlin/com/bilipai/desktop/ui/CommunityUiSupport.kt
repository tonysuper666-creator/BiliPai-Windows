package com.bilipai.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.bilipai.desktop.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class CommunityBatch<T, C>(val items: List<T>, val next: C?)

internal class CommunityFeedMemory {
    private val pages = linkedMapOf<Any?, Any>()
    @Suppress("UNCHECKED_CAST")
    fun <T, C> page(key: Any?): CommunityFeedState<T, C> = pages.getOrPut(key) {
        if (pages.size >= 32) pages.remove(pages.keys.first())
        CommunityFeedState<T, C>()
    } as CommunityFeedState<T, C>
}
internal class CommunityFeedState<T, C> {
    var rows by mutableStateOf(emptyList<T>())
    var next by mutableStateOf<C?>(null)
    var initialized by mutableStateOf(false)
    val scroll = LazyListState()
}
internal val LocalCommunityFeedMemory = staticCompositionLocalOf<CommunityFeedMemory?> { null }
internal val LocalCommunityFeedNamespace = staticCompositionLocalOf<Any?> { null }

@Composable
internal fun <T, C> CommunityFeed(key: Any?, first: C, load: suspend (C) -> CommunityBatch<T, C>,
    identity: (T) -> Any, onLogin: () -> Unit, header: @Composable () -> Unit = {},
    row: @Composable (T) -> Unit) {
    val scope = rememberCoroutineScope()
    val memory = LocalCommunityFeedMemory.current
    val namespace = LocalCommunityFeedNamespace.current
    val page = remember(memory, namespace, key) { memory?.page<T, C>(Pair(namespace, key)) ?: CommunityFeedState() }
    val scroll = page.scroll
    var refresh by remember { mutableIntStateOf(0) }
    var rows by page::rows
    var next by page::next
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    var failedMore by remember { mutableStateOf(false) }
    val generation = remember(key, refresh) { Any() }
    val activeGeneration by rememberUpdatedState(generation)
    val currentLoad by rememberUpdatedState(load)
    val currentIdentity by rememberUpdatedState(identity)
    suspend fun fetch(cursor: C, replace: Boolean) {
        val request = generation
        busy = true; failure = null; failedMore = false
        try {
            val result = currentLoad(cursor)
            if (activeGeneration === request) {
                rows = (if (replace) result.items else rows + result.items).distinctBy(currentIdentity)
                next = result.next
                page.initialized = true
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (activeGeneration === request) { failure = error; failedMore = !replace }
        } finally { if (activeGeneration === request) busy = false }
    }
    LaunchedEffect(generation) { if (!page.initialized || refresh > 0) { rows = emptyList(); next = null; fetch(first, true) } }
    LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${rows.size} 项", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { refresh++ }, enabled = !busy) { Text("刷新") }
            }
            header()
        }
        items(rows, key = identity) { row(it) }
        if (failure != null) item { CommunityFailure(failure!!, onLogin) {
            if (!busy) {
                val cursor = next
                if (failedMore && cursor != null) { busy = true; scope.launch { fetch(cursor, false) } }
                else refresh++
            }
        } }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!busy && rows.isEmpty() && failure == null) item { Text("暂无内容", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (next != null && !busy) item {
            Button(onClick = {
                if (busy) return@Button
                val cursor = next ?: return@Button
                busy = true
                scope.launch { fetch(cursor, false) }
            }) { Text("加载更多") }
        }
    }
}

@Composable
internal fun CommunityFailure(error: Throwable, onLogin: () -> Unit, retry: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(error.message ?: "加载失败", color = MaterialTheme.colorScheme.error)
        if ((error as? BiliApiException)?.apiCode in setOf(-101, -111)) Button(onClick = onLogin) { Text("登录账号") }
        if (retry != null) TextButton(onClick = retry) { Text("重试") }
    }
}

@Composable
internal fun CommunityLoginGate(repository: DesktopRepository, onLogin: () -> Unit, content: @Composable (Long) -> Unit) {
    val account by repository.account.collectAsState()
    if (account == null) Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("登录后查看账号内容", style = MaterialTheme.typography.titleLarge)
        Button(onClick = onLogin) { Text("扫码登录") }
    } else content(account!!.mid)
}

@Composable
internal fun CommunityAction(label: String, onLogin: () -> Unit, action: suspend () -> Unit, onSuccess: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var complete by remember { mutableStateOf(false) }
    Column {
        TextButton(enabled = !busy, onClick = {
            if (busy) return@TextButton
            busy = true; error = null; complete = false
            scope.launch {
                try { action(); complete = true; onSuccess() }
                catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure }
                finally { busy = false }
            }
        }) { Text(if (busy) "处理中…" else label) }
        if (error != null) CommunityFailure(error!!, onLogin)
        if (complete) Text("已完成", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun CommunityVideoRow(card: VideoCard, onVideo: (VideoCard) -> Unit, onUser: (Long) -> Unit = {},
    actions: @Composable () -> Unit = {}) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AsyncImage(model = imageUrl(card.cover), contentDescription = card.title, contentScale = ContentScale.Crop,
                modifier = Modifier.size(160.dp, 94.dp).clickable { onVideo(card) })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(card.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onVideo(card) })
                Text(card.author, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable(enabled = card.authorMid > 0) { onUser(card.authorMid) })
                Text("${card.playCount} 次播放 · ${card.duration / 60}:${(card.duration % 60).toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                card.progressSeconds?.takeIf { it > 0 }?.let { Text("继续观看 · ${it / 60}:${(it % 60).toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                actions()
            }
        }
    }
}

internal fun imageUrl(url: String) = if (url.startsWith("//")) "https:$url" else url

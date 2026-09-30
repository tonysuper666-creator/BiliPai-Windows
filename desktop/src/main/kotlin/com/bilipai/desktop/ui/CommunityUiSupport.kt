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
    fun invalidate(namespace: Any?) {
        pages.forEach { (key, value) ->
            if ((key as? Pair<*, *>)?.first == namespace) (value as CommunityFeedState<*, *>).invalidate()
        }
    }
}
internal class CommunityFeedState<T, C>(val scroll: LazyListState = LazyListState()) : DesktopDynamicCardItemsOwner {
    var reloadRevision by mutableLongStateOf(0L); private set
    internal var editorRefreshRevision=0L
    var rows by mutableStateOf(emptyList<T>())
    var next by mutableStateOf<C?>(null)
    var initialized by mutableStateOf(false)
    var failure by mutableStateOf<Throwable?>(null)
    var failedCursor by mutableStateOf<C?>(null)
    var failedReplace by mutableStateOf(false)
    var statusMessage by mutableStateOf<String?>(null)
    override fun mutateDynamicItems(transform: (List<com.android.purebilibili.data.model.response.DynamicItem>) -> List<com.android.purebilibili.data.model.response.DynamicItem>) {
        if (rows.isEmpty() || rows.any { it !is com.android.purebilibili.data.model.response.DynamicItem }) return
        @Suppress("UNCHECKED_CAST")
        val dynamicRows = rows as List<com.android.purebilibili.data.model.response.DynamicItem>
        @Suppress("UNCHECKED_CAST")
        val updated = transform(dynamicRows) as List<T>
        rows = updated
    }

    /** Keep cached rows and their scroll owner until a replacement first page succeeds. */
    fun invalidate() {
        reloadRevision++
        initialized = false
        failure = null
        failedCursor = null
        failedReplace = false
    }
    fun acceptBatch(revision: Long, batch: CommunityBatch<T, C>, replace: Boolean, identity: (T) -> Any): Boolean {
        if (revision != reloadRevision) return false
        rows = (if (replace) batch.items else rows + batch.items).distinctBy(identity)
        next = batch.next
        failure = null
        failedCursor = null
        initialized = true
        return true
    }
    fun acceptFailure(revision: Long, error: Throwable, cursor: C, replace: Boolean): Boolean {
        if (revision != reloadRevision) return false
        failure = error
        failedCursor = cursor
        failedReplace = replace
        return true
    }
}
internal val LocalCommunityFeedMemory = staticCompositionLocalOf<CommunityFeedMemory?> { null }
internal val LocalCommunityFeedNamespace = staticCompositionLocalOf<Any?> { null }

@Composable
internal fun <T, C> CommunityFeed(key: Any?, first: C, load: suspend (C) -> CommunityBatch<T, C>,
    identity: (T) -> Any, onLogin: () -> Unit, header: @Composable () -> Unit = {}, transform: (List<T>) -> List<T> = { it },
    dynamicContent: Boolean = false, row: @Composable (T) -> Unit) {
    val scope = rememberCoroutineScope()
    val memory = LocalCommunityFeedMemory.current ?: LocalDesktopBrowseMemory.current?.feeds
    val namespace = LocalCommunityFeedNamespace.current
    val page = remember(memory, namespace, key) { memory?.page<T, C>(Pair(namespace, key)) ?: CommunityFeedState() }
    LocalDesktopDynamicCardStateRegistry.current?.register(page)
    val session=LocalDesktopDynamicCardSession.current
    val contentRevision by (session?.contentRevision ?: remember { kotlinx.coroutines.flow.MutableStateFlow(0L) }).collectAsState()
    LaunchedEffect(page,dynamicContent,contentRevision) {
        if(dynamicContent&&contentRevision>page.editorRefreshRevision) {
            page.editorRefreshRevision=contentRevision
            page.invalidate()
        }
    }
    val scroll = page.scroll
    var refresh by remember(page) { mutableIntStateOf(0) }
    var rows by page::rows
    var next by page::next
    var busy by remember(page) { mutableStateOf(false) }
    var failure by page::failure
    val generation = remember(page, refresh, page.reloadRevision) { Any() }
    val activeGeneration by rememberUpdatedState(generation)
    val currentLoad by rememberUpdatedState(load)
    val currentIdentity by rememberUpdatedState(identity)
    val displayed = remember(rows, transform) { transform(rows).distinctBy(identity) }
    suspend fun fetch(cursor: C, replace: Boolean) {
        val request = generation
        val requestRevision = page.reloadRevision
        busy = true; failure = null; page.failedCursor = null
        try {
            val result = currentLoad(cursor)
            if (activeGeneration === request) {
                page.acceptBatch(requestRevision, result, replace, currentIdentity)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (activeGeneration === request) page.acceptFailure(requestRevision, error, cursor, replace)
        } finally { if (activeGeneration === request) busy = false }
    }
    LaunchedEffect(generation) { if ((!page.initialized && failure == null) || refresh > 0) fetch(first, true) }
    LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${displayed.size} 项", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { refresh++ }, enabled = !busy) { Text("刷新") }
            }
            header()
        }
        items(displayed, key = identity) { row(it) }
        if (failure != null) item { CommunityFailure(failure!!, onLogin) {
            if (!busy) {
                val cursor = page.failedCursor
                if (cursor != null) { busy = true; scope.launch { fetch(cursor, page.failedReplace) } }
                else refresh++
            }
        } }
        if (busy) item { DesktopLoadingIndicator(Modifier.fillMaxWidth()) }
        if (!busy && displayed.isEmpty() && failure == null) item { Text(if (rows.isEmpty()) "暂无内容" else "当前筛选隐藏了这一批内容", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (next != null && !busy && page.initialized) item {
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

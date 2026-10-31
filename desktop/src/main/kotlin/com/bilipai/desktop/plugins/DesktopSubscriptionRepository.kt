package com.bilipai.desktop.plugins

import com.android.purebilibili.core.plugin.feed.*
import com.android.purebilibili.feature.plugin.resolveImportPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

data class DesktopSubscriptionState(val sources: List<SavedSubscriptionFeed> = emptyList(),
    val reading: FeedReadingSnapshot = FeedReadingSnapshot(), val errors: List<String> = emptyList(),
    val loading: Boolean = false, val activeSources: List<FeedSource> = emptyList())

data class DesktopSubscriptionExtraSources(val revision: Long = 0, val sources: List<FeedSource> = emptyList())

/** Windows orchestration around the original feed parser, conditional HTTP and reading store. */
class DesktopSubscriptionRepository(private val context: DesktopPluginContext,
    private val extraSources: suspend () -> DesktopSubscriptionExtraSources = { DesktopSubscriptionExtraSources() },
    private val extraSourceRevision: () -> Long = { 0 },
    private val enabled: () -> Boolean) {
    /** Upstream feed stores take an Android Context over this plugin platform. */
    private val upstreamContext = context.asAndroidContext()
    private val mutation = Mutex()
    private val generation = AtomicLong()
    @Volatile private var stopped = false
    private val _state = MutableStateFlow(DesktopSubscriptionState())
    val state: StateFlow<DesktopSubscriptionState> = _state.asStateFlow()

    suspend fun loadCached() = withContext(Dispatchers.IO) {
        check(!stopped) { "订阅服务已停止" }
        val token = generation.incrementAndGet()
        val sources = SubscriptionFeedStore.list(upstreamContext)
        val builtinEnabled = enabled()
        val extra = extraSources()
        val reading = FeedReadingStore.load(upstreamContext)
        val builtinSources = if (builtinEnabled) sources.filter { it.enabled && isHttpFeedUrl(it.url) }
            .map { FeedSource("builtin:${it.id}", it.title, it.url, group = it.group) } else emptyList()
        val activeSources = (builtinSources + extra.sources.filter { isHttpFeedUrl(it.url) }).distinctBy { it.url }
        val enabledIds = activeSources.map { it.id }.toSet()
        if (!stopped && token == generation.get() && builtinEnabled == enabled() && extra.revision == extraSourceRevision())
            publishState(DesktopSubscriptionState(sources, reading.copy(items = mergeCachedFeedItems(reading.items, emptyList(), enabledIds)), activeSources = activeSources)) { !stopped && token == generation.get() && builtinEnabled == enabled() && extra.revision == extraSourceRevision() }
    }

    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        check(!stopped) { "订阅服务已停止" }
        val token = generation.incrementAndGet()
        val feeds = SubscriptionFeedStore.list(upstreamContext)
        val builtinEnabled = enabled()
        val extra = extraSources()
        val builtinSources = if (builtinEnabled) feeds.filter { it.enabled && isHttpFeedUrl(it.url) }
            .map { FeedSource("builtin:${it.id}", it.title, it.url, group = it.group) } else emptyList()
        val sources = (builtinSources + extra.sources.filter { isHttpFeedUrl(it.url) }).distinctBy { it.url }
        val ids = sources.map { it.id }.toSet()
        val cache = FeedReadingStore.load(upstreamContext)
        fun current() = !stopped && token == generation.get() && enabled() == builtinEnabled &&
            extra.revision == extraSourceRevision()
        if (sources.isEmpty()) {
            if (current()) publishState(DesktopSubscriptionState(feeds,
                cache.copy(items = emptyList()), if (builtinEnabled) emptyList() else listOf("请启用订阅插件或已授权的 JS 订阅模块"), activeSources = sources)) { current() }
            return@withContext
        }
        publishState(DesktopSubscriptionState(feeds, cache.copy(items = mergeCachedFeedItems(cache.items, emptyList(), ids)), loading = true, activeSources = sources)) { current() }
        try {
            val result = loadFeedSources(sources, FeedConditionalStore.load(upstreamContext)) { update ->
                if (current()) publishProgress(DesktopSubscriptionState(feeds,
                    cache.copy(items = mergeCachedFeedItems(cache.items, update.items, ids)), update.errors, loading = true, activeSources = sources)) { current() }
            }
            if (!current()) return@withContext
            val merged = mergeCachedFeedItems(cache.items, result.items, ids)
            mutation.withLock {
                if (!current()) return@withLock
                DesktopSubscriptionWriteAdmission.whileCurrent(::current) {
                FeedReadingStore.saveItems(upstreamContext, merged)
                if (result.validators.isNotEmpty()) FeedConditionalStore.update(upstreamContext, result.validators)
                publishState(DesktopSubscriptionState(SubscriptionFeedStore.list(upstreamContext), FeedReadingStore.load(upstreamContext), result.errors, activeSources = sources)) { current() }
                }
            }
        } finally {
            if (token == generation.get()) publishCleanupState { token == generation.get() }
        }
    }

    suspend fun add(title: String, url: String): SavedSubscriptionFeed = withContext(Dispatchers.IO) {
        check(!stopped) { "订阅服务已停止" }
        val resolvedTitle = resolveSubscriptionTitle(url, title).getOrThrow()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        mutate { SubscriptionFeedStore.add(upstreamContext, resolvedTitle, url).getOrThrow() }
    }
    suspend fun importOpml(text: String): Int = withContext(Dispatchers.IO) {
        check(!stopped) { "订阅服务已停止" }
        val parsed = parseSubscriptionImport(resolveImportPayload(text))
        require(parsed.isNotEmpty()) { "没有有效的 RSS、Atom 或 OPML 订阅地址" }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        // Persist and publish immediately. The retained Root schedules title completion separately.
        mutate { SubscriptionFeedStore.addAll(upstreamContext, parsed) }
    }

    /** The retained caller owns/drains this operation; this repository creates no detached Job. */
    suspend fun refreshMissingTitles(): Unit = withContext(Dispatchers.IO) {
        check(!stopped) { "订阅服务已停止" }
        DesktopSubscriptionWriteAdmission.whileCurrent({ !stopped }) {
            // Only the original resolver mutex/Semaphore spans the network batch.
            refreshMissingSubscriptionTitles(upstreamContext)
            mutation.withLock {
                check(!stopped) { "订阅服务已停止" }
                val feeds = SubscriptionFeedStore.list(upstreamContext)
                val byIdentity = feeds.associateBy { "builtin:${it.id}" to it.url }
                DesktopSubscriptionWriteAdmission.commitOrOriginal {
                    if (!stopped) {
                        val latest = _state.value
                        val activeSources = latest.activeSources.mapNotNull { source ->
                            if (!source.id.startsWith("builtin:")) source
                            else byIdentity[source.id to source.url]?.takeIf { it.enabled }?.let {
                                source.copy(title = it.title, group = it.group)
                            }
                        }
                        _state.value = latest.copy(sources = feeds, activeSources = activeSources)
                    }
                }
            }
        }
    }
    suspend fun exportOpml(): String = withContext(Dispatchers.IO) { buildSubscriptionOpml(SubscriptionFeedStore.list(upstreamContext)) }
    suspend fun setEnabled(id: String, enabled: Boolean) = mutate { SubscriptionFeedStore.setEnabled(upstreamContext, id, enabled) }
    /** Saved feeds only. A builtin:* id is rejected before the store is touched. */
    suspend fun setGroup(id: String, group: String) = mutate {
        require(!id.startsWith("builtin:")) { "内置订阅源不能修改分组" }
        SubscriptionFeedStore.setGroup(upstreamContext, id, group)
    }
    suspend fun remove(id: String): Unit = mutate {
        val source = SubscriptionFeedStore.list(upstreamContext).firstOrNull { it.id == id } ?: return@mutate
        SubscriptionFeedStore.remove(upstreamContext, id)
        FeedConditionalStore.clear(upstreamContext, setOf(source.url))
    }
    suspend fun setRead(item: ParsedFeedItem, read: Boolean): Unit = mutation.withLock {
        check(!stopped) { "订阅服务已停止" }
        if (read) FeedReadingStore.recordRead(upstreamContext, feedItemKey(item))
        else FeedReadingStore.setRead(upstreamContext, feedItemKey(item), false)
        publishReading(FeedReadingStore.load(upstreamContext))
    }
    suspend fun readProgress(item: ParsedFeedItem): Int {
        check(!stopped) { "订阅服务已停止" }
        return FeedReadingStore.readProgress(upstreamContext, feedItemKey(item))
    }
    suspend fun recordProgress(item: ParsedFeedItem, percent: Int): Unit = mutation.withLock {
        check(!stopped) { "订阅服务已停止" }
        FeedReadingStore.recordProgress(upstreamContext, feedItemKey(item), percent)
        publishReading(FeedReadingStore.load(upstreamContext))
    }
    suspend fun loadFullArticle(item: ParsedFeedItem): String {
        val key = feedItemKey(item)
        FeedReadingStore.load(upstreamContext).fullBodies[key]?.let { return it }
        val body = fetchArticleHtml(item.link).getOrThrow()
        mutation.withLock {
            check(!stopped) { "订阅服务已停止" }
            FeedReadingStore.saveFullBody(upstreamContext, key, body)
            publishReading(FeedReadingStore.load(upstreamContext))
        }
        return body
    }
    /** Original article fetch without premature persistence; the original UI keeps its length comparison. */
    suspend fun fetchArticleBody(item: ParsedFeedItem): Result<String> {
        check(!stopped) { "订阅服务已停止" }
        return fetchArticleHtml(item.link)
    }
    suspend fun saveFullBody(item: ParsedFeedItem, html: String): Unit = mutation.withLock {
        check(!stopped) { "订阅服务已停止" }
        FeedReadingStore.saveFullBody(upstreamContext, feedItemKey(item), html)
        publishReading(FeedReadingStore.load(upstreamContext))
    }
    /** Local RSS notes reuse the original codec/merge policy and the same global Store backing. */
    val articleNotes get() = ArticleNoteStore.observe(upstreamContext)
    suspend fun saveArticleNote(note: SavedArticleNote): Unit = mutation.withLock {
        withContext(Dispatchers.IO) {
            check(!stopped) { "订阅服务已停止" }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ArticleNoteStore.save(upstreamContext, note)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
        }
    }
    suspend fun removeArticleNote(link: String): Boolean = mutation.withLock {
        withContext(Dispatchers.IO) {
            check(!stopped) { "订阅服务已停止" }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ArticleNoteStore.remove(upstreamContext, link).also { kotlinx.coroutines.currentCoroutineContext().ensureActive() }
        }
    }

    private fun publishState(value: DesktopSubscriptionState, current: () -> Boolean = { !stopped }) =
        DesktopSubscriptionWriteAdmission.commitOrOriginal { if (current()) _state.value = value }
    private fun publishReading(reading: FeedReadingSnapshot) =
        DesktopSubscriptionWriteAdmission.commitOrOriginal { if (!stopped) _state.value = _state.value.copy(reading = reading) }
    private fun publishProgress(value: DesktopSubscriptionState, current: () -> Boolean) =
        DesktopSubscriptionWriteAdmission.commitOrOriginal {
            if (current()) _state.value = value.copy(reading = value.reading.copy(
                readKeys = _state.value.reading.readKeys, fullBodies = _state.value.reading.fullBodies,
                readTimestamps = _state.value.reading.readTimestamps, readProgress = _state.value.reading.readProgress))
        }
    private fun publishCleanupState(current: () -> Boolean = { true }) =
        DesktopSubscriptionWriteAdmission.cleanupOrOriginal { if (current()) _state.value = _state.value.copy(loading = false) }

    private suspend fun <T> mutate(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        check(!stopped) { "订阅服务已停止" }
        val token = generation.incrementAndGet()
        try { mutation.withLock { check(!stopped) { "订阅服务已停止" }; block().also { loadCached() } } }
        catch (error: Throwable) { publishCleanupState { token == generation.get() }; throw error }
    }
    internal suspend fun shutdownForRestore() {
        stopped = true
        generation.incrementAndGet()
        mutation.withLock { publishCleanupState() }
    }
}

/** UTF-8 text for a path the subscription save dialog already chose. */
internal fun writeSubscriptionOpml(path: Path, text: String) {
    Files.writeString(path, text, Charsets.UTF_8)
}

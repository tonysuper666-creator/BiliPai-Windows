package com.bilipai.desktop.plugins

import com.android.purebilibili.core.plugin.feed.*
import com.android.purebilibili.feature.plugin.resolveImportPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class DesktopSubscriptionState(val sources: List<SavedSubscriptionFeed> = emptyList(),
    val reading: FeedReadingSnapshot = FeedReadingSnapshot(), val errors: List<String> = emptyList(),
    val loading: Boolean = false)

data class DesktopSubscriptionExtraSources(val revision: Long = 0, val sources: List<FeedSource> = emptyList())

/** Windows orchestration around the original feed parser, conditional HTTP and reading store. */
class DesktopSubscriptionRepository(private val context: DesktopPluginContext,
    private val extraSources: suspend () -> DesktopSubscriptionExtraSources = { DesktopSubscriptionExtraSources() },
    private val extraSourceRevision: () -> Long = { 0 },
    private val enabled: () -> Boolean) {
    private val mutation = Mutex()
    private val generation = AtomicLong()
    @Volatile private var stopped = false
    private val _state = MutableStateFlow(DesktopSubscriptionState())
    val state: StateFlow<DesktopSubscriptionState> = _state.asStateFlow()

    suspend fun loadCached() = withContext(Dispatchers.IO) {
        check(!stopped) { "订阅服务已停止" }
        val token = generation.incrementAndGet()
        val sources = SubscriptionFeedStore.list(context)
        val builtinEnabled = enabled()
        val extra = extraSources()
        val reading = FeedReadingStore.load(context)
        val enabledIds = ((if (builtinEnabled) sources.filter { it.enabled }.map { "builtin:${it.id}" } else emptyList()) +
            extra.sources.filter { isHttpFeedUrl(it.url) }.map { it.id }).toSet()
        if (!stopped && token == generation.get() && builtinEnabled == enabled() && extra.revision == extraSourceRevision())
            _state.value = DesktopSubscriptionState(sources, reading.copy(items = mergeCachedFeedItems(reading.items, emptyList(), enabledIds)))
    }

    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        check(!stopped) { "订阅服务已停止" }
        val token = generation.incrementAndGet()
        val feeds = SubscriptionFeedStore.list(context)
        val builtinEnabled = enabled()
        val extra = extraSources()
        val builtinSources = if (builtinEnabled) feeds.filter { it.enabled && isHttpFeedUrl(it.url) }
            .map { FeedSource("builtin:${it.id}", it.title, it.url) } else emptyList()
        val sources = (builtinSources + extra.sources.filter { isHttpFeedUrl(it.url) }).distinctBy { it.url }
        val ids = sources.map { it.id }.toSet()
        val cache = FeedReadingStore.load(context)
        fun current() = !stopped && token == generation.get() && enabled() == builtinEnabled &&
            extra.revision == extraSourceRevision()
        if (sources.isEmpty()) {
            if (current()) _state.value = DesktopSubscriptionState(feeds,
                cache.copy(items = emptyList()), if (builtinEnabled) emptyList() else listOf("请启用订阅插件或已授权的 JS 订阅模块"))
            return@withContext
        }
        _state.value = DesktopSubscriptionState(feeds, cache.copy(items = mergeCachedFeedItems(cache.items, emptyList(), ids)), loading = true)
        try {
            val result = loadFeedSources(sources, FeedConditionalStore.load(context)) { update ->
                if (current()) _state.value = DesktopSubscriptionState(feeds,
                    cache.copy(items = mergeCachedFeedItems(cache.items, update.items, ids)), update.errors, loading = true)
            }
            if (!current()) return@withContext
            val merged = mergeCachedFeedItems(cache.items, result.items, ids)
            mutation.withLock {
                if (!current()) return@withLock
                FeedReadingStore.saveItems(context, merged)
                if (result.validators.isNotEmpty()) FeedConditionalStore.update(context, result.validators)
                _state.value = DesktopSubscriptionState(SubscriptionFeedStore.list(context), FeedReadingStore.load(context), result.errors)
            }
        } finally {
            if (token == generation.get()) _state.value = _state.value.copy(loading = false)
        }
    }

    suspend fun add(title: String, url: String): SavedSubscriptionFeed = mutate {
        val resolvedTitle = resolveSubscriptionTitle(url, title).getOrThrow()
        SubscriptionFeedStore.add(context, resolvedTitle, url).getOrThrow()
    }
    suspend fun importOpml(text: String): Int = mutate {
        val parsed = parseSubscriptionImport(resolveImportPayload(text))
        require(parsed.isNotEmpty()) { "没有有效的 RSS、Atom 或 OPML 订阅地址" }
        val resolved = resolveImportedSubscriptionTitles(parsed)
        require(resolved.isNotEmpty()) { "没有可识别的 RSS 或 Atom 地址" }
        SubscriptionFeedStore.addAll(context, resolved)
    }
    suspend fun exportOpml(): String = withContext(Dispatchers.IO) { buildSubscriptionOpml(SubscriptionFeedStore.list(context)) }
    suspend fun setEnabled(id: String, enabled: Boolean) = mutate { SubscriptionFeedStore.setEnabled(context, id, enabled) }
    suspend fun remove(id: String): Unit = mutate {
        val source = SubscriptionFeedStore.list(context).firstOrNull { it.id == id } ?: return@mutate
        SubscriptionFeedStore.remove(context, id)
        FeedConditionalStore.clear(context, setOf(source.url))
    }
    suspend fun setRead(item: ParsedFeedItem, read: Boolean): Unit = mutation.withLock {
        check(!stopped) { "订阅服务已停止" }
        FeedReadingStore.setRead(context, feedItemKey(item), read)
        _state.value = _state.value.copy(reading = FeedReadingStore.load(context))
    }
    suspend fun loadFullArticle(item: ParsedFeedItem): String {
        val key = feedItemKey(item)
        FeedReadingStore.load(context).fullBodies[key]?.let { return it }
        val body = fetchArticleHtml(item.link).getOrThrow()
        mutation.withLock {
            check(!stopped) { "订阅服务已停止" }
            FeedReadingStore.saveFullBody(context, key, body)
            _state.value = _state.value.copy(reading = FeedReadingStore.load(context))
        }
        return body
    }
    private suspend fun <T> mutate(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        check(!stopped) { "订阅服务已停止" }
        generation.incrementAndGet()
        try { mutation.withLock { check(!stopped) { "订阅服务已停止" }; block().also { loadCached() } } }
        catch (error: Throwable) { _state.value = _state.value.copy(loading = false); throw error }
    }
    internal suspend fun shutdownForRestore() {
        stopped = true
        generation.incrementAndGet()
        mutation.withLock { _state.value = _state.value.copy(loading = false) }
    }
}

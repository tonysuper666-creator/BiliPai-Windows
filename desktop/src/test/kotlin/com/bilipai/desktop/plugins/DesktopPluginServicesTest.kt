package com.bilipai.desktop.plugins

import com.android.purebilibili.core.plugin.feed.*
import com.android.purebilibili.core.store.TodayWatchProfileStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class DesktopPluginServicesTest {
    @Test fun `failed atomic feed writes preserve the committed document`() {
        val file = Files.createTempDirectory("plugin-atomic-").resolve("reading.json")
        Files.writeString(file, "old")
        val atomic = DesktopPluginAtomicFile(file.toFile())
        val failed = atomic.startWrite()
        failed.write("partial".toByteArray())
        atomic.failWrite(failed)
        assertEquals("old", atomic.openRead().bufferedReader().use { it.readText() })
        val committed = atomic.startWrite()
        committed.write("完整 🎬".toByteArray(Charsets.UTF_8))
        atomic.finishWrite(committed)
        assertEquals("完整 🎬", Files.readString(file))
        Files.list(file.parent).use { assertEquals(listOf(file), it.toList()) }
    }

    @Test fun `old preferences cannot overwrite files after restore quiescence`() {
        val root = Files.createTempDirectory("plugin-frozen-")
        val old = DesktopPluginStore(root)
        val prefs = DesktopPluginContext(old).getSharedPreferences("plugin_prefs", 0)
        prefs.edit().putString("value", "before").apply()
        old.freezeWrites()
        val fresh = DesktopPluginContext(DesktopPluginStore(root)).getSharedPreferences("plugin_prefs", 0)
        fresh.edit().putString("value", "restored").apply()
        assertFailsWith<IllegalStateException> { prefs.edit().putString("value", "stale").apply() }
        assertEquals("restored", DesktopPluginContext(DesktopPluginStore(root)).getSharedPreferences("plugin_prefs", 0).getString("value", null))
    }

    @Test fun `original creator personalization stays isolated between account contexts`() {
        val root = Files.createTempDirectory("plugin-creators-")
        val first = DesktopPluginContext(DesktopPluginStore(root.resolve("100")))
        val second = DesktopPluginContext(DesktopPluginStore(root.resolve("200")))
        TodayWatchProfileStore.recordWatchProgress(first, 11, "作者甲", 90, 1_000)
        assertEquals("作者甲", TodayWatchProfileStore.getCreatorSignals(first, 1_000).single().name)
        assertTrue(TodayWatchProfileStore.getCreatorSignals(second, 1_000).isEmpty())
        TodayWatchProfileStore.recordWatchProgress(second, 12, "作者乙", 120, 1_000)
        TodayWatchProfileStore.clear(first)
        assertEquals(12L, TodayWatchProfileStore.getCreatorSignals(second, 1_000).single().mid)
        assertTrue(TodayWatchProfileStore.getCreatorSignals(first, 1_000).isEmpty())
    }

    @Test fun `subscription cache retains unchanged feed entries and original read state`(): Unit = runBlocking {
        val root = Files.createTempDirectory("plugin-feed-")
        val context = DesktopPluginContext(DesktopPluginStore(root))
        val source = SubscriptionFeedStore.add(context, "测试订阅", "https://example.invalid/feed.xml").getOrThrow()
        val item = ParsedFeedItem("article", "builtin:${source.id}", source.title, "标题", "https://example.invalid/article",
            "作者", 123L, "摘要", "<p>全文</p>", null)
        FeedReadingStore.saveItems(context, listOf(item))
        FeedConditionalStore.update(context, mapOf(source.url to FeedConditionalValidators(etag = "revision-1")))
        val repository = DesktopSubscriptionRepository(context) { true }
        repository.loadCached()
        assertEquals(listOf(item), repository.state.value.reading.items)
        assertEquals(listOf(item), mergeCachedFeedItems(listOf(item), emptyList(), setOf(item.sourceId)))
        repository.setRead(item, true)
        assertTrue(feedItemKey(item) in repository.state.value.reading.readKeys)
        val reopened = DesktopSubscriptionRepository(DesktopPluginContext(DesktopPluginStore(root))) { true }
        reopened.loadCached()
        assertEquals(repository.state.value.reading, reopened.state.value.reading)
        assertEquals("revision-1", FeedConditionalStore.load(context)[source.url]?.etag)
        assertTrue(reopened.exportOpml().contains(source.url))
        repository.shutdownForRestore()
        assertFailsWith<IllegalStateException> { repository.remove(source.id) }
        assertEquals(1, SubscriptionFeedStore.list(context).size)
    }

    @Test fun `real public feed transport retains articles on 304 and never sends account cookies`(): Unit = runBlocking {
        val headers = CopyOnWriteArrayList<Pair<String?, String?>>()
        val xml = """<?xml version="1.0" encoding="utf-8"?><rss version="2.0"><channel><title>Fixture</title><link>https://example.invalid/</link><item><guid>one</guid><title>真实缓存文章</title><link>https://example.invalid/one</link><description>&lt;p&gt;正文&lt;/p&gt;</description></item></channel></rss>"""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/feed") { exchange ->
            headers.add(exchange.requestHeaders.getFirst("If-None-Match") to exchange.requestHeaders.getFirst("Cookie"))
            try {
                exchange.responseHeaders.add("ETag", "fixture-v1")
                if (exchange.requestHeaders.getFirst("If-None-Match") == "fixture-v1") exchange.sendResponseHeaders(304, -1)
                else {
                    val bytes = xml.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/rss+xml; charset=utf-8")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            } finally { exchange.close() }
        }
        server.start()
        try {
            val context = DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("plugin-feed-http-")))
            val repository = DesktopSubscriptionRepository(context) { true }
            val source = repository.add("Fixture", "http://127.0.0.1:${server.address.port}/feed")
            repository.refresh()
            val first = repository.state.value.reading.items.single()
            assertEquals("真实缓存文章", first.title)
            repository.setRead(first, true)
            repository.refresh()
            assertEquals(listOf(first), repository.state.value.reading.items)
            assertTrue(feedItemKey(first) in repository.state.value.reading.readKeys)
            assertEquals("fixture-v1", headers.last().first)
            assertTrue(headers.all { it.second == null })
            repository.setEnabled(source.id, false)
            repository.refresh()
            assertTrue(repository.state.value.reading.items.isEmpty())
            assertEquals(2, headers.size)
        } finally { server.stop(0) }
    }
}

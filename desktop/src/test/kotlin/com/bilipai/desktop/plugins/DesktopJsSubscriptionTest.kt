package com.bilipai.desktop.plugins

import com.android.purebilibili.core.plugin.feed.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class DesktopJsSubscriptionTest {
    private fun context() = DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("js-feed-owner-")))
    private val xml = """<rss version="2.0"><channel><title>JS fixture</title><item><guid>js-one</guid><title>JS 阅读文章</title><link>https://example.invalid/js-one</link><description>正文</description></item></channel></rss>"""

    @Test fun `approved JS feed uses the original reader when the builtin plugin is disabled`(): Unit = runBlocking {
        val context = context()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val cookies = mutableListOf<String?>()
        server.createContext("/feed") { exchange ->
            cookies += exchange.requestHeaders.getFirst("Cookie")
            val bytes = xml.toByteArray()
            exchange.responseHeaders.add("ETag", "js-fixture-v1")
            exchange.responseHeaders.add("Content-Type", "application/rss+xml")
            if (exchange.requestHeaders.getFirst("If-None-Match") == "js-fixture-v1") exchange.sendResponseHeaders(304, -1)
            else { exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) } }
            exchange.close()
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/feed"
            val repository = DesktopSubscriptionRepository(context,
                extraSources = { DesktopSubscriptionExtraSources(7, listOf(FeedSource("js:fixture:news", "JS news", url))) },
                extraSourceRevision = { 7 }) { false }
            repository.refresh()
            val item = repository.state.value.reading.items.single()
            assertEquals("js:fixture:news", item.sourceId)
            assertEquals("JS 阅读文章", item.title)
            repository.setRead(item, true)
            repository.refresh()
            assertEquals(listOf(item), repository.state.value.reading.items)
            assertTrue(feedItemKey(item) in repository.state.value.reading.readKeys)
            assertEquals("js-fixture-v1", FeedConditionalStore.load(context)[url]?.etag)
            assertEquals(listOf<String?>(null, null), cookies)
            assertTrue(repository.state.value.errors.isEmpty())
        } finally { server.stop(0) }
    }

    @Test fun `revoking JS approval during a real feed request prevents article and validator writes`(): Unit = runBlocking {
        val context = context()
        val revision = AtomicLong(1)
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/feed") { exchange ->
            requested.countDown()
            try {
                check(release.await(5, TimeUnit.SECONDS))
                val bytes = xml.toByteArray()
                exchange.responseHeaders.add("ETag", "must-not-commit")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/feed"
            val repository = DesktopSubscriptionRepository(context,
                extraSources = { DesktopSubscriptionExtraSources(revision.get(), listOf(FeedSource("js:fixture:news", "JS", url))) },
                extraSourceRevision = revision::get) { false }
            val pending = async(Dispatchers.IO) { repository.refresh() }
            assertTrue(withContext(Dispatchers.IO) { requested.await(5, TimeUnit.SECONDS) })
            revision.incrementAndGet(); release.countDown()
            withTimeout(8_000) { pending.await() }
            assertTrue(FeedReadingStore.load(context).items.isEmpty())
            assertTrue(FeedConditionalStore.load(context).isEmpty())
            assertFalse(repository.state.value.loading)
        } finally { release.countDown(); server.stop(0) }
    }

    @Test fun `cached JS and builtin sources retain independent enable rules`(): Unit = runBlocking {
        val context = context()
        val builtin = SubscriptionFeedStore.add(context, "Builtin", "https://example.invalid/builtin").getOrThrow()
        fun item(id: String) = ParsedFeedItem(id, id, id, id, "https://example.invalid/$id", "", 1, "", "", null)
        val builtinItem = item("builtin:${builtin.id}")
        val jsItem = item("js:fixture:news")
        FeedReadingStore.saveItems(context, listOf(builtinItem, jsItem))
        var builtinEnabled = false
        var jsEnabled = true
        val revision = AtomicLong(1)
        val repository = DesktopSubscriptionRepository(context,
            extraSources = { DesktopSubscriptionExtraSources(revision.get(), if (jsEnabled) listOf(FeedSource(jsItem.sourceId, "JS", "https://example.invalid/js")) else emptyList()) },
            extraSourceRevision = revision::get) { builtinEnabled }
        repository.loadCached(); assertEquals(listOf(jsItem), repository.state.value.reading.items)
        builtinEnabled = true
        repository.loadCached(); assertEquals(setOf(builtinItem, jsItem), repository.state.value.reading.items.toSet())
        jsEnabled = false; revision.incrementAndGet()
        repository.loadCached(); assertEquals(listOf(builtinItem), repository.state.value.reading.items)
    }
}

package com.bilipai.desktop.plugins

import com.android.purebilibili.core.plugin.feed.*
import com.bilipai.desktop.ui.DesktopHomeEmbeddedLifetime
import com.bilipai.desktop.ui.DesktopSubscriptionTitleRefreshWorker
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Real original parser/resolver/HTTP/AtomicFile against private loopback fixtures. */
class DesktopSubscriptionTitleRefreshTest {
    private class Server(handler: (HttpExchange, Int) -> Unit) : AutoCloseable {
        val requests = AtomicInteger()
        val credentials = CopyOnWriteArrayList<Pair<String?, String?>>()
        private val executor = Executors.newFixedThreadPool(6)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val origin get() = "http://127.0.0.1:${server.address.port}"
        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                credentials.add(exchange.requestHeaders.getFirst("Cookie") to exchange.requestHeaders.getFirst("Authorization"))
                try { handler(exchange, requests.incrementAndGet()) }
                finally { exchange.close() }
            }
            server.start()
        }
        override fun close() {
            server.stop(0)
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
    private fun xml(title: String) = """<rss version="2.0"><channel><title>$title</title>
        <link>https://example.invalid/</link><item><guid>one</guid><title>Article</title>
        <link>https://example.invalid/article</link><description>Body</description></item></channel></rss>"""
    private fun reply(exchange: HttpExchange, body: String, status: Int = 200) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/rss+xml; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    private fun context() = DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("bilipai-title-regression-")))
    private fun opml(vararg urls: String) = "<opml><body><outline text=\"Research\">" +
        urls.joinToString("") { "<outline type=\"rss\" xmlUrl=\"$it\"/>" } + "</outline></body></opml>"
    private suspend fun await(latch: CountDownLatch) = assertTrue(withContext(Dispatchers.IO) {
        latch.await(5, TimeUnit.SECONDS)
    }, "Fixture barrier timed out")
    private suspend fun eventually(predicate: () -> Boolean) {
        withTimeout(5000) { while (!predicate()) delay(5) }
    }

    @Test fun importPersistsAllSourcesBeforeAnyTitleHTTPAndColdStoreKeepsGroups(): Unit = runBlocking {
        Server { exchange, _ -> reply(exchange, xml("Resolved")) }.use { server ->
            val context = context(); val repository = DesktopSubscriptionRepository(context) { true }
            val urls = listOf(server.origin + "/first", server.origin + "/second")
            assertEquals(2, withTimeout(2000) { repository.importOpml(opml(*urls.toTypedArray())) })
            assertEquals(0, server.requests.get())
            val before = SubscriptionFeedStore.list(context)
            assertEquals(urls, before.map { it.url })
            assertTrue(before.all { it.title == it.url && it.group == "Research" })
            assertEquals(before, repository.state.value.sources)
            repository.refreshMissingTitles()
            val cold = DesktopPluginContext(DesktopPluginStore(context.filesDir.toPath()))
            val after = SubscriptionFeedStore.list(cold)
            assertEquals(before.map { it.id }, after.map { it.id })
            assertTrue(after.all { it.title == "Resolved" && it.group == "Research" && it.enabled })
            assertTrue(server.credentials.all { it.first == null && it.second == null })
        }
    }

    @Test fun failedTitleFetchKeepsPlaceholderAndTheSameRealResolverRetriesLater(): Unit = runBlocking {
        Server { exchange, count ->
            if (count == 1) reply(exchange, "retry later", 503) else reply(exchange, xml("Recovered"))
        }.use { server ->
            val context = context(); val repository = DesktopSubscriptionRepository(context) { true }
            val url = server.origin + "/retry"
            repository.importOpml(opml(url))
            val before = SubscriptionFeedStore.list(context).single()
            repository.refreshMissingTitles()
            assertEquals(before, SubscriptionFeedStore.list(context).single())
            repository.refreshMissingTitles()
            assertEquals(before.copy(title = "Recovered"), SubscriptionFeedStore.list(context).single())
            assertEquals(2, server.requests.get())
            assertEquals("Recovered", repository.state.value.activeSources.single().title)
        }
    }

    @Test fun manualRenameAndDeleteProceedWhileTitleHTTPWaitsAndLateResultsCannotUndoThem(): Unit = runBlocking {
        val entered = CountDownLatch(2); val release = CountDownLatch(1)
        val replies = CountDownLatch(2); val timedOut = AtomicBoolean()
        Server { exchange, _ ->
            entered.countDown()
            val released = release.await(15, TimeUnit.SECONDS)
            if (!released) timedOut.set(true)
            check(released)
            reply(exchange, xml("Late title")); replies.countDown()
        }.use { server ->
            val context = context(); val repository = DesktopSubscriptionRepository(context) { true }
            val first = server.origin + "/rename"; val second = server.origin + "/delete"
            repository.importOpml(opml(first, second))
            val feeds = SubscriptionFeedStore.list(context)
            val task = launch(Dispatchers.Default) { repository.refreshMissingTitles() }
            try {
                await(entered)
                withTimeout(2000) {
                    repository.add("Manual title", first)
                    repository.remove(feeds.single { it.url == second }.id)
                }
                val file = context.filesDir.resolve("plugin/subscription_feeds.json")
                val bytes = file.readBytes(); val revision = SubscriptionFeedStore.revision.value
                release.countDown(); withTimeout(5000) { task.join() }
                await(replies); assertFalse(timedOut.get())
                assertFalse(task.isCancelled)
                assertContentEquals(bytes, file.readBytes())
                assertEquals(revision, SubscriptionFeedStore.revision.value)
                assertEquals(listOf(feeds.single { it.url == first }.copy(title = "Manual title")), SubscriptionFeedStore.list(context))
            } finally { release.countDown(); withContext(NonCancellable) { withTimeout(5000) { task.cancelAndJoin() } } }
        }
    }

    @Test fun retiringTheActualHomeLifetimeCancelsBlockedHTTPDrainsAndPreservesTheFile(): Unit = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val waiting = AtomicInteger(); val timedOut = AtomicBoolean()
        Server { exchange, _ ->
            waiting.incrementAndGet(); entered.countDown()
            try {
                val released = release.await(15, TimeUnit.SECONDS)
                if (!released) timedOut.set(true)
                check(released); reply(exchange, xml("Retired result"))
            } finally { waiting.decrementAndGet() }
        }.use { server ->
            val context = context(); val repository = DesktopSubscriptionRepository(context) { true }
            repository.importOpml(opml(server.origin + "/blocked"))
            val file = context.filesDir.resolve("plugin/subscription_feeds.json")
            val bytes = file.readBytes(); val revision = SubscriptionFeedStore.revision.value
            val lifetime = DesktopHomeEmbeddedLifetime(this, { true }, { action -> action(); true })
            val worker = DesktopSubscriptionTitleRefreshWorker(lifetime.scope, lifetime::owns, lifetime::commit,
                { repository.refreshMissingTitles() }, { throw AssertionError("Unexpected title failure", it) })
            try {
                worker.request()
                await(entered)
                // The HTTP handler remains held: cancellation must close the actual blocking Call.
                withTimeout(2500) { lifetime.closeAndJoin() }
                assertEquals(1, waiting.get()); assertFalse(timedOut.get())
                assertFalse(lifetime.owns())
                assertContentEquals(bytes, file.readBytes())
                assertEquals(revision, SubscriptionFeedStore.revision.value)
            } finally {
                release.countDown()
                worker.close()
                withContext(NonCancellable) { withTimeout(5000) { lifetime.closeAndJoin() } }
            }
        }
    }

    @Test fun metadataPublicationRetainsProgressPublishedWhileItWaitsForFinalRootAdmission(): Unit = runBlocking {
        val arrived = CountDownLatch(1); val admit = CountDownLatch(1)
        val published = CountDownLatch(1); val returnFromCommit = CountDownLatch(1)
        Server { exchange, _ -> reply(exchange, xml("Resolved")) }.use { server ->
            val context = context(); val repository = DesktopSubscriptionRepository(context) { true }
            repository.importOpml(opml(server.origin + "/feed"))
            val commits = AtomicInteger()
            val title = launch(Dispatchers.Default) {
                DesktopSubscriptionWriteAdmission.withOwned({ true }, { action ->
                    if (commits.incrementAndGet() == 2) {
                        arrived.countDown(); check(admit.await(5, TimeUnit.SECONDS))
                        action(); published.countDown(); check(returnFromCommit.await(5, TimeUnit.SECONDS))
                    } else action()
                    true
                }) { repository.refreshMissingTitles() }
            }
            var refresh: Job? = null
            try {
                await(arrived)
                val refreshTask = launch(Dispatchers.Default) { repository.refresh() }
                refresh = refreshTask
                eventually { repository.state.value.loading && repository.state.value.reading.items.isNotEmpty() }
                val progress = repository.state.value
                admit.countDown(); await(published)
                val actual = repository.state.value
                assertEquals(progress.reading, actual.reading)
                assertEquals(progress.errors, actual.errors)
                assertEquals(progress.loading, actual.loading)
                assertEquals("Resolved", actual.sources.single().title)
                assertEquals("Research", actual.activeSources.single().group)
                returnFromCommit.countDown()
                withTimeout(5000) { title.join(); refreshTask.join() }
                assertFalse(title.isCancelled); assertFalse(refreshTask.isCancelled)
            } finally {
                admit.countDown(); returnFromCommit.countDown()
                withContext(NonCancellable) { withTimeout(5000) { title.cancelAndJoin(); refresh?.cancelAndJoin() } }
            }
        }
    }

    @Test fun actualWorkerRetainsAnImportTriggerWhileThePreviousPendingSnapshotIsFetching(): Unit = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val replies = CountDownLatch(2); val failures = CopyOnWriteArrayList<Exception>()
        Server { exchange, _ ->
            if (exchange.requestURI.path == "/first") {
                entered.countDown(); check(release.await(15, TimeUnit.SECONDS))
            }
            reply(exchange, xml("Resolved ${exchange.requestURI.path}")); replies.countDown()
        }.use { server ->
            val context = context(); val repository = DesktopSubscriptionRepository(context) { true }
            val lifetime = DesktopHomeEmbeddedLifetime(this, { true }, { action -> action(); true })
            val worker = DesktopSubscriptionTitleRefreshWorker(lifetime.scope, lifetime::owns, lifetime::commit,
                { repository.refreshMissingTitles() }, { failures.add(it) })
            try {
                // The completed action's Operation must not be retained by the worker.
                withContext(Dispatchers.Default) {
                    DesktopSubscriptionWriteAdmission.withOwned(lifetime::owns, lifetime::commit) {
                        repository.importOpml(opml(server.origin + "/first")); worker.request()
                    }
                }
                await(entered)
                assertEquals(1, server.requests.get())
                withTimeout(2000) { repository.importOpml(opml(server.origin + "/second")) }
                repeat(20) { worker.request() } // Coalesce to one pending batch, never concurrent workers.
                release.countDown(); await(replies)
                eventually { repository.state.value.sources.size == 2 && repository.state.value.sources.none(::needsTitleResolution) }
                assertTrue(failures.isEmpty())
                assertEquals(2, server.requests.get())
                assertTrue(SubscriptionFeedStore.list(context).none(::needsTitleResolution))
            } finally {
                release.countDown(); worker.close()
                withContext(NonCancellable) { withTimeout(5000) { lifetime.closeAndJoin() } }
            }
        }
    }

    @Test fun actualWorkerSurvivesARejectedCorruptBatchAndCanProcessTheNextExplicitTrigger(): Unit = runBlocking {
        val failed = CountDownLatch(1); val failures = CopyOnWriteArrayList<Exception>()
        Server { exchange, _ -> reply(exchange, xml("Recovered source")) }.use { server ->
            val context = context(); val repository = DesktopSubscriptionRepository(context) { true }
            val file = context.filesDir.resolve("plugin/subscription_feeds.json")
            file.parentFile.mkdirs(); val corrupt = "{incomplete".toByteArray(); file.writeBytes(corrupt)
            val lifetime = DesktopHomeEmbeddedLifetime(this, { true }, { action -> action(); true })
            val worker = DesktopSubscriptionTitleRefreshWorker(lifetime.scope, lifetime::owns, lifetime::commit,
                { repository.refreshMissingTitles() }, { failures.add(it); failed.countDown() })
            try {
                worker.request(); await(failed)
                assertContentEquals(corrupt, file.readBytes())
                assertEquals(0, server.requests.get())
                assertTrue(failures.single() is kotlinx.serialization.SerializationException)
                // Explicit fixture repair, never performed by the application.
                file.writeText("[]")
                repository.importOpml(opml(server.origin + "/repaired"))
                worker.request()
                eventually { repository.state.value.sources.singleOrNull()?.title == "Recovered source" }
                assertEquals(1, server.requests.get())
                assertEquals(1, failures.size)
            } finally {
                worker.close()
                withContext(NonCancellable) { withTimeout(5000) { lifetime.closeAndJoin() } }
            }
        }
    }
}

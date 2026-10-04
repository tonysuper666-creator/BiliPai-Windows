package com.bilipai.desktop.plugins

import com.android.purebilibili.core.plugin.feed.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class DesktopSubscriptionIdentityTest {
    private fun context() = DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("rss-id-fixture-")))
    private val firstUrl = "https://example.invalid/Aa"
    private val secondUrl = "https://example.invalid/BB"

    private fun seed(context: DesktopPluginContext, feeds: List<SavedSubscriptionFeed>) {
        val file = File(context.filesDir, "plugin/subscription_feeds.json")
        file.parentFile.mkdirs()
        file.writeText(Json.encodeToString(feeds))
    }

    private fun article(feed: SavedSubscriptionFeed) = ParsedFeedItem(
        id = "article-one", sourceId = "builtin:${feed.id}", sourceTitle = feed.title,
        title = "Persisted article", link = "https://example.invalid/article", author = "",
        publishedEpochSec = 1L, summary = "Summary", htmlContent = "Body", imageUrl = null,
    )

    @Test fun `distinct URLs with the same hash receive independent persisted identities`() {
        assertEquals(firstUrl.hashCode(), secondUrl.hashCode())
        val context = context()
        val first = SubscriptionFeedStore.add(context, "First", firstUrl).getOrThrow()
        val second = SubscriptionFeedStore.add(context, "Second", secondUrl).getOrThrow()
        val base = firstUrl.hashCode().toUInt().toString(16)
        assertEquals(base, first.id)
        assertEquals("$base-1", second.id)
        assertEquals(listOf(first, second), SubscriptionFeedStore.list(context))
        SubscriptionFeedStore.setEnabled(context, second.id, false)
        assertTrue(SubscriptionFeedStore.list(context).single { it.id == first.id }.enabled)
        assertFalse(SubscriptionFeedStore.list(context).single { it.id == second.id }.enabled)
    }

    @Test fun `batch import reserves each new identity and skips existing suffixes and duplicate URLs`() {
        val context = context()
        val urls = listOf("AaAa", "AaBB", "BBAa", "BBBB").map { "https://example.invalid/$it" }
        assertEquals(1, urls.map(String::hashCode).distinct().size)
        val base = urls.first().hashCode().toUInt().toString(16)
        val existing = SavedSubscriptionFeed(base, "Existing", urls.first(), false)
        val reserved = SavedSubscriptionFeed("$base-1", "Legacy suffix", "https://example.invalid/reserved")
        seed(context, listOf(existing, reserved))
        val imported = listOf(ImportedSubscription("Ignored existing", urls.first()),
            ImportedSubscription("Second", " ${urls[1]} "), ImportedSubscription("Duplicate", urls[1]),
            ImportedSubscription("Invalid", "file:///private.xml"),
            ImportedSubscription("Third", urls[2]), ImportedSubscription("Fourth", urls[3]))
        assertEquals(3, SubscriptionFeedStore.addAll(context, imported))
        val feeds = SubscriptionFeedStore.list(context)
        assertEquals(listOf(existing, reserved), feeds.take(2))
        assertEquals(listOf("$base-2", "$base-3", "$base-4"), feeds.drop(2).map { it.id })
        assertEquals(urls.drop(1), feeds.drop(2).map { it.url })
        assertEquals(feeds.size, feeds.map { it.id }.distinct().size)
        assertEquals(0, SubscriptionFeedStore.addAll(context, imported))
        assertEquals(feeds, SubscriptionFeedStore.list(context))
    }

    @Test fun `adding an existing URL preserves its legacy identity and reading data`(): Unit = runBlocking {
        val context = context()
        val existing = SavedSubscriptionFeed("legacy-not-a-hash", "Original", firstUrl)
        val neighbour = SavedSubscriptionFeed("untouched-id", "Neighbour", secondUrl, false)
        seed(context, listOf(existing, neighbour))
        val item = article(existing)
        FeedReadingStore.saveItems(context, listOf(item))
        FeedReadingStore.setRead(context, feedItemKey(item), true)
        FeedReadingStore.saveFullBody(context, feedItemKey(item), "Persisted full body")
        val reading = FeedReadingStore.load(context)
        val replaced = SubscriptionFeedStore.add(context, " Renamed ", " $firstUrl ").getOrThrow()
        assertEquals(existing.id, replaced.id)
        assertEquals("Renamed", replaced.title)
        assertEquals(firstUrl, replaced.url)
        assertEquals(listOf(neighbour, replaced), SubscriptionFeedStore.list(context))
        assertEquals(reading, FeedReadingStore.load(context))
        val repository = DesktopSubscriptionRepository(context) { true }
        repository.loadCached()
        assertEquals(listOf(item), repository.state.value.reading.items)
        assertTrue(feedItemKey(item) in repository.state.value.reading.readKeys)
    }

    @Test fun `removing one collision through the mounted repository keeps the other feed and reading state`(): Unit = runBlocking {
        assertEquals(firstUrl.hashCode(), secondUrl.hashCode())
        val context = context()
        val first = SubscriptionFeedStore.add(context, "First", firstUrl).getOrThrow()
        val second = SubscriptionFeedStore.add(context, "Second", secondUrl).getOrThrow()
        val firstArticle = article(first)
        val secondArticle = article(second)
        assertNotEquals(feedItemKey(firstArticle), feedItemKey(secondArticle))
        FeedReadingStore.saveItems(context, listOf(firstArticle, secondArticle))
        FeedReadingStore.setRead(context, feedItemKey(secondArticle), true)
        FeedReadingStore.saveFullBody(context, feedItemKey(secondArticle), "Second full body")
        val readingBefore = FeedReadingStore.load(context)
        val repository = DesktopSubscriptionRepository(context) { true }
        repository.remove(first.id)
        assertEquals(listOf(second), SubscriptionFeedStore.list(context))
        assertEquals(listOf(secondArticle), repository.state.value.reading.items)
        assertEquals(readingBefore, FeedReadingStore.load(context))
        assertTrue(feedItemKey(secondArticle) in repository.state.value.reading.readKeys)
        assertEquals("Second full body", repository.state.value.reading.fullBodies[feedItemKey(secondArticle)])
    }

    @Test fun `new allocations do not renumber preexisting duplicate identities`() {
        val context = context()
        val urls = listOf("AaAa", "AaBB", "BBAa").map { "https://example.invalid/$it" }
        assertEquals(1, urls.map(String::hashCode).distinct().size)
        val base = urls.first().hashCode().toUInt().toString(16)
        val legacy = listOf(SavedSubscriptionFeed(base, "Old first", urls[0]),
            SavedSubscriptionFeed(base, "Old second", urls[1], false),
            SavedSubscriptionFeed("$base-1", "Reserved", "https://example.invalid/reserved"))
        seed(context, legacy)
        val added = SubscriptionFeedStore.add(context, "New", urls[2]).getOrThrow()
        assertEquals(legacy, SubscriptionFeedStore.list(context).take(legacy.size))
        assertEquals("$base-2", added.id)
    }
}

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

    @Test fun `distinct URLs with the same hash receive independent persisted identities`(): Unit = runBlocking {
        assertEquals(firstUrl.hashCode(), secondUrl.hashCode())
        val context = context()
        val first = SubscriptionFeedStore.add(context.asAndroidContext(), "First", firstUrl).getOrThrow()
        val second = SubscriptionFeedStore.add(context.asAndroidContext(), "Second", secondUrl).getOrThrow()
        val base = firstUrl.hashCode().toUInt().toString(16)
        assertEquals(base, first.id)
        assertEquals("$base-1", second.id)
        assertEquals(listOf(first, second), SubscriptionFeedStore.list(context.asAndroidContext()))
        SubscriptionFeedStore.setEnabled(context.asAndroidContext(), second.id, false)
        assertTrue(SubscriptionFeedStore.list(context.asAndroidContext()).single { it.id == first.id }.enabled)
        assertFalse(SubscriptionFeedStore.list(context.asAndroidContext()).single { it.id == second.id }.enabled)
    }

    @Test fun `batch import reserves each new identity and skips existing suffixes and duplicate URLs`(): Unit = runBlocking {
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
        assertEquals(3, SubscriptionFeedStore.addAll(context.asAndroidContext(), imported))
        val feeds = SubscriptionFeedStore.list(context.asAndroidContext())
        assertEquals(listOf(existing, reserved), feeds.take(2))
        assertEquals(listOf("$base-2", "$base-3", "$base-4"), feeds.drop(2).map { it.id })
        assertEquals(urls.drop(1), feeds.drop(2).map { it.url })
        assertEquals(feeds.size, feeds.map { it.id }.distinct().size)
        assertEquals(0, SubscriptionFeedStore.addAll(context.asAndroidContext(), imported))
        assertEquals(feeds, SubscriptionFeedStore.list(context.asAndroidContext()))
    }

    @Test fun `adding an existing URL preserves its legacy identity and reading data`(): Unit = runBlocking {
        val context = context()
        val existing = SavedSubscriptionFeed("legacy-not-a-hash", "Original", firstUrl)
        val neighbour = SavedSubscriptionFeed("untouched-id", "Neighbour", secondUrl, false)
        seed(context, listOf(existing, neighbour))
        val item = article(existing)
        FeedReadingStore.saveItems(context.asAndroidContext(), listOf(item))
        FeedReadingStore.setRead(context.asAndroidContext(), feedItemKey(item), true)
        FeedReadingStore.saveFullBody(context.asAndroidContext(), feedItemKey(item), "Persisted full body")
        val reading = FeedReadingStore.load(context.asAndroidContext())
        val replaced = SubscriptionFeedStore.add(context.asAndroidContext(), " Renamed ", " $firstUrl ").getOrThrow()
        assertEquals(existing.id, replaced.id)
        assertEquals("Renamed", replaced.title)
        assertEquals(firstUrl, replaced.url)
        assertEquals(listOf(neighbour, replaced), SubscriptionFeedStore.list(context.asAndroidContext()))
        assertEquals(reading, FeedReadingStore.load(context.asAndroidContext()))
        val repository = DesktopSubscriptionRepository(context) { true }
        repository.loadCached()
        assertEquals(listOf(item), repository.state.value.reading.items)
        assertTrue(feedItemKey(item) in repository.state.value.reading.readKeys)
    }

    @Test fun `removing one collision through the mounted repository keeps the other feed and reading state`(): Unit = runBlocking {
        assertEquals(firstUrl.hashCode(), secondUrl.hashCode())
        val context = context()
        val first = SubscriptionFeedStore.add(context.asAndroidContext(), "First", firstUrl).getOrThrow()
        val second = SubscriptionFeedStore.add(context.asAndroidContext(), "Second", secondUrl).getOrThrow()
        val firstArticle = article(first)
        val secondArticle = article(second)
        assertNotEquals(feedItemKey(firstArticle), feedItemKey(secondArticle))
        FeedReadingStore.saveItems(context.asAndroidContext(), listOf(firstArticle, secondArticle))
        FeedReadingStore.setRead(context.asAndroidContext(), feedItemKey(secondArticle), true)
        FeedReadingStore.saveFullBody(context.asAndroidContext(), feedItemKey(secondArticle), "Second full body")
        val readingBefore = FeedReadingStore.load(context.asAndroidContext())
        val repository = DesktopSubscriptionRepository(context) { true }
        repository.remove(first.id)
        assertEquals(listOf(second), SubscriptionFeedStore.list(context.asAndroidContext()))
        assertEquals(listOf(secondArticle), repository.state.value.reading.items)
        assertEquals(readingBefore, FeedReadingStore.load(context.asAndroidContext()))
        assertTrue(feedItemKey(secondArticle) in repository.state.value.reading.readKeys)
        assertEquals("Second full body", repository.state.value.reading.fullBodies[feedItemKey(secondArticle)])
    }

    @Test fun `new allocations do not renumber preexisting duplicate identities`(): Unit = runBlocking {
        val context = context()
        val urls = listOf("AaAa", "AaBB", "BBAa").map { "https://example.invalid/$it" }
        assertEquals(1, urls.map(String::hashCode).distinct().size)
        val base = urls.first().hashCode().toUInt().toString(16)
        val legacy = listOf(SavedSubscriptionFeed(base, "Old first", urls[0]),
            SavedSubscriptionFeed(base, "Old second", urls[1], false),
            SavedSubscriptionFeed("$base-1", "Reserved", "https://example.invalid/reserved"))
        seed(context, legacy)
        val added = SubscriptionFeedStore.add(context.asAndroidContext(), "New", urls[2]).getOrThrow()
        assertEquals(legacy, SubscriptionFeedStore.list(context.asAndroidContext()).take(legacy.size))
        assertEquals("$base-2", added.id)
    }

    @Test fun `readding a disabled grouped URL preserves its cold stored record identity`(): Unit = runBlocking {
        val context = context()
        val previous = SavedSubscriptionFeed("legacy-custom-id", "Before", firstUrl, false, "Research")
        seed(context, listOf(previous))
        val renamed = SubscriptionFeedStore.add(context.asAndroidContext(), " After ", " $firstUrl ").getOrThrow()
        assertEquals(previous.copy(title = "After"), renamed)
        val cold = DesktopPluginContext(DesktopPluginStore(context.filesDir.toPath()))
        assertEquals(listOf(renamed), SubscriptionFeedStore.list(cold.asAndroidContext()))
        assertEquals(listOf(renamed), SubscriptionFeedStore.listBlocking(cold.asAndroidContext()))
    }

    @Test fun `legacy group default and nested OPML groups survive real store and export`(): Unit = runBlocking {
        val context = context()
        val file = File(context.filesDir, "plugin/subscription_feeds.json")
        file.parentFile.mkdirs()
        file.writeText("""[{"id":"legacy","title":"Legacy","url":"https://example.invalid/old"}]""")
        val legacy = SubscriptionFeedStore.list(context.asAndroidContext()).single()
        assertEquals("", legacy.group)
        assertTrue(legacy.enabled)
        val imported = parseSubscriptionImport("""<opml><body>
            <outline text="Outer"><outline text="A &amp; B">
            <outline type="rss" title="Grouped" xmlUrl="https://example.invalid/grouped"/>
            </outline></outline><outline title="Flat" xmlUrl="https://example.invalid/flat"/>
            </body></opml>""")
        assertEquals(2, SubscriptionFeedStore.addAll(context.asAndroidContext(), imported))
        val cold = DesktopPluginContext(DesktopPluginStore(context.filesDir.toPath()))
        val saved = SubscriptionFeedStore.list(cold.asAndroidContext())
        assertEquals(legacy, saved.first())
        assertEquals("A & B", saved.single { it.url.endsWith("/grouped") }.group)
        assertEquals("", saved.single { it.url.endsWith("/flat") }.group)
        val roundTrip = parseSubscriptionImport(buildSubscriptionOpml(saved))
        assertEquals(saved.associate { it.url to it.group }, roundTrip.associate { it.url to it.group })
    }

    @Test fun `background title commit checks the exact stored identity URL and placeholder`(): Unit = runBlocking {
        val context = context()
        val placeholder = SavedSubscriptionFeed("shared-id", firstUrl, firstUrl, false, "Kept")
        seed(context, listOf(placeholder))
        SubscriptionFeedStore.updateTitle(context.asAndroidContext(), placeholder.id, firstUrl, firstUrl, "Resolved")
        assertEquals(placeholder.copy(title = "Resolved"), SubscriptionFeedStore.list(context.asAndroidContext()).single())
        for (current in listOf(
            listOf(placeholder.copy(title = "Manually renamed")),
            listOf(placeholder.copy(url = secondUrl, title = secondUrl)),
            listOf(placeholder.copy(id = "different-id")),
            listOf(placeholder.copy(title = "")),
            emptyList<SavedSubscriptionFeed>(),
        )) {
            seed(context, current)
            val file = File(context.filesDir, "plugin/subscription_feeds.json")
            val bytes = file.readBytes()
            val revision = SubscriptionFeedStore.revision.value
            SubscriptionFeedStore.updateTitle(context.asAndroidContext(), placeholder.id, firstUrl, firstUrl, "Stale network result")
            assertContentEquals(bytes, file.readBytes())
            assertEquals(revision, SubscriptionFeedStore.revision.value)
            assertEquals(current, SubscriptionFeedStore.list(context.asAndroidContext()))
        }
    }

    @Test fun `legacy duplicated IDs retain distinct URL metadata through title refresh`(): Unit = runBlocking {
        val context = context()
        val legacy = listOf(
            SavedSubscriptionFeed("shared-legacy-id", "First", firstUrl, true, "A"),
            SavedSubscriptionFeed("shared-legacy-id", "Second", secondUrl, true, "B"),
        )
        seed(context, legacy)
        val repository = DesktopSubscriptionRepository(context) { true }
        repository.loadCached()
        val before = repository.state.value
        repository.refreshMissingTitles()
        assertEquals(before, repository.state.value)
        assertEquals(listOf(firstUrl, secondUrl), repository.state.value.activeSources.map { it.url })
        assertEquals(listOf("A", "B"), repository.state.value.activeSources.map { it.group })
        assertEquals(legacy, SubscriptionFeedStore.list(context.asAndroidContext()))
    }

    @Test fun `corrupt subscription bytes are preserved and cannot become a replacement empty list`(): Unit = runBlocking {
        val context = context()
        val file = File(context.filesDir, "plugin/subscription_feeds.json")
        file.parentFile.mkdirs()
        val corrupt = "{truncated-subscription-record".toByteArray()
        file.writeBytes(corrupt)
        val revision = SubscriptionFeedStore.revision.value
        assertFailsWith<kotlinx.serialization.SerializationException> { SubscriptionFeedStore.list(context.asAndroidContext()) }
        assertFailsWith<kotlinx.serialization.SerializationException> { SubscriptionFeedStore.listBlocking(context.asAndroidContext()) }
        assertFailsWith<kotlinx.serialization.SerializationException> { SubscriptionFeedStore.add(context.asAndroidContext(), "New", firstUrl) }
        assertFailsWith<kotlinx.serialization.SerializationException> { SubscriptionFeedStore.addAll(context.asAndroidContext(),
            listOf(ImportedSubscription("New", firstUrl, "Group"))) }
        assertContentEquals(corrupt, file.readBytes())
        assertEquals(revision, SubscriptionFeedStore.revision.value)
    }

    @Test fun `rejected Root final admission leaves store bytes revision and temporary files unchanged`(): Unit = runBlocking {
        val context = context()
        seed(context, listOf(SavedSubscriptionFeed("existing", "Before", secondUrl, false, "Existing")))
        val file = File(context.filesDir, "plugin/subscription_feeds.json")
        val before = file.readBytes()
        val revision = SubscriptionFeedStore.revision.value
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            DesktopSubscriptionWriteAdmission.withOwned({ true }, { false }) {
                SubscriptionFeedStore.add(context.asAndroidContext(), "Rejected", firstUrl).getOrThrow()
            }
        }
        assertContentEquals(before, file.readBytes())
        assertEquals(revision, SubscriptionFeedStore.revision.value)
        assertTrue(file.parentFile.listFiles().orEmpty().none { it.name.startsWith(".plugin-atomic-") })
    }
}

package com.bilipai.desktop.plugins

import com.android.purebilibili.core.plugin.feed.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Same temp-store fixtures as the subscription identity and title-refresh tests. */
class DesktopSubscriptionGroupTest {
    @TempDir lateinit var opmlDirectory: Path

    private fun context() = DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("rss-id-fixture-")))
    private val firstUrl = "https://example.invalid/Aa"
    private val secondUrl = "https://example.invalid/BB"

    private fun seed(context: DesktopPluginContext, feeds: List<SavedSubscriptionFeed>) {
        val file = File(context.filesDir, "plugin/subscription_feeds.json")
        file.parentFile.mkdirs()
        file.writeText(Json.encodeToString(feeds))
    }

    private fun reopen(context: DesktopPluginContext) =
        DesktopPluginContext(DesktopPluginStore(context.filesDir.toPath()))

    private suspend fun fresh(context: DesktopPluginContext): DesktopSubscriptionRepository {
        val repository = DesktopSubscriptionRepository(reopen(context)) { true }
        repository.loadCached()
        return repository
    }

    @Test fun `setGroup trims and persists for a fresh repository on the same storage`(): Unit = runBlocking {
        val context = context()
        val repository = DesktopSubscriptionRepository(context) { true }
        val first = SubscriptionFeedStore.add(context.asAndroidContext(), "First", firstUrl).getOrThrow()
        val second = SubscriptionFeedStore.add(context.asAndroidContext(), "Second", secondUrl).getOrThrow()
        val revision = SubscriptionFeedStore.revision.value
        repository.setGroup(first.id, "  Research  ")
        assertTrue(SubscriptionFeedStore.revision.value > revision)
        assertEquals(first.copy(group = "Research"), repository.state.value.sources.single { it.id == first.id })
        assertEquals(second, repository.state.value.sources.single { it.id == second.id })
        assertEquals("Research", repository.state.value.activeSources.single { it.url == firstUrl }.group)
        assertEquals("", repository.state.value.activeSources.single { it.url == secondUrl }.group)
        val reloaded = fresh(context)
        assertEquals(first.copy(group = "Research"), reloaded.state.value.sources.single { it.id == first.id })
        assertEquals(second, reloaded.state.value.sources.single { it.id == second.id })
        repository.setGroup(first.id, " \n ")
        val cleared = fresh(context)
        assertEquals("", cleared.state.value.sources.single { it.id == first.id }.group)
        assertEquals("", cleared.state.value.activeSources.single { it.url == firstUrl }.group)
        assertEquals(second, cleared.state.value.sources.single { it.id == second.id })
    }

    @Test fun `unchanged group does not rewrite storage`(): Unit = runBlocking {
        val context = context()
        val existing = SavedSubscriptionFeed("legacy", "Before", firstUrl, true, "Research")
        seed(context, listOf(existing))
        val repository = DesktopSubscriptionRepository(context) { true }
        val file = File(context.filesDir, "plugin/subscription_feeds.json")
        val bytes = file.readBytes()
        val revision = SubscriptionFeedStore.revision.value
        repository.setGroup(existing.id, "Research")
        repository.setGroup(existing.id, "  Research  ")
        assertContentEquals(bytes, file.readBytes())
        assertEquals(revision, SubscriptionFeedStore.revision.value)
        assertEquals(listOf(existing), SubscriptionFeedStore.list(reopen(context).asAndroidContext()))
        assertEquals(listOf(existing), fresh(context).state.value.sources)
    }

    @Test fun `builtin subscription id is rejected`(): Unit = runBlocking {
        val context = context()
        val builtIn = SavedSubscriptionFeed("builtin:legacy", "Built-in", firstUrl, true, "Keep")
        val user = SavedSubscriptionFeed("user", "User", secondUrl, true, "Stay")
        seed(context, listOf(builtIn, user))
        val repository = DesktopSubscriptionRepository(context) { true }
        val file = File(context.filesDir, "plugin/subscription_feeds.json")
        val bytes = file.readBytes()
        val revision = SubscriptionFeedStore.revision.value
        val rejected = assertFailsWith<IllegalArgumentException> { repository.setGroup(builtIn.id, "Changed") }
        assertEquals("内置订阅源不能修改分组", rejected.message)
        val missing = assertFailsWith<IllegalArgumentException> { repository.setGroup("builtin:missing", "Changed") }
        assertEquals("内置订阅源不能修改分组", missing.message)
        assertContentEquals(bytes, file.readBytes())
        assertEquals(revision, SubscriptionFeedStore.revision.value)
        assertEquals(listOf(builtIn, user), SubscriptionFeedStore.list(reopen(context).asAndroidContext()))
        repository.setGroup(user.id, "Moved")
        assertEquals("Moved", fresh(context).state.value.sources.single { it.id == user.id }.group)
        assertEquals("Keep", fresh(context).state.value.sources.single { it.id == builtIn.id }.group)
    }

    @Test fun `exported opml nests a changed group and leaves a blank group ungrouped`(): Unit = runBlocking {
        val context = context()
        val repository = DesktopSubscriptionRepository(context) { true }
        val grouped = SubscriptionFeedStore.add(context.asAndroidContext(), "Grouped", firstUrl).getOrThrow()
        val flat = SubscriptionFeedStore.add(context.asAndroidContext(), "Flat", secondUrl).getOrThrow()
        repository.setGroup(grouped.id, " A & B ")
        val saved = SubscriptionFeedStore.list(reopen(context).asAndroidContext())
        assertEquals(grouped.copy(group = "A & B"), saved.single { it.url == firstUrl })
        assertEquals(flat, saved.single { it.url == secondUrl })
        val opml = DesktopSubscriptionRepository(reopen(context)) { true }.exportOpml()
        assertEquals(buildSubscriptionOpml(saved), opml)
        val flatOutline = "        <outline type=\"rss\" text=\"Flat\" title=\"Flat\" xmlUrl=\"$secondUrl\"/>"
        val folder = "    <outline text=\"A &amp; B\" title=\"A &amp; B\">"
        val child = "        <outline type=\"rss\" text=\"Grouped\" title=\"Grouped\" xmlUrl=\"$firstUrl\"/>"
        val folderStart = opml.indexOf(folder)
        assertTrue(folderStart >= 0, opml)
        val folderEnd = opml.indexOf("\n    </outline>", folderStart)
        assertTrue(folderEnd > folderStart, opml)
        assertTrue(opml.indexOf(child) in (folderStart + folder.length) until folderEnd, opml)
        assertTrue(opml.indexOf(flatOutline) in 0 until folderStart, opml)
        assertFalse(opml.contains("category="), opml)
        val parsed = parseSubscriptionImport(opml)
        assertEquals("A & B", parsed.single { it.url == firstUrl }.group)
        assertEquals("", parsed.single { it.url == secondUrl }.group)
        val path = opmlDirectory.resolve("bilipai-subscriptions.opml")
        writeSubscriptionOpml(path, opml)
        assertContentEquals(opml.toByteArray(Charsets.UTF_8), Files.readAllBytes(path))
    }

    @Test fun `writeSubscriptionOpml replaces a file with exact utf8 text`() {
        val path = opmlDirectory.resolve("bilipai-subscriptions.opml")
        writeSubscriptionOpml(path, "stale")
        val text = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n分组 · 订阅\n"
        writeSubscriptionOpml(path, text)
        assertContentEquals(text.toByteArray(Charsets.UTF_8), Files.readAllBytes(path))
    }
}

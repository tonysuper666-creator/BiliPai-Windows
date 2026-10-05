package com.bilipai.desktop.plugins

import com.android.purebilibili.core.plugin.feed.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class DesktopHistoryRecapStoreTest {
    private suspend fun fixture(block: suspend (DesktopPluginContext) -> Unit) {
        val path = Files.createTempDirectory("recap-store-test-")
        try { block(DesktopPluginContext(DesktopPluginStore(path))) }
        finally { path.toFile().deleteRecursively() }
    }
    private fun article() = ParsedFeedItem("item", "builtin:source", "Source", "Article",
        "https://fixture.invalid/item", "", 1L, "summary", "body", null)

    @Test fun oldReadingDocumentKeepsItsDataWithoutInventingDates(): Unit = runBlocking {
        fixture { context ->
            val file = File(context.filesDir,"plugin/subscription_reading.json")
            file.parentFile.mkdirs()
            file.writeText("""{"items":[],"readKeys":["builtin:old\u001fold"],"fullBodies":{"old":"body"}}""")
            val old = FeedReadingStore.load(context)
            assertEquals(listOf("builtin:old\u001fold"),old.readKeys)
            assertEquals(mapOf("old" to "body"),old.fullBodies)
            assertTrue(old.readTimestamps.isEmpty());assertTrue(old.readProgress.isEmpty())
            FeedReadingStore.saveItems(context,listOf(article()))
            val saved = FeedReadingStore.load(context)
            assertEquals(old.readKeys,saved.readKeys);assertEquals(old.fullBodies,saved.fullBodies)
            assertTrue(saved.readTimestamps.isEmpty())
        }
    }
    @Test fun completeOriginalTimestampMethodsUseRealReadTimeAndKeepUnreadHistory(): Unit = runBlocking {
        fixture { context ->
            val key=feedItemKey(article())
            FeedReadingStore.recordRead(context,key,atMs=123_456L)
            assertEquals(mapOf(key to 123_456L),FeedReadingStore.loadTimestamps(context))
            assertEquals(listOf(key),FeedReadingStore.load(context).readKeys)
            assertEquals(listOf(key to 123_456L),FeedReadingStore.recentReads(context,123_456L))
            assertTrue(FeedReadingStore.recentReads(context,123_457L).isEmpty())
            // Upstream setRead(false) changes the visible marker, not past reading history.
            FeedReadingStore.setRead(context,key,false)
            assertTrue(FeedReadingStore.load(context).readKeys.isEmpty())
            assertEquals(mapOf(key to 123_456L),FeedReadingStore.loadTimestamps(context))
        }
    }
    @Test fun originalTimestampAndProgressBoundsArePreserved(): Unit = runBlocking {
        val input=(0..2_100).associate { "k$it" to it.toLong() }
        val bounded=updateReadTimestamps(input,"latest",true,9_999L)
        assertEquals(2_000,bounded.size);assertEquals(9_999L,bounded["latest"])
        assertFalse("k0" in bounded);assertEquals(input.filterKeys { it!="k1" },updateReadTimestamps(input,"k1",false,0L))
        fixture { context ->
            FeedReadingStore.recordProgress(context,"a",120)
            assertEquals(100,FeedReadingStore.readProgress(context,"a"))
            FeedReadingStore.recordProgress(context,"a",-1)
            assertEquals(0,FeedReadingStore.readProgress(context,"a"))
        }
    }
    @Test fun actualRssActorRecordsTrueReadInsteadOfArticlePublicationTime(): Unit = runBlocking {
        fixture { context ->
            val repository=DesktopSubscriptionRepository(context,enabled={true})
            val item=article();val before=System.currentTimeMillis()
            repository.setRead(item,true)
            val timestamp=assertNotNull(FeedReadingStore.loadTimestamps(context)[feedItemKey(item)])
            assertTrue(timestamp in before..System.currentTimeMillis())
            assertNotEquals(item.publishedEpochSec!!*1_000L,timestamp)
            assertEquals(timestamp,repository.state.value.reading.readTimestamps[feedItemKey(item)])
            repository.setRead(item,false)
            assertTrue(repository.state.value.reading.readKeys.isEmpty())
            assertEquals(timestamp,repository.state.value.reading.readTimestamps[feedItemKey(item)])
        }
    }
    @Test fun retiredActualRssWriteCannotReplaceExistingAtomicDocument(): Unit = runBlocking {
        fixture { context ->
            val item=article();val key=feedItemKey(item)
            FeedReadingStore.recordRead(context,key,42L)
            val file=File(context.filesDir,"plugin/subscription_reading.json");val before=file.readBytes()
            val repository=DesktopSubscriptionRepository(context,enabled={true})
            var commits=0
            val failed=runCatching { DesktopSubscriptionWriteAdmission.withOwned({true},{ _ -> commits++; false }) {
                repository.setRead(item,true)
            } }
            assertIs<CancellationException>(failed.exceptionOrNull())
            assertEquals(1,commits);assertContentEquals(before,file.readBytes())
            assertTrue(repository.state.value.reading.readTimestamps.isEmpty())
            assertEquals(mapOf(key to 42L),FeedReadingStore.loadTimestamps(context))
        }
    }
    @Test fun actualReadWritesKeepFullBodiesItemsAndProgressTogether(): Unit = runBlocking {
        fixture { context ->
            val item=article();val key=feedItemKey(item)
            FeedReadingStore.saveItems(context,listOf(item))
            FeedReadingStore.saveFullBody(context,key,"complete")
            FeedReadingStore.recordProgress(context,key,37)
            DesktopSubscriptionRepository(context,enabled={true}).setRead(item,true)
            val saved=FeedReadingStore.load(context)
            assertEquals(listOf(item),saved.items);assertEquals("complete",saved.fullBodies[key])
            assertEquals(37,saved.readProgress[key]);assertNotNull(saved.readTimestamps[key])
            assertEquals(listOf(key),saved.readKeys)
        }
    }
}

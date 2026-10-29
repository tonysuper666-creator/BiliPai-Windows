package com.bilipai.desktop.ui

import com.bilipai.desktop.plugins.asAndroidContext
import com.android.purebilibili.core.plugin.feed.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Real original reading codec/AtomicFile and current repository/admission. Private local
 * data only; no rendered reader, Runtime/JS worker, network or user account is constructed. */
class DesktopSubscriptionReadingProgressRegressionTest {
    private class Fixture {
        val root = Files.createTempDirectory("bp-reading-progress-")
        val context = DesktopPluginContext(DesktopPluginStore(root))
        val repository = DesktopSubscriptionRepository(context) { true }
        val readingFile get() = root.resolve("plugin/subscription_reading.json")
        val presented = AtomicReference(Any())
        fun identity(item: ParsedFeedItem): DesktopSubscriptionArticleReadingIdentity {
            val session = presented.get()
            return DesktopSubscriptionArticleReadingIdentity(item) { presented.get() === session }
        }
        suspend fun seed(vararg items: ParsedFeedItem) { FeedReadingStore.saveItems(context.asAndroidContext(),items.toList()) }
        suspend fun record(item: ParsedFeedItem, identity: DesktopSubscriptionArticleReadingIdentity,
            percent: Int, admit: ((() -> Unit) -> Boolean) = { it(); true }) {
            DesktopSubscriptionWriteAdmission.withOwned({ identity.isOwnedFor(item) },admit) {
                repository.recordProgress(item,percent)
            }
        }
        fun bytes() = Files.readAllBytes(readingFile)
    }
    private fun item(id: String, source: String = "source-one") = ParsedFeedItem(
        id=id,sourceId=source,sourceTitle="Synthetic feed",title="Private article $id",
        link="https://example.invalid/article/$id",author="",publishedEpochSec=1L,
        summary="Short summary",htmlContent="Private body",imageUrl=null)

    @Test fun progressPersistsThroughTheRealRepositoryAndColdReadingStore(): Unit = runBlocking {
        val f=Fixture(); val first=item("article-one"); val other=item("article-two")
        f.seed(first,other)
        FeedReadingStore.recordRead(f.context.asAndroidContext(),feedItemKey(first),123456789L)
        FeedReadingStore.saveFullBody(f.context.asAndroidContext(),feedItemKey(first),"Preserved full body")
        val original=FeedReadingStore.load(f.context.asAndroidContext())
        val identity=f.identity(first)
        try {
            assertEquals(0,f.repository.readProgress(first))
            f.record(first,identity,47)
            assertEquals(47,f.repository.readProgress(first))
            val coldContext=DesktopPluginContext(DesktopPluginStore(f.root))
            val cold=DesktopSubscriptionRepository(coldContext) { true }
            assertEquals(47,cold.readProgress(first))
            assertEquals(0,cold.readProgress(other))
            val actual=FeedReadingStore.load(coldContext.asAndroidContext())
            assertEquals(original.items,actual.items)
            assertEquals(original.readKeys,actual.readKeys)
            assertEquals(original.readTimestamps,actual.readTimestamps)
            assertEquals(original.fullBodies,actual.fullBodies)
            assertEquals(47,actual.readProgress[feedItemKey(first)])
            assertEquals(actual,f.repository.state.value.reading)
        } finally { identity.close() }
    }

    @Test fun SameArticleIdsFromDifferentSourcesKeepIndependentProgress(): Unit = runBlocking {
        val f=Fixture(); val first=item("same-id","feed-A"); val other=item("same-id","feed-B")
        f.seed(first,other)
        val firstIdentity=f.identity(first); val otherIdentity=f.identity(other)
        try {
            f.record(first,firstIdentity,28)
            f.record(other,otherIdentity,76)
            assertEquals(28,f.repository.readProgress(first))
            assertEquals(76,f.repository.readProgress(other))
            assertNotEquals(feedItemKey(first),feedItemKey(other))
            assertFalse(firstIdentity.isOwnedFor(other))
            assertFailsWith<CancellationException> { f.record(other,firstIdentity,99) }
            assertEquals(76,f.repository.readProgress(other))
        } finally { firstIdentity.close(); otherIdentity.close() }
    }

    @Test fun OriginalProgressBoundsAndZeroRemovalKeepOtherArticleData(): Unit = runBlocking {
        val f=Fixture(); val first=item("first"); val other=item("other")
        f.seed(first,other)
        val a=f.identity(first); val b=f.identity(other)
        try {
            f.record(other,b,63); f.record(first,a,145)
            assertEquals(100,f.repository.readProgress(first))
            f.record(first,a,0)
            assertEquals(0,f.repository.readProgress(first))
            assertEquals(63,f.repository.readProgress(other))
            assertFalse(feedItemKey(first) in FeedReadingStore.load(f.context.asAndroidContext()).readProgress)
        } finally { a.close(); b.close() }
    }

    @Test fun ClosingAndReopeningTheSameArticleNeverRevivesTheOldSamplingIdentity(): Unit = runBlocking {
        val f=Fixture(); val article=item("same-article"); f.seed(article)
        val old=f.identity(article)
        f.record(article,old,35)
        old.close(); f.presented.set(Any())
        val reopened=f.identity(article)
        try {
            val before=f.bytes()
            assertFailsWith<CancellationException> { f.record(article,old,5) }
            assertContentEquals(before,f.bytes())
            assertFalse(old.isOwnedFor(article)); assertTrue(reopened.isOwnedFor(article))
            f.record(article,reopened,71)
            assertEquals(71,f.repository.readProgress(article))
        } finally { reopened.close() }
    }

    @Test fun RetiredPresentationAtActualAtomicRenamePreservesThePriorDiskAndPublishedSnapshot(): Unit = runBlocking {
        val f=Fixture(); val article=item("retire-at-write"); f.seed(article)
        val identity=f.identity(article)
        try {
            f.record(article,identity,52)
            val before=f.bytes(); val snapshot=f.repository.state.value
            var commits=0
            assertFailsWith<CancellationException> {
                f.record(article,identity,11) { action ->
                    commits++
                    // This is the actual original AtomicFile final replacement admission.
                    identity.close(); action(); true
                }
            }
            assertEquals(1,commits)
            assertContentEquals(before,f.bytes())
            assertSame(snapshot,f.repository.state.value)
            assertEquals(52,FeedReadingStore.readProgress(f.context.asAndroidContext(),feedItemKey(article)))
            Files.list(f.readingFile.parent).use { files ->
                assertFalse(files.anyMatch { it.fileName.toString().startsWith(".plugin-atomic-") })
            }
        } finally { identity.close() }
    }

    @Test fun CancellationWhileTheActualFinalWriteWaitsCannotPublishAndTheSuccessorCanWrite(): Unit = runBlocking {
        val f=Fixture(); val article=item("cancel-at-write"); f.seed(article)
        val identity=f.identity(article)
        f.record(article,identity,44)
        val before=f.bytes(); val snapshot=f.repository.state.value
        val arrived=CountDownLatch(1); val release=CountDownLatch(1)
        val task=launch(Dispatchers.Default) {
            f.record(article,identity,8) { action ->
                arrived.countDown()
                check(release.await(5,TimeUnit.SECONDS)) { "Synthetic final-write release timed out" }
                action(); true
            }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { arrived.await(5,TimeUnit.SECONDS) })
            task.cancel(); release.countDown()
            withTimeout(5000) { task.join() }
            assertTrue(task.isCancelled)
            assertContentEquals(before,f.bytes()); assertSame(snapshot,f.repository.state.value)
            identity.close(); f.presented.set(Any())
            val successor=f.identity(article)
            try { f.record(article,successor,82); assertEquals(82,f.repository.readProgress(article)) }
            finally { successor.close() }
        } finally {
            release.countDown()
            withContext(NonCancellable) { withTimeout(5000) { task.cancelAndJoin() } }
            identity.close()
        }
    }
}

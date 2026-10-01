package com.bilipai.desktop.ui

import com.bilipai.desktop.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.data.PlaybackSource as ResolvedSource
import com.bilipai.desktop.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlin.test.*

/** Actual Controller + Host with synthetic transport; no native surface, account, or network. */
class DesktopStoryInitialFailureTest {
    @Test fun `metadata failure remains visible and retry uses the held owner and same page`(): Unit = runBlocking {
        Fixture().use { f ->
            f.metadataFailures = 1
            val baseline = f.player.currentSourceVersion
            f.select()
            f.awaitFailure()
            f.onMain {
                assertTrue(f.controller.ownsQueue(f.owner))
                assertTrue(f.host.owns(f.owner))
                val snapshot = f.snapshot()
                assertSame(f.owner, snapshot.owner)
                assertEquals("Synthetic metadata failure", snapshot.error)
                assertEquals("BV-one", snapshot.bvid)
                assertFalse(snapshot.loading)
                assertTrue(f.host.retry(f.owner.copy(), active = true))
            }
            f.awaitLoaded("BV-one")
            assertEquals(listOf("BV-one", "BV-one"), f.metadataCalls.toList())
            assertEquals(listOf("BV-one-7"), f.streamCalls.toList())
            assertTrue(f.player.currentSourceVersion > baseline)
            f.onMain { assertTrue(f.host.owns(f.owner)); assertNull(f.snapshot().error) }
        }
    }

    @Test fun `stream failure retries cached details while preserving selected CID and queue token`(): Unit = runBlocking {
        Fixture().use { f ->
            f.streamFailures = 1
            f.select(cards = listOf(f.card("BV-one", 9), f.card("BV-two")))
            f.awaitFailure()
            f.onMain {
                assertEquals(9L, f.snapshot().cid)
                assertSame(f.owner, f.snapshot().owner)
                assertEquals("Synthetic stream failure", f.snapshot().error)
                assertTrue(f.host.retry(f.owner, true))
            }
            f.awaitLoaded("BV-one")
            assertEquals(listOf("BV-one"), f.metadataCalls.toList())
            assertEquals(listOf("BV-one-9", "BV-one-9"), f.streamCalls.toList())
            assertEquals(1, f.controller.state.value.currentPart)
            assertEquals(0, f.controller.state.value.queueIndex)
            f.onMain { assertTrue(f.controller.ownsQueue(f.owner)); assertTrue(f.host.owns(f.owner)) }
        }
    }

    @Test fun `metadata failure can select next page without retiring the route`(): Unit = runBlocking {
        Fixture().use { f ->
            f.metadataFailures = 1; f.select(); f.awaitFailure()
            f.onMain { assertTrue(f.host.request(f.request(index = 1, revision = 2), true)) }
            f.awaitLoaded("BV-two")
            assertEquals(listOf("BV-one", "BV-two"), f.metadataCalls.toList())
            assertEquals(listOf("BV-two-7"), f.streamCalls.toList())
            f.onMain { assertSame(f.owner, f.host.owner.value); assertTrue(f.host.owns(f.owner)) }
        }
    }

    @Test fun `stream failure can select next page without reusing failed details or retiring owner`(): Unit = runBlocking {
        Fixture().use { f ->
            f.streamFailures = 1; f.select(); f.awaitFailure()
            f.onMain { assertTrue(f.host.request(f.request(index = 1, revision = 2), true)) }
            f.awaitLoaded("BV-two")
            assertEquals(listOf("BV-one", "BV-two"), f.metadataCalls.toList())
            assertEquals(listOf("BV-one-7", "BV-two-7"), f.streamCalls.toList())
            assertEquals(1, f.controller.state.value.queueIndex)
            f.onMain { assertSame(f.owner, f.host.owner.value); assertTrue(f.host.owns(f.owner)) }
        }
    }

    @Test fun `failed lease cannot retry mutate or stop a foreign native takeover`(): Unit = runBlocking {
        Fixture().use { f ->
            f.streamFailures = 1; f.select(); f.awaitFailure()
            val calls = f.streamCalls.toList()
            f.onMain {
                val foreign = f.player.loadVersioned(PlaybackSource("foreign.avi", title = "Foreign"))
                assertFalse(f.controller.ownsQueue(f.owner))
                assertFalse(f.controller.retryQueueForOwner(f.owner))
                assertFalse(f.host.retry(f.owner, true))
                assertEquals(DesktopStoryPlaybackSnapshot(), f.snapshot())
                assertFalse(f.host.request(f.request(index = 1, revision = 2), true))
                assertNull(f.host.owner.value)
                assertFalse(f.host.request(f.request(revision = 3), true))
                assertFalse(f.host.release(f.owner))
                assertEquals(foreign, f.player.currentSourceVersion)
                assertEquals("Foreign", f.player.state.value.sourceTitle)
            }
            assertEquals(calls, f.streamCalls.toList())
        }
    }

    @Test fun `changed epoch released inactive and equal reference tokens cannot bypass retry ownership`(): Unit = runBlocking {
        Fixture().use { f ->
            f.metadataFailures = 1; f.select(); f.awaitFailure()
            val baseline = f.player.currentSourceVersion
            f.onMain {
                assertFalse(f.controller.retryQueueForOwner(f.owner.copy()))
                assertFalse(f.host.retry(f.owner, active = false))
                f.epoch.incrementAndGet()
                assertFalse(f.host.retry(f.owner, true))
                assertFalse(f.controller.retryQueueForOwner(f.owner))
                assertEquals(DesktopStoryPlaybackSnapshot(), f.snapshot())
                assertFalse(f.host.request(f.request(index = 1, revision = 2), true))
                f.host.release(f.owner)
                assertFalse(f.host.retry(f.owner, true))
                assertEquals(baseline, f.player.currentSourceVersion)
            }
            assertEquals(listOf("BV-one"), f.metadataCalls.toList())
        }
    }

    @Test fun `duplicate retry during pending metadata submits once and canceled retry cannot reacquire`(): Unit = runBlocking {
        Fixture().use { f ->
            f.metadataFailures = 1; f.select(); f.awaitFailure()
            val baseline = f.player.currentSourceVersion
            f.metadataGate = CompletableDeferred()
            f.onMain {
                assertTrue(f.host.retry(f.owner, true))
                assertFalse(f.host.retry(f.owner, true))
            }
            f.await { f.metadataCalls.size == 2 }
            f.onMain { assertTrue(f.host.release(f.owner)) }
            f.metadataCanceled.await()
            f.metadataGate!!.complete(Unit)
            f.onMain {
                assertFalse(f.controller.ownsQueue(f.owner))
                assertFalse(f.controller.retryQueueForOwner(f.owner))
                assertFalse(f.host.request(f.request(revision = 2), true))
                assertEquals(baseline, f.player.currentSourceVersion)
            }
            assertTrue(f.streamCalls.isEmpty())
        }
    }

    @Test fun `late uncooperative failed retry cannot replace the next pages success`(): Unit = runBlocking {
        Fixture().use { f ->
            f.metadataFailures = 1; f.select(); f.awaitFailure()
            val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
            val returned = CompletableDeferred<Unit>()
            f.customMetadata = { bvid ->
                if (bvid == "BV-one") withContext(NonCancellable) {
                    try { entered.complete(Unit); gate.await(); throw IllegalStateException("Obsolete retry failure") }
                    finally { returned.complete(Unit) }
                }
                f.details(bvid)
            }
            try {
                f.onMain { assertTrue(f.host.retry(f.owner, true)) }; entered.await()
                f.onMain { assertTrue(f.host.request(f.request(index = 1, revision = 2), true)) }
                f.awaitLoaded("BV-two")
                val native = f.player.currentSourceVersion
                gate.complete(Unit); returned.await()
                withContext(Dispatchers.Swing) { }
                f.onMain {
                    assertTrue(f.host.owns(f.owner)); assertNull(f.snapshot().error)
                    assertEquals("BV-two", f.snapshot().bvid)
                    assertEquals(native, f.player.currentSourceVersion)
                }
            } finally { gate.complete(Unit) }
        }
    }

    private class Fixture : AutoCloseable {
        val epoch = AtomicLong(7)
        val owner = DesktopStoryOwner("failure-fixture", 7)
        val player = MpvPlayer()
        var metadataFailures = 0; var streamFailures = 0
        var metadataGate: CompletableDeferred<Unit>? = null
        val metadataCanceled = CompletableDeferred<Unit>()
        var customMetadata: (suspend (String) -> VideoDetails)? = null
        val metadataCalls = CopyOnWriteArrayList<String>()
        val streamCalls = CopyOnWriteArrayList<String>()
        private val errors = CopyOnWriteArrayList<Throwable>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing + CoroutineExceptionHandler { _, error -> errors.add(error) })
        private val directory = Files.createTempDirectory("bp-story-failure-")
        private val repo = DesktopRepository(DesktopSessionStore.temporary())
        private val source = object : DesktopPlaybackDataSource {
            override val sessionEpoch get() = epoch.get()
            override suspend fun videoDetails(bvid: String): VideoDetails {
                metadataCalls.add(bvid)
                if (metadataFailures > 0) { metadataFailures--; throw IllegalStateException("Synthetic metadata failure") }
                try { metadataGate?.await() } catch (cancel: CancellationException) { metadataCanceled.complete(Unit); throw cancel }
                return customMetadata?.invoke(bvid) ?: details(bvid)
            }
            override suspend fun related(bvid: String) = emptyList<VideoCard>()
            override suspend fun playback(details: VideoDetails, index: Int, quality: Int, codecOverride: String?, forceRefresh: Boolean): ResolvedSource {
                streamCalls.add("${details.bvid}-${details.pages[index].cid}")
                if (streamFailures > 0) { streamFailures--; throw IllegalStateException("Synthetic stream failure") }
                return ResolvedSource("file:///C:/story-fixture.avi", null, details.title, "", quality = quality, videoCodecFamily = "avc1")
            }
        }
        val controller = DesktopPlaybackController(repo, player, null, null, DesktopLibrary(directory) { false }, { PlayerPreferences() }, scope, currentDanmakuSettings = { error("This controller harness has no danmaku overlay") }, dataSource = source)
        val host = DesktopStoryPlaybackHost(ControllerStoryQueuePlayer(controller), { epoch.get() })
        fun details(bvid: String) = VideoDetails(bvid, 51, bvid, "", "", "", 0, 0,
            listOf(VideoPart(7, "P1", 120), VideoPart(9, "P2", 120)))
        fun card(bvid: String, cid: Long = 7) = VideoCard(bvid, bvid, "", "", 0, 120, preferredCid = cid)
        fun request(index: Int = 0, revision: Long = 1, cards: List<VideoCard> = listOf(card("BV-one"), card("BV-two"))) =
            DesktopStoryPlaybackRequest(owner, revision, cards, index, true)
        fun select(cards: List<VideoCard> = listOf(card("BV-one"), card("BV-two"))) = onMain {
            assertTrue(host.request(request(cards = cards), true))
        }
        fun snapshot() = host.snapshot(controller.state.value, player.state.value)
        suspend fun await(condition: () -> Boolean) = withTimeout(4_000) {
            while (!withContext(Dispatchers.Swing) { condition() }) delay(5)
        }
        suspend fun awaitFailure() = await { !controller.state.value.opening && controller.state.value.error != null }
        suspend fun awaitLoaded(bvid: String) = await { !controller.state.value.opening &&
            controller.state.value.error == null && controller.state.value.details?.bvid == bvid && controller.ownsQueue(owner) }
        fun onMain(block: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
        }
        override fun close() {
            onMain { host.close(); controller.close() }; scope.cancel(); player.close()
            val target = directory.toAbsolutePath().normalize()
            check(target.fileName.toString().startsWith("bp-story-failure-") && target.startsWith(
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            Files.walk(target).use { files -> files.sorted(Comparator.reverseOrder()).forEach {
                check(it.toAbsolutePath().normalize().startsWith(target)); Files.deleteIfExists(it)
            } }
            check(errors.isEmpty()) { "Unexpected task-owned failure: ${errors.first()}" }
        }
    }
}

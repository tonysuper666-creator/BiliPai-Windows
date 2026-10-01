package com.bilipai.desktop

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

class DesktopPlaybackQueueOwnershipTest {
    private fun card(id: String, cid: Long = 7) = VideoCard(id, id, "", "", 0, 120, preferredCid = cid)

    @Test fun `append preserves actual source owner CID pause and position without a transport request`() = runBlocking {
        Fixture().use { f ->
            val token = Any()
            swingQueue { f.controller.openQueue(listOf(card("BV-one")), 0, token) }
            f.await { !f.controller.state.value.opening && f.controller.ownsQueue(token) }
            val owner = f.player.currentSourceVersion
            swingQueue { f.player.recoverSource(owner, positionSeconds = 33.5, paused = true) }
            swingQueue {
                assertTrue(f.controller.updateQueueForOwner(token, listOf(card("BV-one"), card("BV-two")), 0))
                assertFalse(f.controller.updateQueueForOwner(token, listOf(card("BV-one", 9)), 0))
            }
            assertEquals(1, f.calls.size)
            assertEquals(owner, f.player.currentSourceVersion)
            assertEquals(33.5, f.player.state.value.positionSeconds)
            assertTrue(f.player.state.value.paused)
            assertEquals(7L, f.controller.state.value.details?.pages?.get(f.controller.state.value.currentPart)?.cid)
            assertEquals(2, f.controller.state.value.queue.size)
        }
    }

    @Test fun `same BV different CID next retains queue token and original shuffle reconciliation maps both identities`() = runBlocking {
        Fixture(PlaybackMode.SHUFFLE).use { f ->
            val token = Any()
            // AV route identity remains the host's identity, even when details resolve a canonical BV.
            val a = card("av51", 7); val b = card("av51", 9)
            swingQueue { f.controller.openQueue(listOf(a, b), 0, token) }
            f.await { !f.controller.state.value.opening && f.controller.ownsQueue(token) }
            swingQueue { f.controller.next() }
            f.await { !f.controller.state.value.opening && f.controller.state.value.queueIndex == 1 }
            assertTrue(f.controller.ownsQueue(token))
            assertEquals(9L, f.controller.state.value.details?.pages?.get(f.controller.state.value.currentPart)?.cid)
            val before = f.calls.size
            swingQueue { assertTrue(f.controller.updateQueueForOwner(token, listOf(b, a, card("BV-next")), 0)) }
            assertEquals(before, f.calls.size)
            swingQueue { f.controller.previous() }
            f.await { !f.controller.state.value.opening && f.controller.state.value.queueIndex == 1 }
            assertEquals(7L, f.controller.state.value.details?.pages?.get(f.controller.state.value.currentPart)?.cid)
            swingQueue { f.controller.next() }
            f.await { !f.controller.state.value.opening && f.controller.state.value.queueIndex == 0 }
            assertEquals(9L, f.controller.state.value.details?.pages?.get(f.controller.state.value.currentPart)?.cid)
            assertTrue(f.controller.ownsQueue(token))
        }
    }

    @Test fun `ordinary open retires an equal but distinct or previously owned host token`() = runBlocking {
        Fixture().use { f ->
            val token = "same-value".toCharArray().concatToString()
            val equalToken = "same-value".toCharArray().concatToString()
            assertEquals(token, equalToken); assertFalse(token === equalToken)
            swingQueue { f.controller.openQueue(listOf(card("BV-one")), 0, token) }
            f.await { !f.controller.state.value.opening }
            assertFalse(f.controller.ownsQueue(equalToken))
            swingQueue { f.controller.open(card("BV-two")) }
            f.await { !f.controller.state.value.opening && f.controller.state.value.details?.bvid == "BV-two" }
            val owner = f.player.currentSourceVersion
            assertFalse(f.controller.ownsQueue(token))
            swingQueue { assertFalse(f.controller.stopQueueForOwner(token)) }
            assertEquals(owner, f.player.currentSourceVersion)
            assertEquals("BV-two", f.controller.state.value.details?.bvid)
        }
    }

    @Test fun `initial delayed stream can update queue but cannot mutate or stop a foreign native replacement`() = runBlocking {
        Fixture().use { f ->
            val token = Any(); val gate = CompletableDeferred<Unit>(); f.gate = gate
            swingQueue { f.controller.openQueue(listOf(card("BV-one")), 0, token) }
            f.entered.await()
            swingQueue {
                assertTrue(f.controller.ownsQueue(token))
                assertTrue(f.controller.updateQueueForOwner(token, listOf(card("BV-one"), card("BV-two")), 0))
                f.player.loadVersioned(PlaybackSource("foreign.avi", title = "Foreign"))
            }
            val owner = f.player.currentSourceVersion
            swingQueue {
                assertFalse(f.controller.ownsQueue(token))
                assertFalse(f.controller.updateQueueForOwner(token, listOf(card("BV-one")), 0))
                assertFalse(f.controller.stopQueueForOwner(token))
            }
            gate.complete(Unit)
            f.returned.await(); withContext(Dispatchers.Swing) { }
            assertEquals(owner, f.player.currentSourceVersion)
            assertEquals("Foreign", f.player.state.value.sourceTitle)
            assertNull(f.controller.currentCastSource(owner))
        }
    }

    @Test fun `canceling a matching pending host cancels its fetch and retains the native baseline`() = runBlocking {
        Fixture().use { f ->
            val token = Any(); f.gate = CompletableDeferred()
            val baseline = f.player.currentSourceVersion
            swingQueue { f.controller.openQueue(listOf(card("BV-one")), 0, token) }
            f.entered.await()
            swingQueue { assertTrue(f.controller.stopQueueForOwner(token)) }
            f.cancelled.await()
            assertFalse(f.controller.ownsQueue(token))
            assertFalse(f.controller.state.value.opening)
            assertEquals(baseline, f.player.currentSourceVersion)
            assertNull(f.player.currentSourceSnapshot())
        }
    }

    @Test fun `parts quality retry and pause retain lease while changed account rejects host stop`() = runBlocking {
        Fixture().use { f ->
            val token = Any()
            swingQueue { f.controller.openQueue(listOf(card("BV-one", 0)), 0, token) }
            f.await { !f.controller.state.value.opening }
            swingQueue { f.controller.playPart(1) }
            f.await { !f.controller.state.value.opening && f.controller.state.value.currentPart == 1 }
            assertTrue(f.controller.ownsQueue(token))
            val owner = f.player.currentSourceVersion
            swingQueue { f.controller.switchQuality(64) }
            f.await { !f.controller.state.value.opening && f.controller.state.value.effectiveQuality == 64 }
            assertTrue(f.controller.ownsQueue(token))
            swingQueue { f.controller.retry() }
            f.await { !f.controller.state.value.opening && f.calls.size >= 4 }
            assertTrue(f.controller.ownsQueue(token))
            swingQueue { f.controller.pause() }
            assertTrue(f.controller.ownsQueue(token))
            f.epoch.incrementAndGet()
            swingQueue {
                assertFalse(f.controller.ownsQueue(token))
                assertFalse(f.controller.stopQueueForOwner(token))
            }
            assertEquals(owner, f.player.currentSourceVersion)
        }
    }

    private class Fixture(mode: PlaybackMode = PlaybackMode.SEQUENTIAL) : AutoCloseable {
        val epoch = AtomicLong()
        val player = MpvPlayer()
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val calls = CopyOnWriteArrayList<String>()
        private val repo = DesktopRepository(DesktopSessionStore.temporary())
        private val errors = CopyOnWriteArrayList<Throwable>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing + CoroutineExceptionHandler { _, error -> errors.add(error) })
        private val directory = Files.createTempDirectory("bilipai-queue-owner-")
        private val source = object : DesktopPlaybackDataSource {
            override val sessionEpoch get() = epoch.get()
            override suspend fun videoDetails(bvid: String) = VideoDetails(if(bvid.startsWith("av")) "BV-canonical" else bvid,
                51, bvid, "", "", "", 0, 0, listOf(VideoPart(7, "P1", 120), VideoPart(9, "P2", 120)))
            override suspend fun related(bvid: String) = emptyList<VideoCard>()
            override suspend fun playback(details: VideoDetails, index: Int, quality: Int, codecOverride: String?, forceRefresh: Boolean): ResolvedSource {
                calls.add("${details.bvid}-${details.pages[index].cid}-$quality")
                entered.complete(Unit)
                try { gate?.await() } catch (cancel: CancellationException) { cancelled.complete(Unit); throw cancel }
                returned.complete(Unit)
                return ResolvedSource("file:///C:/queue-fixture.avi", null, "${details.bvid}-${details.pages[index].cid}", "",
                    quality = quality, videoCodecFamily = "avc1")
            }
        }
        val controller = DesktopPlaybackController(repo, player, null, null, DesktopLibrary(directory) { false },
            { PlayerPreferences(playbackMode = mode) }, scope, currentDanmakuSettings = { error("This controller harness has no danmaku overlay") }, dataSource = source, publication = com.bilipai.desktop.player.DesktopLocalPlaybackPublication(
                { scope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true },
                { admitted -> synchronized(scope) {
                    if (scope.coroutineContext[kotlinx.coroutines.Job]?.isActive != true) false else { admitted(); true }
                } }))
        suspend fun await(condition: () -> Boolean) = withTimeout(4_000) {
            while(!withContext(Dispatchers.Swing) { condition() }) delay(5)
        }
        override fun close() {
            swingQueue { controller.close() }; scope.cancel(); player.close()
            val target = directory.toAbsolutePath().normalize()
            check(target.startsWith(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()) &&
                target.fileName.toString().startsWith("bilipai-queue-owner-"))
            Files.walk(target).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                check(path.toAbsolutePath().normalize().startsWith(target)); Files.deleteIfExists(path)
            } }
            check(errors.isEmpty()) { "Unexpected queue background failure: ${errors.first()}" }
        }
    }
}

private fun swingQueue(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
}

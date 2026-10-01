package com.bilipai.desktop

import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.data.VideoCard
import com.bilipai.desktop.data.VideoDetails
import com.bilipai.desktop.data.VideoPart
import com.bilipai.desktop.data.PlaybackSource as ResolvedSource
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlaybackMode
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.PlayerPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.swing.Swing
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import java.util.concurrent.atomic.AtomicLong

class DesktopPlaybackControllerTest {
    @Test fun `cloud favorite projection survives part and quality changes in the same video owner`() {
        val source = FakeSource({ id -> details(id).let { info -> info.copy(raw =
            com.android.purebilibili.data.model.response.ViewInfo(aid = info.aid, bvid = id,
                stat = com.android.purebilibili.data.model.response.Stat(favorite = 5))) } }, ::resolved)
        Fixture(source).use { f ->
            onSwing { f.controller.open(card("BVA", 11)) }
            f.await { !it.opening && it.details?.bvid == "BVA" }
            val nativeOwner = f.player.currentSourceVersion
            onSwing {
                assertTrue(f.controller.updateFavoriteCountForOwner(10, f.repository.sessionEpoch, 6))
                assertFalse(f.controller.updateFavoriteCountForOwner(20, f.repository.sessionEpoch, 99))
                assertFalse(f.controller.updateFavoriteCountForOwner(10, f.repository.sessionEpoch + 1, 99))
            }
            assertEquals(6, f.controller.state.value.details?.raw?.stat?.favorite)
            assertEquals(nativeOwner, f.player.currentSourceVersion)
            onSwing { f.controller.switchQuality(64) }
            f.await { !it.opening && it.effectiveQuality == 64 }
            assertEquals(6, f.controller.state.value.details?.raw?.stat?.favorite)
            onSwing { f.controller.playPart(1) }
            f.await { !it.opening && it.currentPart == 1 }
            assertEquals(6, f.controller.state.value.details?.raw?.stat?.favorite)
        }
    }

    @Test fun `cloud favorite projection rejects changed session and foreign source before flow delivery`() {
        val epoch = AtomicLong()
        val base = FakeSource({ id -> details(id).let { info -> info.copy(raw =
            com.android.purebilibili.data.model.response.ViewInfo(aid = info.aid, bvid = id,
                stat = com.android.purebilibili.data.model.response.Stat(favorite = 5))) } }, ::resolved)
        val source = object : DesktopPlaybackDataSource by base { override val sessionEpoch get() = epoch.get() }
        Fixture(source).use { f ->
            onSwing { f.controller.open(card("BVA", 11)) }
            f.await { !it.opening && it.details?.bvid == "BVA" }
            onSwing {
                epoch.incrementAndGet()
                assertFalse(f.controller.updateFavoriteCountForOwner(10, 0, 99))
                epoch.set(0)
                f.player.loadVersioned(PlaybackSource("file:///C:/foreign-favorite-fixture.mp4", title = "Foreign"))
                assertFalse(f.controller.updateFavoriteCountForOwner(10, 0, 99))
                assertEquals(5, f.controller.state.value.details?.raw?.stat?.favorite)
                assertEquals("Foreign", f.player.state.value.sourceTitle)
            }
        }
    }

    @Test fun `casting cannot read a previous account or a foreign native source before flow delivery`() {
        val epoch = AtomicLong()
        val original = FakeSource(::details, ::resolved)
        val source = object : DesktopPlaybackDataSource by original {
            override val sessionEpoch get() = epoch.get()
        }
        Fixture(source).use { f ->
            onSwing { f.controller.open(card("BVA", 11)) }
            f.await { !it.opening && it.details?.bvid == "BVA" }
            val owned = f.player.currentSourceVersion
            onSwing {
                assertEquals("BVA-11", assertNotNull(f.controller.currentCastSource(owned)).title)
                epoch.incrementAndGet()
                assertNull(f.controller.currentCastSource(owned))
                epoch.set(0)
                val foreign = f.player.loadVersioned(PlaybackSource("file:///C:/foreign-cast-fixture.mp4", title = "Foreign"))
                assertNull(f.controller.currentCastSource(owned))
                assertNull(f.controller.currentCastSource(foreign))
            }
        }
    }

    private fun card(id: String, cid: Long = 0) = VideoCard(id, id, "", "", 0, 100, preferredCid = cid)
    private fun details(id: String): VideoDetails {
        val start = if (id == "BVA") 10L else if (id == "BVB") 20L else 30L
        return VideoDetails(id, start, id, "", "", "", 0, 0,
            listOf(VideoPart(start + 1, "P1", 100), VideoPart(start + 2, "P2", 100), VideoPart(start + 3, "P3", 100)))
    }
    private fun resolved(info: VideoDetails, index: Int, quality: Int) = ResolvedSource("file:///C:/queue-test.mp4", null,
        "${info.bvid}-${info.pages[index].cid}", "https://www.bilibili.com/", quality = quality, videoCodecFamily = "avc1")

    @Test fun `same BV collection preserves distinct CIDs and goes directly to the selected queue item`() {
        Fixture(FakeSource(::details, ::resolved)).use { f ->
            onSwing { f.controller.openQueue(listOf(card("BVA", 11), card("BVA", 13)), 0) }
            f.await { !it.opening && it.details?.bvid == "BVA" }
            assertEquals(0, f.controller.state.value.currentPart)
            assertTrue(f.controller.hasNext)
            onSwing { f.controller.next() }
            f.await { !it.opening && it.queueIndex == 1 }
            assertEquals(2, f.controller.state.value.currentPart)
            assertEquals("BVA-13", f.player.state.value.sourceTitle)
            assertFalse(f.controller.hasNext)
            onSwing { f.controller.previous() }
            f.await { !it.opening && it.queueIndex == 0 }
            assertEquals("BVA-11", f.player.state.value.sourceTitle)
        }
    }

    @Test fun `ordinary queue walks each video parts before advancing to the next video`() {
        Fixture(FakeSource(::details, ::resolved)).use { f ->
            onSwing { f.controller.openQueue(listOf(card("BVA"), card("BVB"))) }
            f.await { !it.opening && it.details?.bvid == "BVA" }
            repeat(2) { part -> onSwing { f.controller.next() }; f.await { !it.opening && it.currentPart == part + 1 } }
            onSwing { f.controller.next() }
            f.await { !it.opening && it.details?.bvid == "BVB" }
            assertEquals(1, f.controller.state.value.queueIndex)
            assertEquals("BVB-21", f.player.state.value.sourceTitle)
        }
    }

    @Test fun `shuffle retains original history so previous then next returns to the same item`() {
        Fixture(FakeSource(::details, ::resolved), PlaybackMode.SHUFFLE).use { f ->
            onSwing { f.controller.openQueue(listOf(card("BVA", 11), card("BVB", 21), card("BVC", 31))) }
            f.await { !it.opening && it.details != null }
            onSwing { f.controller.next() }
            f.await { !it.opening && it.queueIndex != 0 }
            val next = f.controller.state.value.queueIndex
            assertTrue(f.controller.hasPrevious)
            onSwing { f.controller.previous() }
            f.await { !it.opening && it.queueIndex == 0 }
            onSwing { f.controller.next() }
            f.await { !it.opening && it.queueIndex == next }
        }
    }

    @Test fun `quality switch and explicit retry preserve native source ownership CID position and pause`() {
        val calls = mutableListOf<Triple<Long, Int, Boolean>>()
        val source = object : DesktopPlaybackDataSource {
            override suspend fun videoDetails(bvid: String) = details(bvid)
            override suspend fun related(bvid: String) = emptyList<VideoCard>()
            override suspend fun playback(details: VideoDetails, index: Int, quality: Int, codecOverride: String?, forceRefresh: Boolean): ResolvedSource {
                calls += Triple(details.pages[index].cid, quality, forceRefresh)
                return resolved(details, index, quality)
            }
        }
        Fixture(source).use { f ->
            onSwing { f.controller.open(card("BVA", 13).copy(progressSeconds = 25)) }
            f.await { !it.opening && it.effectiveQuality == 80 }
            onSwing { f.player.setPaused(true) }
            val version = f.player.currentSourceVersion
            onSwing { f.controller.switchQuality(64) }
            f.await { !it.opening && it.effectiveQuality == 64 }
            assertEquals(version, f.player.currentSourceVersion)
            assertEquals(25.0, f.player.state.value.positionSeconds)
            assertTrue(f.player.state.value.paused)
            onSwing { f.controller.retry() }
            f.await { !it.opening }
            assertEquals(version, f.player.currentSourceVersion)
            assertEquals(Triple(13L, 64, true), calls.last())
        }
    }

    @Test fun `closing the old account controller cannot release a newer native source`() {
        Fixture(FakeSource(::details, ::resolved)).use { f ->
            onSwing { f.controller.open(card("BVA")) }
            f.await { !it.opening && it.details != null }
            val version = f.player.loadVersioned(PlaybackSource("file:///C:/other-account.mp4", title = "Other account"))
            onSwing { f.controller.close() }
            assertEquals(version, f.player.currentSourceVersion)
            assertEquals("Other account", f.player.state.value.sourceTitle)
        }
    }

    @Test fun `a newer source also prevents a delayed initial stream request from taking ownership`() {
        val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        Fixture(FakeSource(::details) { info, index, qn ->
            started.complete(Unit); withContext(NonCancellable) { gate.await() }; resolved(info, index, qn)
        }).use { f ->
            onSwing { f.controller.open(card("BVA")) }
            runBlocking { withTimeout(3_000) { started.await() } }
            val version = f.player.loadVersioned(PlaybackSource("file:///C:/foreign.mp4", title = "Foreign"))
            gate.complete(Unit); onSwing { }
            assertEquals(version, f.player.currentSourceVersion)
            assertEquals("Foreign", f.player.state.value.sourceTitle)
        }
    }

    @Test fun `details alone do not finish opening or enable queue navigation before the stream is installed`() {
        val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        Fixture(FakeSource(::details) { info, index, qn -> started.complete(Unit); gate.await(); resolved(info, index, qn) }).use { f ->
            onSwing { f.controller.openQueue(listOf(card("BVA", 11), card("BVB", 21))) }
            runBlocking { withTimeout(3_000) { started.await() } }
            assertEquals("BVA", f.controller.state.value.details?.bvid)
            assertTrue(f.controller.state.value.opening)
            assertFalse(f.controller.hasNext)
            assertEquals("BiliPai", f.player.state.value.sourceTitle)
            onSwing { f.controller.next() }
            assertEquals(0, f.controller.state.value.queueIndex)
            gate.complete(Unit)
            f.await { !it.opening }
            assertEquals("BVA-11", f.player.state.value.sourceTitle)
            assertTrue(f.controller.hasNext)
        }
    }

    @Test fun `pause cancels outstanding stream preparation and cannot start sound when it finally completes`() {
        val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        Fixture(FakeSource(::details) { info, index, qn -> started.complete(Unit); gate.await(); resolved(info, index, qn) }).use { f ->
            onSwing { f.controller.open(card("BVA")) }
            runBlocking { withTimeout(3_000) { started.await() } }
            onSwing { f.controller.pause() }
            val version = f.player.currentSourceVersion
            gate.complete(Unit); onSwing { }
            assertEquals(version, f.player.currentSourceVersion)
            assertEquals("BiliPai", f.player.state.value.sourceTitle)
            assertFalse(f.controller.state.value.opening)
        }
    }

    private class FakeSource(private val info: suspend (String) -> VideoDetails,
        private val resolve: suspend (VideoDetails, Int, Int) -> ResolvedSource) : DesktopPlaybackDataSource {
        override suspend fun videoDetails(bvid: String) = info(bvid)
        override suspend fun related(bvid: String) = emptyList<VideoCard>()
        override suspend fun playback(details: VideoDetails, index: Int, quality: Int, codecOverride: String?, forceRefresh: Boolean) = resolve(details, index, quality)
    }
    private class Fixture(source: DesktopPlaybackDataSource, mode: PlaybackMode = PlaybackMode.SEQUENTIAL) : AutoCloseable {
        val directory = Files.createTempDirectory("bilipai-video-controller-test-")
        val player = MpvPlayer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        val repository = DesktopRepository(DesktopSessionStore(directory.resolve("account.json"), persistent = false))
        val controller = DesktopPlaybackController(repository, player, null, null, DesktopLibrary(directory) { false },
            { PlayerPreferences(playbackMode = mode) }, scope, dataSource = source)
        fun await(predicate: (DesktopPlaybackState) -> Boolean) = runBlocking { withTimeout(3_000) { controller.state.first { predicate(it) } } }
        override fun close() {
            onSwing { controller.close() }; scope.cancel(); player.close()
            Files.list(directory).use { it.forEach { file -> Files.deleteIfExists(file) } }
            Files.deleteIfExists(directory)
        }
    }
    companion object {
        private fun onSwing(action: () -> Unit) {
            var failure: Throwable? = null
            SwingUtilities.invokeAndWait { try { action() } catch (caught: Throwable) { failure = caught } }
            failure?.let { throw it }
        }
    }
}

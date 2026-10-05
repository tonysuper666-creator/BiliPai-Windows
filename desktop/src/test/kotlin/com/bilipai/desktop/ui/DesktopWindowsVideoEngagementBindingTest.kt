package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol
import com.android.purebilibili.data.repository.DesktopOriginalVideoEngagementProtocol
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.usecase.TripleActionResult
import com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Explicit ordinary entry -> actual generated engagement VM, without command
 * metadata, network, native playback or real credentials. */
class DesktopWindowsVideoEngagementBindingTest {
    private class Harness(loggedIn: Boolean = true) : AutoCloseable {
        val folder = Files.createTempDirectory("ordinary-engagement-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val subject = VideoSubjectSnapshot("BVfixture", 70, 17, 33, "title", "", 60_000, 1)
        val source = DesktopOriginalVideoAcceptedPublication(
            PlaybackRequest.create(subject.bvid, aid = subject.aid, cid = subject.cid),
            OwnedPlaybackSourceSnapshot(1, PlaybackSource("https://example.invalid/fixture")))
        var accepted = source
        var account = 1
        var entry = true
        var admissionDepth = 0
        val follows = mutableListOf<Pair<Long, Boolean>>()
        val triples = mutableListOf<Long>()
        val guestApiCalls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val guestCsrfReads = AtomicInteger()
        val guestProtocolCheckpoints = AtomicInteger()
        var tripleResult: suspend () -> Result<TripleActionResult> = {
            Result.success(TripleActionResult(true, true, null, true))
        }
        val actions = object : VideoEngagementActions {
            override suspend fun toggleFollow(mid: Long, currentlyFollowing: Boolean): Result<Boolean> {
                assertEquals(0, admissionDepth)
                follows += mid to currentlyFollowing
                return Result.success(!currentlyFollowing)
            }
            override suspend fun doTripleAction(aid: Long): Result<TripleActionResult> {
                assertEquals(0, admissionDepth); triples += aid; return tripleResult()
            }
            override suspend fun toggleLike(aid: Long, currentlyLiked: Boolean, bvid: String) = error("not selected")
            override suspend fun toggleDislike(aid: Long, currentlyDisliked: Boolean, bvid: String) = error("not selected")
            override suspend fun toggleFavorite(aid: Long, currentlyFavorited: Boolean, bvid: String) = error("not selected")
            override suspend fun toggleWatchLater(aid: Long, currentlyInWatchLater: Boolean, bvid: String) = error("not selected")
            override suspend fun doCoin(aid: Long, count: Int, alsoLike: Boolean, bvid: String) = error("not selected")
        }
        fun owned() = entry && account == 1 && accepted === source
        fun admit(action: () -> Unit): Boolean {
            if (!owned()) return false
            admissionDepth++
            return try { if (!owned()) false else { action(); true } }
            finally { admissionDepth-- }
        }
        private fun guestActions(): VideoEngagementActions {
            val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader, arrayOf(BilibiliApi::class.java)) { _, method, _ ->
                guestApiCalls += method.name
                error("Guest protocol must not issue ${method.name}")
            } as BilibiliApi
            fun checkpoint() {
                guestProtocolCheckpoints.incrementAndGet()
                if (!owned()) throw CancellationException("Guest captured owner retired")
            }
            val protocol = DesktopOriginalVideoEngagementProtocol(api,
                { guestCsrfReads.incrementAndGet(); null }, { null }, { null }, { null }, ::checkpoint,
                { error("Guest cannot confirm follow") },
                DesktopOriginalFavoriteFolderProtocol(api, { null }, { null }, ::checkpoint))
            val analytics = object : DesktopOriginalVideoInteractionAnalytics {
                override fun logLike(videoId: String, isLiked: Boolean) = error("Guest success")
                override fun logDislike(videoId: String, isDisliked: Boolean) = error("Guest success")
                override fun logFavorite(videoId: String, isFavorited: Boolean) = error("Guest success")
                override fun logFollow(userId: String, isFollowed: Boolean) = error("Guest success")
                override fun logCoin(videoId: String, coinCount: Int) = error("Guest success")
            }
            return DefaultVideoEngagementActions(VideoInteractionUseCase(protocol, analytics))
        }
        val vm = VideoEngagementViewModel(DesktopOriginalVideoEngagementEnvironment(
            DesktopPluginContext(DesktopPluginStore(folder)), scope, if (loggedIn) actions else guestActions(), VideoCoinBalanceLoader { 0.0 },
            { entry && account == 1 }, { action -> if (entry && account == 1) { action(); true } else false }))
        val binding: DesktopWindowsVideoEngagementBinding
        init {
            vm.bindSubject(subject, VideoEngagementSeed(isLoggedIn = loggedIn))
            binding = DesktopWindowsVideoEngagementBinding(source, vm, subject, ::owned, ::admit)
        }
        suspend fun drain() = withTimeout(2_000) {
            while (scope.coroutineContext.job.children.any()) scope.coroutineContext.job.children.toList().joinAll()
        }
        override fun close() { binding.close(); scope.cancel(); folder.toFile().deleteRecursively() }
    }

    @Test fun manualTripleNeedsNoCommandMetadataAndKeepsOriginalConfirmedFields() = runBlocking {
        Harness().use { h ->
            assertTrue(h.binding.triple()); h.drain()
            assertEquals(listOf(17L), h.triples); assertTrue(h.follows.isEmpty())
            val value = h.vm.uiState.value
            assertTrue(value.isLiked); assertTrue(value.isFavorited); assertEquals(2, value.coinCount)
            assertTrue(value.tripleCelebrationVisible); assertFalse(value.isFollowing)
        }
    }
    @Test fun partialTripleFailureKeepsSuccessfulFieldsAndCanRetry() = runBlocking {
        Harness().use { h ->
            h.tripleResult = { Result.success(TripleActionResult(true, false, "余额不足", true)) }
            assertTrue(h.binding.triple()); h.drain()
            assertTrue(h.vm.uiState.value.isLiked); assertTrue(h.vm.uiState.value.isFavorited)
            assertEquals(0, h.vm.uiState.value.coinCount); assertFalse(h.vm.uiState.value.tripleCelebrationVisible)
            h.tripleResult = { Result.success(TripleActionResult(true, true, null, true)) }
            assertTrue(h.binding.triple()); h.drain(); assertEquals(2, h.triples.size)
            assertEquals(2, h.vm.uiState.value.coinCount)
        }
    }
    @Test fun followAndUnfollowUseSameOriginalOwnerAndNeverTriple() = runBlocking {
        Harness().use { h ->
            assertTrue(h.binding.toggleFollow()); h.drain(); assertTrue(h.vm.uiState.value.isFollowing)
            assertTrue(h.binding.toggleFollow()); h.drain(); assertFalse(h.vm.uiState.value.isFollowing)
            assertEquals(listOf(33L to false, 33L to true), h.follows); assertTrue(h.triples.isEmpty())
        }
    }
    @Test fun originalGuestGuardDoesNotSendAnyMutation() = runBlocking {
        Harness(false).use { h ->
            assertTrue(h.binding.triple()); assertTrue(h.binding.toggleFollow()); h.drain()
            assertTrue(h.triples.isEmpty()); assertTrue(h.follows.isEmpty())
            assertEquals(2, h.guestCsrfReads.get()); assertTrue(h.guestProtocolCheckpoints.get() >= 2)
            assertTrue(h.guestApiCalls.isEmpty())
            assertFalse(h.vm.uiState.value.isLiked); assertFalse(h.vm.uiState.value.isFollowing)
            assertFalse(h.vm.uiState.value.isFavorited); assertEquals(0, h.vm.uiState.value.coinCount)
        }
    }
    @Test fun accountOrSameValueAcceptedReplacementBeforeClickRejects() = runBlocking {
        for (replace in listOf<(Harness) -> Unit>(
            { it.account = 2 }, { it.accepted = DesktopOriginalVideoAcceptedPublication(it.source.request, it.source.nativeSource) },
            { it.entry = false }, { it.binding.close() })) Harness().use { h ->
            replace(h); assertFalse(h.binding.triple()); assertFalse(h.binding.toggleFollow()); h.drain()
            assertTrue(h.triples.isEmpty()); assertTrue(h.follows.isEmpty())
        }
    }
    @Test fun acceptedReplacementAcrossAwaitCannotCommitTripleSuccess() = runBlocking {
        Harness().use { h ->
            val result = CompletableDeferred<Result<TripleActionResult>>()
            h.tripleResult = { result.await() }
            assertTrue(h.binding.triple()); assertEquals(listOf(17L), h.triples)
            h.accepted = DesktopOriginalVideoAcceptedPublication(h.source.request, h.source.nativeSource)
            result.complete(Result.success(TripleActionResult(true, true, null, true))); h.drain()
            assertFalse(h.vm.uiState.value.isLiked); assertFalse(h.vm.uiState.value.isFavorited)
            assertFalse(h.vm.uiState.value.tripleCelebrationVisible); assertTrue(h.follows.isEmpty())
        }
    }
    @Test fun cancelledCallerLeavesOriginalStateUnmodified() = runBlocking {
        Harness().use { h ->
            h.tripleResult = { throw CancellationException("fixture cancel") }
            assertTrue(h.binding.triple()); h.drain()
            assertFalse(h.vm.uiState.value.isLiked); assertFalse(h.vm.uiState.value.isFavorited)
            assertFalse(h.vm.uiState.value.tripleCelebrationVisible)
        }
    }
}

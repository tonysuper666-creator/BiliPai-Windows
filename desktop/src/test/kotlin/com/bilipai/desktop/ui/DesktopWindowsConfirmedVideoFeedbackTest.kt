package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.usecase.TripleActionResult
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

/** Actual existing generated VM + existing presentation port. These tests do
 * not prove the unmounted decorative native host or animation raster output. */
class DesktopWindowsConfirmedVideoFeedbackTest {
    private class Harness : AutoCloseable {
        val folder = Files.createTempDirectory("confirmed-feedback-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val subject = VideoSubjectSnapshot("BVfixture", 70, 17, 33, "title", "", 60_000, 1)
        val source = DesktopOriginalVideoAcceptedPublication(
            PlaybackRequest.create(subject.bvid, aid = subject.aid, cid = subject.cid),
            OwnedPlaybackSourceSnapshot(1, PlaybackSource("https://example.invalid/fixture")))
        var accepted = source
        var account = 1
        var entry = true
        var depth = 0
        var like: suspend () -> Result<Boolean> = { Result.success(true) }
        var triple: suspend () -> Result<TripleActionResult> = { Result.success(TripleActionResult(true, true, null, true)) }
        val actions = object : VideoEngagementActions {
            override suspend fun toggleLike(aid: Long, currentlyLiked: Boolean, bvid: String): Result<Boolean> {
                assertEquals(0, depth, "Protocol work must stay outside admission")
                return like()
            }
            override suspend fun doTripleAction(aid: Long, coinCount: Int): Result<TripleActionResult> {
                assertEquals(0, depth)
                return triple()
            }
            override suspend fun toggleFollow(mid: Long, currentlyFollowing: Boolean) = error("not selected")
            override suspend fun toggleDislike(aid: Long, currentlyDisliked: Boolean, bvid: String) = error("not selected")
            override suspend fun toggleFavorite(aid: Long, currentlyFavorited: Boolean, bvid: String) = error("not selected")
            override suspend fun toggleWatchLater(aid: Long, currentlyInWatchLater: Boolean, bvid: String) = error("not selected")
            override suspend fun doCoin(aid: Long, count: Int, alsoLike: Boolean, bvid: String) = error("not selected")
        }
        fun owned() = entry && account == 1 && accepted === source
        fun admit(action: () -> Unit): Boolean {
            if (!owned()) return false
            depth++
            return try { if (!owned()) false else { action(); true } }
            finally { depth-- }
        }
        val presentation = DesktopOriginalVideoEngagementPresentation(::owned, ::admit)
        val vm = VideoEngagementViewModel(DesktopOriginalVideoEngagementEnvironment(
            DesktopPluginContext(DesktopPluginStore(folder)), scope, actions, VideoCoinBalanceLoader { 0.0 },
            { entry && account == 1 }, { action -> if (entry && account == 1) { action(); true } else false }))
        init { vm.bindSubject(subject, VideoEngagementSeed(isLoggedIn = true)) }
        suspend fun drain() = withTimeout(2_000) {
            while (scope.coroutineContext.job.children.any()) scope.coroutineContext.job.children.toList().joinAll()
        }
        override fun close() { scope.cancel(); folder.toFile().deleteRecursively() }
    }

    @Test fun onlyConfirmedLikeCreatesAnIdAndOldCompletionCannotDismissTheSuccessor() = runBlocking {
        Harness().use { h ->
            h.like = { Result.success(false) }
            h.vm.toggleLikeWithDesktopPresentation(presentation = h.presentation); h.drain()
            assertFalse(h.vm.uiState.value.likeBurstVisible); assertEquals(0L, h.vm.uiState.value.likeBurstId)
            h.like = { Result.success(true) }
            h.vm.toggleLikeWithDesktopPresentation(presentation = h.presentation); h.drain()
            val first = h.vm.uiState.value.likeBurstId
            assertTrue(h.vm.uiState.value.likeBurstVisible); assertEquals(1L, first)
            h.vm.toggleLikeWithDesktopPresentation(presentation = h.presentation); h.drain()
            val second = h.vm.uiState.value.likeBurstId
            assertEquals(first + 1, second)
            assertTrue(h.presentation.admit { h.vm.dismissLikeBurst(first) })
            assertTrue(h.vm.uiState.value.likeBurstVisible)
            assertTrue(h.presentation.admit { h.vm.dismissLikeBurst(second) })
            assertFalse(h.vm.uiState.value.likeBurstVisible)
        }
        Unit
    }

    @Test fun partialTriplePublishesOnlyConfirmedFieldsAndExactCompletionMarksTheFullResult() = runBlocking {
        Harness().use { h ->
            h.triple = { Result.success(TripleActionResult(true, false, "余额不足", true)) }
            h.vm.doTripleAction(presentation = h.presentation); h.drain()
            assertTrue(h.vm.uiState.value.isLiked); assertTrue(h.vm.uiState.value.isFavorited)
            assertEquals(0, h.vm.uiState.value.coinCount); assertFalse(h.vm.uiState.value.tripleCelebrationVisible)
            val partial = h.vm.uiState.value.tripleCelebrationId
            h.triple = { Result.success(TripleActionResult(true, true, null, true)) }
            h.vm.doTripleAction(presentation = h.presentation); h.drain()
            val full = h.vm.uiState.value.tripleCelebrationId
            assertTrue(full > partial); assertTrue(h.vm.uiState.value.tripleCelebrationVisible)
            assertTrue(h.presentation.admit { h.vm.completeTripleCelebration(partial) })
            assertTrue(h.vm.uiState.value.tripleCelebrationVisible); assertFalse(h.vm.uiState.value.tripleCelebrationFinished)
            assertTrue(h.presentation.admit { h.vm.completeTripleCelebration(full) })
            assertFalse(h.vm.uiState.value.tripleCelebrationVisible); assertTrue(h.vm.uiState.value.tripleCelebrationFinished)
        }
        Unit
    }

    @Test fun acceptedReplacementOrAccountChangeAcrossAwaitCannotConfirmLike() = runBlocking {
        for (retire in listOf<(Harness) -> Unit>(
            { it.account = 2 },
            { it.accepted = DesktopOriginalVideoAcceptedPublication(it.source.request, it.source.nativeSource) },
        )) Harness().use { h ->
            val reply = CompletableDeferred<Result<Boolean>>()
            h.like = { reply.await() }
            h.vm.toggleLikeWithDesktopPresentation(presentation = h.presentation)
            assertEquals(0L, h.vm.uiState.value.likeBurstId)
            retire(h)
            reply.complete(Result.success(true)); h.drain()
            assertFalse(h.vm.uiState.value.isLiked); assertFalse(h.vm.uiState.value.likeBurstVisible)
            assertEquals(0L, h.vm.uiState.value.likeBurstId)
        }
        Unit
    }

    @Test fun retiredNativePresentationCannotCompleteAnExistingCelebration() = runBlocking {
        Harness().use { h ->
            h.vm.doTripleAction(presentation = h.presentation); h.drain()
            val id = h.vm.uiState.value.tripleCelebrationId
            assertTrue(h.vm.uiState.value.tripleCelebrationVisible)
            h.accepted = DesktopOriginalVideoAcceptedPublication(h.source.request, h.source.nativeSource)
            assertFalse(h.presentation.admit { h.vm.completeTripleCelebration(id) })
            assertTrue(h.vm.uiState.value.tripleCelebrationVisible); assertFalse(h.vm.uiState.value.tripleCelebrationFinished)
        }
        Unit
    }
}

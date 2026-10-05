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

/** Actual complete generated VM and the actual ordinary/command bindings.
 * No rendering, native playback, protocol network or real profile is used. */
class DesktopWindowsVideoFeedbackOriginTest {
    private class Harness : AutoCloseable {
        val folder = Files.createTempDirectory("feedback-origin-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val subject = VideoSubjectSnapshot("BVfixture", 70, 17, 33, "title", "", 60_000, 1)
        val source = DesktopOriginalVideoAcceptedPublication(
            PlaybackRequest.create(subject.bvid, aid = subject.aid, cid = subject.cid),
            OwnedPlaybackSourceSnapshot(1, PlaybackSource("https://example.invalid/fixture")))
        var accepted = source
        var account = 1
        var entry = true
        var admissionDepth = 0
        var like: suspend () -> Result<Boolean> = { Result.success(true) }
        var triple: suspend () -> Result<TripleActionResult> = { Result.success(TripleActionResult(true, true, null, true)) }
        val actions = object : VideoEngagementActions {
            override suspend fun toggleLike(aid: Long, currentlyLiked: Boolean, bvid: String): Result<Boolean> {
                assertEquals(0, admissionDepth); return like()
            }
            override suspend fun doTripleAction(aid: Long): Result<TripleActionResult> {
                assertEquals(0, admissionDepth); return triple()
            }
            override suspend fun doCoin(aid: Long, count: Int, alsoLike: Boolean, bvid: String): Result<Boolean> {
                assertEquals(0, admissionDepth); return Result.success(true)
            }
            override suspend fun toggleFollow(mid: Long, currentlyFollowing: Boolean) = error("not selected")
            override suspend fun toggleDislike(aid: Long, currentlyDisliked: Boolean, bvid: String) = error("not selected")
            override suspend fun toggleFavorite(aid: Long, currentlyFavorited: Boolean, bvid: String) = error("not selected")
            override suspend fun toggleWatchLater(aid: Long, currentlyInWatchLater: Boolean, bvid: String) = error("not selected")
        }
        // Account intentionally lives in final admission as well as VM ownership:
        // a coarse source predicate alone cannot validate a rendered receipt.
        fun currentSource(expected: DesktopOriginalVideoAcceptedPublication) = entry && accepted === expected
        fun admit(expected: DesktopOriginalVideoAcceptedPublication, action: () -> Unit): Boolean {
            if (!currentSource(expected) || account != 1) return false
            admissionDepth++
            return try { if (!currentSource(expected) || account != 1) false else { action(); true } }
            finally { admissionDepth-- }
        }
        val vm = VideoEngagementViewModel(DesktopOriginalVideoEngagementEnvironment(
            DesktopPluginContext(DesktopPluginStore(folder)), scope, actions, VideoCoinBalanceLoader { 0.0 },
            { entry && account == 1 }, { action -> if (entry && account == 1) { action(); true } else false }))
        fun binding(expected: DesktopOriginalVideoAcceptedPublication = source) = DesktopWindowsVideoEngagementBinding(
            expected, vm, subject, { currentSource(expected) }, { action -> admit(expected, action) })
        val ordinary: DesktopWindowsVideoEngagementBinding
        val command: DesktopWindowsCommandAttentionBinding
        init {
            vm.bindSubject(subject, VideoEngagementSeed(isLoggedIn = true))
            ordinary = binding()
            command = DesktopWindowsCommandAttentionBinding(source, vm, subject,
                { currentSource(source) }, { action -> admit(source, action) })
        }
        suspend fun drain() = withTimeout(2_000) {
            while (scope.coroutineContext.job.children.any()) scope.coroutineContext.job.children.toList().joinAll()
        }
        override fun close() { ordinary.close(); command.close(); scope.cancel(); folder.toFile().deleteRecursively() }
    }

    @Test fun sameSubjectAndNumericNativeVersionReplacementCannotShowOrFinishTheOldSource(): Unit = runBlocking {
        Harness().use { h ->
            assertTrue(h.ordinary.like()); h.drain()
            val old = assertNotNull(h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.LIKE))
            val replacement = DesktopOriginalVideoAcceptedPublication(h.source.request, h.source.nativeSource)
            h.accepted = replacement
            h.binding(replacement).use { successor ->
                assertTrue(h.vm.uiState.value.likeBurstVisible, "Original state remains intact; receipt must enforce source")
                assertNull(successor.feedback(DesktopWindowsVideoFeedbackKind.LIKE))
                assertFalse(successor.completeFeedback(old)); assertFalse(h.ordinary.completeFeedback(old))
                assertTrue(successor.like()); h.drain()
                val next = assertNotNull(successor.feedback(DesktopWindowsVideoFeedbackKind.LIKE))
                assertNotSame(old, next); assertSame(replacement, next.source.accepted)
                assertFalse(successor.completeFeedback(old)); assertTrue(successor.completeFeedback(next))
                assertFalse(h.vm.uiState.value.likeBurstVisible)
            }
        }
    }

    @Test fun ordinaryAndCommandTripleCanUseDistinctPresentationsOnTheSameAcceptedSource(): Unit = runBlocking {
        Harness().use { h ->
            assertTrue(h.ordinary.like()); h.drain()
            val like = assertNotNull(h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.LIKE))
            assertSame(h.source, like.source.accepted)
            assertTrue(h.command.triple()); h.drain()
            val triple = assertNotNull(h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.TRIPLE))
            assertSame(h.source, triple.source.accepted)
            assertTrue(h.vm.uiState.value.tripleCelebrationVisible)
            assertTrue(h.ordinary.completeFeedback(triple))
            assertTrue(h.vm.uiState.value.tripleCelebrationFinished)
            assertNull(h.vm.uiState.value.desktopTripleFeedbackOrigin)
        }
    }

    @Test fun missingTypedStampNeverProducesRenderableSourceConfirmedMetadata(): Unit = runBlocking {
        Harness().use { h ->
            val unstamped = DesktopOriginalVideoEngagementPresentation({ h.currentSource(h.source) }) { action -> h.admit(h.source, action) }
            h.vm.toggleLikeWithDesktopPresentation(presentation = unstamped); h.drain()
            assertTrue(h.vm.uiState.value.isLiked); assertTrue(h.vm.uiState.value.likeBurstVisible)
            assertNull(h.vm.uiState.value.desktopLikeFeedbackOrigin)
            assertNull(h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.LIKE))
        }
    }

    @Test fun accountAdmissionRejectsReadingAndFinishingAnAlreadyConfirmedReceipt(): Unit = runBlocking {
        Harness().use { h ->
            assertTrue(h.ordinary.like()); h.drain()
            val origin = assertNotNull(h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.LIKE))
            h.account = 2
            assertTrue(h.ordinary.isOwned(), "Synthetic source predicate stays true to exercise the real final account permit")
            assertNull(h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.LIKE))
            assertFalse(h.ordinary.completeFeedback(origin))
            assertSame(origin, h.vm.uiState.value.desktopLikeFeedbackOrigin)
        }
    }

    @Test fun lateReplyAndCancelledCallerCannotPublishAFeedbackOrigin(): Unit = runBlocking {
        for (cancel in listOf(false, true)) Harness().use { h ->
            val reply = CompletableDeferred<Result<Boolean>>()
            h.like = { reply.await() }
            assertTrue(h.ordinary.like())
            if (cancel) h.scope.coroutineContext.job.children.single().cancel()
            else h.accepted = DesktopOriginalVideoAcceptedPublication(h.source.request, h.source.nativeSource)
            reply.complete(Result.success(true)); h.drain()
            assertFalse(h.vm.uiState.value.isLiked); assertNull(h.vm.uiState.value.desktopLikeFeedbackOrigin)
        }
    }

    @Test fun partialTripleAndOldCompletionCannotCompleteASuccessorConfirmedInstance(): Unit = runBlocking {
        Harness().use { h ->
            h.triple = { Result.success(TripleActionResult(true, false, "余额不足", true)) }
            assertTrue(h.ordinary.triple()); h.drain()
            assertTrue(h.vm.uiState.value.isLiked); assertTrue(h.vm.uiState.value.isFavorited)
            assertFalse(h.vm.uiState.value.tripleCelebrationVisible)
            assertNull(h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.TRIPLE))
            h.triple = { Result.success(TripleActionResult(true, true, null, true)) }
            assertTrue(h.ordinary.triple()); h.drain()
            val first = assertNotNull(h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.TRIPLE))
            assertTrue(h.ordinary.triple()); h.drain()
            val second = assertNotNull(h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.TRIPLE))
            assertTrue(second.instanceId > first.instanceId)
            assertFalse(h.ordinary.completeFeedback(first))
            assertTrue(h.vm.uiState.value.tripleCelebrationVisible); assertFalse(h.vm.uiState.value.tripleCelebrationFinished)
            assertTrue(h.ordinary.cancelFeedback(second))
            assertFalse(h.vm.uiState.value.tripleCelebrationVisible); assertFalse(h.vm.uiState.value.tripleCelebrationFinished)
        }
    }

    @Test fun confirmedCoinMaidUsesTheSameSourceAndOriginalDismissal(): Unit = runBlocking {
        Harness().use { h ->
            assertTrue(h.ordinary.coin(1, false)); h.drain()
            val coin = assertNotNull(h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.COIN))
            assertSame(h.source, coin.source.accepted); assertEquals(VideoMaidAction.COIN, h.vm.uiState.value.maidAction)
            assertEquals(1, h.vm.uiState.value.coinCount)
            assertTrue(h.ordinary.completeFeedback(coin))
            assertNull(h.vm.uiState.value.maidAction); assertNull(h.vm.uiState.value.desktopMaidFeedbackOrigin)
        }
    }
}

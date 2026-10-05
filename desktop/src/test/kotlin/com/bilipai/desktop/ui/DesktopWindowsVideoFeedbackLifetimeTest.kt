package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.usecase.TripleActionResult
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import kotlin.test.*

/** Complete generated owner and real ordinary/command consumption. No native
 * window, remote protocol, default settings path or account is constructed. */
class DesktopWindowsVideoFeedbackLifetimeTest {
    private class Harness : AutoCloseable {
        val folder = Files.createTempDirectory("feedback-lifetime-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val subject = VideoSubjectSnapshot("BVfixture", 70, 17, 33, "title", "", 60_000, 1)
        val source = DesktopOriginalVideoAcceptedPublication(
            PlaybackRequest.create(subject.bvid, aid = subject.aid, cid = subject.cid),
            OwnedPlaybackSourceSnapshot(1, PlaybackSource("https://example.invalid/fixture")))
        var accepted = source
        var account = 1
        var entry = true
        var presentationAlive = true
        var interactive = true
        var admissionDepth = 0
        var likeCalls = 0
        var tripleCalls = 0
        var like: suspend () -> Result<Boolean> = { Result.success(true) }
        var triple: suspend () -> Result<TripleActionResult> = { Result.success(TripleActionResult(true, true, null, true)) }
        val actions = object : VideoEngagementActions {
            override suspend fun toggleLike(aid: Long, currentlyLiked: Boolean, bvid: String): Result<Boolean> {
                assertEquals(0, admissionDepth); likeCalls++; return like()
            }
            override suspend fun doTripleAction(aid: Long): Result<TripleActionResult> {
                assertEquals(0, admissionDepth); tripleCalls++; return triple()
            }
            override suspend fun doCoin(aid: Long, count: Int, alsoLike: Boolean, bvid: String): Result<Boolean> {
                assertEquals(0, admissionDepth); return Result.success(true)
            }
            override suspend fun toggleFollow(mid: Long, currentlyFollowing: Boolean) = error("not selected")
            override suspend fun toggleDislike(aid: Long, currentlyDisliked: Boolean, bvid: String) = error("not selected")
            override suspend fun toggleFavorite(aid: Long, currentlyFavorited: Boolean, bvid: String) = error("not selected")
            override suspend fun toggleWatchLater(aid: Long, currentlyInWatchLater: Boolean, bvid: String) = error("not selected")
        }
        fun lifetime(expected: DesktopOriginalVideoAcceptedPublication = source): Boolean =
            entry && presentationAlive && account == 1 && accepted === expected
        fun admit(expected: DesktopOriginalVideoAcceptedPublication, action: () -> Unit): Boolean {
            if (!lifetime(expected)) return false
            admissionDepth++
            return try { if (!lifetime(expected)) false else { action(); true } }
            finally { admissionDepth-- }
        }
        val vm = VideoEngagementViewModel(DesktopOriginalVideoEngagementEnvironment(
            DesktopPluginContext(DesktopPluginStore(folder)), scope, actions, VideoCoinBalanceLoader { 0.0 },
            { entry && account == 1 }, { action -> if (entry && account == 1) { action(); true } else false }))
        val ordinary: DesktopWindowsVideoEngagementBinding
        val command: DesktopWindowsCommandAttentionBinding
        private val ownedBindings = mutableListOf<AutoCloseable>()
        fun binding(expected: DesktopOriginalVideoAcceptedPublication = source) =
            DesktopWindowsVideoEngagementBinding(expected, vm, subject,
                { interactive && lifetime(expected) }, { lifetime(expected) }, { action -> admit(expected, action) })
                .also { ownedBindings += it }
        init {
            vm.bindSubject(subject, VideoEngagementSeed(isLoggedIn = true))
            ordinary = binding()
            command = DesktopWindowsCommandAttentionBinding(source, vm, subject,
                { interactive && lifetime() }, { lifetime() },
                { action -> admit(source, action) },
                { action -> interactive && admit(source, action) }).also { ownedBindings += it }
        }
        suspend fun drain(): Unit = withTimeout(2_000) {
            while (scope.coroutineContext.job.children.any()) scope.coroutineContext.job.children.toList().joinAll()
        }
        override fun close() { ownedBindings.forEach { it.close() }; scope.cancel(); folder.toFile().deleteRecursively() }
    }

    @Test fun confirmedOrdinaryLikeKeepsItsInstanceAcrossHideAndRejectsNewHiddenActions(): Unit = runBlocking {
        Harness().use { h ->
            assertTrue(h.ordinary.like()); h.drain()
            val origin = assertNotNull(h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.LIKE))
            h.interactive = false
            assertSame(origin, h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.LIKE))
            assertFalse(h.ordinary.like()); assertFalse(h.ordinary.triple()); assertEquals(1, h.likeCalls)
            assertEquals(0, h.tripleCalls)
            h.interactive = true
            assertSame(origin, h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.LIKE))
            assertEquals(origin.instanceId, h.vm.uiState.value.likeBurstId)
            assertTrue(h.ordinary.completeFeedback(origin)); assertFalse(h.vm.uiState.value.likeBurstVisible)
        }
    }

    @Test fun actualPendingLikeCanConfirmWhileHiddenAndRestoresTheSameReceipt(): Unit = runBlocking {
        Harness().use { h ->
            val reply = CompletableDeferred<Result<Boolean>>()
            h.like = { reply.await() }
            assertTrue(h.ordinary.like()); assertEquals(1, h.likeCalls)
            assertNull(h.vm.uiState.value.desktopLikeFeedbackOrigin)
            h.interactive = false
            reply.complete(Result.success(true)); h.drain()
            assertTrue(h.vm.uiState.value.isLiked)
            val origin = assertNotNull(h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.LIKE))
            assertSame(h.source, origin.source.accepted)
            assertFalse(h.ordinary.like()); assertEquals(1, h.likeCalls)
            h.interactive = true
            assertSame(origin, h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.LIKE))
            assertTrue(h.ordinary.completeFeedback(origin))
        }
    }

    @Test fun commandRequestAndConfirmedTripleOutliveItsTemporarilyHiddenPopup(): Unit = runBlocking {
        Harness().use { h ->
            val reply = CompletableDeferred<Result<TripleActionResult>>()
            h.triple = { reply.await() }
            assertTrue(h.command.triple()); assertEquals(1, h.tripleCalls)
            h.interactive = false; h.command.close()
            assertFalse(h.command.triple())
            reply.complete(Result.success(TripleActionResult(true, true, null, true))); h.drain()
            val origin = assertNotNull(h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.TRIPLE))
            assertTrue(h.vm.uiState.value.tripleCelebrationVisible)
            h.interactive = true
            assertSame(origin, h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.TRIPLE))
            assertTrue(h.ordinary.completeFeedback(origin))
            assertTrue(h.vm.uiState.value.tripleCelebrationFinished)
            assertEquals(1, h.tripleCalls)
        }
    }

    @Test fun sourceAccountEntryAndPresentationRetirementStillRejectActualDelayedReplies(): Unit = runBlocking {
        for (retirement in listOf("source", "account", "entry", "presentation")) Harness().use { h ->
            val reply = CompletableDeferred<Result<Boolean>>()
            h.like = { reply.await() }
            assertTrue(h.ordinary.like())
            h.interactive = false
            when (retirement) {
                "source" -> h.accepted = DesktopOriginalVideoAcceptedPublication(h.source.request, h.source.nativeSource)
                "account" -> h.account = 2
                "entry" -> h.entry = false
                "presentation" -> h.presentationAlive = false
            }
            reply.complete(Result.success(true)); h.drain()
            assertFalse(h.vm.uiState.value.isLiked, retirement)
            assertNull(h.vm.uiState.value.desktopLikeFeedbackOrigin, retirement)
        }
    }

    @Test fun actualCancelledCallerCannotConfirmEvenWhenSourceSurvivesHide(): Unit = runBlocking {
        Harness().use { h ->
            val reply = CompletableDeferred<Result<Boolean>>()
            h.like = { reply.await() }
            assertTrue(h.ordinary.like())
            h.interactive = false; h.scope.coroutineContext.job.children.single().cancel()
            reply.complete(Result.success(true)); h.drain()
            assertFalse(h.vm.uiState.value.isLiked); assertNull(h.vm.uiState.value.desktopLikeFeedbackOrigin)
        }
    }

    @Test fun aRetiredPresentationCannotResurrectAConfirmedOriginOnSameIdentityReturn(): Unit = runBlocking {
        Harness().use { h ->
            assertTrue(h.ordinary.like()); h.drain()
            val old = assertNotNull(h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.LIKE))
            h.presentationAlive = false; h.ordinary.close()
            h.presentationAlive = true
            val successor = h.binding()
            assertNull(successor.feedback(DesktopWindowsVideoFeedbackKind.LIKE))
            assertFalse(successor.completeFeedback(old))
            assertTrue(successor.like()); h.drain()
            val next = assertNotNull(successor.feedback(DesktopWindowsVideoFeedbackKind.LIKE))
            assertNotSame(old, next); assertFalse(successor.completeFeedback(old))
            assertTrue(successor.completeFeedback(next))
        }
    }

    @Test fun staleCompletionCannotClearANewInstanceCreatedAfterRestore(): Unit = runBlocking {
        Harness().use { h ->
            assertTrue(h.ordinary.like()); h.drain()
            val first = assertNotNull(h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.LIKE))
            h.interactive = false; assertSame(first, h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.LIKE))
            h.interactive = true; assertTrue(h.ordinary.like()); h.drain()
            val second = assertNotNull(h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.LIKE))
            assertTrue(second.instanceId > first.instanceId)
            assertFalse(h.ordinary.completeFeedback(first))
            assertSame(second, h.vm.uiState.value.desktopLikeFeedbackOrigin)
            assertTrue(h.ordinary.completeFeedback(second))
        }
    }

    @Test fun originalPartialTripleRemainsUncelebratedWhenItCompletesInBackground(): Unit = runBlocking {
        Harness().use { h ->
            val reply = CompletableDeferred<Result<TripleActionResult>>()
            h.triple = { reply.await() }
            assertTrue(h.ordinary.triple()); h.interactive = false
            reply.complete(Result.success(TripleActionResult(true, false, "余额不足", true))); h.drain()
            assertTrue(h.vm.uiState.value.isLiked); assertTrue(h.vm.uiState.value.isFavorited)
            assertFalse(h.vm.uiState.value.tripleCelebrationVisible)
            assertNull(h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.TRIPLE))
        }
    }

    @Test fun legacyFiveArgumentCommandCloseStillRetiresItsOwnPendingRequest(): Unit = runBlocking {
        Harness().use { h ->
            val reply = CompletableDeferred<Result<TripleActionResult>>()
            h.triple = { reply.await() }
            val legacy = DesktopWindowsCommandAttentionBinding(h.source, h.vm, h.subject,
                { h.lifetime() }, { action -> h.admit(h.source, action) })
            try {
                assertTrue(legacy.triple()); legacy.close()
                reply.complete(Result.success(TripleActionResult(true, true, null, true))); h.drain()
                assertFalse(h.vm.uiState.value.tripleCelebrationVisible)
                assertNull(h.vm.uiState.value.desktopTripleFeedbackOrigin)
            } finally { legacy.close() }
        }
    }

    @Test fun confirmedQueuedMessageUsesTheSameLifetimeAtFinalConsumption(): Unit = runBlocking {
        Harness().use { h ->
            assertTrue(h.ordinary.like()); h.drain(); h.interactive = false
            val event = withTimeout(2_000) { h.vm.events.first() }
            assertIs<VideoEngagementEvent.Message>(event)
            assertNotNull(h.ordinary.feedback(DesktopWindowsVideoFeedbackKind.LIKE))
        }
    }

    @Test fun aHiddenUnstartedPresentationCannotBorrowAValidSourceToBeginProtocolWork(): Unit = runBlocking {
        Harness().use { h ->
            h.interactive = false
            val presentation = DesktopOriginalVideoEngagementPresentation(h.source, h.subject,
                { h.interactive && h.lifetime() }, { action -> h.interactive && h.admit(h.source, action) },
                { h.lifetime() }, { action -> h.admit(h.source, action) })
            h.vm.toggleLikeWithDesktopPresentation(presentation = presentation); h.drain()
            assertEquals(0, h.likeCalls)
            assertFalse(h.vm.uiState.value.isLiked); assertNull(h.vm.uiState.value.desktopLikeFeedbackOrigin)
        }
    }
}

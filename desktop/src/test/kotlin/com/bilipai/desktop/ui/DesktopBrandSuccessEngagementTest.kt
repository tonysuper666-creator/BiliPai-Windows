package com.bilipai.desktop.ui

import com.android.purebilibili.core.events.*
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.SimpleApiResponse
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.test.*

/** Full original VM -> existing binding -> original protocol -> confirmed
 * callback. Synthetic API responses only; no network, DLL, UI or real account. */
class DesktopBrandSuccessEngagementTest {
    private class Harness {
        val directory = Files.createTempDirectory("brand-engagement-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val subject = VideoSubjectSnapshot("BVfixture", 70, 17, 33, "title", "", 60_000, 1)
        val source = DesktopOriginalVideoAcceptedPublication(PlaybackRequest.create(subject.bvid, aid = subject.aid, cid = subject.cid),
            OwnedPlaybackSourceSnapshot(1, PlaybackSource("https://fixture.invalid/video")))
        @Volatile var accepted = source
        @Volatile var interactive = true
        @Volatile var account = 1
        var depth = 0
        val changes = CopyOnWriteArrayList<FollowStateChange>()
        val events = CopyOnWriteArrayList<BrandSuccessFeedback>()
        val bus = BrandSuccessEvents { scope.isActive }
        var response: (Array<out Any?>) -> Any = { SimpleApiResponse() }
        fun lifetime() = scope.isActive && account == 1 && accepted === source
        fun admit(block: () -> Unit): Boolean {
            if (!lifetime()) return false
            depth++
            return try { if (lifetime()) { block(); true } else false } finally { depth-- }
        }
        fun checkpoint() {
            if (!lifetime()) throw CancellationException("Fixture accepted lifetime retired")
        }
        private val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader, arrayOf(BilibiliApi::class.java)) { _, method, args ->
            assertEquals("modifyRelation", method.name); assertEquals(0, depth)
            response(args.orEmpty())
        } as BilibiliApi
        private val protocol = DesktopOriginalVideoEngagementProtocol(api, { "fixture-csrf" }, { 1L }, { null }, { null }, ::checkpoint,
            { change -> assertNotNull(DesktopOriginalVideoEngagementPresentation.capture()); changes += change; DesktopOriginalVideoEngagementPresentation.confirmBrandFollow(change.isFollowing) },
            DesktopOriginalFavoriteFolderProtocol(api, { 1L }, { "fixture-csrf" }, ::checkpoint))
        private val analytics = object : DesktopOriginalVideoInteractionAnalytics {
            override fun logFollow(userId: String, isFollowed: Boolean) {}
            override fun logLike(videoId: String, isLiked: Boolean) = error("Unexpected like")
            override fun logDislike(videoId: String, isDisliked: Boolean) = error("Unexpected dislike")
            override fun logFavorite(videoId: String, isFavorited: Boolean) = error("Unexpected favorite")
            override fun logCoin(videoId: String, coinCount: Int) = error("Unexpected coin")
        }
        val vm = VideoEngagementViewModel(DesktopOriginalVideoEngagementEnvironment(
            DesktopPluginContext(DesktopPluginStore(directory)), scope,
            DefaultVideoEngagementActions(VideoInteractionUseCase(protocol, analytics)), VideoCoinBalanceLoader { 0.0 },
            ::lifetime, ::admit))
        val binding: DesktopWindowsVideoEngagementBinding
        init {
            vm.bindSubject(subject, VideoEngagementSeed(isLoggedIn = true))
            binding = DesktopWindowsVideoEngagementBinding(source, vm, subject,
                { interactive && lifetime() }, ::lifetime, ::admit).also { it.mountBrandFeedback(bus) }
        }
        suspend fun drain() = withTimeout(2_000) {
            while (scope.coroutineContext.job.children.any()) scope.coroutineContext.job.children.toList().joinAll()
        }
        suspend fun close() {
            binding.close(); bus.close(); scope.cancel(); scope.coroutineContext.job.join()
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun actualBindingConfirmedFollowAndUnfollowUseOriginatingPresentation(): Unit = runBlocking {
        val h = Harness()
        val collect = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { h.bus.events.collect { h.events += it } }
        try {
            assertTrue(h.binding.toggleFollow()); h.drain()
            assertTrue(h.vm.uiState.value.isFollowing)
            assertTrue(h.binding.toggleFollow()); h.drain()
            assertFalse(h.vm.uiState.value.isFollowing)
            assertEquals(listOf(true, false), h.changes.map { it.isFollowing })
            assertEquals(listOf(BrandSuccessKind.FOLLOW, BrandSuccessKind.UNFOLLOW), h.events.map { it.kind })
        } finally { collect.cancelAndJoin(); h.close() }
    }

    @Test fun actualApiFailureCannotProduceSuccessFeedbackOrStateEvent(): Unit = runBlocking {
        val h = Harness()
        val collect = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { h.bus.events.collect { h.events += it } }
        try {
            h.response = { SimpleApiResponse(code = -1, message = "synthetic denied") }
            assertTrue(h.binding.toggleFollow()); h.drain()
            assertFalse(h.vm.uiState.value.isFollowing)
            assertTrue(h.changes.isEmpty()); assertTrue(h.events.isEmpty())
        } finally { collect.cancelAndJoin(); h.close() }
    }

    @Test fun pendingRealProtocolCanConfirmWhileHiddenButCannotStartAnotherAction(): Unit = runBlocking {
        val h = Harness()
        val collect = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { h.bus.events.collect { h.events += it } }
        try {
            val pending = CompletableDeferred<Continuation<Any?>>()
            h.response = { args ->
                @Suppress("UNCHECKED_CAST") val continuation = args.last() as Continuation<Any?>
                pending.complete(continuation); COROUTINE_SUSPENDED
            }
            assertTrue(h.binding.toggleFollow())
            val continuation = withTimeout(2_000) { pending.await() }
            h.interactive = false
            assertFalse(h.binding.toggleFollow())
            continuation.intercepted().resumeWith(Result.success(SimpleApiResponse())); h.drain()
            assertTrue(h.vm.uiState.value.isFollowing)
            assertEquals(BrandSuccessKind.FOLLOW, h.events.single().kind)
        } finally { collect.cancelAndJoin(); h.close() }
    }

    @Test fun sameValueNewAcceptedAcrossApiAwaitRejectsConfirmedFeedback(): Unit = runBlocking {
        val h = Harness()
        val collect = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { h.bus.events.collect { h.events += it } }
        try {
            val pending = CompletableDeferred<Continuation<Any?>>()
            h.response = { args ->
                @Suppress("UNCHECKED_CAST") val continuation = args.last() as Continuation<Any?>
                pending.complete(continuation); COROUTINE_SUSPENDED
            }
            assertTrue(h.binding.toggleFollow())
            val continuation = withTimeout(2_000) { pending.await() }
            h.accepted = DesktopOriginalVideoAcceptedPublication(h.source.request, h.source.nativeSource)
            continuation.intercepted().resumeWith(Result.success(SimpleApiResponse())); h.drain()
            assertFalse(h.vm.uiState.value.isFollowing)
            assertTrue(h.changes.isEmpty()); assertTrue(h.events.isEmpty())
        } finally { collect.cancelAndJoin(); h.close() }
    }
}

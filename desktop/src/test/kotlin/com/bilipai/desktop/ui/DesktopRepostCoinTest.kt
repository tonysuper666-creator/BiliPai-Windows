package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.test.*

/** Actual decoded detail -> generated seed/VM -> owned binding -> unchanged
 * usecase/protocol, with in-memory API replies only. No network/UI/account. */
class DesktopRepostCoinTest {
    private class Harness(copyright: Int = 2, initialCoins: Int = 0) {
        val directory = Files.createTempDirectory("repost-coin-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val subject = VideoSubjectSnapshot("BVfixture", 70, 17, 33, "title", "", 60_000, 1)
        val source = DesktopOriginalVideoAcceptedPublication(
            PlaybackRequest.create(subject.bvid, aid = subject.aid, cid = subject.cid),
            OwnedPlaybackSourceSnapshot(1, PlaybackSource("https://fixture.invalid/video")))
        @Volatile var accepted = source
        @Volatile var account = 1
        var depth = 0
        val calls = CopyOnWriteArrayList<String>()
        val coinAmounts = CopyOnWriteArrayList<Int>()
        val analyticsCoins = CopyOnWriteArrayList<Int>()
        var response: (String, Array<out Any?>) -> Any = { name, _ ->
            if (name == "getFavFolders") FavFolderResponse(data = FavFolderList(list = listOf(FavFolder(id = 9))))
            else SimpleApiResponse()
        }
        fun lifetime() = scope.isActive && account == 1 && accepted === source
        fun admit(block: () -> Unit): Boolean {
            if (!lifetime()) return false
            depth++
            return try { if (lifetime()) { block(); true } else false } finally { depth-- }
        }
        fun checkpoint() { if (!lifetime()) throw CancellationException("Fixture owner retired") }
        private val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader, arrayOf(BilibiliApi::class.java)) { _, method, arguments ->
            val args = arguments.orEmpty()
            assertEquals(0, depth); calls += method.name
            assertTrue(method.name in setOf("coinVideo", "likeVideo", "getFavFolders", "dealFavorite"), method.name)
            if (method.name == "coinVideo") {
                assertEquals(subject.aid, args[0]); coinAmounts += args[1] as Int
            }
            response(method.name, args)
        } as BilibiliApi
        private val protocol = DesktopOriginalVideoEngagementProtocol(api, { "fixture-csrf" }, { 1L }, { null }, { null },
            ::checkpoint, { error("Unexpected follow") },
            DesktopOriginalFavoriteFolderProtocol(api, { 1L }, { "fixture-csrf" }, ::checkpoint))
        private val analytics = object : DesktopOriginalVideoInteractionAnalytics {
            override fun logFollow(userId: String, isFollowed: Boolean) = error("Unexpected follow")
            override fun logLike(videoId: String, isLiked: Boolean) {}
            override fun logDislike(videoId: String, isDisliked: Boolean) = error("Unexpected dislike")
            override fun logFavorite(videoId: String, isFavorited: Boolean) {}
            override fun logCoin(videoId: String, coinCount: Int) { analyticsCoins += coinCount }
        }
        val vm = VideoEngagementViewModel(DesktopOriginalVideoEngagementEnvironment(
            DesktopPluginContext(DesktopPluginStore(directory)), scope,
            DefaultVideoEngagementActions(VideoInteractionUseCase(protocol, analytics)), VideoCoinBalanceLoader { 10.0 },
            ::lifetime, ::admit))
        val binding: DesktopWindowsVideoEngagementBinding
        init {
            val info = Json.decodeFromString<ViewInfo>("""{"copyright":$copyright}""")
            val loaded = VideoPlaybackUiState.Success(info = info, playUrl = "", isLoggedIn = true, coinCount = initialCoins)
            vm.bindSubject(subject, loaded.toEngagementSeed())
            binding = DesktopWindowsVideoEngagementBinding(source, vm, subject, ::lifetime, ::lifetime, ::admit)
        }
        suspend fun drain() = withTimeout(2_000) {
            while (scope.coroutineContext.job.children.any()) scope.coroutineContext.job.children.toList().joinAll()
        }
        suspend fun close() { binding.close(); scope.cancel(); scope.coroutineContext.job.join(); directory.toFile().deleteRecursively() }
    }

    @Test fun decodedCopyrightSeedsOnlyExplicitRepostAndRefreshKeepsWatchLaterSync(): Unit = runBlocking {
        for ((json, expected) in listOf("{}" to false, "{\"copyright\":0}" to false,
                "{\"copyright\":1}" to false, "{\"copyright\":2}" to true)) {
            val info = Json.decodeFromString<ViewInfo>(json)
            assertEquals(expected, info.isRepost)
            assertEquals(expected, VideoPlaybackUiState.Success(info, "").toEngagementSeed().isRepost)
        }
        val h = Harness()
        try {
            assertEquals(1, h.vm.uiState.value.coinLimit)
            h.vm.sync(VideoEngagementSeed(isLoggedIn = true, isRepost = true, isInWatchLater = true))
            assertTrue(h.vm.uiState.value.isInWatchLater)
            h.vm.sync(VideoEngagementSeed(isLoggedIn = true, isRepost = false, isInWatchLater = false))
            assertEquals(2, h.vm.uiState.value.coinLimit); assertFalse(h.vm.uiState.value.isInWatchLater)
            assertTrue(h.calls.isEmpty())
        } finally { h.close() }
    }

    @Test fun ordinaryCoinsRejectUnavailableChoicesAndSendOnlyTheRemainingAmountOnce(): Unit = runBlocking {
        for ((copyright, already, limit) in listOf(Triple(2, 0, 1), Triple(1, 1, 2))) {
            val h = Harness(copyright, already)
            try {
                h.binding.coin(0, false); h.binding.coin(2, false); h.drain(); assertTrue(h.calls.isEmpty())
                assertTrue(h.binding.coin(1, false)); h.drain()
                assertEquals(listOf("coinVideo"), h.calls.toList()); assertEquals(listOf(1), h.coinAmounts.toList())
                assertEquals(listOf(1), h.analyticsCoins.toList()); assertEquals(limit, h.vm.uiState.value.coinCount)
                h.binding.coin(1, false); h.vm.openCoinDialog(); h.drain()
                assertFalse(h.vm.uiState.value.coinDialogVisible); assertEquals(1, h.calls.size)
            } finally { h.close() }
        }
    }

    @Test fun tripleCarriesOriginalOrRepostCapThroughOneCoinCallAndVisualResult(): Unit = runBlocking {
        for ((copyright, already, cap) in listOf(Triple(1, 0, 2), Triple(1, 1, 2), Triple(2, 0, 1))) {
            val h = Harness(copyright, already)
            try {
                assertTrue(h.binding.triple()); h.drain()
                assertEquals(listOf("likeVideo", "coinVideo", "getFavFolders", "dealFavorite"), h.calls.toList())
                assertEquals(listOf(cap), h.coinAmounts.toList()); assertEquals(cap, h.vm.uiState.value.coinCount)
                assertTrue(h.vm.uiState.value.isLiked); assertTrue(h.vm.uiState.value.isFavorited)
                assertTrue(h.vm.uiState.value.tripleCelebrationVisible)
            } finally { h.close() }
        }
    }

    @Test fun repostLimitFailureKeepsPartialTripleAndDoesNotInventTwoCoinsOrSuccess(): Unit = runBlocking {
        val h = Harness()
        try {
            val normal = h.response
            h.response = { name, args -> if (name == "coinVideo") SimpleApiResponse(code = 34005) else normal(name, args) }
            assertTrue(h.binding.triple()); h.drain()
            assertEquals(listOf(1), h.coinAmounts.toList()); assertEquals(1, h.vm.uiState.value.coinCount)
            assertTrue(h.vm.uiState.value.isLiked); assertTrue(h.vm.uiState.value.isFavorited)
            assertFalse(h.vm.uiState.value.tripleCelebrationVisible)
            assertNull(h.vm.uiState.value.desktopTripleFeedbackOrigin)
        } finally { h.close() }
    }

    @Test fun failedOrCancelledCoinNeverRetriesOrPublishesSuccess(): Unit = runBlocking {
        for (cancel in listOf(false, true)) {
            val h = Harness()
            try {
                h.response = { _, _ -> if (cancel) throw CancellationException("fixture cancellation")
                    else SimpleApiResponse(code = -104, message = "fixture denied") }
                assertTrue(h.binding.coin(1, false)); h.drain()
                assertEquals(listOf("coinVideo"), h.calls.toList()); assertEquals(0, h.vm.uiState.value.coinCount)
                assertNull(h.vm.uiState.value.desktopMaidFeedbackOrigin); assertTrue(h.analyticsCoins.isEmpty())
            } finally { h.close() }
        }
    }

    @Test fun pendingCoinCannotPublishAcrossAccountAcceptedSourceOrSubjectReplacement(): Unit = runBlocking {
        for (retirement in 0..2) {
            val h = Harness()
            try {
                val pending = CompletableDeferred<Continuation<Any?>>()
                h.response = { _, args ->
                    @Suppress("UNCHECKED_CAST") val continuation = args.last() as Continuation<Any?>
                    pending.complete(continuation); COROUTINE_SUSPENDED
                }
                assertTrue(h.binding.coin(1, false))
                val continuation = withTimeout(2_000) { pending.await() }
                when (retirement) {
                    0 -> h.account = 2
                    1 -> h.accepted = DesktopOriginalVideoAcceptedPublication(h.source.request, h.source.nativeSource)
                    else -> h.vm.bindSubject(h.subject.copy(generation = 2), VideoEngagementSeed(isLoggedIn = true))
                }
                continuation.intercepted().resumeWith(Result.success(SimpleApiResponse())); h.drain()
                assertEquals(listOf("coinVideo"), h.calls.toList()); assertEquals(0, h.vm.uiState.value.coinCount)
                assertNull(h.vm.uiState.value.desktopMaidFeedbackOrigin)
            } finally { h.close() }
        }
    }
}

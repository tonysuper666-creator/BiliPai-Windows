package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.NavData
import com.android.purebilibili.data.model.response.NavResponse
import com.android.purebilibili.data.model.response.SimpleApiResponse
import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol
import com.android.purebilibili.data.repository.DesktopOriginalVideoEngagementProtocol
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.test.*

/** Real generated VM/binding/original loader/usecase/protocol. Only the API
 * replies and Root authority boundary are fixtures; no HTTP/native/UI/account.
 * Accepted replacements below do not execute NativeOwner quality/recovery. */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopWindowsCoinDialogRequestTest {
    private data class CoinCall(val aid: Long, val amount: Int, val selectLike: Int, val csrf: String)
    private data class Choice(val copyright: Int, val initial: Int, val amount: Int, val alsoLike: Boolean)

    private class PendingReply(private val continuation: Continuation<Any?>) {
        private val resumed = AtomicBoolean(false)
        fun complete(response: Any) {
            check(resumed.compareAndSet(false, true)) { "Fixture reply completed twice" }
            continuation.intercepted().resumeWith(Result.success(response))
        }
        fun cancelIfPending() {
            if (resumed.compareAndSet(false, true))
                continuation.intercepted().resumeWith(Result.failure(CancellationException("Fixture cleanup")))
        }
    }

    private class Harness(copyright: Int = 1, initialCoins: Int = 0, disliked: Boolean = false) {
        private val tempRoot = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val directory = Files.createTempDirectory(tempRoot, "coin-dialog-request-")
        val scheduler = TestCoroutineScheduler()
        private val failures = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler) +
            CoroutineExceptionHandler { _, failure -> failures += failure })
        val subject = VideoSubjectSnapshot("BVfixture", 70, 17, 33, "title", "", 60_000, 1)
        val source = DesktopOriginalVideoAcceptedPublication(
            PlaybackRequest.create(subject.bvid, aid = subject.aid, cid = subject.cid),
            OwnedPlaybackSourceSnapshot(1, PlaybackSource("https://fixture.invalid/video")))
        @Volatile var accepted = source
        @Volatile var account = 1
        @Volatile var entry = true
        @Volatile var interactive = true
        private val admissionLock = Any()
        val admissionDepth = AtomicInteger()
        val balanceStarts = AtomicInteger()
        val apiCalls = CopyOnWriteArrayList<String>()
        val coinCalls = CopyOnWriteArrayList<CoinCall>()
        val analyticsCoins = CopyOnWriteArrayList<Pair<String, Int>>()
        private val pendingReplies = CopyOnWriteArrayList<PendingReply>()
        private val navSignals = Channel<PendingReply>(Channel.UNLIMITED)
        private val bindings = mutableListOf<DesktopWindowsVideoEngagementBinding>()
        @Volatile var navResponse: (Array<out Any?>) -> Any = { nav(10.0) }
        @Volatile var coinResponse: (Array<out Any?>) -> Any = { SimpleApiResponse() }

        // Deliberately exclude accepted identity from the VM entry owner. Source
        // retirement must be rejected by the real request/presentation/binding.
        fun entryOwned() = scope.isActive && entry && account == 1
        private fun sourceOwned(expected: DesktopOriginalVideoAcceptedPublication) =
            entryOwned() && accepted === expected
        private fun admitEntry(action: () -> Unit): Boolean = synchronized(admissionLock) {
            if (!entryOwned()) false else {
                admissionDepth.incrementAndGet()
                try { action(); true } finally { admissionDepth.decrementAndGet() }
            }
        }
        private fun admitSource(expected: DesktopOriginalVideoAcceptedPublication, action: () -> Unit): Boolean {
            var applied = false
            return admitEntry { if (sourceOwned(expected)) { action(); applied = true } } && applied
        }
        private fun checkpoint() {
            if (!entryOwned()) throw CancellationException("Fixture account/entry retired")
        }
        @Suppress("UNCHECKED_CAST")
        private fun captureReply(args: Array<out Any?>): PendingReply =
            PendingReply(args.last() as Continuation<Any?>).also { pendingReplies += it }
        private val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader,
            arrayOf(BilibiliApi::class.java)) { _, method, arguments ->
            val args = arguments.orEmpty()
            assertEquals(0, admissionDepth.get(), "Actual API IO must stay outside entry admission")
            apiCalls += method.name
            when (method.name) {
                "getNavInfo" -> navResponse(args)
                "coinVideo" -> {
                    coinCalls += CoinCall(args[0] as Long, args[1] as Int, args[2] as Int, args[3] as String)
                    coinResponse(args)
                }
                else -> error("Unexpected fixture API: ${method.name}")
            }
        } as BilibiliApi
        private val protocol = com.android.purebilibili.data.repository.createTestVideoEngagementProtocol(api,
            { "fixture-csrf" }, { 1L }, { null }, { null }, ::checkpoint,
            { error("Unexpected follow") },
            DesktopOriginalFavoriteFolderProtocol(api, { 1L }, { "fixture-csrf" }, ::checkpoint))
        private val analytics = object : DesktopOriginalVideoInteractionAnalytics {
            override fun logCoin(videoId: String, coinCount: Int) {
                assertEquals(0, admissionDepth.get()); analyticsCoins += videoId to coinCount
            }
            override fun logLike(videoId: String, isLiked: Boolean) = error("Unexpected separate like")
            override fun logDislike(videoId: String, isDisliked: Boolean) = error("Unexpected dislike")
            override fun logFavorite(videoId: String, isFavorited: Boolean) = error("Unexpected favorite")
            override fun logFollow(userId: String, isFollowed: Boolean) = error("Unexpected follow")
        }
        private val originalBalance = originalVideoCoinBalanceLoader(api, { true }, ::checkpoint)
        val vm = VideoEngagementViewModel(DesktopOriginalVideoEngagementEnvironment(
            DesktopPluginContext(DesktopPluginStore(directory)), scope,
            DefaultVideoEngagementActions(VideoInteractionUseCase(protocol, analytics)),
            VideoCoinBalanceLoader {
                assertEquals(0, admissionDepth.get(), "Actual balance loader must stay outside admission")
                balanceStarts.incrementAndGet()
                originalBalance.load()
            }, ::entryOwned, ::admitEntry,
            interactionSession = { directory },
        ))
        val binding: DesktopWindowsVideoEngagementBinding
        init {
            val info = Json.decodeFromString<ViewInfo>("""{"copyright":$copyright}""")
            val loaded = VideoPlaybackUiState.Success(info = info, playUrl = "", isLoggedIn = true,
                coinCount = initialCoins)
            vm.bindSubject(subject, loaded.toEngagementSeed().copy(isDisliked = disliked))
            binding = binding()
        }
        fun binding(expected: DesktopOriginalVideoAcceptedPublication = accepted): DesktopWindowsVideoEngagementBinding {
            val captured = assertNotNull(vm.uiState.value.subject)
            return DesktopWindowsVideoEngagementBinding(expected, vm, captured,
                { interactive && sourceOwned(expected) }, { sourceOwned(expected) },
                { action -> admitSource(expected, action) }).also { bindings += it }
        }
        fun runQueued() {
            scheduler.runCurrent()
            assertTrue(failures.isEmpty(), "Unexpected coroutine failure: $failures")
        }
        suspend fun drain(): Unit = withTimeout(5_000) {
            do { runQueued(); delay(1) } while (scope.coroutineContext.job.children.any())
            runQueued()
        }
        fun holdNav() {
            navResponse = { args ->
                val reply = captureReply(args)
                check(navSignals.trySend(reply).isSuccess)
                COROUTINE_SUSPENDED
            }
        }
        suspend fun awaitNav(): PendingReply {
            runQueued()
            return withTimeout(5_000) { navSignals.receive() }
        }
        suspend fun holdCoin(request: DesktopWindowsVideoCoinDialogRequest, alsoLike: Boolean = false): PendingReply {
            val signal = CompletableDeferred<PendingReply>()
            coinResponse = { args -> captureReply(args).also { signal.complete(it) }; COROUTINE_SUSPENDED }
            assertTrue(binding.coin(request, 1, alsoLike))
            runQueued()
            return withTimeout(5_000) { signal.await() }
        }
        fun request(binding: DesktopWindowsVideoEngagementBinding = this.binding): DesktopWindowsVideoCoinDialogRequest {
            assertTrue(binding.openCoinDialog())
            return assertNotNull(vm.uiState.value.desktopCoinDialogRequest)
        }
        suspend fun close() {
            bindings.forEach { it.close() }
            scope.cancel()
            pendingReplies.forEach { it.cancelIfPending() }
            withTimeout(5_000) {
                do { scheduler.runCurrent(); delay(1) } while (scope.coroutineContext.job.children.any())
            }
            navSignals.close()
            check(directory.toAbsolutePath().normalize().parent == tempRoot)
            directory.toFile().deleteRecursively()
        }
    }

    private companion object {
        fun nav(money: Double) = NavResponse(data = NavData(isLogin = true, money = money))
    }

    @Test fun closedQueuedRequestSkipsActualLoaderButFreshRequestStillLoads(): Unit = runBlocking {
        val h = Harness()
        try {
            val old = h.request()
            assertTrue(h.binding.dismissCoinDialog(old))
            assertTrue(h.entryOwned())
            h.drain()
            assertEquals(0, h.balanceStarts.get()); assertTrue(h.apiCalls.isEmpty())
            val fresh = h.request(); h.drain()
            assertEquals(1, h.balanceStarts.get()); assertEquals(listOf("getNavInfo"), h.apiCalls.toList())
            assertSame(fresh, h.vm.uiState.value.desktopCoinDialogRequest)
            assertEquals(10.0, h.vm.uiState.value.userCoinBalance); assertTrue(fresh.isOwned())
        } finally { h.close() }
    }

    @Test fun closeReopenSameSubjectKeepsFreshBalanceAfterOldReadCompletes(): Unit = runBlocking {
        val h = Harness()
        try {
            h.holdNav(); val old = h.request(); val oldRead = h.awaitNav()
            assertNull(h.vm.uiState.value.userCoinBalance)
            assertTrue(h.binding.dismissCoinDialog(old))
            val fresh = h.request(); val freshRead = h.awaitNav()
            assertNotSame(old, fresh); assertSame(old.sourceOwner, fresh.sourceOwner)
            freshRead.complete(nav(7.0)); h.runQueued()
            withTimeout(5_000) { while (h.vm.uiState.value.userCoinBalance == null) { h.runQueued(); delay(1) } }
            assertEquals(7.0, h.vm.uiState.value.userCoinBalance)
            oldRead.complete(nav(99.0)); h.drain()
            assertSame(fresh, h.vm.uiState.value.desktopCoinDialogRequest)
            assertTrue(h.vm.uiState.value.coinDialogVisible); assertEquals(7.0, h.vm.uiState.value.userCoinBalance)
            assertEquals(2, h.balanceStarts.get()); assertTrue(h.coinCalls.isEmpty())
        } finally { h.close() }
    }

    @Test fun staleDraftDismissAndDisposeCannotClearSuccessorOrSendCoin(): Unit = runBlocking {
        val h = Harness()
        try {
            val old = h.request(); h.drain()
            val fresh = h.request(); h.drain()
            assertFalse(h.binding.coin(old, 1, true)); assertFalse(h.binding.dismissCoinDialog(old))
            old.close(); h.drain()
            assertSame(fresh, h.vm.uiState.value.desktopCoinDialogRequest)
            assertTrue(h.vm.uiState.value.coinDialogVisible); assertEquals(10.0, h.vm.uiState.value.userCoinBalance)
            assertTrue(h.coinCalls.isEmpty())
            assertTrue(h.binding.coin(fresh, 1, false)); h.drain()
            assertEquals(listOf(CoinCall(h.subject.aid, 1, 0, "fixture-csrf")), h.coinCalls.toList())
            assertEquals(1, h.vm.uiState.value.coinCount)
        } finally { h.close() }
    }

    @Test fun originalAndRepostCapsPreserveAlsoLikeParameterAndConsumeRequestOnce(): Unit = runBlocking {
        for ((copyright, initial, amount, alsoLike) in listOf(Choice(1, 1, 1, false), Choice(1, 1, 1, true),
                Choice(2, 0, 1, false), Choice(2, 0, 1, true), Choice(1, 0, 2, false), Choice(1, 0, 2, true))) {
            val h = Harness(copyright, initial, disliked = true)
            try {
                val request = h.request(); h.drain()
                val limit = if (copyright == 2) 1 else 2
                assertEquals(limit, h.vm.uiState.value.coinLimit)
                assertFalse(h.binding.coin(request, 0, alsoLike)); assertFalse(h.binding.coin(request, amount + 1, alsoLike))
                assertTrue(h.coinCalls.isEmpty()); assertSame(request, h.vm.uiState.value.desktopCoinDialogRequest)
                assertTrue(h.binding.coin(request, amount, alsoLike))
                assertFalse(h.binding.coin(request, amount, alsoLike)); assertFalse(request.isOwned())
                assertNull(h.vm.uiState.value.desktopCoinDialogRequest); assertFalse(h.vm.uiState.value.coinDialogVisible)
                h.drain()
                assertEquals(listOf(CoinCall(h.subject.aid, amount, if (alsoLike) 1 else 0, "fixture-csrf")), h.coinCalls.toList())
                assertEquals(listOf(h.subject.bvid to amount), h.analyticsCoins.toList())
                assertEquals(limit, h.vm.uiState.value.coinCount)
                assertEquals(alsoLike, h.vm.uiState.value.isLiked); assertEquals(!alsoLike, h.vm.uiState.value.isDisliked)
                assertNotNull(h.binding.feedback(DesktopWindowsVideoFeedbackKind.COIN))
                assertFalse(h.binding.openCoinDialog()); assertFalse(h.binding.coin(request, amount, alsoLike)); h.drain()
                assertEquals(1, h.coinCalls.size); assertFalse("likeVideo" in h.apiCalls)
            } finally { h.close() }
        }
    }

    @Test fun fixtureAcceptedReplacementRejectsOldReadAndCoinEvenAtSameSourceVersion(): Unit = runBlocking {
        for (sameVersion in listOf(false, true)) for (pendingCoin in listOf(false, true)) {
            val h = Harness()
            try {
                if (!pendingCoin) h.holdNav()
                val old = h.request()
                val pending = if (pendingCoin) { h.drain(); h.holdCoin(old, alsoLike = true) } else h.awaitNav()
                val snapshot = if (sameVersion) h.source.nativeSource else
                    OwnedPlaybackSourceSnapshot(2, h.source.nativeSource.source)
                val next = DesktopOriginalVideoAcceptedPublication(h.source.request, snapshot)
                assertNotSame(h.source, next)
                if (sameVersion) assertEquals(h.source.sourceVersion, next.sourceVersion)
                else assertNotEquals(h.source.sourceVersion, next.sourceVersion)
                h.accepted = next
                assertTrue(h.entryOwned(), "VM entry owner must not implement the source rejection")
                pending.complete(if (pendingCoin) SimpleApiResponse() else nav(99.0)); h.drain()
                if (!pendingCoin) {
                    // No close, reopen, generation or request replacement yet:
                    // this rejection must come from the old source permission.
                    assertSame(old, h.vm.uiState.value.desktopCoinDialogRequest)
                    assertTrue(h.vm.uiState.value.coinDialogVisible)
                    assertNull(h.vm.uiState.value.userCoinBalance)
                }
                assertFalse(old.isOwned()); assertEquals(0, h.vm.uiState.value.coinCount)
                assertFalse(h.vm.uiState.value.isLiked); assertNull(h.vm.uiState.value.desktopMaidFeedbackOrigin)
                assertTrue(h.analyticsCoins.isEmpty()); assertFalse(h.binding.coin(old, 1, true))
                val newBinding = h.binding(next)
                if (pendingCoin) h.navResponse = { nav(7.0) }
                val fresh = h.request(newBinding)
                if (!pendingCoin) h.awaitNav().complete(nav(7.0))
                h.drain()
                assertSame(next, fresh.sourceOwner); assertSame(fresh, h.vm.uiState.value.desktopCoinDialogRequest)
                assertEquals(7.0, h.vm.uiState.value.userCoinBalance); assertEquals(0, h.vm.uiState.value.coinCount)
                assertEquals(if (pendingCoin) 1 else 0, h.coinCalls.size)
            } finally { h.close() }
        }
    }

    @Test fun accountEntrySubjectOrCallerRetirementRejectsPendingBalanceAndCoinSuccess(): Unit = runBlocking {
        for (retirement in 0..3) {
            val h = Harness()
            try {
                h.holdNav(); val request = h.request(); val read = h.awaitNav()
                val coin = h.holdCoin(request, alsoLike = true)
                when (retirement) {
                    0 -> h.account = 2
                    1 -> h.entry = false
                    2 -> h.vm.bindSubject(h.subject.copy(generation = 2), VideoEngagementSeed(likeStatusKnown = true, followStatusKnown = true, isLoggedIn = true))
                    else -> h.scope.cancel()
                }
                read.complete(nav(99.0)); coin.complete(SimpleApiResponse()); h.drain()
                assertNull(h.vm.uiState.value.userCoinBalance); assertEquals(0, h.vm.uiState.value.coinCount)
                assertFalse(h.vm.uiState.value.isLiked); assertNull(h.vm.uiState.value.desktopMaidFeedbackOrigin)
                assertTrue(h.analyticsCoins.isEmpty()); assertFalse(h.binding.isOwned())
                assertFalse(h.binding.coin(request, 1, true))
            } finally { h.close() }
        }
    }

    @Test fun admittedCoinCanConfirmAfterDialogReopenAndBindingDisposal(): Unit = runBlocking {
        val h = Harness()
        try {
            val old = h.request(); h.drain(); val reply = h.holdCoin(old, alsoLike = true)
            val reopened = h.request(); h.runQueued()
            withTimeout(5_000) { while (h.vm.uiState.value.userCoinBalance == null) { h.runQueued(); delay(1) } }
            old.close(); h.binding.close(); reopened.close()
            val current = h.binding()
            val fresh = h.request(current); h.runQueued()
            withTimeout(5_000) { while (h.vm.uiState.value.userCoinBalance == null) { h.runQueued(); delay(1) } }
            reply.complete(SimpleApiResponse()); h.drain()
            assertEquals(1, h.vm.uiState.value.coinCount); assertTrue(h.vm.uiState.value.isLiked)
            assertSame(fresh, h.vm.uiState.value.desktopCoinDialogRequest)
            val origin = assertNotNull(current.feedback(DesktopWindowsVideoFeedbackKind.COIN))
            assertSame(h.source, origin.source.accepted)
            assertTrue(current.dismissCoinDialog(fresh)); h.drain()
            assertSame(origin, current.feedback(DesktopWindowsVideoFeedbackKind.COIN))
            assertTrue(current.completeFeedback(origin)); assertNull(h.vm.uiState.value.desktopMaidFeedbackOrigin)
        } finally { h.close() }
    }

    @Test fun confirmedFeedbackCannotBeConsumedAfterSourceAccountOrSubjectRetires(): Unit = runBlocking {
        for (retirement in 0..2) {
            val h = Harness()
            try {
                val request = h.request(); h.drain()
                assertTrue(h.binding.coin(request, 1, false)); h.drain()
                val origin = assertNotNull(h.binding.feedback(DesktopWindowsVideoFeedbackKind.COIN))
                when (retirement) {
                    0 -> h.accepted = DesktopOriginalVideoAcceptedPublication(h.source.request, h.source.nativeSource)
                    1 -> h.account = 2
                    else -> h.vm.bindSubject(h.subject.copy(generation = 2), VideoEngagementSeed(likeStatusKnown = true, followStatusKnown = true, isLoggedIn = true))
                }
                val state = h.vm.uiState.value
                assertNull(h.binding.feedback(DesktopWindowsVideoFeedbackKind.COIN))
                assertFalse(h.binding.completeFeedback(origin)); assertFalse(h.binding.cancelFeedback(origin))
                assertEquals(state, h.vm.uiState.value); assertEquals(1, h.coinCalls.size)
            } finally { h.close() }
        }
    }

    @Test fun legacyZeroArgumentFunctionReferenceStillLoadsAndRejectsOldReopenResult(): Unit = runBlocking {
        val h = Harness()
        try {
            h.holdNav(); val open: () -> Unit = h.vm::openCoinDialog
            open(); val old = h.awaitNav()
            assertNull(h.vm.uiState.value.desktopCoinDialogRequest); assertTrue(h.vm.uiState.value.coinDialogVisible)
            h.vm.setCoinDialogVisible(false); open(); val fresh = h.awaitNav()
            fresh.complete(nav(7.0)); old.complete(nav(99.0)); h.drain()
            assertNull(h.vm.uiState.value.desktopCoinDialogRequest); assertTrue(h.vm.uiState.value.coinDialogVisible)
            assertEquals(7.0, h.vm.uiState.value.userCoinBalance); assertEquals(2, h.balanceStarts.get())
            assertTrue(h.coinCalls.isEmpty())
        } finally { h.close() }
    }
}

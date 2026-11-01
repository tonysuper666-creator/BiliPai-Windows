package com.bilipai.desktop.ui

import com.android.purebilibili.core.plugin.PluginManager
import com.android.purebilibili.feature.video.ui.pager.resolvePortraitPlaybackStreamUrls
import com.android.purebilibili.feature.video.ui.pager.resolvePortraitMediaId
import com.android.purebilibili.core.store.DesktopOriginalVideoControlSettings
import com.android.purebilibili.core.store.PlaybackCompletionBehavior
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.story.StoryViewModel
import com.android.purebilibili.core.store.SettingsManager
import com.android.purebilibili.data.repository.DesktopOriginalHomeVideoProtocol
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionState
import com.android.purebilibili.feature.video.player.*
import com.android.purebilibili.feature.video.viewmodel.DesktopVideoCommentRequests
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.cache.*
import com.bilipai.desktop.plugins.*
import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.swing.Swing
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.awt.EventQueue
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.Proxy as NetworkProxy
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Source58 actual Portrait binding plus complete original VM, Slot/Gate/Assembly/RequestFactory/RootMediaFactory,
 * Read-only onVideoInfo callbacks are observed as data; the Composable's visible
 * caption/error/manual-retry button is not executed by these CPU business tests.
 * Required media preparation always uses the actual raw repository 15s wrapper;
 * each test supplies an explicit controlled CPU stage, never a nativeReady claim.
 * Fixture construction is reused from the already qualified CDN Assembly tests.
 * NativeOwner, RuntimeBinding and PluginBridge. Only HTTP responses, external queue
 * and platform effects are fixture inputs. The existing memory ABI executes MPV
 * commands/ACKs without a native DLL, rendering thread, HWND or physical decoder.
 * One real Runtime is retained for this class, matching PluginManager's process
 * lifetime. All optional providers are disabled in its temporary Store before
 * registration. No fake Runtime readiness or manufactured accepted source exists. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesktopOriginalPortraitMetadataPreparationTest {
    private lateinit var runtimeDirectory: Path
    private lateinit var store: DesktopPluginStore
    private lateinit var runtime: DesktopPluginRuntime

    @BeforeAll fun createRuntime(): Unit = runBlocking {
        runtimeDirectory = Files.createTempDirectory("cdn-actual-runtime-")
        store = DesktopPluginStore(runtimeDirectory.resolve("settings"))
        val ids = listOf("sponsor_block", "danmaku_enhance", "eye_protection", "bilipai_feed_filter",
            "home_feed_anonymizer", "adfilter", "subscription_feed", "today_watch", "cdn_region", "dlna_cast", "google_cast")
        store.update("plugin_prefs", ids.associate { "plugin_enabled_$it" to JsonPrimitive(false) })
        runtime = withContext(Dispatchers.Swing) { DesktopPluginRuntime(store) }
        withTimeout(8_000) { for (id in ids) PluginManager.awaitPluginReady(id) }
        assertTrue(runtime.plugins.value.none { it.enabled })
    }

    @AfterAll fun closeRuntime(): Unit = runBlocking {
        if (::runtime.isInitialized) runtime.shutdownForRestore()
        if (::runtimeDirectory.isInitialized) deleteCreatedDirectory(runtimeDirectory, "cdn-actual-runtime-")
    }

    @Test fun sameActualPageBindingPublishesReadonlyDetailBeforePreparationAndThenAcceptsMedia(): Unit = runBlocking<Unit> {
        Fixture().useSuspending { f ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.preparationPort = { binding ->
                f.assertOutsideAdmission(); binding.assertCurrent()
                entered.complete(Unit); release.await()
            }
            val baseline = f.player.currentSourceVersion
            val op = f.startPage(A, 101, 104, publish = true)
            val binding = withTimeout(8_000) { op.binding.await() }
            val detail = withTimeout(8_000) { op.detail.await() }
            withTimeout(8_000) { entered.await() }
            val original = assertNotNull(op.original)
            assertEquals(A, detail.bvid); assertEquals(104L, detail.cid)
            assertSame(binding, f.pageRepositories.single().first)
            assertSame(binding, f.preparationBindings.single())
            assertSame(original.currentRequest, f.owner.captureLoadState().currentRequest)
            assertEquals(original.currentLoadRequestToken, f.owner.captureLoadState().currentLoadRequestToken)
            assertTrue(op.result.isActive); assertFalse(op.result.isCompleted)
            assertEquals(1, f.detailRequests.size); assertTrue(f.playRequests.isEmpty())
            assertNull(f.owner.native.current()); assertEquals(baseline, f.player.currentSourceVersion)
            assertFalse(f.player.state.value.ready, "A detail callback is not native core readiness")
            release.complete(Unit)
            val (info, data) = withTimeout(8_000) { op.result.await().getOrThrow() }
            assertEquals(detail, info); assertNotNull(data.dash)
            val accepted = f.accepted()
            assertEquals(A, accepted.request.bvid); assertEquals(104L, accepted.request.cid)
            assertEquals(binding.receipt, accepted.nativeSource.source.authorizationReceipt)
            assertEquals(1, f.playRequests.size)
            assertEquals(0, f.waitingFacetCalls)
            withTimeout(8_000) {
                while ((f.owner.playback.uiState.value as? VideoPlaybackUiState.Success)?.info?.bvid != A) delay(5)
            }
            assertEquals(A, f.state().info.bvid)
        }
    }

    @Test fun realDetailApiRejectionDoesNotEnterPreparationOrRequestAnyPlayUrl(): Unit = runBlocking<Unit> {
        Fixture().useSuspending { f ->
            f.rejectDetails = true
            f.preparationPort = { error("Rejected metadata must never prepare media") }
            val baseline = f.player.currentSourceVersion
            val op = f.startPage(A, 101, 104, publish = true)
            val result = withTimeout(8_000) { op.result.await() }
            val failure = assertNotNull(result.exceptionOrNull())
            assertFalse(failure is DesktopOriginalVideoNativePreparationFailure)
            assertFalse(op.detail.isCompleted)
            assertTrue(f.preparationBindings.isEmpty()); assertTrue(f.playRequests.isEmpty())
            assertTrue(f.publications.isEmpty()); assertNull(f.owner.native.current())
            assertEquals(baseline, f.player.currentSourceVersion)
            assertEquals(A, f.owner.captureLoadState().currentRequest?.bvid)
            assertEquals(0, f.waitingFacetCalls)
        }
    }

    @Test fun typedPreparationFailureRetainsDetailAndFreshPageRetryCapturesNewRequest(): Unit = runBlocking<Unit> {
        Fixture().useSuspending { f ->
            val fatal = IllegalStateException("Controlled CPU preparation failure")
            f.preparationPort = { throw fatal }
            val failed = f.startPage(A, 101, 104, publish = true)
            val firstBinding = withTimeout(8_000) { failed.binding.await() }
            val detail = withTimeout(8_000) { failed.detail.await() }
            val failure = assertIs<DesktopOriginalVideoNativePreparationFailure>(
                withTimeout(8_000) { failed.result.await() }.exceptionOrNull())
            assertTrue(f.causeContainsIdentity(failure, fatal), "The actual initialization cause must be retained")
            assertEquals(A, detail.bvid); assertTrue(f.playRequests.isEmpty())
            assertNull(f.owner.native.current()); assertEquals(1, f.preparationBindings.size)
            val original = assertNotNull(failed.original)
            assertSame(original.currentRequest, f.owner.captureLoadState().currentRequest)
            f.preparationPort = { binding -> binding.assertCurrent(); f.assertOutsideAdmission() }
            val retry = f.startPage(A, 101, 104, publish = true)
            val retryBinding = withTimeout(8_000) { retry.binding.await() }
            withTimeout(8_000) { retry.result.await().getOrThrow() }
            val retried = assertNotNull(retry.original)
            assertNotSame(firstBinding, retryBinding)
            assertEquals(original.currentRequest, retried.currentRequest)
            assertSame(original.currentRequest, retried.currentRequest,
                "The original StateFlow retains a same-value request after independently advancing its tokens")
            assertSame(failed.result, assertNotNull(failed.caller))
            assertSame(retry.result, assertNotNull(retry.caller))
            assertNotSame(failed.caller, retry.caller)
            assertTrue(retried.currentLoadRequestToken > original.currentLoadRequestToken)
            assertEquals(firstBinding.receipt, retryBinding.receipt)
            assertEquals(listOf(firstBinding, retryBinding), f.preparationBindings.toList())
            assertEquals(1, f.playRequests.size)
            assertEquals(A, f.accepted().request.bvid)
            assertEquals(retryBinding.receipt, f.accepted().nativeSource.source.authorizationReceipt)
            assertEquals(0, f.waitingFacetCalls)
        }
    }

    @Test fun retiringActualEntryAfterReadonlyDetailCancelsPreparationWithoutMediaRequests(): Unit = runBlocking<Unit> {
        Fixture().useSuspending { f ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.preparationPort = { entered.complete(Unit); release.await() }
            val baseline = f.player.currentSourceVersion
            val op = f.startPage(A, 101, 104, publish = true)
            withTimeout(8_000) { op.detail.await(); entered.await() }
            f.ui { f.gate.retire() }
            release.complete(Unit)
            assertFailsWith<CancellationException> { withTimeout(8_000) { op.result.await() } }
            assertTrue(op.result.isCancelled); assertFalse(f.gate.owns())
            assertTrue(f.playRequests.isEmpty()); assertTrue(f.publications.isEmpty())
            assertEquals(baseline, f.player.currentSourceVersion)
            assertFalse(f.player.state.value.ready)
        }
    }

    @Test fun olderCapturedPageCannotStopOrPublishOverNewerRealAcceptedPage(): Unit = runBlocking<Unit> {
        Fixture().useSuspending { f ->
            f.preparationPort = { it.assertCurrent() }
            val first = f.startPage(A, 101, 104, publish = true)
            withTimeout(8_000) { first.result.await().getOrThrow() }
            val acceptedA = f.accepted()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.preparationPort = { binding ->
                if (binding === f.metadataBindings.last() && f.owner.captureLoadState().currentRequest?.bvid == A) {
                    entered.complete(Unit); release.await()
                }
                binding.assertCurrent()
            }
            val old = f.startPage(A, 101, 104, publish = true)
            withTimeout(8_000) { old.detail.await(); entered.await() }
            val oldState = assertNotNull(old.original); val oldCaller = assertNotNull(old.caller)
            assertTrue(oldCaller.isActive)
            f.preparationPort = { it.assertCurrent() }
            val newer = f.startPage(B, 102, 105, publish = true)
            withTimeout(8_000) { newer.result.await().getOrThrow() }
            val acceptedB = f.accepted(); val newState = assertNotNull(newer.original)
            assertNotSame(acceptedA, acceptedB); assertEquals(B, acceptedB.request.bvid)
            assertNotSame(oldState.currentRequest, newState.currentRequest)
            assertTrue(newState.currentLoadRequestToken > oldState.currentLoadRequestToken)
            val version = f.player.currentSourceVersion
            f.ui {
                assertFailsWith<CancellationException> {
                    f.portrait.clearCapturedPagePlayback(oldState.currentLoadRequestToken, oldCaller)
                }
            }
            assertSame(acceptedB, f.accepted()); assertEquals(version, f.player.currentSourceVersion)
            release.complete(Unit)
            assertFailsWith<CancellationException> { withTimeout(8_000) { old.result.await() } }
            assertSame(acceptedB, f.accepted()); assertEquals(version, f.player.currentSourceVersion)
            assertSame(newState.currentRequest, f.owner.captureLoadState().currentRequest)
            assertEquals(newState.currentLoadRequestToken, f.owner.captureLoadState().currentLoadRequestToken)
            assertEquals(listOf(A, B), f.playRequests.map { it.url.queryParameter("bvid") })
            assertEquals(0, f.waitingFacetCalls)
        }
    }

    private class PageOperation {
        val binding = CompletableDeferred<DesktopOriginalVideoRepositoryBinding>()
        val detail = CompletableDeferred<ViewInfo>()
        @Volatile var original: PlaybackSessionState? = null
        @Volatile var caller: Job? = null
        lateinit var result: Deferred<Result<Pair<ViewInfo, PlayUrlData>>>
    }
    private data class Capture(val state: PlaybackSessionState, val job: Job)
    private data class StoryCapture(val binding: DesktopOriginalVideoRepositoryBinding, val caller: Job)
    private data class CdnEmission(val source: DesktopOriginalVideoAcceptedPublication, val index: Int, val runtimeGeneration: Long?)
    /** Source65 follows the actual retained Story owner and original VM/protocol.
     * These business tests do not mount the physical Root/Pager or claim nativeReady. */
    @Test fun retainedStoryStartsAtEndpointSeedDisplayOneAndPreservesOriginalFilterDedupCursor(): Unit = runBlocking<Unit> {
        Fixture(storyMode = true).useSuspending { f ->
            f.storyBody = { request -> when (request.url.queryParameter("display_id")) {
                "1" -> storyJson(storyItem(101, A), storyItem(102, B), """{"id":901,"goto":"bangumi","bvid":"filtered"}""")
                "2" -> storyJson(storyItem(201, C, aid = 101), storyItem(202, B, aid = 202),
                    storyItem(101, D, aid = 203), storyItem(103, C))
                "3" -> storyJson(storyItem(103, C))
                "4" -> storyJson(storyItem(104, D))
                else -> error("Unexpected Story cursor")
            } }
            val vm = f.startStory("  $A  ")
            f.awaitStory(vm) { it.uiState.value.items.size == 2 }
            assertEquals(listOf(A, B), vm.uiState.value.items.map { it.bvid })
            assertSame(vm, f.feedOwners.find(f.storyKey)); assertTrue(f.homeRequests.isEmpty())
            val first = f.storyCaptures.single()
            withTimeout(8_000) { first.caller.join() }
            assertTrue(first.caller.isCompleted); assertFalse(first.caller.isCancelled)
            assertFailsWith<CancellationException> { first.binding.assertCurrent() }
            f.ui { vm.startFeed(A); vm.updateCurrentIndex(1); vm.loadMoreStories(); vm.loadMoreStories() }
            f.awaitStory(vm) { it.uiState.value.items.size == 3 }
            assertEquals(listOf(A, B, C), vm.uiState.value.items.map { it.bvid })
            assertEquals(1, vm.uiState.value.currentIndex)
            f.ui { vm.loadMoreStories() }
            f.awaitStory(vm) { it.uiState.value.error != null }
            assertEquals("暂时没有新的推荐视频", vm.uiState.value.error)
            assertEquals(listOf(A, B, C), vm.uiState.value.items.map { it.bvid })
            f.ui { vm.loadMoreStories() }
            f.awaitStory(vm) { it.uiState.value.items.size == 4 }
            assertEquals(listOf(A, B, C, D), vm.uiState.value.items.map { it.bvid })
            assertEquals(listOf("1", "2", "3", "4"), f.storyRequests.map { it.url.queryParameter("display_id") })
            assertTrue(f.storyRequests.all { it.url.queryParameter("bvid") == A && it.url.queryParameter("aid") == "0" && it.url.queryParameter("ps") == "20" })
            assertEquals(1, vm.uiState.value.currentIndex); assertTrue(f.homeRequests.isEmpty())
            f.assertStoryOnly()
        }
    }

    @Test fun actualStoryEmptyAndApiErrorFallBackOnceToOriginalHomeCursorWithoutReshuffle(): Unit = runBlocking<Unit> {
        for (firstBody in listOf(storyJson(), """{"code":-101,"message":"Controlled Story rejection"}""")) {
            Fixture(storyMode = true).useSuspending { f ->
                f.storyBody = { firstBody }
                f.homeBody = { request -> when (request.url.queryParameter("fresh_idx")) {
                    "0" -> homeJson(homeItem(101, A), homeItem(102, B))
                    "1" -> homeJson(homeItem(102, B), homeItem(103, C))
                    else -> error("Unexpected Home cursor")
                } }
                val vm = f.startStory(A)
                f.awaitStory(vm) { it.uiState.value.items.size == 2 }
                f.ui { vm.updateCurrentIndex(1); vm.loadMoreStories() }
                f.awaitStory(vm) { it.uiState.value.items.size == 3 }
                assertEquals(listOf(A, B, C), vm.uiState.value.items.map { it.bvid })
                assertEquals(1, vm.uiState.value.currentIndex); assertNull(vm.uiState.value.error)
                assertEquals(1, f.storyRequests.size)
                assertEquals(listOf("0", "1"), f.homeRequests.map { it.url.queryParameter("fresh_idx") })
                assertEquals(3, f.storyCaptures.size); f.assertStoryOnly()
            }
        }
    }

    @Test fun actualFallbackHttpFailureRemainsErrorAndManualLoadRetriesSameHomeCursor(): Unit = runBlocking<Unit> {
        Fixture(storyMode = true).useSuspending { f ->
            f.storyBody = { storyJson() }; f.homeStatus = 503
            val vm = f.startStory(A)
            f.awaitStory(vm) { !it.uiState.value.isLoading && it.uiState.value.error != null }
            assertTrue(vm.uiState.value.items.isEmpty())
            assertTrue(assertNotNull(vm.uiState.value.error).contains("503"))
            assertEquals(listOf("0"), f.homeRequests.map { it.url.queryParameter("fresh_idx") })
            f.homeStatus = 200; f.homeBody = { homeJson(homeItem(102, B)) }
            f.ui { vm.loadMoreStories() }
            f.awaitStory(vm) { it.uiState.value.items.singleOrNull()?.bvid == B }
            assertNull(vm.uiState.value.error)
            assertEquals(listOf("0", "0"), f.homeRequests.map { it.url.queryParameter("fresh_idx") })
            assertEquals(1, f.storyRequests.size); f.assertStoryOnly()
        }
    }

    @Test fun realStoryRefreshCancelsOldCallerAndCannotPublishItsLateResponse(): Unit = runBlocking<Unit> {
        Fixture(storyMode = true).useSuspending { f ->
            val release = f.storyGate(); val entered = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>()
            f.storyBody = { request ->
                if (f.storyRequests.indexOf(request) == 0) {
                    entered.complete(Unit)
                    try { check(release.await(8, TimeUnit.SECONDS)); storyJson(storyItem(101, A)) }
                    finally { returned.complete(Unit) }
                } else storyJson(storyItem(102, B))
            }
            val vm = f.startStory(A); withTimeout(8_000) { entered.await() }
            val old = f.storyCaptures.single()
            f.ui { vm.refresh() }
            f.awaitStory(vm) { it.uiState.value.items.singleOrNull()?.bvid == B }
            release.countDown(); withTimeout(8_000) { returned.await(); old.caller.join() }
            f.ui { assertEquals(listOf(B), vm.uiState.value.items.map { it.bvid }) }
            assertTrue(old.caller.isCancelled)
            assertNotSame(old.binding, f.storyCaptures.last().binding)
            assertNotSame(old.caller, f.storyCaptures.last().caller)
            assertEquals(listOf("1", "1"), f.storyRequests.map { it.url.queryParameter("display_id") })
            assertTrue(f.homeRequests.isEmpty()); assertNull(vm.uiState.value.error); f.assertStoryOnly()
        }
    }

    @Test fun retiredStoryEntryAccountOrOriginalCallerCannotPublishLateFeedOrStartFallback(): Unit = runBlocking<Unit> {
        for (retirement in listOf("entry", "account", "caller")) {
            Fixture(storyMode = true).useSuspending { f ->
                val release = f.storyGate(); val entered = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>()
                f.storyBody = {
                    entered.complete(Unit)
                    try { check(release.await(8, TimeUnit.SECONDS)); storyJson(storyItem(101, A)) }
                    finally { returned.complete(Unit) }
                }
                val vm = f.startStory(A); withTimeout(8_000) { entered.await() }
                val capture = f.storyCaptures.single(); val before = vm.uiState.value
                f.ui { when (retirement) {
                    "entry" -> f.feedOwners.reconcile(emptySet())
                    "account" -> f.sessions.saveAccount(mapOf("SESSDATA" to "synthetic-successor", "bili_jct" to "synthetic-successor-csrf"), AccountSummary(42, "Successor", ""))
                    "caller" -> capture.caller.cancel("Retire actual original Story caller")
                } }
                release.countDown(); withTimeout(8_000) { returned.await(); capture.caller.join() }
                f.ui { assertSame(before, vm.uiState.value, retirement) }
                assertTrue(vm.uiState.value.items.isEmpty(), retirement)
                assertFailsWith<CancellationException> { capture.binding.assertCurrent() }
                assertTrue(f.homeRequests.isEmpty(), retirement); assertEquals(1, f.storyRequests.size)
                if (retirement == "entry") { assertNull(f.feedOwners.find(f.storyKey)); assertTrue(f.gate.owns()) }
                if (retirement == "account") assertFalse(f.gate.owns())
                if (retirement == "caller") { assertSame(vm, f.feedOwners.find(f.storyKey)); assertTrue(f.gate.owns()); assertTrue(capture.caller.isCancelled) }
                f.assertStoryOnly()
            }
        }
    }

    private data class HeldDispatch(val context: CoroutineContext, val runnable: Runnable) {
        val job get() = checkNotNull(context[Job])
    }
    /** Delay the actual DEFAULT launch before Assembly captures its SessionState.
     * Every released continuation executes on the same real EDT. This never
     * edits VM state, NativeOwner, an authorization receipt or Runtime generation. */
    private class OneDispatchHold : CoroutineDispatcher() {
        private val lock = Any()
        private var armedThread: Thread? = null
        private var held: HeldDispatch? = null
        override fun isDispatchNeeded(context: CoroutineContext) = true
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val intercepted = synchronized(lock) {
                // The click synchronously starts DEFAULT on this EDT. An unrelated
                // HTTP completion can dispatch concurrently and must never steal
                // the test's delay or become the Job that the test later joins.
                if (armedThread === Thread.currentThread() && context[Job]?.isActive == true) {
                    check(held == null); armedThread = null; held = HeldDispatch(context, block); true
                } else false
            }
            if (!intercepted) EventQueue.invokeLater(block)
        }
        fun arm() = synchronized(lock) {
            check(EventQueue.isDispatchThread() && armedThread == null && held == null)
            armedThread = Thread.currentThread()
        }
        fun take(): HeldDispatch = synchronized(lock) { checkNotNull(held) }
        fun release(expected: HeldDispatch) {
            synchronized(lock) { check(held === expected); held = null }
            EventQueue.invokeLater(expected.runnable)
        }
        fun drainOnClose() { val pending = synchronized(lock) { armedThread = null; held.also { held = null } }; pending?.let { EventQueue.invokeLater(it.runnable) } }
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T

    private inner class Fixture(private val holdTags: Boolean = false, private val refreshExpiredUrls: Boolean = false,
        private val holdRelated: Boolean = false, private val nextWatchMode: Boolean = false,
        private val storyMode: Boolean = false) {
        val directory = Files.createTempDirectory("cdn-original-owner-")
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        private val parentJob = SupervisorJob()
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val dispatcher = OneDispatchHold()
        private val scope = CoroutineScope(parentJob + dispatcher + CoroutineExceptionHandler { _, error -> failures += error })
        private val slot = DesktopOriginalVideoRootOwnerSlot(repository, scope, parentJob::isActive)
        lateinit var gate: DesktopOriginalVideoRootGate
        lateinit var owner: DesktopOriginalVideoOwnerAssembly
        lateinit var portrait: DesktopOriginalPortraitPlatformBinding
        private lateinit var pageFactory: DesktopOriginalVideoOwnerRequestFactory
        private lateinit var rootMedia: DesktopOriginalVideoRootMediaFactory
        val metadataBindings = CopyOnWriteArrayList<DesktopOriginalVideoRepositoryBinding>()
        val preparationBindings = CopyOnWriteArrayList<DesktopOriginalVideoRepositoryBinding>()
        val pageRepositories = CopyOnWriteArrayList<Pair<DesktopOriginalVideoRepositoryBinding, DesktopOriginalVideoOwnerRequestRepository>>()
        val detailRequests = CopyOnWriteArrayList<Request>()
        @Volatile var rejectDetails = false
        @Volatile var waitingFacetCalls = 0
        var preparationPort: (suspend (DesktopOriginalVideoRepositoryBinding) -> Unit)? = null
        lateinit var facade: DesktopUnifiedPlaybackFacade
        private lateinit var settingsContext: DesktopOriginalPlayerSettingsContext
        lateinit var runtimeBinding: DesktopOriginalVideoRootRuntimeBinding
        lateinit var actor: PremiumRecoveryActor
        val player = MpvPlayer()
        val captures = CopyOnWriteArrayList<Capture>()
        val publications = CopyOnWriteArrayList<DesktopOriginalVideoAcceptedPublication>()
        val preparations = CopyOnWriteArrayList<DesktopOriginalMediaCachePreparation>()
        val cdnEmissions = CopyOnWriteArrayList<CdnEmission>()
        val toasts = CopyOnWriteArrayList<String>()
        private val unexpectedRequests = CopyOnWriteArrayList<String>()
        val playRequests = CopyOnWriteArrayList<Request>()
        val storyCaptures = CopyOnWriteArrayList<StoryCapture>()
        val storyRequests = CopyOnWriteArrayList<Request>()
        val homeRequests = CopyOnWriteArrayList<Request>()
        private val storyResponseGates = CopyOnWriteArrayList<CountDownLatch>()
        @Volatile var storyBody: (Request) -> String = { error("Unexpected Story feed response") }
        @Volatile var homeBody: (Request) -> String = { error("Unexpected Story Home fallback") }
        @Volatile var homeStatus = 200
        lateinit var feedOwners: DesktopOriginalStoryFeedOwners
        val storyKey = BiliPaiNavKey.Story(seedBvid = A, seedCid = 104L)
        @Volatile private var firstUrlsExpired = false
        val tagsEntered = CompletableDeferred<Unit>()
        val tagsRelease = CountDownLatch(if (holdTags) 1 else 0)
        val relatedEntered = CompletableDeferred<Unit>()
        val relatedRelease = CountDownLatch(if (holdRelated) 1 else 0)
        var beforePreparation: ((DesktopOriginalMediaCachePreparation) -> Unit)? = null
        private val context = runtime.context
        private val privacy = DesktopSearchPreferences(directory.resolve("privacy"))
        private lateinit var cache: DesktopMediaByteCache
        private val transport: OkHttpClient
        private val subtitles: DesktopSubtitleAssets
        private val epoch: Long
        private val playlist = MutableStateFlow<List<PlaylistItem>>(emptyList())
        private val index = MutableStateFlow(0)
        private val nextWatchQueue = if (nextWatchMode) DesktopOriginalVideoPlaylistBinding(
            DesktopPluginStore(directory.resolve("next-watch-queue")), scope, parentJob::isActive,
            { action -> if (parentJob.isActive) { action(); true } else false }, failures::add,
        ) else null
        val realQueue: DesktopOriginalVideoPlaylistBinding get() = checkNotNull(nextWatchQueue)
        private val queue: DesktopOriginalVideoOwnerPlaylist = nextWatchQueue ?: object : DesktopOriginalVideoOwnerPlaylist {
            override val playlist get() = this@Fixture.playlist
            override val currentIndex get() = index
            override val playMode = MutableStateFlow(PlayMode.SEQUENTIAL)
            override val isExternalPlaylist = MutableStateFlow(false)
            override val externalPlaylistSource = MutableStateFlow(ExternalPlaylistSource.NONE)
            override fun setPlaylist(items: List<PlaylistItem>, startIndex: Int) { playlist.value = items; index.value = startIndex }
            override fun playAt(index: Int) = playlist.value.getOrNull(index).also { if (it != null) this@Fixture.index.value = index }
            override fun playNext(): PlaylistItem? = error("No queue navigation")
            override fun playPrevious(): PlaylistItem? = error("No queue navigation")
        }
        private val mini = object : DesktopOriginalVideoOwnerMini {
            override var onNavigateNextCallback: (() -> Boolean)? = null
            override var onNavigatePreviousCallback: (() -> Boolean)? = null
            override var onHasNextNavigationCallback: (() -> Boolean)? = null
            override var onHasPreviousNavigationCallback: (() -> Boolean)? = null
            override val currentBvid: String? = null
            override val currentCid: Long? = null
            override val isActive = false
            override val player: DesktopOriginalMpvSectionControl? = null
            override fun syncCurrentVideoInfo(state: VideoPlaybackUiState.Success) { check(state.info.bvid in listOf(A, B)) }
            override fun updateCachedVideoTags(bvid: String, tags: List<VideoTag>) { check(bvid in listOf(A, B)) }
        }
        private val network = object : DesktopOriginalVideoOwnerNetwork {
            override fun isMobileData() = false
            override fun isWifi() = true
            override fun relatedNetworkObservation() = DesktopOriginalVideoRelatedNetworkObservation(
                DesktopHomeNetworkObservation(true, 3, 71L, false, "cpu-memory-transport"),
                downstreamBandwidthKbps = null,
            )
            override fun cdnNetwork() = DesktopCdnNetworkObservation(true, "cpu-memory-transport")
            override fun getDefaultQualityId(context: DesktopOriginalPlayerSettingsContext) = 64
        }
        private val capabilities = object : DesktopOriginalVideoPlaybackCapabilities {
            override fun isHevcSupported() = false
            override fun isAv1Supported() = false
            override fun isHdrSupported() = false
            override fun isDolbyVisionSupported() = false
            override fun isDolbyAtmosAudioSupported() = false
            override fun isDolbySoftwareAudioDecoderRequired() = false
        }
        private val progress = object : DesktopOriginalVideoProgressPort {
            override fun getCachedPosition(bvid: String, cid: Long) = 0L
            override fun savePosition(bvid: String, cid: Long, positionMs: Long) { check(bvid in listOf(A, B)); check(cid in listOf(104L, 105L)) }
        }
        init {
            // The original guest fetch deliberately omits audioLang. This case verifies
            // the logged-in language-preserving route with private synthetic credentials.
            if (refreshExpiredUrls || storyMode) sessions.saveAccount(
                mapOf("SESSDATA" to "synthetic-retry-session", "bili_jct" to "synthetic-retry-csrf"),
                AccountSummary(41, "Retry fixture", "", isVip = false))
            sessions.saveSpiCookies(mapOf("buvid3" to "synthetic-visitor", "buvid4" to "synthetic-visitor4"), sessions.generation)
            epoch = repository.sessionEpoch
            store.update("quality_settings", mapOf("auto_highest_quality" to JsonPrimitive(false), "video_codec" to JsonPrimitive("avc1"),
                "video_second_codec" to JsonPrimitive("avc1"), "wifi_quality" to JsonPrimitive(64)))
            store.update("settings", mapOf("video_codec_preference" to JsonPrimitive("avc1"), "video_second_codec_preference" to JsonPrimitive("avc1")))
            store.update("video_note_settings", mapOf("video_note_enabled" to JsonPrimitive(false)))
            transport = repository.httpClient.newBuilder().proxy(NetworkProxy.NO_PROXY).retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val request = chain.request()
                    if (!request.url.isHttps || request.url.port != 443) rejectRequest(request)
                    if (request.url.host in setOf("media.invalid", "backup.invalid")) return@addInterceptor memoryMedia(request)
                    if (request.url.host == "bvc.bilivideo.com") return@addInterceptor memoryPbp(request)
                    if (request.url.host == "app.bilibili.com") return@addInterceptor strictStoryResponse {
                        if (!storyMode || request.method != "GET" || request.url.encodedPath != "/x/v2/feed/index/story") rejectRequest(request)
                        check(request.url.queryParameterNames == setOf("display_id", "fnval", "fnver", "force_host", "fourk", "qn", "ps", "aid", "bvid"))
                        check(request.url.queryParameter("display_id")!!.toInt() > 0)
                        check(request.url.queryParameter("fnval") == "4048" && request.url.queryParameter("fnver") == "0" &&
                            request.url.queryParameter("force_host") == "0" && request.url.queryParameter("fourk") == "1" && request.url.queryParameter("qn") == "32")
                        check(request.url.queryParameter("ps") == "20" && request.url.queryParameter("aid") == "0")
                        check(request.url.queryParameter("bvid") in setOf(A, B))
                        // This memory response intercepts before OkHttp's BridgeInterceptor.
                        // Inspect the real jar under the captured request's active admission.
                        check(transport.cookieJar.loadForRequest(request.url).any {
                            it.name == "SESSDATA" && it.value == "synthetic-retry-session"
                        })
                        storyRequests.add(request)
                        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory")
                            .body(storyBody(request).toResponseBody("application/json".toMediaType())).build()
                    }
                    if (storyMode && request.url.host == "api.bilibili.com" && request.url.encodedPath == "/x/web-interface/wbi/index/top/feed/rcmd") return@addInterceptor strictStoryResponse {
                        check(request.method == "GET")
                        check(request.url.queryParameterNames == setOf("ps", "fresh_type", "fresh_idx", "feed_version", "y_num", "wts", "w_rid"))
                        check(request.url.queryParameter("ps") == "20" && request.url.queryParameter("fresh_type") == "3")
                        check(request.url.queryParameter("fresh_idx") == request.url.queryParameter("y_num"))
                        check(request.url.queryParameter("fresh_idx")!!.toInt() >= 0)
                        check(transport.cookieJar.loadForRequest(request.url).any {
                            it.name == "SESSDATA" && it.value == "synthetic-retry-session"
                        })
                        homeRequests.add(request)
                        val status = homeStatus
                        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("memory")
                            .body((if (status == 200) homeBody(request) else "Controlled Home HTTP failure").toResponseBody("application/json".toMediaType())).build()
                    }
                    if (request.url.host !in setOf("api.bilibili.com", "www.bilibili.com")) rejectRequest(request)
                    val bvid = request.url.queryParameter("bvid") ?: A
                    val body = when (request.url.encodedPath) {
                        "/" -> "memory bootstrap"
                        "/x/frontend/finger/spi" -> """{"code":0,"data":{"b_3":"synthetic-visitor","b_4":"synthetic-visitor4"}}"""
                        "/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":${refreshExpiredUrls},"vip":{"status":0},"wbi_img":{"img_url":"https://fixture.invalid/0123456789abcdef0123456789abcdef.png","sub_url":"https://fixture.invalid/fedcba9876543210fedcba9876543210.png"}}}"""
                        "/x/web-interface/view" -> {
                            detailRequests.add(request)
                            if (rejectDetails) """{"code":-404,"message":"Controlled detail rejection"}""" else viewJson(bvid)
                        }
                        "/x/player/wbi/playurl", "/x/player/playurl" -> {
                            val revision = synchronized(playRequests) { playRequests.add(request); playRequests.size }
                            if (refreshExpiredUrls) {
                                check(bvid == A && revision in 1..2)
                                retryPlayJson(revision)
                            } else playJson(bvid)
                        }
                        "/x/web-interface/archive/related" -> {
                            relatedEntered.complete(Unit)
                            check(relatedRelease.await(12, TimeUnit.SECONDS))
                            if (nextWatchMode && bvid == A)
                                """{"code":0,"data":[{"aid":102,"bvid":"$B","cid":105,"title":"Real related B","pic":"https://media.invalid/cover.jpg","duration":60,"owner":{"mid":52,"name":"Related UP","face":""}}]}"""
                            else """{"code":0,"data":[]}"""
                        }
                        "/x/tag/archive/tags" -> {
                            if (holdTags && bvid == A) { tagsEntered.complete(Unit); check(tagsRelease.await(8, TimeUnit.SECONDS)) }
                            """{"code":0,"data":[{"tag_id":71,"tag_name":"Fresh metadata"}]}"""
                        }
                        "/x/player/wbi/v2", "/x/player/v2" -> """{"code":0,"data":{"subtitle":{"subtitles":[]}}}"""
                        "/x/emote/user/panel/web" -> """{"code":0,"data":{"packages":[]}}"""
                        else -> """{"code":-404,"message":"No optional fixture data"}"""
                    }
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory")
                        .body(body.toResponseBody("application/json".toMediaType())).build()
                }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
            subtitles = DesktopSubtitleAssets(transport)
        }

        suspend fun initialize() {
            privacy.setPrivacyMode(true)
            cache = DesktopMediaByteCache(directory.resolve("bytes"), scope, repository)
            slot.install(epoch, { actualGate ->
                gate = actualGate
                val settings = DesktopOriginalPlayerSettingsContext(context, gate::owns, gate::commitEntry,
                    largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
                settingsContext = settings
                val readbacks = DesktopOriginalVideoEntryReadbacks(repository, epoch, gate.scope, gate::owns, gate::commitEntry)
                val operations = DesktopDynamicCardOperations(repository, epoch, gate::owns)
                val comments = Proxy.newProxyInstance(DesktopVideoCommentRequests::class.java.classLoader,
                    arrayOf(DesktopVideoCommentRequests::class.java)) { _, method, _ ->
                    when (method.name) { "isOwned" -> gate.owns(); "currentMid" -> null
                        else -> error("No comment effect ${method.name}") }
                } as DesktopVideoCommentRequests
                val root = DesktopOriginalCommentRootOwner(operations, comments, gate.scope, operations.emotes, gate::owns,
                    { error("No comment feedback") })
                val effects = DesktopOriginalVideoOwnerEffects(mini, queue, unused(), network,
                    object : DesktopOriginalVideoOwnerCache {
                        override fun get(bvid: String, cid: Long): PlayUrlData? = null
                        override fun invalidate(bvid: String, cid: Long) = Unit
                    }, object : DesktopOriginalVideoOwnerAnalytics {
                        override fun logVideoPlay(videoId: String, title: String, author: String) { check(videoId in listOf(A, B)) }
                        override fun logQualityChange(videoId: String, fromQuality: Int, toQuality: Int) = error("No quality change")
                    }, DesktopOriginalVideoOwnerCrash { _, _, message -> error("Unexpected original load error $message") },
                    unused(), MutableStateFlow(false))
                val media = DesktopOriginalVideoRootMediaFactory(repository, cache, gate, { owner }, { preparation ->
                    preparations.add(preparation); beforePreparation?.invoke(preparation)
                })
                rootMedia = media
                val requests = DesktopOriginalVideoOwnerRequestFactory(repository, DesktopLoginRepository(repository), epoch,
                    gate.scope, gate::owns, gate::commitEntry, { PlayerPreferences() }, capabilities,
                    { true }, { false }, { false }, subtitles, privacy,
                    { _, _ -> error("Preseeded visitor authorization must not retire") }, media::request)
                pageFactory = requests
                runtimeBinding = DesktopOriginalVideoRootRuntimeBinding(repository, runtime, gate) { owner.native }
                DesktopOriginalVideoOwnerAssembly.create(context, settings, repository, epoch, gate.scope,
                    gate::owns, gate::commitEntry, player, DesktopRepositoryPlaybackPublication(repository), { false },
                    runtimeBinding.sourceVersions, { expected -> publications.add(expected); runtimeBinding.accepted(expected) },
                    media::admission, captureMetadataBinding = { error("No readonly metadata recapture in this fixture") }, captureInvocation = { state, native ->
                        captures.add(Capture(state, currentCoroutineContext().job)); requests.capture(state, native, mediaPreparation = {
                            // Fixed-capability headless fixture; no physical NativeReady claim.
                            currentCoroutineContext().ensureActive()
                            if (!gate.owns()) throw CancellationException("Fixture preparation entry retired")
                        })
                    }, status = readbacks, prepareAcceptedMedia = media::accepted,
                    createPlugins = { native, invocations -> DesktopOriginalVideoOwnerPluginBridge(runtime, native, invocations,
                        runtimeBinding::generation, invocations::captureCurrentRequestAdmission, runtimeBinding::calls) },
                    confirmFollow = { error("No follow") }, feedback = {}, readHasPrimarySession = readbacks::hasPrimarySession,
                    readHasPrimaryAccessToken = readbacks::hasPrimaryAccessToken, readPrimaryMid = readbacks::primaryMid,
                    readOwnedCooldown = readbacks::cooldownRemainingMs, progress = progress, capabilities = capabilities,
                    applyPreferredVolume = {}, dashSegmentRequestsEnabled = { false }, logSeek = { _, _, _, _ -> },
                    diagnosticLoggingEnabled = { false }, resumeEndedSource = { error("No replay") }, root = root,
                    interactionAnalytics = unused(), effects = effects,
                    todayWatchFeedback = DesktopTodayWatchFeedbackWriteBinding(context, gate::owns, gate::commitEntry))
            }, {}, { runtimeBinding.close() }, {})
            ui {
                player.loadVersioned(PlaybackSource("file:///synthetic-bootstrap.avi"))
                actor = PremiumRecoveryActor(player)
                owner = slot.requireAssembly()
                owner.playback.initWithContext(DesktopOriginalPlayerSettingsContext(context, gate::owns, gate::commitEntry,
                    largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false }))
                portrait = DesktopOriginalPortraitPlatformBinding(owner, slot::currentAssembly, unused(),
                    if (storyMode) storyHomeRequests() else unused(), unused(), unused(),
                    captureRequest = { waitingFacetCalls++; error("Ordinary page cannot enter the native-waiting facet") },
                    creatorTeamFollowStateChanges = emptyFlow(), mediaCache = cache,
                    captureCookieHeader = { binding, url -> binding.captureMediaCookieHeader(url) },
                    favoriteQuickSaveDefaultFolder = flowOf(false), blockedUps = unused(), danmaku = unused(),
                    currentCdnPlugin = { null }, presentationLease = { _, _ -> error("No surface") },
                    danmakuLease = { _, _ -> error("No surface") }, releasePager = { _, _ -> error("No surface") },
                    recover = { _, _ -> error("No surface") }, share = { _, _ -> error("No share") },
                    feedback = { _, _ -> error("No feedback") }, surface = { _, _, _ -> error("No rendering") },
                    viewport = { _, _, _, _, _, _, _ -> error("No viewport") }, danmakuSurface = { _, _, _, _, _ -> error("No rendering") },
                    onPreparation = { preparations.add(it) }, prepareAcceptedMedia = { expected, plan, current, failure ->
                        owner.native.acceptedMedia({ rootMedia.accepted(it, plan) }, failure, current)
                    }, captureMetadataRequest = { value ->
                        pageFactory.captureBinding(value.captureLoadState()).also { metadataBindings.add(it) }
                    }, createPageRequestRepository = { value, binding ->
                        check(value === owner); binding.assertCurrent()
                        createDesktopOriginalVideoOwnerRequestRepository(repository, binding, subtitles, privacy,
                            { receipt, current -> repository.ownedHomeVisitorInitialized(receipt.accountEpoch, current) }).also { raw ->
                            pageRepositories.add(binding to raw)
                            raw.installMediaPreparation {
                                currentCoroutineContext().ensureActive(); binding.assertCurrent()
                                if (!gate.owns()) throw CancellationException("Actual entry preparation retired")
                                preparationBindings.add(binding)
                                checkNotNull(preparationPort) { "An explicit controlled preparation stage is required" }(binding)
                                currentCoroutineContext().ensureActive(); binding.assertCurrent()
                            }
                        }
                    }, storyFeed = { aid, bvid, pageSize, displayId ->
                        if (!storyMode) error("Unexpected Story feed in Portrait metadata fixture")
                        val rootFeed = DesktopOriginalVideoRootStoryFeedBinding(owner) { value ->
                            check(value === owner && slot.currentAssembly() === value && gate.owns())
                            pageFactory.captureBinding(value.captureLoadState()).also { binding ->
                                binding.assertCurrent()
                                storyCaptures.add(StoryCapture(binding, currentCoroutineContext().job))
                            }
                        }
                        rootFeed.getStoryFeed(aid, bvid, pageSize, displayId)
                    })
                if (storyMode) feedOwners = DesktopOriginalStoryFeedOwners(owner, portrait)
            }
            if (nextWatchMode) {
                behavior(PlaybackCompletionBehavior.CONTINUE_CURRENT_LOGIC)
                facade = DesktopUnifiedPlaybackFacade(scope, slot.assemblies, slot::currentAssembly,
                    slot::requireAssembly, realQueue, parentJob::isActive, MutableStateFlow(false),
                    { error("No assembly retirement from this explicit NextWatch fixture") },
                    { _, _ -> error("No cast") }, { _, _ -> error("No subtitle mode") },
                    { _, _ -> error("No subtitle preference") }, { error("No cancellation UI") },
                    { _, _, _ -> error("No queue-card resolution") }, { error("Unexpected facade error $it") })
            }
            // Observe the actual original backing StateFlow without seeding,
            // replacing or mutating it. Its synchronous commit is distinct from
            // the original public combine(...).stateIn projection on Main.
            gate.scope.launch(Dispatchers.Unconfined) {
                originalState().collect { state ->
                    if (state is VideoPlaybackUiState.Success && state.currentCdnIndex == 1) {
                        owner.native.current()?.let { child -> cdnEmissions.add(CdnEmission(child, state.currentCdnIndex, runtimeBinding.generation(child))) }
                    }
                }
            }
            gate.scope.launch { owner.playback.toastEvent.collect { toasts.add(it.toString()) } }
        }
        fun state() = owner.playback.uiState.value as VideoPlaybackUiState.Success
        @Suppress("UNCHECKED_CAST")
        fun originalState(): StateFlow<VideoPlaybackUiState> = owner.playback.javaClass
            .getDeclaredField("_uiState").apply { isAccessible = true }.get(owner.playback) as StateFlow<VideoPlaybackUiState>
        fun accepted() = checkNotNull(owner.native.current())
        fun publicationRequestJob(expected: DesktopOriginalVideoAcceptedPublication): Job {
            val initial = assertIs<DesktopOriginalVideoInitialPublication>(expected.nativeSource.source.nativePublication)
            // Read the exact immutable request Job of the actual production
            // publication. No Job/source/state is replaced by this observation.
            return DesktopOriginalVideoInitialPublication::class.java.getDeclaredField("requestJob")
                .apply { isAccessible = true }.get(initial) as Job
        }
        private fun rejectRequest(request: Request): Nothing {
            unexpectedRequests.add("${request.method} ${request.url.host}${request.url.encodedPath}")
            throw IOException("Unrecognized memory fixture request")
        }
        private fun memoryMedia(request: Request): Response {
            if (request.method !in setOf("HEAD", "GET") || request.url.encodedPath !in
                listOf(A, B).flatMap { listOf("/$it-video.m4s", "/$it-audio.m4s") }) rejectRequest(request)
            if (refreshExpiredUrls && firstUrlsExpired && request.url.queryParameter("retry_revision") == "1") {
                return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(403).message("expired fixture URL")
                    .body(ByteArray(0).toResponseBody("application/octet-stream".toMediaType())).build()
            }
            val content = ByteArray(4096) { (it % 251).toByte() }
            val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("memory media")
                .header("Accept-Ranges", "bytes").header("ETag", "\"synthetic-${request.url.encodedPath}\"")
            val type = "application/octet-stream".toMediaType()
            if (request.method == "HEAD") return builder.code(200).header("Content-Length", "${content.size}")
                .body(ByteArray(0).toResponseBody(type)).build()
            val range = request.header("Range")
            if (range == null) return builder.code(200).header("Content-Length", "${content.size}")
                .body(content.toResponseBody(type)).build()
            val match = Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(range) ?: rejectRequest(request)
            val start = match.groupValues[1].toLongOrNull() ?: rejectRequest(request)
            val requestedEnd = match.groupValues[2].takeIf(String::isNotEmpty)?.toLongOrNull() ?: (content.size - 1L)
            if (start >= content.size || requestedEnd < start) return builder.code(416)
                .header("Content-Range", "bytes */${content.size}").body(ByteArray(0).toResponseBody(type)).build()
            val end = minOf(requestedEnd, content.size - 1L)
            val bytes = content.copyOfRange(start.toInt(), end.toInt() + 1)
            return builder.code(206).header("Content-Range", "bytes $start-$end/${content.size}")
                .header("Content-Length", "${bytes.size}").body(bytes.toResponseBody(type)).build()
        }
        private fun memoryPbp(request: Request): Response {
            val bvid = request.url.queryParameter("bvid")
            val subject = when (bvid) { A -> "104" to "101"; B -> "105" to "102"; else -> rejectRequest(request) }
            if (request.method != "GET" || request.url.encodedPath != "/pbp/data" ||
                request.url.queryParameterNames != setOf("cid", "bvid", "aid") ||
                request.url.queryParameter("cid") != subject.first || request.url.queryParameter("aid") != subject.second)
                rejectRequest(request)
            // Exact original API returns ResponseBody; its real metadata protocol
            // invokes parsePbpProgressData on top-level step_sec/events.default.
            val body = """{"code":0,"step_sec":5,"events":{"default":[0,1,3,1]}}"""
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory PBP")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        fun causeContainsIdentity(value: Throwable, expected: Throwable): Boolean {
            var current: Throwable? = value
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            repeat(32) {
                val next = current ?: return false
                if (!seen.add(next)) return false
                if (next === expected) return true
                current = next.cause
            }
            return false
        }
        /** Same actual page caller remains active through raw metadata, preparation,
         * original protocol, source preparation and the final Native gate. */
        fun startPage(bvid: String, aid: Long, cid: Long, publish: Boolean): PageOperation = PageOperation().also { op ->
            op.result = gate.scope.async {
                op.caller = currentCoroutineContext().job
                val binding = portrait.capturePageRequest(bvid, aid, cid)
                op.original = owner.captureLoadState(); op.binding.complete(binding)
                val original = checkNotNull(op.original)
                fun current() = gate.owns() && owner.captureLoadState().let {
                    it.currentRequest === original.currentRequest && it.currentLoadRequestToken == original.currentLoadRequestToken
                }
                val result = portrait.loadPageDetails(binding, bvid, aid, cid, 64, ::current) { op.detail.complete(it) }
                if (publish && result.isSuccess) {
                    val (info, data) = result.getOrThrow()
                    val selection = assertNotNull(resolvePortraitPlaybackStreamUrls(data, 64,
                        isHevcSupported = false, isAv1Supported = false, isDolbyAudioSupported = false,
                        isDolbyAudioSoftwareDecoded = false))
                    val source = portrait.prepareSource(binding, info, data, selection.videoUrl, selection.audioUrl,
                        resolvePortraitMediaId(info.bvid, info.cid), selection, emptyList())
                    assertTrue(portrait.publishSource(binding, source, 1, false, ::current))
                }
                result
            }
        }
        fun queueItem(bvid: String, title: String = "Queue $bvid") = PlaylistItem(
            bvid, if (bvid == A) 104L else 105L, title, "https://media.invalid/cover.jpg", "Fixture UP", duration = 60L)
        suspend fun setQueue(items: List<PlaylistItem>) = ui { realQueue.setPlaylist(items, 0) }
        suspend fun behavior(value: PlaybackCompletionBehavior) {
            check(nextWatchMode)
            DesktopOriginalPlaybackPreferenceOperation.run(settingsContext, gate::owns) {
                DesktopOriginalVideoControlSettings.setPlaybackCompletionBehavior(settingsContext, value)
            }
        }
        /** Supply property-string readbacks to the existing memory ABI actor. This
         * does not replace MPV state, a Native publication or the original VM. */
        suspend fun clock(position: Double) = ui {
            check(nextWatchMode && position.isFinite())
            assertEquals(0, actor.native.mpv_set_property_string(Pointer(1L), "time-pos", position.toString()))
            assertEquals(0, actor.native.mpv_set_property_string(Pointer(1L), "duration", "120.0"))
            actor.drain(); actor.loaded()
            assertEquals(position, player.state.value.positionSeconds)
            assertEquals(120.0, player.state.value.durationSeconds)
            assertEquals(DesktopOriginalPlaybackStates.STATE_READY, owner.section.playbackState)
            assertTrue(player.ownsSourceSnapshot(accepted().nativeSource))
            assertFalse(player.state.value.ready, "Memory ABI FILE_LOADED never claims real MPV core initialization")
        }
        suspend fun awaitHint(predicate: (DesktopOriginalVideoNextWatchHint) -> Boolean): DesktopOriginalVideoNextWatchHint =
            withTimeout(8_000) { assertNotNull(owner.nextWatch.state.first { it != null && predicate(it) }) }
        suspend fun dispatchForward(click: DesktopOriginalManualPlaybackClick): Boolean = scope.async {
            val actualCaller = currentCoroutineContext().job
            assertTrue(EventQueue.isDispatchThread()); assertTrue(actualCaller.isActive)
            facade.navigateManual(click, forward = true, caller = actualCaller, uiCurrent = gate::owns)
        }.await()
        suspend fun load(bvid: String, aid: Long, cid: Long, audioLang: String? = null) {
            ui { owner.playback.loadVideo(bvid, aid, cid = cid, audioLang = audioLang, autoPlay = false) }
            awaitLoaded(bvid)
            ui { actor.drain(); actor.loaded(); player.setPaused(true); actor.drain() }
            withTimeout(8_000) { while (runtimeBinding.generation(accepted()) == null) delay(5) }
            withTimeout(8_000) {
                while (state().relatedLoadState != VideoPlaybackUiState.RelatedLoadState.READY) delay(5)
            }
        }
        suspend fun awaitLoaded(bvid: String) = withTimeout(8_000) {
            while ((owner.playback.uiState.value as? VideoPlaybackUiState.Success)?.info?.bvid != bvid ||
                owner.native.current()?.request?.bvid != bvid) delay(5)
        }
        /** Deliver actual log-message and end-file ABI input to the existing memory MPV actor.
         * The real registered section listener and original recovery controller initiate retry;
         * this helper never calls VM.retry, replaces a VM state or fabricates a source ticket. */
        suspend fun failExpiredNetwork(): PlayerFailure = uiResult {
            check(refreshExpiredUrls)
            firstUrlsExpired = true
            fun text(value: String): Memory = value.toByteArray(Charsets.UTF_8).let { bytes ->
                Memory(bytes.size + 1L).apply { write(0, bytes, 0, bytes.size); setByte(bytes.size.toLong(), 0) }
            }
            text("ffmpeg").use { prefix -> text("error").use { level -> text("connection failed").use { message ->
                Memory(32).use { data ->
                    data.clear(); data.setPointer(0, prefix); data.setPointer(8, level); data.setPointer(16, message)
                    data.setInt(24, 20)
                    PremiumRecoveryActor::class.java.getDeclaredMethod("event", Integer.TYPE, Pointer::class.java)
                        .apply { isAccessible = true }.invoke(actor, 2, data)
                }
            } } }
            actor.fail(-13).also { failure ->
                assertSame(failure, player.state.value.failure)
                assertEquals(PlayerFailureKind.NETWORK, failure.kind)
                assertEquals(-13, failure.nativeCode)
            }
        }
        suspend fun holdCdnSwitch(): HeldDispatch = uiResult {
            assertTrue(owner.environment.plugins.isPlaybackDispatchCurrent(accepted()))
            assertTrue(state().cdnCount > 1)
            dispatcher.arm(); owner.playback.switchCdnTo(1); dispatcher.take()
        }
        fun release(held: HeldDispatch) = dispatcher.release(held)
        fun assertOutsideAdmission() {
            fun lock(type: Class<*>, instance: Any) = type.getDeclaredField("lock").apply { isAccessible = true }.get(instance)
            assertFalse(Thread.holdsLock(lock(DesktopSessionStore::class.java, sessions)))
            assertFalse(Thread.holdsLock(lock(DesktopOriginalVideoRootGate::class.java, gate)))
            assertFalse(Thread.holdsLock(lock(MpvPlayer::class.java, player)))
        }
        suspend fun ui(action: () -> Unit) = withContext(Dispatchers.Swing) { action() }
        suspend fun <T> uiResult(action: () -> T): T = withContext(Dispatchers.Swing) { action() }
        private fun strictStoryResponse(block: () -> Response): Response = try { block() }
            catch (error: Throwable) { failures.add(error); throw error }
        private fun storyHomeRequests(): DesktopHomeVideoRequests = object : DesktopHomeVideoRequests {
            override suspend fun getHomeVideos(idx: Int): Result<List<VideoItem>> {
                currentCoroutineContext().ensureActive(); check(storyMode && slot.currentAssembly() === owner && gate.owns())
                val binding = pageFactory.captureBinding(owner.captureLoadState())
                storyCaptures.add(StoryCapture(binding, currentCoroutineContext().job))
                val environment = DesktopHomeProtocolEnvironment(binding.primaryApi, binding.environment.guestApi, unused(),
                    gate.scope, { runCatching { binding.assertCurrent() }.isSuccess }, binding::admitCurrentMutation,
                    { SettingsManager.FeedApiType.WEB }, { 20 },
                    { Result.failure(IllegalStateException("Use actual sealed nav API for WBI keys")) },
                    binding::primaryAccessToken, binding::primaryCsrf, {
                        repository.ownedHomeCookie("buvid3", binding.receipt.accountEpoch) { runCatching { binding.assertCurrent() }.isSuccess }
                    },
                    { currentCoroutineContext().ensureActive(); binding.assertCurrent() }, binding.environment.ensureBuvid)
                val protocol = DesktopOriginalHomeVideoProtocol(environment)
                return try { protocol.getHomeVideos(idx).also { currentCoroutineContext().ensureActive(); binding.assertCurrent() } }
                    finally { protocol.close() }
            }
            override suspend fun getPopularVideos(page: Int): Result<List<VideoItem>> = error("No Story popular request")
            override suspend fun getRankingVideos(rid: Int, type: String): Result<List<VideoItem>> = error("No Story ranking request")
            override suspend fun getWeeklyMustWatchVideos(): Result<List<VideoItem>> = error("No Story weekly request")
            override suspend fun getPreciousVideos(): Result<List<VideoItem>> = error("No Story precious request")
            override suspend fun getRegionVideos(tid: Int, page: Int): Result<List<VideoItem>> = error("No Story region request")
            override suspend fun getNavInfo(): Result<DesktopHomeNavPublication> = error("No Story nav publication")
            override suspend fun getPreviewVideoUrl(bvid: String, cid: Long): String? = error("No Story preview request")
            override suspend fun isVerticalVideo(bvid: String, aid: Long): Boolean = error("No Story dimension request")
        }
        suspend fun startStory(seed: String): StoryViewModel = uiResult {
            check(storyMode); feedOwners.get(storyKey).also { it.startFeed(seed) }
        }
        suspend fun awaitStory(vm: StoryViewModel, condition: (StoryViewModel) -> Boolean) = withTimeout(8_000) {
            while (!uiResult { condition(vm) && !vm.uiState.value.isLoading }) {
                assertTrue(failures.isEmpty(), failures.toString()); assertTrue(unexpectedRequests.isEmpty(), unexpectedRequests.toString()); delay(5)
            }
        }
        fun storyGate() = CountDownLatch(1).also(storyResponseGates::add)
        fun assertStoryOnly() {
            assertTrue(playRequests.isEmpty()); assertTrue(preparationBindings.isEmpty()); assertTrue(preparations.isEmpty())
            assertTrue(publications.isEmpty()); assertNull(owner.native.current())
            assertFalse(player.state.value.ready, "Story feed success is not native core readiness")
        }
        suspend fun close() {
            storyResponseGates.forEach { it.countDown() }
            if (::feedOwners.isInitialized) feedOwners.close()
            tagsRelease.countDown(); relatedRelease.countDown(); dispatcher.drainOnClose()
            if (::facade.isInitialized) facade.close()
            if (nextWatchMode && ::settingsContext.isInitialized && gate.owns())
                behavior(PlaybackCompletionBehavior.CONTINUE_CURRENT_LOGIC)
            slot.closeAndJoin()
            nextWatchQueue?.closeAndJoin()
            parentJob.cancelAndJoin()
            if (::runtimeBinding.isInitialized) runtimeBinding.close()
            if (::actor.isInitialized) actor.close()
            if (::cache.isInitialized) cache.close()
            subtitles.close(); player.close()
            transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll()
            assertTrue(failures.isEmpty(), failures.toString())
            assertTrue(unexpectedRequests.isEmpty(), unexpectedRequests.toString())
            deleteCreatedDirectory(directory, "cdn-original-owner-")
        }
        suspend fun useSuspending(block: suspend (Fixture) -> Unit) {
            var primary: Throwable? = null
            try { initialize(); withTimeout(30_000) { block(this@Fixture) } }
            catch (failure: Throwable) { primary = failure; throw failure }
            finally {
                try { withContext(NonCancellable) { close() } }
                catch (cleanup: Throwable) {
                    val failure = primary
                    if (failure == null) throw cleanup
                    if (failure !== cleanup) failure.addSuppressed(cleanup)
                }
            }
        }
    }

    companion object {
        private const val A = "BV1GJ411x7h7"
        private const val B = "BV17x411w7KC"
        private const val C = "BV1xx411c7mD"
        private const val D = "BV1xx411c7mE"
        private fun storyItem(id: Long, bvid: String, aid: Long = id) =
            """{"id":$id,"bvid":"$bvid","goto":"av","title":"Story $id","player_args":{"aid":$aid,"cid":${aid + 3},"bvid":"$bvid"}}"""
        private fun storyJson(vararg items: String) = """{"code":0,"data":{"items":[${items.joinToString(",")}]}}"""
        private fun homeItem(aid: Long, bvid: String) =
            """{"id":$aid,"bvid":"$bvid","cid":${aid + 3},"goto":"av","title":"Home $aid","pic":"","duration":30,"owner":{"mid":51,"name":"Fixture","face":""},"stat":{"view":1,"danmaku":0}}"""
        private fun homeJson(vararg items: String) = """{"code":0,"data":{"item":[${items.joinToString(",")}]}}"""
        private fun video(bvid: String) = "https://media.invalid/$bvid-video.m4s?synthetic=1"
        private fun backupVideo(bvid: String) = "https://backup.invalid/$bvid-video.m4s?synthetic=1"
        private fun audio(bvid: String) = "https://media.invalid/$bvid-audio.m4s?synthetic=1"
        private fun backupAudio(bvid: String) = "https://backup.invalid/$bvid-audio.m4s?synthetic=1"
        private fun viewJson(bvid: String): String {
            check(bvid in listOf(A, B)); val aid = if (bvid == A) 101 else 102; val cid = if (bvid == A) 104 else 105
            return """{"code":0,"data":{"bvid":"$bvid","aid":$aid,"cid":$cid,"title":"Fixture $bvid","pic":"https://media.invalid/cover.jpg","duration":60,"owner":{"mid":51,"name":"Fixture","face":""},"pages":[{"cid":$cid,"page":1,"part":"Fixture","duration":60}]}}"""
        }
        private fun playJson(bvid: String): String = """{"code":0,"data":{"quality":64,"timelength":60000,"accept_quality":[64],"accept_description":["720P"],"dash":{"duration":60,"video":[{"id":64,"baseUrl":"${video(bvid)}","backupUrl":["${backupVideo(bvid)}"],"bandwidth":100000,"mime_type":"video/mp4","codecs":"avc1.640028","width":1280,"height":720,"frameRate":"30","codecid":7,"segment_base":{"initialization":"0-31","index_range":"32-63"}}],"audio":[{"id":30280,"baseUrl":"${audio(bvid)}","backupUrl":["${backupAudio(bvid)}"],"bandwidth":192000,"mime_type":"audio/mp4","codecs":"mp4a.40.2","segment_base":{"initialization":"0-31","index_range":"32-63"}}]}}}"""
        private fun retryVideo(revision: Int) = "https://media.invalid/$A-video.m4s?retry_revision=$revision"
        private fun retryAudio(revision: Int) = "https://media.invalid/$A-audio.m4s?retry_revision=$revision"
        private fun retryPlayJson(revision: Int): String = """{"code":0,"data":{"quality":64,"timelength":60000,"cur_language":"en","accept_quality":[64],"accept_description":["720P"],"dash":{"duration":60,"video":[{"id":64,"baseUrl":"${retryVideo(revision)}","backupUrl":[],"bandwidth":100000,"mime_type":"video/mp4","codecs":"avc1.640028","width":1280,"height":720,"frameRate":"30","codecid":7,"segment_base":{"initialization":"0-31","index_range":"32-63"}}],"audio":[{"id":30280,"baseUrl":"${retryAudio(revision)}","backupUrl":[],"bandwidth":192000,"mime_type":"audio/mp4","codecs":"mp4a.40.2","segment_base":{"initialization":"0-31","index_range":"32-63"}}]}}}"""
        private fun deleteCreatedDirectory(directory: Path, prefix: String) {
            val absolute = directory.toAbsolutePath().normalize(); val real = directory.toRealPath()
            val temp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
            check(absolute.parent == temp && real.parent == temp.toRealPath() && absolute != temp)
            check(absolute.fileName.toString().startsWith(prefix))
            fun validate(path: Path, attrs: BasicFileAttributes) {
                val value = path.toAbsolutePath().normalize()
                check(value.startsWith(absolute) && !attrs.isSymbolicLink && !attrs.isOther)
                // The runner's temporary parent may use a Windows short-path alias.
                // Every child must still resolve to its exact location inside this real root.
                check(path.toRealPath() == real.resolve(absolute.relativize(value)).normalize())
                if (System.getProperty("os.name").startsWith("Windows"))
                    check((Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS) as Int) and 0x400 == 0)
            }
            val nodes = mutableListOf<Path>()
            Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult { validate(dir, attrs); nodes.add(dir); return FileVisitResult.CONTINUE }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { validate(file, attrs); nodes.add(file); return FileVisitResult.CONTINUE }
            })
            for (path in nodes.sortedByDescending { it.nameCount }) {
                check(directory.toRealPath() == real); validate(path, Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)); Files.delete(path)
            }
        }
    }
}

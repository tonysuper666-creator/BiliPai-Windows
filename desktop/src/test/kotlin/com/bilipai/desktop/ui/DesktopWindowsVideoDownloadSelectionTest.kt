package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol
import com.android.purebilibili.data.repository.DesktopOriginalVideoEngagementProtocol
import com.android.purebilibili.feature.download.DownloadOptions
import com.android.purebilibili.feature.download.DownloadTask
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionStore
import com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Actual compiled generated VM and existing invocation port, with in-memory
 * cached DASH/task ports only. No socket, account, player, DLL, SDK or GUI. */
class DesktopWindowsVideoDownloadSelectionTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T

    private class PausedDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        fun drain() {
            var count = 0
            while (queued.isNotEmpty()) {
                check(++count <= 1000) { "Fixture dispatch did not quiesce" }
                queued.removeFirst().run()
            }
        }
    }

    private fun success() = VideoPlaybackUiState.Success(
        info = ViewInfo(bvid = "BVfixture", aid = 17, cid = 70, title = "Fixture"),
        playUrl = "https://fixture.invalid/playing64", audioUrl = "https://fixture.invalid/audio",
        currentQuality = 64, qualityIds = listOf(64, 80, 120), qualityLabels = listOf("720P", "1080P", "4K"),
        cachedDashVideos = listOf(DashVideo(id = 80, baseUrl = "https://fixture.invalid/selected80")),
        cachedDashAudios = listOf(DashAudio(id = 30280, baseUrl = "https://fixture.invalid/audio")),
    )

    private inner class Harness(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined) : AutoCloseable {
        val folder = Files.createTempDirectory("original-download-selection-")
        val unexpected = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error -> unexpected += error })
        val pageJob = Job()
        val context = DesktopPluginContext(DesktopPluginStore(folder))
        var entry = true
        var account = 1L
        val original = publication()
        var accepted: DesktopOriginalVideoAcceptedPublication? = original
        val constructed = mutableListOf<DownloadTask>()
        val queued = mutableListOf<DownloadTask>()
        var afterConstruction: () -> Unit = {}
        var beforeFinalAdmission: () -> Unit = {}
        fun publication() = DesktopOriginalVideoAcceptedPublication(
            PlaybackRequest.create("BVfixture", aid = 17, cid = 70),
            OwnedPlaybackSourceSnapshot(1, PlaybackSource("https://fixture.invalid/no-media")))
        fun owns() = entry && account == 1L
        fun clickCurrent() = owns() && pageJob.isActive && accepted === original
        fun commit(action: () -> Unit): Boolean = if (!owns()) false else { action(); true }
        val repository = unused<DesktopOriginalVideoOwnerRepository>()
        val media = unused<DesktopOriginalVideoMediaPort>()
        val actions = unused<DesktopOriginalVideoOwnerActions>()
        val invocations = DesktopOriginalVideoPlaybackInvocationPorts(scope, ::owns, {
            val caller = currentCoroutineContext().job
            val epoch = account
            DesktopOriginalVideoPlaybackInvocation(repository, media) {
                if (!entry || account != epoch || !caller.isActive) throw CancellationException("Captured request retired")
            }
        }, unused<DesktopOriginalVideoPlaybackStatus>(), { media })
        val plugins = Proxy.newProxyInstance(DesktopOriginalVideoOwnerPlugins::class.java.classLoader,
            arrayOf(DesktopOriginalVideoOwnerPlugins::class.java)) { _, method, args ->
            when (method.name) {
                "capturePlaybackDispatch" -> accepted.takeIf { owns() }
                "isPlaybackDispatchCurrent" -> owns() && accepted === args!![0]
                else -> error("No plugin execution: ${method.name}")
            }
        } as DesktopOriginalVideoOwnerPlugins
        val downloads = object : DesktopOriginalVideoOwnerDownload {
            override val tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
            override fun captureTask(task: DownloadTask, explicitReply: PlayUrlData?): DownloadTask {
                assertNull(explicitReply) // These tests use the actual cached-track branch.
                currentThreadRequest()
                constructed += task
                afterConstruction()
                return task
            }
            private fun currentThreadRequest() { invocations.requireRequestRepository() }
            override fun addTask(task: DownloadTask): Boolean = error("Exact selection gate must reach the existing overload")
            override fun addTask(task: DownloadTask, stillCaptured: () -> Boolean): Boolean {
                currentThreadRequest()
                if (!stillCaptured()) throw CancellationException("Old download click")
                beforeFinalAdmission()
                if (!stillCaptured()) throw CancellationException("Retired at admission")
                queued += task
                return true
            }
            override fun getVideoTask(bvid: String, cid: Long): DownloadTask? = null
            override suspend fun saveImageToGallery(context: DesktopOriginalPlayerSettingsContext, url: String, title: String) = error("No cover download")
        }
        private val api = unused<BilibiliApi>()
        private val interaction = VideoInteractionUseCase(DesktopOriginalVideoEngagementProtocol(api,
            { "fixture-csrf" }, { 1L }, { "fixture-session" }, { null }, {}, {},
            DesktopOriginalFavoriteFolderProtocol(api, { 1L }, { "fixture-csrf" }, {})),
            unused<DesktopOriginalVideoInteractionAnalytics>())
        private val settings = DesktopOriginalPlayerSettingsContext(context, ::owns, ::commit,
            largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
        private val useCase = DesktopOriginalVideoPlaybackUseCaseEnvironment(context, repository, actions,
            unused<DesktopOriginalVideoProgressPort>(), unused<DesktopOriginalVideoPlaybackCapabilities>(), media,
            { error("No native volume effect") }, { emptyMap() }, {}, { false }, { _, _, _, _ -> }, ::owns)
        val vm = VideoPlaybackViewModel(DesktopOriginalVideoPlaybackOwnerEnvironment(scope, settings, invocations,
            repository, unused(), actions, useCase, interaction, unused(), unused(), unused(), plugins, downloads,
            unused(), unused(), { null }, unused(), unused(), unused(), unused(), MutableStateFlow(false), ::owns, ::commit,
            DesktopTodayWatchFeedbackWriteBinding(context, ::owns, ::commit)))
        val session = VideoPlaybackViewModel::class.java.getDeclaredField("playbackSessionStore").apply { isAccessible = true }
            .get(vm) as PlaybackSessionStore
        init {
            @Suppress("UNCHECKED_CAST")
            val raw = VideoPlaybackViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
                .get(vm) as MutableStateFlow<VideoPlaybackUiState>
            raw.value = success()
            session.updateCurrentMedia("BVfixture", 70)
            session.setCurrentLoadRequestToken(12)
        }
        fun chooser() = assertNotNull(DesktopWindowsVideoDownloadSelection.capture(success(), pageJob, ::clickCurrent,
            vm::downloadWithQuality))
        override fun close() {
            pageJob.cancel(); scope.cancel(); folder.toFile().deleteRecursively()
            assertTrue(unexpected.isEmpty(), unexpected.joinToString { it.toString() })
        }
    }

    @Test fun highestAdvertisedQualityAndDanmakuOptionReachActualOriginalTask() {
        Harness().use { h ->
            val chooser = h.chooser()
            assertEquals(listOf(120, 80, 64), chooser.qualityOptions.map { it.first })
            assertEquals(120, chooser.currentQuality)
            assertTrue(chooser.select(80, DownloadOptions(includeDanmaku = false)))
            assertFalse(chooser.select(64, DownloadOptions()))
            val task = h.queued.single()
            assertEquals("BVfixture", task.bvid); assertEquals(70L, task.cid)
            assertEquals(80, task.quality); assertEquals("1080P", task.qualityDesc)
            assertEquals("https://fixture.invalid/selected80", task.videoUrl)
            assertEquals("https://fixture.invalid/audio", task.audioUrl)
            assertFalse(task.options.includeDanmaku)
            assertSame(h.constructed.single(), task)
        }
    }

    @Test fun pausedOriginalDispatchCannotDownloadReplacedAcceptedSource() {
        val dispatcher = PausedDispatcher()
        Harness(dispatcher).use { h ->
            assertTrue(h.chooser().select(80, DownloadOptions()))
            h.accepted = h.publication() // Equal IDs/version do not revive the captured dispatch.
            dispatcher.drain()
            assertTrue(h.constructed.isEmpty()); assertTrue(h.queued.isEmpty())
        }
    }

    @Test fun pausedOriginalDispatchCannotMixSuccessWithLaterVmTarget() {
        val dispatcher = PausedDispatcher()
        Harness(dispatcher).use { h ->
            assertTrue(h.chooser().select(80, DownloadOptions()))
            h.session.updateCurrentMedia("BVsuccessor", 71)
            dispatcher.drain()
            assertTrue(h.constructed.isEmpty()); assertTrue(h.queued.isEmpty())
        }
    }

    @Test fun cancelledPageJobRejectsQueuedOriginalRequestWhileEntryRemainsAlive() {
        val dispatcher = PausedDispatcher()
        Harness(dispatcher).use { h ->
            assertTrue(h.chooser().select(80, DownloadOptions()))
            h.pageJob.cancel(); dispatcher.drain()
            assertTrue(h.scope.isActive); assertTrue(h.constructed.isEmpty()); assertTrue(h.queued.isEmpty())
        }
    }

    @Test fun sourceRetirementAfterTaskConstructionCannotReachQueue() {
        Harness().use { h ->
            h.afterConstruction = { h.accepted = h.publication() }
            assertTrue(h.chooser().select(80, DownloadOptions()))
            assertEquals(1, h.constructed.size); assertTrue(h.queued.isEmpty())
        }
    }

    @Test fun originalAddTaskOverloadRechecksCapturedPredicateAtFinalAdmission() {
        Harness().use { h ->
            h.beforeFinalAdmission = { h.pageJob.cancel() }
            assertTrue(h.chooser().select(80, DownloadOptions()))
            assertEquals(1, h.constructed.size); assertTrue(h.queued.isEmpty())
        }
    }

    @Test fun staleDismissedAndUnadvertisedChooserCallbacksNeverInvokeProducer() {
        Harness().use { h ->
            val chooser = h.chooser()
            assertFalse(chooser.select(999, DownloadOptions())); assertTrue(chooser.isCurrent())
            chooser.dismiss(); assertFalse(chooser.select(80, DownloadOptions()))
            val old = h.chooser(); h.accepted = h.publication()
            assertFalse(old.select(80, DownloadOptions())); assertTrue(h.constructed.isEmpty())
        }
    }

    @Test fun progressiveWithoutSplitAudioKeepsItsExistingCurrentSourceRoute() {
        val progressive = success().copy(audioUrl = null, cachedDashAudios = emptyList())
        assertFalse(desktopWindowsVideoCanChooseDownloadQuality(progressive))
        assertTrue(desktopWindowsVideoCanChooseDownloadQuality(success()))
        val job = Job()
        try {
            assertNull(DesktopWindowsVideoDownloadSelection.capture(progressive, job, { true }) { _, _, _ ->
                error("Progressive source must keep the existing current-source callback")
            })
        } finally { job.cancel() }
    }

    @Test fun missingQualityLabelsPreserveOnlyTheKnownPlayingQuality() {
        val job = Job()
        try {
            var selected = 0
            val value = assertNotNull(DesktopWindowsVideoDownloadSelection.capture(
                success().copy(qualityIds = emptyList(), qualityLabels = emptyList()), job, { true }) { q, _, _ -> selected = q })
            assertEquals(listOf(64), value.qualityOptions.map { it.first })
            assertFalse(value.select(80, DownloadOptions())); assertTrue(value.select(64, DownloadOptions()))
            assertEquals(64, selected)
        } finally { job.cancel() }
    }
}

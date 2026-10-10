package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol
import com.android.purebilibili.data.repository.DesktopOriginalVideoEngagementProtocol
import com.android.purebilibili.feature.download.DownloadOptions
import com.android.purebilibili.feature.download.DownloadTask
import com.android.purebilibili.feature.download.resolveBatchDownloadCandidates
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
import kotlinx.coroutines.flow.collect
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

    private fun batchSuccess() = success().copy(info = success().info.copy(pages = listOf(
        Page(cid = 70, page = 1, part = "First", duration = 10),
        Page(cid = 71, page = 2, part = "Second", duration = 20),
        Page(cid = 72, page = 3, part = "Third", duration = 30),
    )))

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
        var afterAdmission: () -> Unit = {}
        var beforeReply: () -> Unit = {}
        val requested = mutableListOf<Pair<String, Long>>()
        val capturedReplies = mutableListOf<PlayUrlData?>()
        val savedCovers = mutableListOf<Pair<String, String>>()
        var coverAdmissions = 0
        var beforeCoverPublication: () -> Unit = {}
        val existing = mutableMapOf<Long, DownloadTask>()
        fun publication() = DesktopOriginalVideoAcceptedPublication(
            PlaybackRequest.create("BVfixture", aid = 17, cid = 70),
            OwnedPlaybackSourceSnapshot(1, PlaybackSource("https://fixture.invalid/no-media")))
        fun owns() = entry && account == 1L
        fun clickCurrent() = owns() && pageJob.isActive && accepted === original
        fun commit(action: () -> Unit): Boolean = if (!owns()) false else { action(); true }
        val repository = Proxy.newProxyInstance(DesktopOriginalVideoOwnerRepository::class.java.classLoader,
            arrayOf(DesktopOriginalVideoOwnerRepository::class.java)) { _, method, args ->
            when (method.name) {
                "getPlayUrlData" -> {
                    val bvid = args!![0] as String; val cid = args[1] as Long; val quality = args[2] as Int
                    requested += bvid to cid
                    beforeReply()
                    PlayUrlData(quality = quality, acceptQuality = listOf(quality), dash = Dash(
                        video = listOf(DashVideo(id = quality, baseUrl = "https://fixture.invalid/$bvid/$cid/video", codecs = "avc1.640028")),
                        audio = listOf(DashAudio(id = 30280, baseUrl = "https://fixture.invalid/$bvid/$cid/audio"))))
                }
                "isAppApiCoolingDown" -> false
                else -> error("No real repository execution: ${method.name}")
            }
        } as DesktopOriginalVideoOwnerRepository
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
                if (task.bvid == "BVfixture" && task.cid == 70L) assertNull(explicitReply)
                else assertNotNull(explicitReply)
                capturedReplies += explicitReply
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
                afterAdmission()
                return true
            }
            override fun getVideoTask(bvid: String, cid: Long): DownloadTask? = existing[cid]?.takeIf { it.bvid == bvid }
            override suspend fun saveImageToGallery(context: DesktopOriginalPlayerSettingsContext, url: String, title: String) = error("No cover download")
            override suspend fun saveImageToGallery(context: DesktopOriginalPlayerSettingsContext, url: String, title: String,
                stillCaptured: () -> Boolean, fileAdmission: ((() -> Unit) -> Boolean)?): Boolean {
                currentThreadRequest()
                if (!stillCaptured()) throw CancellationException("Cover click retired")
                beforeCoverPublication()
                val admission = assertNotNull(fileAdmission)
                if (!admission {
                    if (!stillCaptured()) throw CancellationException("Cover click retired at final publication")
                    savedCovers += url to title
                }) throw CancellationException("Cover file admission retired")
                return true
            }
        }
        private val api = unused<BilibiliApi>()
        private val interaction = VideoInteractionUseCase(DesktopOriginalVideoEngagementProtocol(api,
            { "fixture-csrf" }, { 1L }, { "fixture-session" }, { null }, {}, {},
            DesktopOriginalFavoriteFolderProtocol(api, { 1L }, { "fixture-csrf" }, {})),
            unused<DesktopOriginalVideoInteractionAnalytics>())
        private val settings = DesktopOriginalPlayerSettingsContext(context, ::owns, ::commit,
            largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
        private val useCase = DesktopOriginalVideoPlaybackUseCaseEnvironment(context, repository, actions,
            unused<DesktopOriginalVideoProgressPort>(), object : DesktopOriginalVideoPlaybackCapabilities {
                override fun isHevcSupported() = false
                override fun isAv1Supported() = false
                override fun isHdrSupported() = false
                override fun isDolbyVisionSupported() = false
                override fun isDolbyAtmosAudioSupported() = false
                override fun isDolbySoftwareAudioDecoderRequired() = false
            }, media,
            { error("No native volume effect") }, { emptyMap() }, {}, { false }, { _, _, _, _ -> }, ::owns)
        val vm = VideoPlaybackViewModel(DesktopOriginalVideoPlaybackOwnerEnvironment(scope, settings, invocations,
            repository, unused(), actions, useCase, interaction, unused(), unused(), unused(), plugins, downloads,
            unused(), unused(), { null }, unused(), unused(), unused(), unused(), MutableStateFlow(false), ::owns, ::commit,
            DesktopTodayWatchFeedbackWriteBinding(context, ::owns, ::commit)))
        val session = VideoPlaybackViewModel::class.java.getDeclaredField("playbackSessionStore").apply { isAccessible = true }
            .get(vm) as PlaybackSessionStore
        fun installSuccess(value: VideoPlaybackUiState.Success) {
            @Suppress("UNCHECKED_CAST")
            val raw = VideoPlaybackViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
                .get(vm) as MutableStateFlow<VideoPlaybackUiState>
            raw.value = value
        }
        init {
            installSuccess(success())
            session.updateCurrentMedia("BVfixture", 70)
            session.setCurrentLoadRequestToken(12)
        }
        fun audioClick() = vm.downloadAudio(settings, ::clickCurrent, DownloadOptions(includeDanmaku = false))
        fun coverClick() = vm.saveCover(settings, ::clickCurrent) { action ->
            if (!clickCurrent()) false else { action(); coverAdmissions++; true }
        }
        fun chooser() = assertNotNull(DesktopWindowsVideoDownloadSelection.capture(success(), pageJob, ::clickCurrent,
            vm::downloadWithQuality))
        fun batchChooser(value: VideoPlaybackUiState.Success = batchSuccess()): DesktopWindowsVideoBatchDownloadSelection {
            installSuccess(value)
            return assertNotNull(DesktopWindowsVideoBatchDownloadSelection.capture(value, pageJob, ::clickCurrent,
                vm::downloadBatchWithQuality))
        }
        override fun close() {
            pageJob.cancel(); scope.cancel(); folder.toFile().deleteRecursively()
            assertTrue(unexpected.isEmpty(), unexpected.joinToString { it.toString() })
        }
    }

    @Test fun audioResourceUsesActualOriginalProducerAndImmutableAudioTask() {
        Harness().use { h ->
            h.audioClick()
            val task = h.queued.single()
            assertEquals("BVfixture", task.bvid); assertEquals(70L, task.cid)
            assertEquals("https://fixture.invalid/audio", task.audioUrl)
            assertEquals("", task.videoUrl); assertEquals(0, task.quality)
            assertTrue(task.isAudioOnly); assertFalse(task.isVerticalVideo)
            assertFalse(task.options.includeDanmaku)
            assertTrue(h.requested.isEmpty()); assertTrue(h.constructed.isEmpty())
        }
    }

    @Test fun queuedAudioResourceCannotOutlivePageAccountOrExactAcceptedSource() {
        for (retire in listOf<(Harness) -> Unit>({ it.pageJob.cancel() }, { it.account = 2 }, { it.accepted = it.publication() })) {
            val dispatcher = PausedDispatcher()
            Harness(dispatcher).use { h ->
                h.audioClick(); retire(h); dispatcher.drain()
                assertTrue(h.queued.isEmpty()); assertTrue(h.requested.isEmpty())
            }
        }
    }

    @Test fun audioResourceChecksPageAgainAtExistingQueueAdmission() {
        Harness().use { h ->
            h.beforeFinalAdmission = { h.pageJob.cancel() }
            h.audioClick()
            assertTrue(h.queued.isEmpty())
        }
    }

    @Test fun coverResourceUsesCapturedMetadataAndRootFileAdmission() {
        Harness().use { h ->
            h.installSuccess(success().copy(info = success().info.copy(pic = "https://fixture.invalid/cover.jpg", title = "Captured cover")))
            h.coverClick()
            assertEquals(listOf("https://fixture.invalid/cover.jpg" to "Captured cover"), h.savedCovers)
            assertEquals(1, h.coverAdmissions)
            assertTrue(h.queued.isEmpty()); assertTrue(h.requested.isEmpty())
        }
    }

    @Test fun queuedCoverResourceCannotOutlivePageAccountOrExactAcceptedSource() {
        for (retire in listOf<(Harness) -> Unit>({ it.pageJob.cancel() }, { it.account = 2 }, { it.accepted = it.publication() })) {
            val dispatcher = PausedDispatcher()
            Harness(dispatcher).use { h ->
                h.installSuccess(success().copy(info = success().info.copy(pic = "https://fixture.invalid/cover.jpg")))
                h.coverClick(); retire(h); dispatcher.drain()
                assertTrue(h.savedCovers.isEmpty()); assertEquals(0, h.coverAdmissions)
            }
        }
    }

    @Test fun coverResourceRechecksCapturedOwnershipAtFinalFileAdmission() {
        for (retire in listOf<(Harness) -> Unit>({ it.pageJob.cancel() }, { it.account = 2 }, { it.accepted = it.publication() })) {
            Harness().use { h ->
                h.installSuccess(success().copy(info = success().info.copy(pic = "https://fixture.invalid/cover.jpg")))
                h.beforeCoverPublication = { retire(h) }
                h.coverClick()
                assertTrue(h.savedCovers.isEmpty()); assertEquals(0, h.coverAdmissions)
            }
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


    @Test fun actualBatchVmQueuesMultipleOriginalPartTasksWithSeparateReplyCaptures() {
        Harness().use { h ->
            val chooser = h.batchChooser()
            assertEquals(listOf(70L, 71L, 72L), chooser.candidates.map { it.cid })
            assertEquals(listOf(true, false, false), chooser.candidates.map { it.selected })
            assertEquals(120, chooser.currentQuality)
            assertTrue(chooser.select(80, DownloadOptions(includeDanmaku = false), chooser.candidates.map { it.copy(selected = true) }))
            assertFalse(chooser.select(80, DownloadOptions(), chooser.candidates))
            assertEquals(listOf(70L, 71L, 72L), h.queued.map { it.cid })
            assertEquals(listOf("BVfixture" to 71L, "BVfixture" to 72L), h.requested)
            assertNull(h.capturedReplies.first()); assertTrue(h.capturedReplies.drop(1).all { it != null })
            assertEquals(listOf(1, 2, 3), h.queued.map { it.episodeSortIndex })
            assertTrue(h.queued.all { it.groupKey == "bvid:BVfixture" && it.episodeCount == 3 && !it.options.includeDanmaku })
            assertTrue(h.queued.zip(h.constructed).all { (queued, captured) -> queued === captured })
        }
    }

    @Test fun collectionCandidatesUseOriginalSeasonPriorityAndExactOtherBvidReply() {
        Harness().use { h ->
            val value = batchSuccess().copy(info = batchSuccess().info.copy(ugc_season = UgcSeason(
                id = 9, title = "Collection", sections = listOf(UgcSection(episodes = listOf(
                    UgcEpisode(bvid = "BVfixture", cid = 70, title = "Current"),
                    UgcEpisode(bvid = "BVother", cid = 81, title = "Other"),
                ))))))
            val chooser = h.batchChooser(value)
            assertEquals(listOf("BVfixture", "BVother"), chooser.candidates.map { it.bvid })
            assertTrue(chooser.select(80, DownloadOptions(), chooser.candidates.map { it.copy(selected = true) }))
            assertEquals(listOf("BVother" to 81L), h.requested)
            assertEquals(listOf("BVfixture" to 70L, "BVother" to 81L), h.queued.map { it.bvid to it.cid })
            assertTrue(h.queued.all { it.groupKey == "ugc:9" && it.episodeCount == 2 })
        }
    }

    @Test fun batchSourceReplacedBeforeDispatchCannotRequestOrConstructAnyPart() {
        val dispatcher = PausedDispatcher()
        Harness(dispatcher).use { h ->
            val chooser = h.batchChooser()
            assertTrue(chooser.select(80, DownloadOptions(), chooser.candidates.map { it.copy(selected = true) }))
            h.accepted = h.publication(); dispatcher.drain()
            assertTrue(h.requested.isEmpty()); assertTrue(h.constructed.isEmpty()); assertTrue(h.queued.isEmpty())
        }
    }

    @Test fun batchCancelledPageDoesNotRetargetTheQueuedInvocation() {
        val dispatcher = PausedDispatcher()
        Harness(dispatcher).use { h ->
            val chooser = h.batchChooser()
            assertTrue(chooser.select(80, DownloadOptions(), chooser.candidates.map { it.copy(selected = true) }))
            h.pageJob.cancel(); dispatcher.drain()
            assertTrue(h.scope.isActive); assertTrue(h.requested.isEmpty()); assertTrue(h.queued.isEmpty())
        }
    }

    @Test fun sourceRetirementAfterFirstBatchAdmissionPreservesOnlyTheAcceptedTask() {
        Harness().use { h ->
            val chooser = h.batchChooser()
            h.afterAdmission = { h.accepted = h.publication() }
            assertTrue(chooser.select(80, DownloadOptions(), chooser.candidates.map { it.copy(selected = true) }))
            assertEquals(listOf(70L), h.queued.map { it.cid })
            assertEquals(1, h.constructed.size); assertTrue(h.requested.isEmpty())
        }
    }

    @Test fun explicitPartReplyCannotEnterQueueAfterItsPageRetires() {
        Harness().use { h ->
            val chooser = h.batchChooser()
            h.beforeReply = { h.pageJob.cancel() }
            assertTrue(chooser.select(80, DownloadOptions(), chooser.candidates.filter { it.cid == 71L }.map { it.copy(selected = true) }))
            assertEquals(listOf("BVfixture" to 71L), h.requested)
            assertEquals(1, h.constructed.size); assertTrue(h.queued.isEmpty())
        }
    }

    @Test fun existingBatchTaskIsSkippedAndLaterSelectedPartStillUsesOriginalProducer() {
        Harness().use { h ->
            val chooser = h.batchChooser()
            h.existing[71] = DownloadTask(bvid = "BVfixture", cid = 71, title = "Existing", cover = "", ownerName = "", ownerFace = "",
                duration = 0, quality = 80, qualityDesc = "1080P", videoUrl = "", audioUrl = "")
            assertTrue(chooser.select(80, DownloadOptions(), chooser.candidates.map { it.copy(selected = true) }))
            assertEquals(listOf(70L, 72L), h.queued.map { it.cid })
            assertEquals(listOf("BVfixture" to 72L), h.requested)
        }
    }

    @Test fun batchChooserRejectsForgedDuplicateAndEmptySelectionsWithoutConsumingIt() {
        Harness().use { h ->
            val chooser = h.batchChooser()
            val part = chooser.candidates[1].copy(selected = true)
            assertFalse(chooser.select(80, DownloadOptions(), listOf(part.copy(bvid = "BVforeign"))))
            assertFalse(chooser.select(80, DownloadOptions(), listOf(part.copy(title = "Forged"))))
            assertFalse(chooser.select(80, DownloadOptions(), listOf(part, part)))
            assertFalse(chooser.select(80, DownloadOptions(), chooser.candidates.map { it.copy(selected = false) }))
            assertTrue(chooser.isCurrent()); assertTrue(h.constructed.isEmpty())
            h.vm.downloadBatchWithQuality(80, DownloadOptions(), listOf(part.copy(cid = 999)), h::clickCurrent)
            assertTrue(h.constructed.isEmpty()); assertTrue(h.requested.isEmpty())
        }
    }

    @Test fun actualBatchMethodSnapshotsSelectedRosterBeforeAsyncDispatch() {
        val dispatcher = PausedDispatcher()
        Harness(dispatcher).use { h ->
            h.installSuccess(batchSuccess())
            val selected = resolveBatchDownloadCandidates(batchSuccess().info).map { it.copy(selected = true) }.toMutableList()
            h.vm.downloadBatchWithQuality(80, DownloadOptions(), selected, h::clickCurrent)
            selected.clear(); dispatcher.drain()
            assertEquals(listOf(70L, 71L, 72L), h.queued.map { it.cid })
        }
    }

    @Test fun delayedBatchSummaryRejectsReplacedSourceWithoutRemovingAcceptedTasks() {
        val dispatcher = PausedDispatcher()
        Harness(dispatcher).use { h ->
            val chooser = h.batchChooser()
            assertTrue(chooser.select(80, DownloadOptions(), chooser.candidates.map { it.copy(selected = true) }))
            dispatcher.drain() // The real batch sender waits for a toastEvent receiver.
            assertEquals(listOf(70L, 71L, 72L), h.queued.map { it.cid })
            h.accepted = h.publication()
            val messages = mutableListOf<String>()
            val marker = "fresh-source-marker"
            val collector = h.scope.launch { h.vm.toastEvent.collect {
                messages += it.message
                if (it.message == marker) cancel()
            } }
            h.vm.toast(marker)
            dispatcher.drain()
            assertTrue(collector.isCompleted); assertEquals(listOf(marker), messages)
            assertEquals(3, h.queued.size)
        }
    }

    @Test fun delayedBatchSummaryRejectsCancelledPageWhileEntryAndTasksRemainAlive() {
        val dispatcher = PausedDispatcher()
        Harness(dispatcher).use { h ->
            val chooser = h.batchChooser()
            assertTrue(chooser.select(80, DownloadOptions(), chooser.candidates.map { it.copy(selected = true) }))
            dispatcher.drain()
            assertEquals(3, h.queued.size); h.pageJob.cancel()
            val messages = mutableListOf<String>()
            val marker = "live-entry-marker"
            val collector = h.scope.launch { h.vm.toastEvent.collect {
                messages += it.message
                if (it.message == marker) cancel()
            } }
            h.vm.toast(marker)
            dispatcher.drain()
            assertTrue(collector.isCompleted); assertTrue(h.scope.isActive)
            assertEquals(listOf(marker), messages); assertEquals(3, h.queued.size)
        }
    }

    @Test fun delayedHealthyBatchSummaryPreservesOriginalTextAfterSelectionIsConsumed() {
        val dispatcher = PausedDispatcher()
        Harness(dispatcher).use { h ->
            val chooser = h.batchChooser()
            assertTrue(chooser.select(80, DownloadOptions(), chooser.candidates.map { it.copy(selected = true) }))
            dispatcher.drain()
            assertFalse(chooser.isCurrent()); assertEquals(3, h.queued.size)
            val messages = mutableListOf<String>()
            val collector = h.scope.launch { h.vm.toastEvent.collect { messages += it.message } }
            dispatcher.drain()
            assertEquals(listOf("已加入 3 个任务"), messages)
            assertEquals(3, h.queued.size)
            collector.cancel(); dispatcher.drain()
        }
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

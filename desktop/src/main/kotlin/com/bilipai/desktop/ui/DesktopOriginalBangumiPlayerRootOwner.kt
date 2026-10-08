package com.bilipai.desktop.ui

import androidx.compose.runtime.snapshotFlow
import com.android.purebilibili.core.network.BangumiApi
import com.android.purebilibili.core.store.DesktopOriginalBangumiPlayerUiSettings
import com.android.purebilibili.core.store.DesktopOriginalPortraitSettings
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopOriginalBangumiPagesRequests
import com.android.purebilibili.feature.bangumi.*
import com.android.purebilibili.feature.download.DownloadTask
import com.android.purebilibili.feature.plugin.PlaybackCdnPlugin
import com.android.purebilibili.feature.video.player.ExternalPlaylistSource
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.screen.resolveVideoDetailSystemBarsApplySpec
import com.android.purebilibili.feature.video.screen.resolveVideoDetailSystemBarsVisibilityPolicy
import com.android.purebilibili.feature.video.playback.audio.collectAudioStreamCandidates
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.refresh.HistoryRefreshBus
import com.bilipai.desktop.data.reportDesktopPlaybackHeartbeat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicReference

/** One retained original PGC presenter on the already constructed Assembly.
 * This is a binding lifetime, never a media/source/credential/task authority.
 * The original VM owns all PGC business; the shared Store/native/Mini/queue and
 * typed comment domain remain the existing app objects. Covered routes only
 * dispose view leases; actual takeover retires this real coroutine tree. */
internal class DesktopOriginalBangumiPlayerRootOwner(
    private val owner: DesktopOriginalVideoOwnerAssembly,
    private val shell: DesktopOriginalVideoShellOwner,
    private val root: DesktopOriginalVideoRootWindowEnvironment,
    private val factory: DesktopOriginalVideoRootFactory,
    private val resources: DesktopOriginalVideoRootShellResources,
    private val section: DesktopOriginalVideoSectionPlatform,
    private val holder: DesktopOriginalVideoHolderPlatform,
    private val portrait: DesktopOriginalPortraitPlatformBinding,
    private val windows: DesktopOriginalVideoWindowsWindowPort,
    private val textShare: DesktopTextShareBindings,
    private val fullscreenState: StateFlow<Boolean>,
) : AutoCloseable {
    private val current = AtomicReference<Entry?>()
    private val mutableEntries = MutableStateFlow<Entry?>(null)
    val entries: StateFlow<Entry?> = mutableEntries.asStateFlow()

    fun open(key: BiliPaiNavKey.BangumiPlayer): Entry {
        check(EventQueue.isDispatchThread())
        check(owner.owns() && shell.slot.currentAssembly() === owner && root.owns())
        current.get()?.takeIf { it.key == key && it.owns() }?.let { return it }
        current.get()?.close()
        val next = Entry(key)
        current.set(next)
        next.initialize()
        mutableEntries.value = next
        return next
    }

    inner class Entry internal constructor(val key: BiliPaiNavKey.BangumiPlayer) :
        DesktopOriginalBangumiPlayerScreenPlatform, AutoCloseable {
        private val gate = factory.gate(owner)
        private val initialStoreToken = owner.captureLoadState().currentLoadRequestToken
        private val scopeJob = SupervisorJob(checkNotNull(gate.scope.coroutineContext[Job]))
        private val scope = CoroutineScope(gate.scope.coroutineContext + scopeJob)
        private val stateOwner = AtomicReference<BangumiPlayerViewModel?>()
        // Read-only actual original DTO + accepted publication pair; source facts
        // must match again before retirement captures position. Never an authority.
        private val lastOwnedState = AtomicReference<Pair<BangumiPlayerState.Success, DesktopOriginalVideoAcceptedPublication>?>()
        private val native = DesktopOriginalBangumiNativePresenter(owner,
            { shell.slot.currentAssembly().takeIf { lifetimeOwns() } }, portrait, scope,
            { stateOwner.get()?.uiState?.value as? BangumiPlayerState.Success })
        override val player get() = owner.section
        override val section get() = this@DesktopOriginalBangumiPlayerRootOwner.section
        override val comments get() = owner.domains.comments
        override val viewModel get() = checkNotNull(stateOwner.get())
        override val window get() = holder.window
        override val fullscreen get() = fullscreenState
        override val share = DesktopOriginalBangumiPlayerShare(textShare, scope, ::owns, resources.feedback)
        private var nextMini: (() -> Boolean)? = null
        private var previousMini: (() -> Boolean)? = null
        private var hasNextMini: (() -> Boolean)? = null
        private var hasPreviousMini: (() -> Boolean)? = null

        private fun foreignSourceLeaf(): Boolean = when (val visible = root.currentKey()) {
            is BiliPaiNavKey.VideoDetail, is BiliPaiNavKey.AudioMode, is BiliPaiNavKey.NativeMusic, is BiliPaiNavKey.Story,
            is BiliPaiNavKey.OfflineVideoPlayer, is BiliPaiNavKey.Live, is BiliPaiNavKey.ExternalMedia -> true
            is BiliPaiNavKey.BangumiPlayer -> visible != key
            else -> false // Auxiliary covered pages retain the actual PGC source/Mini.
        }
        private fun lifetimeOwns(): Boolean = scopeJob.isActive && current.get() === this &&
            root.owns() && owner.owns() && shell.slot.currentAssembly() === owner && !foreignSourceLeaf() &&
            ((root.commands as DesktopOriginalRootRouteAssembly).containsEntry(key) ||
                owner.playback.isDesktopBangumiPresenterCurrent(native)) &&
            (owner.playback.isDesktopBangumiPresenterCurrent(native) ||
                owner.captureLoadState().currentLoadRequestToken == initialStoreToken)
        override fun owns(): Boolean = lifetimeOwns() && native.owns()
        private fun assertOwned() { if (!owns()) throw CancellationException("Original PGC Root presenter replaced") }
        private fun success() = stateOwner.get()?.uiState?.value as? BangumiPlayerState.Success
        private fun assertSubject(bvid: String, cid: Long, epId: Long, seasonId: Long) {
            assertOwned()
            val state = success() ?: throw CancellationException("Original PGC subject absent")
            require(state.currentEpisode.bvid == bvid && state.currentEpisode.cid == cid &&
                state.currentEpisode.id == epId && state.seasonDetail.seasonId == seasonId)
            if (!native.ownsPlaybackSource()) throw CancellationException("Original PGC accepted publication replaced")
        }

        internal fun initialize() {
            val ports = factory.entryPorts(owner)
            val aggregate = root.root.entry.embeddedPages as? DesktopOriginalHomeEmbeddedAggregate
                ?: error("PGC requires the actual existing Root Home aggregate")
            val api = root.repository.ownedHomeService(BangumiApi::class.java,
                "https://api.bilibili.com/", gate.capturedEpoch, ::owns)
            val pages = DesktopOriginalBangumiPagesRequests(api, aggregate.bangumiEnvironment.repository,
                { root.repository.ownedHomeCookie("bili_jct", gate.capturedEpoch, ::owns) },
                { root.repository.ownedHomeCookie("SESSDATA", gate.capturedEpoch, ::owns) }, ::owns, ::owns)
            val requests = DesktopOriginalBangumiPlayerRequestsView(pages, native::capturedPlaybackBinding, native::assertCurrent) { seasonId, epId, isCourse ->
                // Detail precedes beginEpisode: never borrow a previous ordinary CID/token.
                // The original resolver and genuine typed initial entry define this read.
                val initial = resolveBangumiDetailRequest(key.seasonId, key.epId)
                if (seasonId != initial.seasonId || epId != initial.epId || isCourse != key.isCourse ||
                    (root.commands as? DesktopOriginalRootRouteAssembly)?.stack?.any { it === key } != true) null
                else {
                    native.assertCurrent()
                    val binding = (owner.invocations.requireRequestRepository() as? DesktopOriginalVideoOwnerRequestRepository)
                        ?.binding ?: error("PGC initial detail requires its actual Root invocation")
                    DesktopBangumiInitialDetailSource.capture(root, owner, key,
                        seasonId, epId, isCourse, native.captureInitialDetailLaunchCaller(), binding, ::owns)
                }
            }
            val account = DesktopOriginalBangumiPlayerAccountView(owner, root.repository,
                resources.community.searchPreferences, native::assertCurrent, ::assertSubject)
            val base = object : DesktopOriginalBangumiBaseRequests {
                override suspend fun getSponsorSegments(bvid: String): List<SponsorSegment> {
                    currentCoroutineContext().ensureActive(); assertOwned()
                    val expected = owner.native.current() ?: throw CancellationException("PGC sponsor source unavailable")
                    owner.environment.plugins.ensureSponsorLoaded(expected, bvid, expected.request.cid)
                    currentCoroutineContext().ensureActive(); assertOwned()
                    return owner.environment.plugins.sponsorBlock.getSegments()
                }
                override fun findSegmentAtPosition(segments: List<SponsorSegment>, positionMs: Long): SponsorSegment? {
                    return desktopOriginalBangumiFindSponsorSegment(segments, positionMs)
                }
                override suspend fun getDanmakuRawData(cid: Long): ByteArray? {
                    assertOwned()
                    val binding = (owner.invocations.requireRequestRepository() as DesktopOriginalVideoOwnerRequestRepository).binding
                    return desktopOriginalBangumiRawDanmaku(binding, cid, ::assertOwned)
                }
            }
            val environment = DesktopOriginalBangumiPlayerEnvironment(scope, player, ports.settings,
                requests, account,
                object : DesktopOriginalBangumiPlayerActions {
                    override suspend fun checkLikeStatus(aid: Long) = owner.environment.actions.checkLikeStatus(aid)
                    override suspend fun checkCoinStatus(aid: Long) = owner.environment.actions.checkCoinStatus(aid)
                }, owner.environment.interactionUseCase, desktopOriginalBangumiPrimaryApiView(owner),
                ports.progress, base, native,
                DesktopOriginalBangumiPlayerUiSettings.getSponsorBlockAutoSkip(ports.settings),
                { resources.preferences().defaultAudioQuality }, { resources.preferences().lastSelectedAudioQuality },
                { value -> assertOwned(); DesktopOriginalPortraitSettings.setAudioQuality(ports.settings, value); assertOwned() },
                ports.capabilities::isHevcSupported, ports.capabilities::isHdrSupported,
                ports.capabilities::isDolbyAtmosAudioSupported, ports.capabilities::isDolbySoftwareAudioDecoderRequired,
                { root.runtime.plugins.value.filter { it.enabled }.map { it.plugin }.filterIsInstance<PlaybackCdnPlugin>().firstOrNull() },
                owner.environment.plugins::rewritePlaybackCandidates,
                { items, index, source -> assertOwned(); shell.playlist.setExternalPlaylist(items, index, source) },
                ::enqueueCurrentTask, ports.applyPreferredVolume, native::launch, native::commit, ::owns, native::ownsPlaybackSource)
            val vm = BangumiPlayerViewModel(environment)
            stateOwner.set(vm)
            native.installRetirementCapture(::captureFinalHeartbeat) { _, nextEpisode ->
                if (lastOwnedState.get()?.first?.currentEpisode?.id != nextEpisode.id) captureFinalHeartbeat()
            }
            scope.launch {
                vm.uiState.collect { value ->
                    val state = value as? BangumiPlayerState.Success ?: return@collect
                    val expected = owner.native.current() ?: return@collect
                    if (native.ownsPlaybackSource() && expected.request.bvid == state.currentEpisode.bvid &&
                        expected.request.cid == state.currentEpisode.cid && expected.nativeSource.source.videoUrl == state.playUrl &&
                        expected.nativeSource.source.audioUrl.orEmpty() == state.audioUrl.orEmpty()) {
                        lastOwnedState.set(state to expected)
                        // Same-token Mini/Subject projection after the original PGC
                        // audio/quality commit; no second native publication/load.
                        val plan = desktopOriginalBangumiNativePlan(state.seasonDetail, state.currentEpisode,
                            checkNotNull(state.cachedPlayData), checkNotNull(state.playUrl), state.audioUrl,
                            expected.nativeSource.source.progressiveSegments.takeIf { it.isNotEmpty() }?.map { it.url },
                            expected.nativeSource.source.referer, projectionManifest(state), 0L, true)
                        owner.playback.adoptDesktopPortraitLoad(owner.captureLoadState().currentLoadRequestToken,
                            plan.payload(state), { owns() && owner.native.isCurrent(expected) },
                            { action -> factory.withPresentationAdmission(owner, expected, action) }, native)
                    }
                }
            }
            scope.launch {
                combine(owner.playback.uiState, snapshotFlow { root.currentKey() }) { _, _ -> lifetimeOwns() }
                    .collect { if (!it) scopeJob.cancel("PGC entry/Store request replaced") }
            }
            scopeJob.invokeOnCompletion {
                EventQueue.invokeLater {
                    if (current.compareAndSet(this, null)) mutableEntries.value = null
                    clearMiniCallbacks()
                    // Only this borrowed listener/VM view is closed, never native MPV.
                    stateOwner.get()?.close()
                }
            }
        }

        private fun projectionManifest(state: BangumiPlayerState.Success): String? {
            val data = state.cachedPlayData ?: return null
            val dash = data.dash ?: return null
            val videoUrl = state.playUrl ?: return null
            val video = dash.video.firstOrNull { videoUrl == it.getValidUrl() || videoUrl in it.backupUrl.orEmpty() } ?: return null
            val audioUrl = state.audioUrl?.takeIf(String::isNotBlank)
            val audio = collectAudioStreamCandidates(dash).map { it.track }
                .firstOrNull { audioUrl != null && (audioUrl == it.getValidUrl() || audioUrl in it.backupUrl.orEmpty()) }
            val duration = data.timelength.takeIf { it > 0L } ?: data.timeLengthAlt.takeIf { it > 0L }
                ?: state.currentEpisode.duration.coerceAtLeast(0L)
            return buildBangumiDashManifest(dash, video, videoUrl, audio, audioUrl, duration)
        }

        private fun captureFinalHeartbeat() {
            val remembered = lastOwnedState.get() ?: return
            val state = remembered.first
            // Recovery may replace the accepted object without changing the
            // original PGC DTO. Only the same actual request/receipt/episode
            // may supply its current position; a successor subject is rejected.
            val expected = owner.native.current() ?: return
            val source = expected.nativeSource.source
            val receipt = source.authorizationReceipt ?: return
            if (expected.request != remembered.second.request ||
                expected.accountEpoch != remembered.second.accountEpoch ||
                receipt != remembered.second.nativeSource.source.authorizationReceipt) return
            val episode = state.currentEpisode
            val detail = state.seasonDetail
            val referer = "https://www.bilibili.com/" + (if (detail.seasonType == 10) "cheese" else "bangumi") + "/play/ep${episode.id}"
            if (source.referer != referer || expected.request.bvid != episode.bvid || expected.request.cid != episode.cid ||
                shell.slot.currentAssembly() !== owner || !root.owns() || !owner.owns()) return
            var positionMs: Long? = null
            if (!owner.native.admitPlaybackDispatch(expected) { positionMs = player.currentPosition.coerceAtLeast(0L) }) return
            val position = positionMs ?: return
            // Original cached progress write occurs while that exact source is
            // still admitted, before successor Store/native publication.
            if (episode.bvid.isNotBlank()) factory.entryPorts(owner).progress.savePosition(episode.bvid, episode.cid, position)
            if (!shouldSendBangumiPlaybackHeartbeat(true, episode.bvid, episode.cid, position, episode.id, detail.seasonId)) return
            fun accountCurrent() = root.owns() && gate.owns() && root.repository.sessionEpoch == receipt.accountEpoch &&
                root.repository.playbackAuthorizationRevision.value == receipt.revision
            if (!accountCurrent()) return
            val csrf = root.repository.ownedHomeCookie("bili_jct", gate.capturedEpoch, ::accountCurrent).orEmpty()
            val mid = root.root.entry.gate.mid
            val api = root.repository.ownedHomeService(BilibiliApi::class.java,
                "https://api.bilibili.com/", gate.capturedEpoch, ::accountCurrent)
            // Existing Root scope retains this one final operation after PGC's
            // producer cancels. All fields/primary CSRF/mid/receipt are captured
            // now; a successor source is never used to retag the old episode.
            gate.scope.launch {
                currentCoroutineContext().ensureActive()
                if (!accountCurrent()) return@launch
                reportDesktopPlaybackHeartbeat(resources.community.searchPreferences::isPrivacyModeEnabledSync,
                    receipt.accountEpoch, { root.repository.sessionEpoch }, { mid }, { csrf },
                    episode.bvid, episode.cid, position / 1000L, position / 1000L,
                    System.currentTimeMillis() / 1000L, episode.aid, episode.id, detail.seasonId,
                    4, detail.seasonType.takeIf { it > 0 },
                    onReported = { if (accountCurrent()) HistoryRefreshBus.notifyChanged() }) { fields ->
                    currentCoroutineContext().ensureActive()
                    if (!accountCurrent()) throw CancellationException("PGC retirement primary account replaced")
                    val response = api.reportHeartbeat(fields)
                    currentCoroutineContext().ensureActive()
                    if (!accountCurrent()) throw CancellationException("PGC retirement primary account replaced")
                    response.code
                }
            }
        }

        private fun enqueueCurrentTask(task: DownloadTask): Boolean {
            assertOwned()
            val state = success() ?: throw CancellationException("PGC download detail unavailable")
            val episode = state.currentEpisode
            val accepted = owner.native.current() ?: throw CancellationException("PGC download source unavailable")
            assertSubject(episode.bvid, episode.cid, episode.id, state.seasonDetail.seasonId)
            require(task.bvid == episode.bvid && task.cid == episode.cid && task.aid == episode.aid &&
                task.videoUrl == state.playUrl && task.audioUrl == state.audioUrl.orEmpty())
            val explicitDownloadRight = state.seasonDetail.rights?.allowDownload
            // PUGV's original mapper has no PGC allow_download field. Preserve
            // original course behavior only over this exact accepted URL/receipt,
            // never fabricate a rights value or acquire unrestricted full media.
            val downloadAllowed = explicitDownloadRight == 1 ||
                (explicitDownloadRight == null && state.seasonDetail.seasonType == 10)
            require(downloadAllowed) { "此媒体的服务器权限不允许下载" }
            val plan = desktopOriginalBangumiNativePlan(state.seasonDetail, episode, checkNotNull(state.cachedPlayData),
                task.videoUrl, task.audioUrl.takeIf(String::isNotBlank),
                accepted.nativeSource.source.progressiveSegments.takeIf { it.isNotEmpty() }?.map { it.url },
                accepted.nativeSource.source.referer, null, 0L, true)
            val source = plan.retainedSource(accepted.nativeSource.source, task.videoUrl, task.audioUrl.takeIf(String::isNotBlank))
            val destination = resources.downloads.destinationFor(task.customSaveDir)
            return resources.downloads.enqueueOriginal(task, source, destination, downloadAllowed,
                state.seasonDetail.seasonId, episode.id, state.seasonDetail.seasonType == 10,
                { owns() && native.ownsPlaybackSource() && owner.native.isCurrent(accepted) },
                { action -> factory.withPresentationAdmission(owner, accepted, action) })
        }

        override fun ensureMiniCallbacks(viewModel: BangumiPlayerViewModel, isCourse: Boolean) {
            require(viewModel === this.viewModel); assertOwned()
            if (nextMini != null) return
            fun play(item: PlaylistItem?): Boolean {
                if (!owns() || item == null) return false
                val season = item.seasonId ?: return false
                val episode = item.epId ?: return false
                if (season <= 0L || episode <= 0L) return false
                viewModel.loadBangumiPlay(season, episode, isCourse = isCourse)
                return true
            }
            nextMini = { if (owns()) play(shell.playlist.playNext()) else false }
            previousMini = { if (owns()) play(shell.playlist.playPrevious()) else false }
            hasNextMini = { owns() && shell.playlist.hasNext() }
            hasPreviousMini = { owns() && shell.playlist.hasPrevious() }
            shell.mini.onNavigateNextCallback = nextMini
            shell.mini.onNavigatePreviousCallback = previousMini
            shell.mini.onHasNextNavigationCallback = hasNextMini
            shell.mini.onHasPreviousNavigationCallback = hasPreviousMini
        }
        private fun clearMiniCallbacks() {
            if (shell.mini.onNavigateNextCallback === nextMini) shell.mini.onNavigateNextCallback = null
            if (shell.mini.onNavigatePreviousCallback === previousMini) shell.mini.onNavigatePreviousCallback = null
            if (shell.mini.onHasNextNavigationCallback === hasNextMini) shell.mini.onHasNextNavigationCallback = null
            if (shell.mini.onHasPreviousNavigationCallback === hasPreviousMini) shell.mini.onHasPreviousNavigationCallback = null
        }
        override fun acquireDanmakuPlayer(player: DesktopOriginalMpvSectionControl): AutoCloseable {
            require(player === owner.section); assertOwned(); return portrait.acquireDanmakuPlayer(player)
        }
        override fun acquirePlaybackHandoff(seasonId: Long, epId: Long, position: () -> Long): AutoCloseable {
            // Android's OS HandoffActivityData is absent on Windows. Windows typed
            // re-entry reads this exact accepted/native Store subject directly;
            // no second Handoff registry or fabricated availability is published.
            assertOwned()
            resources.diagnostics?.record("I", "BangumiPlatform", "Android OS playback handoff unavailable on Windows; typed resume uses the existing native/Store subject")
            return AutoCloseable { /* Explicit unavailable OS registration owns no resource. */ }
        }
        override fun acquirePresentation(fullscreen: Boolean): AutoCloseable {
            assertOwned()
            val visibility = resolveVideoDetailSystemBarsVisibilityPolicy(fullscreen, false,
                resources.pip.active.value, true, false)
            val spec = resolveVideoDetailSystemBarsApplySpec(visibilityPolicy = visibility,
                useTabletLayout = false, isLightBackground = false, backgroundColor = 0xff000000.toInt(),
                transparentColor = 0, blackColor = 0xff000000.toInt(), transientBarsBehavior = 2)
            val chrome = windows.acquirePortraitPresentation(spec)
            val awake = windows.acquireKeepAwake(true)
            val sourceVersion = owner.native.current()?.sourceVersion
            return AutoCloseable {
                chrome.close(); awake.close()
                sourceVersion?.let(resources.overlay::clearViewportBrightness)
            }
        }
        override fun requestFullscreen(fullscreen: Boolean) { assertOwned(); resources.setFullscreen(fullscreen) }
        override fun showFeedback(message: String) { if (owns()) resources.feedback(message) }
        override fun elapsedRealtimeMillis() = holder.elapsedRealtimeMillis()
        override fun isActualDrmFailure(error: DesktopOriginalNativePlaybackError): Boolean =
            error.failure?.diagnostics?.any { line ->
                line.contains("drm", true) && (line.contains("unsupported", true) || line.contains("encrypted", true) || line.contains("decrypt", true))
            } == true
        override fun close() {
            native.onSharedPlaybackReplaced()
            clearMiniCallbacks()
        }
    }
    override fun close() { current.getAndSet(null)?.close() }
}

package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.SpaceApi
import com.android.purebilibili.core.util.resolvePlaybackDefaultQualityId
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.plugin.PlaybackCdnPlugin
import com.android.purebilibili.feature.video.playback.audio.collectAudioStreamCandidates
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.ui.pager.*
import com.bilipai.desktop.data.PlaybackSource
import com.bilipai.desktop.player.PlaybackSource as NativeSource
import com.bilipai.desktop.player.cache.DesktopMediaByteCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.util.IdentityHashMap

/** Concrete request/media view of one installed full original Assembly. No VM,
 * player, account, list, cache or scope is created here. Required Window/Overlay
 * delegates refer to the same Section/carrier; closing a borrowed lease does not
 * close the Root player. The blocked-UP port must have its actual owned final
 * write implementation before joint mount, never an empty/default implementation.
 */
internal class DesktopOriginalPortraitPlatformBinding(
    private val assembly: DesktopOriginalVideoOwnerAssembly,
    private val currentAssembly: () -> DesktopOriginalVideoOwnerAssembly?,
    override val section: DesktopOriginalVideoSectionPlatform,
    private val homeVideo: DesktopHomeVideoRequests,
    private val ownedPrimaryApi: BilibiliApi,
    private val ownedSpaceApi: SpaceApi,
    private val captureRequest: suspend (DesktopOriginalVideoOwnerAssembly) -> DesktopOriginalVideoRepositoryBinding,
    private val mediaCache: DesktopMediaByteCache,
    private val captureCookieHeader: (DesktopOriginalVideoRepositoryBinding, String) -> String,
    override val favoriteQuickSaveDefaultFolder: Flow<Boolean>,
    override val blockedUps: DesktopOriginalPortraitBlockedUps,
    override val danmaku: DesktopOriginalPortraitDanmakuPort,
    private val currentCdnPlugin: () -> PlaybackCdnPlugin?,
    private val presentationLease: (DesktopOriginalVideoOwnerAssembly, Boolean) -> AutoCloseable,
    private val danmakuLease: (DesktopOriginalVideoOwnerAssembly, DesktopOriginalMpvSectionControl) -> AutoCloseable,
    private val releasePager: (DesktopOriginalVideoOwnerAssembly, DesktopOriginalMpvSectionControl) -> Unit,
    private val recover: (DesktopOriginalVideoOwnerAssembly, DesktopOriginalMpvSectionControl) -> Unit,
    private val share: (DesktopOriginalVideoOwnerAssembly, String) -> Unit,
    private val feedback: (DesktopOriginalVideoOwnerAssembly, String) -> Unit,
    private val surface: @Composable (DesktopOriginalVideoOwnerAssembly, DesktopOriginalMpvSectionControl, @Composable () -> Unit) -> Unit,
    private val viewport: @Composable (DesktopOriginalVideoOwnerAssembly, DesktopOriginalMpvSectionControl, Modifier, Int, Boolean, Boolean, Boolean) -> Unit,
    private val danmakuSurface: @Composable (DesktopOriginalVideoOwnerAssembly, Modifier, Int, Int, Int) -> Unit,
    private val onPreparation: (DesktopOriginalMediaCachePreparation) -> Unit,
    private val prepareAcceptedMedia: (DesktopOriginalVideoAcceptedPublication, DesktopOriginalBangumiNativeSourcePlan, () -> Boolean) -> DesktopOriginalVideoMediaPort,
) : DesktopOriginalPortraitPlatform {
    private fun owns() = currentAssembly() === assembly && assembly.owns()
    private fun assertOwned() { if (!owns()) throw CancellationException("Portrait Assembly retired") }
    private fun assertPlayer(value: DesktopOriginalMpvSectionControl) {
        assertOwned(); require(value === assembly.section) { "Portrait must use the same installed Section" }
    }

    override val player get() = assembly.section.also { assertOwned() }
    override val composer = object : DesktopOriginalPortraitComposerOwner {
        override fun bindSubject(subject: com.android.purebilibili.feature.video.viewmodel.VideoSubjectSnapshot) {
            assertOwned(); assembly.domains.composer.bindSubject(subject)
        }
    }
    override val supplement get() = assembly.domains.supplement.also { assertOwned() }
    override val comments get() = assembly.domains.comments.also { assertOwned() }
    override val externalPlaylist get() = assembly.environment.playlist.isExternalPlaylist
    override val playbackCdnPlugin get() = currentCdnPlugin().also { assertOwned() }
    override val codecs = object : DesktopOriginalPortraitCodecCapabilities {
        override val hevcSupported get() = assembly.environment.useCase.capabilities.isHevcSupported().also { assertOwned() }
        override val av1Supported get() = assembly.environment.useCase.capabilities.isAv1Supported().also { assertOwned() }
        override val dolbyAudioSupported get() = assembly.environment.useCase.capabilities.isDolbyAtmosAudioSupported().also { assertOwned() }
        override val dolbyAudioSoftwareDecoded get() = assembly.environment.useCase.capabilities.isDolbySoftwareAudioDecoderRequired().also { assertOwned() }
    }

    /** Only transient operation bookkeeping. These keys are actual Binding identities;
     * no next generation, latest credential or raw PlayUrl cache is stored here.
     * Each real Job removes its own records. Native ACK retains its fixed capture
     * independently, using isCancelled so ordinary successful completion is valid.
     */
    private class Capture(val job: Job, val nativeBaseline: Long, val requestToken: Long) {
        var prepared: Prepared? = null
        var bangumi: BangumiCapture? = null
    }
    private class Prepared(val original: PlaybackSource, val request: PlaybackRequest,
        val preparation: DesktopOriginalMediaCachePreparation,
        val payload: com.android.purebilibili.feature.video.usecase.VideoLoadResult.Success,
        val bangumiPresenter: DesktopOriginalBangumiSharedPlaybackPresenter? = null)
    private class BangumiCapture(val presenter: DesktopOriginalBangumiSharedPlaybackPresenter,
        val detail: BangumiDetail, val episode: BangumiEpisode)
    private val captures = IdentityHashMap<DesktopOriginalVideoRepositoryBinding, Capture>()

    override suspend fun capturePageRequest(bvid: String, aid: Long, cid: Long): DesktopOriginalVideoRepositoryBinding {
        currentCoroutineContext().ensureActive(); assertOwned()
        val expectedToken = assembly.playback.beginDesktopPortraitLoad(PlaybackRequest.create(bvid, aid, cid))
        // Preserve original progress flush before stopping; capture only AFTER
        // the real synchronous version retirement / same-actor Stop enqueue.
        clearCapturedPagePlayback(expectedToken, checkNotNull(currentCoroutineContext()[Job]))
        val captured = capturePlaybackRequest()
        if (assembly.captureLoadState().currentLoadRequestToken != expectedToken ||
                synchronized(captures) { captures[captured]?.requestToken } != expectedToken)
            throw CancellationException("Portrait page replaced during request capture")
        return captured
    }

    /** Same caller/token final admission BEFORE reading/stopping the current
     * accepted source. A cancelled or replaced A request cannot stop B. No actor
     * wait, HTTP or IO occurs under this existing Store -> entry -> native gate. */
    internal fun clearCapturedPagePlayback(expectedToken: Long, callerJob: Job) {
        var applied = false
        if (!assembly.environment.commit {
            assertOwned()
            if (callerJob.isCancelled || assembly.captureLoadState().currentLoadRequestToken != expectedToken)
                throw CancellationException("Portrait stop request/caller replaced")
            clearPlaybackForReplacement(assembly.section)
            applied = true
        } || !applied) throw CancellationException("Portrait stop entry retired")
    }

    override suspend fun capturePlaybackRequest(): DesktopOriginalVideoRepositoryBinding {
        currentCoroutineContext().ensureActive(); assertOwned()
        val job = checkNotNull(currentCoroutineContext()[Job])
        val request = captureRequest(assembly)
        currentCoroutineContext().ensureActive(); assertOwned(); request.assertCurrent()
        var baseline: Long? = null
        if (!request.admitCurrentMutation { assertOwned(); baseline = assembly.native.player.currentSourceVersion })
            throw CancellationException("Portrait native baseline admission retired")
        val captured = Capture(job, checkNotNull(baseline), assembly.captureLoadState().currentLoadRequestToken)
        synchronized(captures) { check(captures.put(request, captured) == null) }
        job.invokeOnCompletion {
            val previous = synchronized(captures) { if (captures[request] === captured) captures.remove(request) else null }
            (previous?.prepared?.preparation as? DesktopOriginalMediaCachePreparation.Cached)?.discardUnaccepted()
        }
        currentCoroutineContext().ensureActive(); request.assertCurrent(); return request
    }

    private fun captured(request: DesktopOriginalVideoRepositoryBinding): Capture {
        assertOwned(); request.assertCurrent()
        return synchronized(captures) { captures[request] }
            ?.also { it.job.ensureActive() }
            ?: throw CancellationException("Portrait operation capture missing/finished")
    }

    private suspend fun <T> owned(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive(); assertOwned()
        return block().also { currentCoroutineContext().ensureActive(); assertOwned() }
    }
    override val requests = object : DesktopOriginalPortraitRequests {
        // Existing Root services, tagged before Call.Factory.newCall. No Retrofit/client here.
        override val api get() = ownedPrimaryApi.also { assertOwned() }
        override val spaceApi get() = ownedSpaceApi.also { assertOwned() }
        override suspend fun getHomeVideos(idx: Int) = owned { homeVideo.getHomeVideos(idx) }.also {
            (it.exceptionOrNull() as? CancellationException)?.let { cancelled -> throw cancelled }
        }
        override suspend fun getWatchLaterList(): WatchLaterResponse = owned {
            val request = captureRequest(assembly)
            request.primaryApi.getWatchLaterList().also { request.assertCurrent() }
        }
        override suspend fun getRelatedVideos(bvid: String) = owned {
            val request = captureRequest(assembly)
            request.rawRepository.getRelatedVideos(bvid).also { request.assertCurrent() }
        }
        override suspend fun isVerticalVideo(bvid: String, aid: Long) = owned { homeVideo.isVerticalVideo(bvid, aid) }
        override fun hasPrimarySessionCookie() = assembly.environment.account.hasSession.also { assertOwned() }
        override fun isPlaybackLoggedIn() = assembly.invocations.repository.isPlaybackLoggedIn().also { assertOwned() }
        override fun isPlaybackVip() = assembly.invocations.repository.isPlaybackVip().also { assertOwned() }
    }

    override fun isWifi(): Boolean = assembly.environment.network.isWifi().also { assertOwned() }
    override suspend fun playableDefaultQuality(isLoggedIn: Boolean, isVip: Boolean): Int = owned {
        val context = section.settingsContext
        val prefs = context.getSharedPreferences("quality_settings", DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
        resolvePlaybackDefaultQualityId(
            storedQuality = assembly.environment.network.getDefaultQualityId(context),
            autoHighestEnabled = prefs.getBoolean("auto_highest_quality", false),
            isLoggedIn = isLoggedIn, isVip = isVip,
        )
    }

    override fun currentMediaId(player: DesktopOriginalMpvSectionControl): String? {
        assertPlayer(player)
        return assembly.native.current()?.request?.let { resolvePortraitMediaId(it.bvid, it.cid) }
    }
    override fun clearPlaybackForReplacement(player: DesktopOriginalMpvSectionControl) {
        assertPlayer(player)
        val expected = assembly.native.current() ?: return // Genuine loading entry, not a foreign native source.
        if (!assembly.native.admitPlaybackDispatch(expected) {
            assembly.native.player.stopIfSourceVersion(expected.sourceVersion)
        }) throw CancellationException("Portrait replacement source retired")
    }
    override fun releasePagerLease(player: DesktopOriginalMpvSectionControl) { assertPlayer(player); releasePager(assembly, player) }
    override fun acquirePresentation(active: Boolean): AutoCloseable { assertOwned(); return presentationLease(assembly, active) }
    override fun acquireDanmakuPlayer(player: DesktopOriginalMpvSectionControl): AutoCloseable {
        assertPlayer(player); return danmakuLease(assembly, player)
    }
    override fun recoverSurface(player: DesktopOriginalMpvSectionControl) { assertPlayer(player); recover(assembly, player) }

    private fun nativeSource(request: DesktopOriginalVideoRepositoryBinding, video: String, audio: String?, title: String) =
        request.authorized(NativeSource(video, audio, referer = "https://www.bilibili.com",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            cookieHeader = captureCookieHeader(request, video), title = title))

    /** Exact selected URLs stay remote metadata. Alias attachment only applies when
     * that URL identifies an actual raw track; a CDN rewrite is not guessed to be
     * another representation. The same native byte actor consumes these tracks.
     */
    private fun tracks(source: NativeSource, data: PlayUrlData): List<com.bilipai.desktop.player.cache.DesktopMediaByteTrack> {
        val video = data.dash?.video?.firstOrNull { source.videoUrl == it.getValidUrl() || source.videoUrl in it.backupUrl.orEmpty() }
        val audio = data.dash?.let { collectAudioStreamCandidates(it).map { candidate -> candidate.track } }
            ?.firstOrNull { source.audioUrl == it.getValidUrl() || source.audioUrl in it.backupUrl.orEmpty() }
        return desktopOriginalLegacyByteTracks(source,
            listOfNotNull(video?.getValidUrl()) + video?.backupUrl.orEmpty(),
            listOfNotNull(audio?.getValidUrl()) + audio?.backupUrl.orEmpty(), emptyMap())
    }

    override fun prepareSource(request: DesktopOriginalVideoRepositoryBinding, info: ViewInfo,
        playData: PlayUrlData, videoUrl: String, audioUrl: String?, mediaId: String,
        selection: PortraitPlaybackStreamUrls, recommendations: List<RelatedVideo>): PlaybackSource {
        val capture = captured(request)
        require(info.bvid.isNotBlank() && info.aid > 0L && info.cid > 0L)
        require(mediaId == resolvePortraitMediaId(info.bvid, info.cid))
        val remote = nativeSource(request, videoUrl, audioUrl, info.title)
        val preparation = request.captureMediaBytes(mediaCache).prepare(remote, tracks(remote, playData), null, null) { false }
        onPreparation(preparation)
        val source = PlaybackSource(videoUrl = videoUrl, audioUrl = audioUrl, title = info.title,
            referer = remote.referer, cookieHeader = remote.cookieHeader, quality = playData.quality,
            availableQualities = playData.acceptQuality.map { com.bilipai.desktop.data.PlaybackQuality(it, resolvePortraitQualityLabel(it)) },
            videoAlternatives = playData.dash?.video.orEmpty().flatMap { listOf(it.getValidUrl()) + it.backupUrl.orEmpty() }.distinct(),
            audioAlternatives = playData.dash?.let { collectAudioStreamCandidates(it).flatMap { candidate ->
                listOf(candidate.track.getValidUrl()) + candidate.track.backupUrl.orEmpty() } }.orEmpty().distinct(),
            cachedDashData = playData.dash, authorizationReceipt = request.receipt)
        val payload = desktopOriginalPortraitResolvedPayload(info, playData, source, selection, recommendations,
            request.protocol.isPlaybackLoggedIn(), request.protocol.isPlaybackVip())
        val prepared = Prepared(source, PlaybackRequest.create(info.bvid, info.aid, info.cid), preparation, payload)
        synchronized(captures) {
            if (captures[request] !== capture || capture.prepared != null) {
                (preparation as? DesktopOriginalMediaCachePreparation.Cached)?.discardUnaccepted()
                throw CancellationException("Portrait preparation replaced/duplicated")
            }
            capture.prepared = prepared
        }
        try { request.assertCurrent(); capture.job.ensureActive(); return source }
        catch (failure: Throwable) { (preparation as? DesktopOriginalMediaCachePreparation.Cached)?.discardUnaccepted(); throw failure }
    }

    override fun publishSource(request: DesktopOriginalVideoRepositoryBinding, source: PlaybackSource,
        expectedLoadGeneration: Int, playWhenReady: Boolean, stillCurrentLoad: () -> Boolean): Boolean {
        val capture = captured(request)
        require(expectedLoadGeneration > 0)
        val prepared = synchronized(captures) { capture.prepared }
            ?: error("Portrait must publish its own captured preparation")
        require(prepared.original === source && source.authorizationReceipt == request.receipt)
        val sameSession = { assembly.captureLoadState().let {
            it.currentLoadRequestToken == capture.requestToken && it.currentBvid == prepared.request.bvid
        } }
        if (!stillCurrentLoad() || !sameSession()) {
            (prepared.preparation as? DesktopOriginalMediaCachePreparation.Cached)?.discardUnaccepted()
            return false
        }
        val cached = prepared.preparation as? DesktopOriginalMediaCachePreparation.Cached
        cached?.beginPublish()
        try {
            val accepted = assembly.native.publish(prepared.request,
                prepared.preparation.source.copy(startPaused = !playWhenReady),
                capture.nativeBaseline, capture.job) { !capture.job.isCancelled && owns() && stillCurrentLoad() && sameSession() }
            cached?.accepted(accepted)
            return assembly.playback.adoptDesktopPortraitLoad(capture.requestToken, prepared.payload,
                { !capture.job.isCancelled && owns() && stillCurrentLoad() && assembly.native.isCurrent(accepted) },
                request::admitCurrentMutation, prepared.bangumiPresenter)
        } catch (failure: Throwable) { cached?.failed(); throw failure }
    }


    /** PGC starts the sole original Store, then captures the same request/byte
     * authority as Portrait. The capture map below remains the single map. */
    internal suspend fun captureBangumiPageRequest(presenter: DesktopOriginalBangumiSharedPlaybackPresenter,
        detail: BangumiDetail, episode: BangumiEpisode): DesktopOriginalVideoRepositoryBinding {
        currentCoroutineContext().ensureActive(); assertOwned()
        require(detail.seasonId > 0L && episode.id > 0L && episode.cid >= 0L && episode.aid >= 0L)
        val token = assembly.playback.beginDesktopPortraitLoad(PlaybackRequest.create(episode.bvid, episode.aid, episode.cid), presenter)
        clearCapturedPagePlayback(token, checkNotNull(currentCoroutineContext()[Job]))
        val request = capturePlaybackRequest()
        val capture = captured(request)
        if (capture.requestToken != token || !assembly.playback.isDesktopBangumiPresenterCurrent(presenter))
            throw CancellationException("PGC episode replaced during request capture")
        synchronized(captures) {
            if (captures[request] !== capture) throw CancellationException("PGC request finished during capture")
            capture.bangumi = BangumiCapture(presenter, detail, episode)
        }
        request.assertCurrent(); currentCoroutineContext().ensureActive(); return request
    }

    private fun findBangumiCapture(presenter: DesktopOriginalBangumiSharedPlaybackPresenter, caller: Job):
        Pair<DesktopOriginalVideoRepositoryBinding, Capture>? {
        assertOwned(); caller.ensureActive()
        if (!assembly.playback.isDesktopBangumiPresenterCurrent(presenter))
            throw CancellationException("PGC presenter taken over")
        return synchronized(captures) {
            captures.entries.filter { it.value.job === caller && it.value.bangumi?.presenter === presenter }
                .also { require(it.size <= 1) }.firstOrNull()?.let { it.key to it.value }
        }?.also { (binding, capture) ->
            binding.assertCurrent()
            if (capture.requestToken != assembly.captureLoadState().currentLoadRequestToken)
                throw CancellationException("PGC actual Store token replaced")
        }
    }
    internal fun capturedBangumiRequest(presenter: DesktopOriginalBangumiSharedPlaybackPresenter, caller: Job): DesktopOriginalVideoRepositoryBinding =
        findBangumiCapture(presenter, caller)?.first ?: throw CancellationException("PGC load capture missing/finished")
    internal fun assertBangumiCallerCurrent(presenter: DesktopOriginalBangumiSharedPlaybackPresenter, caller: Job) {
        findBangumiCapture(presenter, caller) // Follow/heartbeat jobs genuinely have no media capture.
    }

    internal fun ownsBangumiPlayback(presenter: DesktopOriginalBangumiSharedPlaybackPresenter,
        state: com.android.purebilibili.feature.bangumi.BangumiPlayerState.Success): Boolean {
        if (!owns() || !assembly.playback.isDesktopBangumiPresenterCurrent(presenter)) return false
        val expected = assembly.native.current() ?: return false
        val episode = state.currentEpisode
        val actual = assembly.captureLoadState()
        val referer = "https://www.bilibili.com/" +
            (if (state.seasonDetail.seasonType == 10) "cheese" else "bangumi") + "/play/ep${episode.id}"
        return expected.request.bvid == episode.bvid && expected.request.aid == episode.aid && expected.request.cid == episode.cid &&
            actual.currentBvid == episode.bvid && actual.currentCid == episode.cid &&
            expected.nativeSource.source.referer == referer && assembly.native.isCurrent(expected)
    }

    internal fun stopBangumiPlayback(presenter: DesktopOriginalBangumiSharedPlaybackPresenter, caller: Job?) {
        assertOwned()
        if (!assembly.playback.isDesktopBangumiPresenterCurrent(presenter))
            throw CancellationException("PGC stop presenter replaced")
        caller?.let { findBangumiCapture(presenter, it) } // If present, its actual token is also required.
        val expected = assembly.native.current() ?: return
        if (!assembly.native.admitPlaybackDispatch(expected) {
            if (!assembly.playback.isDesktopBangumiPresenterCurrent(presenter) || caller?.isCancelled == true)
                throw CancellationException("PGC stop producer replaced")
            assembly.native.player.stopIfSourceVersion(expected.sourceVersion)
        }) throw CancellationException("PGC stop source replaced")
    }

    internal fun publishBangumiSource(presenter: DesktopOriginalBangumiSharedPlaybackPresenter, caller: Job?,
        state: com.android.purebilibili.feature.bangumi.BangumiPlayerState.Success,
        videoUrl: String, audioUrl: String?, segments: List<String>?, seekToMs: Long,
        referer: String, manifest: String?, playWhenReady: Boolean) {
        assertOwned(); caller?.ensureActive()
        val operation = caller?.let { findBangumiCapture(presenter, it) }
        val data = checkNotNull(state.cachedPlayData) { "PGC publication requires its actual full protocol response" }
        val plan = desktopOriginalBangumiNativePlan(state.seasonDetail, state.currentEpisode, data,
            videoUrl, audioUrl, segments, referer, manifest, seekToMs, playWhenReady)
        // Audio selection is committed by the full original VM only after this
        // native source acceptance. Shared Subject/Mini use the actual new URL.
        val projection = state.copy(playUrl = videoUrl, audioUrl = audioUrl?.takeIf(String::isNotBlank))
        if (operation != null) {
            val (request, capture) = operation
            val metadata = checkNotNull(capture.bangumi)
            require(metadata.episode.id == plan.episode.id && metadata.episode.cid == plan.episode.cid &&
                metadata.detail.seasonId == plan.detail.seasonId)
            val remote = request.authorized(plan.nativeSource().copy(cookieHeader = captureCookieHeader(request, videoUrl)))
            val preparation = request.captureMediaBytes(mediaCache).prepare(remote, plan.byteTracks(remote), plan.manifest, null) { false }
            onPreparation(preparation)
            val source = PlaybackSource(videoUrl = videoUrl, audioUrl = plan.audioUrl, title = plan.title,
                referer = plan.referer, cookieHeader = remote.cookieHeader, quality = state.quality,
                authorizationReceipt = request.receipt, cachedDashData = data.dash, progressiveSegments = plan.segments)
            val prepared = Prepared(source, PlaybackRequest.create(plan.episode.bvid, plan.episode.aid, plan.episode.cid),
                preparation, plan.payload(projection), presenter)
            synchronized(captures) {
                if (captures[request] !== capture || capture.prepared != null) {
                    (preparation as? DesktopOriginalMediaCachePreparation.Cached)?.discardUnaccepted()
                    throw CancellationException("PGC preparation replaced/duplicated")
                }
                capture.prepared = prepared
            }
            if (!publishSource(request, source, 1, playWhenReady) {
                    assembly.playback.isDesktopBangumiPresenterCurrent(presenter) &&
                        assembly.captureLoadState().currentLoadRequestToken == capture.requestToken
                }) throw CancellationException("PGC publication rejected")
            return
        }
        // Cached audio/resume use the fixed accepted lease, not a completed load
        // binding or newly captured credentials. Same receipt is preserved by the
        // existing RootMediaFactory and NativeOwner.acceptedMedia final gate.
        if (!ownsBangumiPlayback(presenter, state)) throw CancellationException("PGC accepted source replaced")
        val expected = checkNotNull(assembly.native.current())
        val token = assembly.captureLoadState().currentLoadRequestToken
        val samePresenterAndRequest = {
            ownsBangumiPlayback(presenter, state) && assembly.captureLoadState().currentLoadRequestToken == token &&
                caller?.isCancelled != true
        }
        val media = prepareAcceptedMedia(expected, plan, samePresenterAndRequest)
        media.withPlaybackIntent(seekToMs, playWhenReady) {
            if (!ownsBangumiPlayback(presenter, state) || assembly.native.current() !== expected)
                throw CancellationException("PGC accepted preparation replaced")
            val source = plan.adaptiveSource()?.let { media.prepareAdaptiveDash(it, emptyMap()) }
                ?: media.prepareLegacyDash(videoUrl, plan.audioUrl, emptyMap())
            require(source.referer == plan.referer && source.authorizationReceipt == expected.nativeSource.source.authorizationReceipt)
            media.accept(source)
        }
        val accepted = checkNotNull(assembly.native.current())
        if (accepted.request != expected.request || accepted.accountEpoch != expected.accountEpoch ||
            accepted.nativeSource.source.authorizationReceipt != expected.nativeSource.source.authorizationReceipt)
            throw CancellationException("PGC accepted recovery subject changed")
        if (!assembly.playback.adoptDesktopPortraitLoad(token, plan.payload(projection), {
                ownsBangumiPlayback(presenter, projection) && assembly.native.isCurrent(accepted) && caller?.isCancelled != true
            }, assembly.environment::commit, presenter)) throw CancellationException("PGC accepted metadata adoption rejected")
    }

    override fun captureMediaCache(request: DesktopOriginalVideoRepositoryBinding, playData: PlayUrlData,
        streamUrls: PortraitPlaybackStreamUrls): DesktopOriginalPortraitByteCache {
        captured(request)
        val source = nativeSource(request, streamUrls.videoUrl, streamUrls.audioUrl, "BiliPai")
        return captureDesktopOriginalPortraitByteCache(request.captureMediaBytes(mediaCache), source, tracks(source, playData))
    }

    override val mediaFactory = object : DesktopOriginalPortraitMediaFactory {
        override fun replaceAudioSource(player: DesktopOriginalMpvSectionControl, videoUrl: String,
            audioUrl: String, mediaId: String, positionMs: Long, playWhenReady: Boolean): Boolean {
            assertPlayer(player)
            val expected = assembly.native.current() ?: return false
            if (mediaId != resolvePortraitMediaId(expected.request.bvid, expected.request.cid)) return false
            val media = assembly.invocations.media // Its lexical span captures the exact accepted source once.
            try {
                media.withPlaybackIntent(positionMs, playWhenReady) {
                    if (!assembly.native.isCurrent(expected)) throw CancellationException("Portrait audio source replaced")
                    val source = media.prepareLegacyDash(videoUrl, audioUrl, emptyMap())
                    if (source.authorizationReceipt != expected.nativeSource.source.authorizationReceipt || !assembly.native.isCurrent(expected))
                        throw CancellationException("Portrait audio authorization replaced")
                    media.accept(source)
                }
                return true
            } catch (_: CancellationException) { return false }
        }
    }
    override fun shareText(text: String) { assertOwned(); share(assembly, text) }
    override fun showFeedback(text: String) { assertOwned(); feedback(assembly, text) }
    @Composable override fun Surface(player: DesktopOriginalMpvSectionControl, foreground: @Composable () -> Unit) {
        assertPlayer(player); surface(assembly, player, foreground)
    }
    @Composable override fun Viewport(player: DesktopOriginalMpvSectionControl, modifier: Modifier,
        resizeMode: Int, keepAwake: Boolean, navigationTextureRequested: Boolean, requiresHdr: Boolean) {
        assertPlayer(player); viewport(assembly, player, modifier, resizeMode, keepAwake, navigationTextureRequested, requiresHdr)
    }
    @Composable override fun DanmakuSurface(modifier: Modifier, videoWidth: Int, videoHeight: Int, resizeMode: Int) {
        assertOwned(); danmakuSurface(assembly, modifier, videoWidth, videoHeight, resizeMode)
    }
}

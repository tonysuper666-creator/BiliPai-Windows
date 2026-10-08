package com.bilipai.desktop.ui

import com.android.purebilibili.core.plugin.SkipAction
import com.android.purebilibili.feature.video.player.*
import com.android.purebilibili.feature.video.viewmodel.*
import com.android.purebilibili.feature.video.subtitle.SubtitleDisplayMode
import com.android.purebilibili.feature.video.subtitle.SubtitleAutoPreference
import com.bilipai.desktop.DesktopPlaybackState
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** All ordinary-video consumers switch together to this view of the ONE installed
 * complete VM/native owner and original playlist. Listen keeps its separate actor.
 * No command/state is delegated to the permanently drained old Controller.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DesktopUnifiedPlaybackFacade(
    private val rootScope: CoroutineScope,
    private val assemblies: StateFlow<DesktopOriginalVideoOwnerAssembly?>,
    private val currentAssembly: () -> DesktopOriginalVideoOwnerAssembly?,
    private val requireAssembly: () -> DesktopOriginalVideoOwnerAssembly,
    val playlist: DesktopOriginalVideoPlaylistBinding,
    private val rootOwned: () -> Boolean,
    private val background: MutableStateFlow<Boolean>,
    private val retireAssembly: (DesktopOriginalVideoOwnerAssembly) -> Unit,
    private val resolveCastSource: (DesktopOriginalVideoAcceptedPublication, VideoPlaybackUiState.Success) -> com.bilipai.desktop.data.PlaybackSource?,
    private val setSubtitleMode: (DesktopOriginalVideoOwnerAssembly, SubtitleDisplayMode) -> Boolean,
    private val changeSubtitleAutoPreference: (DesktopOriginalVideoOwnerAssembly, SubtitleAutoPreference) -> Unit,
    private val cancelPendingPlayback: (DesktopOriginalVideoOwnerAssembly) -> Unit,
    private val resolveQueueCard: suspend (DesktopOriginalVideoOwnerAssembly, VideoCard, () -> Boolean) -> VideoCard,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private class Lease(val owner: Any?, val assembly: DesktopOriginalVideoOwnerAssembly,
        val session: PlaylistSession, @Volatile var nativeBaseline: Long?,
        val clickedCard: VideoCard, val resumeMs: Long)
    @Volatile private var lease: Lease? = null
    @Volatile private var closed = false
    // Only acknowledgement of a legacy feedback label, never another playback state.
    private val dismissedError = MutableStateFlow<VideoPlaybackUiState.Error?>(null)
    private val pendingCardResolution = MutableStateFlow<Job?>(null)
    private val facadeOpen = MutableStateFlow(true)
    private fun ownsRoot() = !closed && checkNotNull(rootScope.coroutineContext[Job]).isActive && rootOwned()
    private fun held(): DesktopOriginalVideoOwnerAssembly? = currentAssembly()?.takeIf { ownsRoot() && it.owns() }
    private fun required(): DesktopOriginalVideoOwnerAssembly {
        if (!ownsRoot()) throw CancellationException("Unified ordinary owner retired")
        return requireAssembly().also { if (!it.owns()) throw CancellationException("Original assembly retired") }
    }
    private fun currentSuccess(a: DesktopOriginalVideoOwnerAssembly) = a.playback.uiState.value as? VideoPlaybackUiState.Success
    private fun cards() = playlist.playlist.value.map(::favoriteQueueVideoCard)

    val state: StateFlow<DesktopPlaybackState> = assemblies.flatMapLatest { assembly ->
        if (assembly == null) combine(playlist.playlist, playlist.currentIndex) { items, index ->
            DesktopPlaybackState(queue = items.map(::favoriteQueueVideoCard), queueIndex = index)
        } else combine(combine(assembly.playback.uiState, assembly.domains.engagement.uiState,
                assembly.playback.desktopPlaybackRecoveryState) { ui, engagement, recovery ->
                (if (ui is VideoPlaybackUiState.Success) ui.withEngagementUiState(engagement) else ui) to recovery
            }.combine(combine(dismissedError,pendingCardResolution) { dismissed, pending -> dismissed to pending }) { ui, transient -> ui to transient }, playlist.playlist, playlist.currentIndex,
            assembly.playback.showSkipButton, assembly.playback.currentSponsorSegment) { ui, items, index, showSkip, segment ->
            if (!assembly.owns() || currentAssembly() !== assembly || !ownsRoot()) DesktopPlaybackState()
            else {
                val playbackUi = ui.first.first
                val recovery = ui.first.second
                val success = playbackUi as? VideoPlaybackUiState.Success
                val info = success?.info
                val details = info?.let { raw -> VideoDetails(raw.bvid, raw.aid, raw.title, raw.desc, raw.pic,
                    raw.owner.name, raw.stat.view.toLong(), raw.stat.like.toLong(), raw.pages.map { VideoPart(it.cid, it.part, it.duration.toLong()) }, raw.owner.mid, raw) }
                val part = info?.pages?.indexOfFirst { it.cid == info.cid } ?: -1
                DesktopPlaybackState(details = details, opening = playbackUi is VideoPlaybackUiState.Loading || ui.second.second!=null,
                    currentPart = part.coerceAtLeast(0), quality = success?.requestedQuality ?: success?.currentQuality ?: 0,
                    effectiveQuality = success?.currentQuality ?: 0,
                    availableQualities = success?.qualityIds?.mapIndexed { i, id -> PlaybackQuality(id, success.qualityLabels.getOrNull(i) ?: id.toString()) }.orEmpty(),
                    related = success?.related?.map { related -> VideoCard(related.bvid, related.title, related.pic,
                        related.owner.name, related.stat.view.toLong(), related.duration, authorMid = related.owner.mid) }.orEmpty(),
                    error = if (recovery.status == PlaybackStatus.Failed) recovery.message
                        else if (recovery.status == PlaybackStatus.Recovering) null
                        else (playbackUi as? VideoPlaybackUiState.Error)?.takeUnless { it === ui.second.first }?.msg,
                    queue = items.map(::favoriteQueueVideoCard), queueIndex = index,
                    recovering = recovery.status == PlaybackStatus.Recovering || success?.isQualitySwitching == true,
                    recoveryMessage = recovery.recoveryStage,
                    manualSkip = if (showSkip && segment != null) SkipAction.ShowButton(segment.endTimeMs,
                        assembly.playback.currentSkipReason.value ?: segment.category, segment.UUID) else null)
            }
        }
    }.combine(facadeOpen) { projected, open -> if (open && ownsRoot()) projected else DesktopPlaybackState() }
        .stateIn(rootScope, SharingStarted.Eagerly, DesktopPlaybackState(queue = cards(), queueIndex = playlist.currentIndex.value))

    fun open(card: VideoCard) = openQueue(listOf(card), 0)
    fun open(bvid: String) = open(VideoCard(bvid, "", "", "", 0, 0))
    fun open(card: VideoCard, resumePositionMs: Long) = openQueue(listOf(card), 0, resumePositionMs = resumePositionMs)
    /** Root calls after real legacy drain/native adoption and before publishing
     * commands. Actual part shuffle belongs to the old Windows-only strategy:
     * the required caller must accept/map that boundary explicitly. */
    fun adoptQueueHandoff(queue: DesktopOrdinaryPlaybackQueueHandoff,
        source: ExternalPlaylistSource, mode: PlayMode, shuffleEnabled: Boolean,
        acceptPartShuffle: (ShuffleProgress) -> Boolean): Boolean {
        val a=held() ?: return false
        val expected=a.native.current() ?: return false
        val selected=queue.cards[queue.selectedIndex]
        if (selected.bvid!=expected.request.bvid ||
            (selected.preferredCid>0L && selected.preferredCid!=expected.request.cid) || !acceptPartShuffle(queue.partShuffle)) return false
        var adopted=false
        if (!a.native.admitPlaybackDispatch(expected) {
            playlist.setPlayMode(mode)
            playlist.setShuffleEnabled(shuffleEnabled)
            val session=playlist.adoptQueue(queue.cards.map { PlaylistItem(it.bvid,it.preferredCid,it.title,
                it.cover,it.author,duration=it.duration.toLong()) },queue.selectedIndex,source,
                queue.owner!=null,queue.shuffle)
            lease=Lease(queue.owner,a,session,expected.sourceVersion,selected,0L)
            adopted=true
        }) return false
        return adopted
    }
    fun openVideoDetail(card: VideoCard, resumePositionMs: Long, keepMatchingSource: Boolean, bootstrapSource: DesktopVideoBootstrapSeed? = null): Boolean {
        val a = held(); val accepted = a?.native?.current(); val success = a?.let(::currentSuccess)
        val same = accepted != null && card.preferredCid > 0 && accepted.request.bvid == card.bvid &&
            accepted.request.cid == card.preferredCid && success?.info?.bvid == card.bvid && success.info.cid == card.preferredCid
        if (keepMatchingSource && same && !success!!.isQualitySwitching && a!!.section.isOwned()) {
            if (a.native.admitPlaybackDispatch(checkNotNull(accepted)) {
                if (resumePositionMs > 0) a.playback.seekTo(resumePositionMs)
            }) return true
        }
        openQueue(listOf(card), 0, resumePositionMs = resumePositionMs, bootstrapSource = bootstrapSource); return false
    }
    fun openQueue(cards: List<VideoCard>, selectedIndex: Int = 0, owner: Any? = null, resumePositionMs: Long? = null, bootstrapSource: DesktopVideoBootstrapSeed? = null) {
        require(cards.size <= 10_000 && selectedIndex in cards.indices && cards.all { it.bvid.isNotBlank() })
        checkpoint()
        val a = required()
        cancelCardResolution()
        val items = cards.map { card -> PlaylistItem(card.bvid, card.preferredCid, card.title, card.cover,
            card.author, duration = card.duration.toLong()) }
        val session = if (owner == null) playlist.adoptQueue(items, selectedIndex, ExternalPlaylistSource.NONE,
            false, ShuffleProgress()) else playlist.setExternalPlaylist(items, selectedIndex, ExternalPlaylistSource.UNKNOWN)
        val selected = cards[selectedIndex]
        val resume = resumePositionMs?.coerceAtLeast(0) ?: (selected.progressSeconds?.toLong()?.times(1000) ?: 0L)
        val capturedLease=Lease(owner,a,session,a.section.nativePlayer.currentSourceVersion,selected,resume)
        lease=capturedLease
        beginCardLoad(capturedLease,selectedIndex,bootstrapSource)
    }
    private fun beginCardLoad(capturedLease: Lease, selectedIndex: Int, bootstrapSource: DesktopVideoBootstrapSeed? = null) {
        val a=capturedLease.assembly;val session=capturedLease.session
        val selected=capturedLease.clickedCard;val resume=capturedLease.resumeMs
        if (selected.preferredCid <= 0L && selected.pageIndex > 0 && playlist.getCurrentItem()?.cid==selected.preferredCid) {
            fun current() = held()===a && lease===capturedLease && playlist.isSessionCurrent(session) &&
                playlist.currentIndex.value==selectedIndex && playlist.getCurrentItem()?.let {
                    it.bvid==selected.bvid && it.cid==selected.preferredCid }==true
            val job=a.environment.scope.launch(start=CoroutineStart.LAZY) {
                try {
                    currentCoroutineContext().ensureActive()
                    if (!current()) throw CancellationException("Queued part resolution retired")
                    val resolved=resolveQueueCard(a,selected,::current)
                    currentCoroutineContext().ensureActive()
                    if (!current() || resolved.bvid!=selected.bvid || resolved.preferredCid<=0L)
                        throw CancellationException("Queued part resolution replaced")
                    if (!playlist.resolveSelectedCidIfCurrent(session,selected.bvid,selected.preferredCid,resolved.preferredCid))
                        throw CancellationException("Queued part resolution lost original session")
                    a.playback.loadVideo(resolved.bvid,cid=resolved.preferredCid,fallbackResumePositionMs=resume, desktopBootstrapSource=bootstrapSource)
                } catch (cancelled:CancellationException) { throw cancelled }
                catch (failure:Exception) { if (current()) onFailure(failure.message ?: "视频分P解析失败") }
                finally { if (pendingCardResolution.value===currentCoroutineContext()[Job]) pendingCardResolution.value=null }
            }
            pendingCardResolution.value=job
            job.start()
            return
        }
        a.playback.loadVideo(selected.bvid, cid = selected.preferredCid,
            fallbackResumePositionMs = resume, desktopBootstrapSource = bootstrapSource)
    }
    private fun cancelCardResolution() { pendingCardResolution.value?.cancel();pendingCardResolution.value=null }
    /** Read only the sole original queue/session. Root collects [state] and must
     * keep a missing-CID non-first-part route unmounted until this returns the
     * actually resolved item; null is never permission to load CID 0 instead. */
    fun resolvedQueueCardForOwner(owner: Any?, bvid: String): VideoCard? {
        val captured = lease ?: return null
        val a = held() ?: return null
        if (captured.owner !== owner || captured.assembly !== a ||
            !playlist.isSessionCurrent(captured.session) || pendingCardResolution.value != null) return null
        val selected = playlist.getCurrentItem()?.takeIf { it.bvid == bvid } ?: return null
        if (captured.clickedCard.bvid == bvid && captured.clickedCard.pageIndex > 0 && selected.cid <= 0L) return null
        return favoriteQueueVideoCard(selected)
    }
    fun ownsQueue(owner: Any): Boolean {
        val heldLease = lease ?: return false
        val a = held() ?: return false
        if (heldLease.owner !== owner || heldLease.assembly !== a || !playlist.isSessionCurrent(heldLease.session)) return false
        val accepted = a.native.current()
        if (accepted != null) { heldLease.nativeBaseline = accepted.sourceVersion; return true }
        return a.section.nativePlayer.currentSourceVersion == heldLease.nativeBaseline
    }
    fun updateQueueForOwner(owner: Any, cards: List<VideoCard>, selectedIndex: Int): Boolean {
        if (!ownsQueue(owner) || cards.size > 10_000 || selectedIndex !in cards.indices) return false
        val current = playlist.getCurrentItem() ?: return false
        val selected = cards[selectedIndex]
        if (selected.bvid != current.bvid || selected.preferredCid != current.cid) return false
        val normalized = cards.filter { it.bvid.isNotBlank() }.distinctBy { it.bvid to it.preferredCid }
        val index = normalized.indexOfFirst { it.bvid == selected.bvid && it.preferredCid == selected.preferredCid }
        if (index < 0) return false
        // Favorite's upstream caller already applies original unseen-BVID append.
        // Story also needs removal/reorder. Both update the ONE original list and
        // session while retaining selected CID/source/native position and pause.
        val heldLease = checkNotNull(lease)
        return playlist.replaceQueueIfCurrent(normalized.map { PlaylistItem(it.bvid, it.preferredCid, it.title,
            it.cover, it.author, duration = it.duration.toLong()) }, index, heldLease.session)
    }
    fun retryQueueForOwner(owner: Any): Boolean {
        if (!ownsQueue(owner) || state.value.opening || state.value.recovering) return false
        val heldLease=checkNotNull(lease)
        val selected=playlist.getCurrentItem() ?: return false
        val loaded=heldLease.assembly.captureLoadState()
        if (loaded.currentBvid==selected.bvid && (selected.cid<=0L || loaded.currentCid==selected.cid)) {
            heldLease.assembly.playback.retry()
        } else {
            cancelCardResolution()
            beginCardLoad(heldLease,playlist.currentIndex.value)
        }
        return true
    }
    fun stopQueueForOwner(owner: Any): Boolean { if (!ownsQueue(owner)) return false; stop(); return true }
    private fun navigationOwner(): DesktopOriginalVideoOwnerAssembly? = held()?.takeIf { a ->
        pendingCardResolution.value==null && currentSuccess(a)?.let { ui ->
            val selected=playlist.getCurrentItem()
            selected!=null && selected.bvid==ui.info.bvid && (selected.cid<=0L || selected.cid==ui.info.cid) && !ui.isQualitySwitching
        }==true }
    val hasPrevious: Boolean get() = navigationOwner()?.environment?.mini?.onHasPreviousNavigationCallback?.invoke() == true
    val hasNext: Boolean get() = navigationOwner()?.environment?.mini?.onHasNextNavigationCallback?.invoke() == true
    fun previous() { navigationOwner()?.environment?.mini?.onNavigatePreviousCallback?.invoke() }
    fun next() { navigationOwner()?.environment?.mini?.onNavigateNextCallback?.invoke() }
    /** The UI captures this before launching its genuine per-click task. An
     * explicitly supplied publication is the existing SMTC/source lease, never
     * a later native getter used to replace that lease. */
    internal fun captureManualNavigation(
        expected: DesktopOriginalVideoAcceptedPublication? = null,
    ): DesktopOriginalManualPlaybackClick? {
        val a = navigationOwner() ?: return null
        val accepted = expected ?: a.native.current() ?: return null
        val raw = a.playback.captureDesktopPlaybackState() as? VideoPlaybackUiState.Success ?: return null
        if (!a.native.isCurrent(accepted) || raw.info.bvid != accepted.request.bvid ||
            raw.info.cid != accepted.request.cid || raw.isQualitySwitching) return null
        return DesktopOriginalManualPlaybackClick(a, accepted,
            a.environment.mini.onNavigateNextCallback, a.environment.mini.onNavigatePreviousCallback)
    }
    /** No VM/queue/checkpoint/cancellation runs in native/Store admission. The
     * complete original helper selects its target on this actual UI task. */
    internal fun navigateManual(click: DesktopOriginalManualPlaybackClick, forward: Boolean,
        caller: Job, uiCurrent: () -> Boolean): Boolean {
        val a = click.assembly
        val callback = if (forward) click.nextCallback else click.previousCallback
        fun current(): Boolean {
            val raw = a.playback.captureDesktopPlaybackState() as? VideoPlaybackUiState.Success ?: return false
            val actualCallback = if (forward) a.environment.mini.onNavigateNextCallback
                else a.environment.mini.onNavigatePreviousCallback
            return !caller.isCancelled && uiCurrent() && held() === a && pendingCardResolution.value == null &&
                callback != null && actualCallback === callback && a.native.isCurrent(click.accepted) &&
                raw.info.bvid == click.accepted.request.bvid && raw.info.cid == click.accepted.request.cid &&
                !raw.isQualitySwitching
        }
        if (!caller.isActive || !current()) return false
        val origin = click.accepted.bootstrapOrigin
        // Genuine Unknown sources keep the original callback (including PGC).
        // A known but rejected origin must never fall through to this old path.
        if (origin == null) return callback?.invoke() == true
        if (origin.seed.assembly !== a) return false
        val source = DesktopOriginalManualPlaybackNavigation(origin, a.native, click.accepted, caller, ::current)
        if (!source.isCurrent()) return false
        return if (forward) a.playback.playNextPageOrRecommended(
            ignoreSavedProgress = false, desktopManualNavigation = source)
        else a.playback.playPreviousPageOrRecommended(
            ignoreSavedProgress = false, desktopManualNavigation = source)
    }
    /** EOF is consumed by original VM's existing native listener, never a second
     * observer. This entry explicitly requests original next strategy if called. */
    fun nextAtEnd() { held()?.playback?.playNextPageOrRecommended() }
    fun playPart(index: Int, position: Double = 0.0, paused: Boolean = false,
        bootstrapSource: DesktopVideoBootstrapSeed? = null,
        expectedPartState: VideoPlaybackUiState.Success? = null) {
        require(position.isFinite())
        val a = held() ?: return
        val success = if (bootstrapSource == null) currentSuccess(a) else expectedPartState?.takeIf {
            bootstrapSource.assembly === a && a.playback.captureDesktopPlaybackState() === it
        }
        if (success == null) return
        val page = success.info.pages.getOrNull(index) ?: return
        val partSource = if (bootstrapSource == null) null
            else bootstrapSource.forPart(success, index, position, paused) ?: return
        a.playback.loadVideo(success.info.bvid, success.info.aid, force = true, autoPlay = !paused,
            cid = page.cid, fallbackResumePositionMs = (position.coerceAtLeast(0.0)*1000).toLong(),
            desktopBootstrapSource = partSource)
    }
    fun onPlaybackPreferencesChanged(previous: PlayerPreferences, next: PlayerPreferences, forceSpeed: Boolean = false) {
        val a = held() ?: return
        val normalized = next.normalized()
        val accepted = a.native.current()
        if (accepted != null && previous.hardwareDecodeEnabled != normalized.hardwareDecodeEnabled)
            a.native.admitPlaybackDispatch(accepted) { a.section.nativePlayer.setHardwareDecodingEnabled(normalized.hardwareDecodeEnabled, accepted.sourceVersion) }
        if (previous.videoCodecPreference != normalized.videoCodecPreference) a.playback.setVideoCodec(normalized.videoCodecPreference)
        if (previous.videoSecondCodecPreference != normalized.videoSecondCodecPreference) a.playback.setVideoSecondCodec(normalized.videoSecondCodecPreference)
        if (forceSpeed || previous.speed != normalized.speed) a.playback.applyPlaybackSpeedFromUi(normalized.speed.toFloat())
        if (previous.subtitleAutoPreference != normalized.subtitleAutoPreference) changeSubtitleAutoPreference(a, normalized.subtitleAutoPreference)
    }
    fun selectAudioQuality(preferenceId: Int) { held()?.playback?.setAudioQuality(preferenceId) }
    /** Capture the actual source and raw VM state before opening an audio popup. */
    fun captureAudioSelection(expectedAssembly: DesktopOriginalVideoOwnerAssembly,
        expected: DesktopOriginalVideoAcceptedPublication,
        presentationCurrent: () -> Boolean): DesktopWindowsVideoAudioSelection? {
        checkAudioUiDispatcher()
        if (!presentationCurrent()) return null
        val a = held()?.takeIf { it === expectedAssembly } ?: return null
        var captured: DesktopWindowsVideoAudioSelection? = null
        a.native.admitPlaybackDispatch(expected) {
            val raw = a.playback.captureDesktopPlaybackState() as? VideoPlaybackUiState.Success
            val session = a.captureLoadState()
            if (presentationCurrent() && raw != null && !raw.isQualitySwitching && raw.info.bvid == expected.request.bvid &&
                raw.info.cid == expected.request.cid && session.currentBvid == raw.info.bvid &&
                session.currentCid == raw.info.cid) {
                captured = DesktopWindowsVideoAudioSelection(a, expected, raw, session.currentLoadRequestToken, presentationCurrent,
                    a.section.nativePlayer.state.value.nativeTrackIdentity)
            }
        }
        return captured
    }
    fun isAudioSelectionCurrent(selection: DesktopWindowsVideoAudioSelection): Boolean {
        if (!selection.presentationCurrent()) return false
        val a = held() ?: return false
        if (a !== selection.assembly || !a.native.isCurrent(selection.accepted)) return false
        return try {
            desktopWindowsAudioSelectionIdentityCurrent(selection.accepted, a.native.current(),
                selection.success, a.playback.captureDesktopPlaybackState(), selection.loadToken, a.captureLoadState())
        } catch (_: CancellationException) { false }
    }
    fun selectAudioLanguage(selection: DesktopWindowsVideoAudioSelection, language: String?): Boolean {
        val a = selection.assembly
        return consumeAudioLanguage(selection.success, language, { isAudioSelectionCurrent(selection) },
            admit = { action -> a.native.admitPlaybackDispatch(selection.accepted, action) },
            readNative = { a.section.nativePlayer.state.value },
            checkpoint = a.playback::saveCurrentPosition,
            reload = { change ->
                val request = change.request
                // The original VM owns cancellation, request token, transport and native publication.
                // Full load/checkpoint work runs after Store/entry/native monitors have returned.
                a.playback.loadVideo(request.bvid, request.aid, force = request.force,
                    autoPlay = request.autoPlay, cid = request.cid, audioLang = request.audioLang,
                    fallbackResumePositionMs = change.positionMs,
                    desktopExplicitStartPositionMs = change.positionMs)
            })
    }
    fun selectNativeAudioTrack(selection: DesktopWindowsVideoAudioSelection, id: Int): Boolean {
        val native = selection.assembly.section.nativePlayer
        val identity = selection.nativeTrackIdentity ?: return false
        return consumeNativeAudioTrack(id, { isNativeAudioSelectionCurrent(selection) },
            admit = { action -> selection.assembly.native.admitPlaybackDispatch(selection.accepted, action) },
            readNative = { native.state.value },
            select = { track -> native.selectAudioTrackForIdentity(identity, track) })
    }
    fun isNativeAudioSelectionCurrent(selection: DesktopWindowsVideoAudioSelection): Boolean =
        selection.nativeTrackIdentity?.let { identity -> isAudioSelectionCurrent(selection) &&
            selection.assembly.section.nativePlayer.isNativeTrackIdentityCurrent(identity) } == true

    companion object {
        /** Shared by the actual facade and headless complete-VM command tests.
         * Admission only starts the existing invocation; no plugin IO or new actor lives here. */
        internal fun consumeManualSponsorSkip(expected: SkipAction.ShowButton,
            segment: com.android.purebilibili.data.model.response.SponsorSegment,
            current: () -> Boolean, admit: ((() -> Unit) -> Boolean),
            read: () -> Pair<SkipAction.ShowButton?, com.android.purebilibili.data.model.response.SponsorSegment?>,
            execute: () -> Unit): Boolean {
            checkAudioUiDispatcher()
            if (expected.skipToMs <= 0L || expected.segmentId.isBlank() || !current()) return false
            var dispatched = false
            val admitted = admit {
                if (current()) {
                    val (shown, active) = read()
                    if (active === segment && shown == expected && expected.segmentId == segment.UUID &&
                        expected.skipToMs == segment.endTimeMs) {
                        execute(); dispatched = true
                    }
                }
            }
            return admitted && dispatched
        }
        private fun checkAudioUiDispatcher() {
            check(javax.swing.SwingUtilities.isEventDispatchThread()) { "Audio selection requires the desktop UI dispatcher" }
        }
        /** Actual facade dispatcher, shared with headless consumption tests; never performs IO inside admit. */
        internal fun consumeAudioLanguage(success: VideoPlaybackUiState.Success, language: String?,
            current: () -> Boolean, admit: ((() -> Unit) -> Boolean), readNative: () -> PlayerState,
            checkpoint: () -> Unit, reload: (DesktopWindowsAudioLanguageSelection) -> Unit): Boolean {
            checkAudioUiDispatcher()
            if (!current()) return false
            var change: DesktopWindowsAudioLanguageSelection? = null
            if (!admit { if (current()) change = resolveDesktopWindowsAudioLanguageSelection(success, language, readNative()) }) return false
            val selected = change ?: return false
            if (!current()) return false
            try { checkpoint() } catch (_: CancellationException) { return false }
            if (!current()) return false
            reload(selected)
            return true
        }
        internal fun consumeNativeAudioTrack(id: Int, current: () -> Boolean,
            admit: ((() -> Unit) -> Boolean), readNative: () -> PlayerState, select: (Int) -> Boolean): Boolean {
            checkAudioUiDispatcher()
            if (id <= 0 || !current()) return false
            var selected = false
            val admitted = admit {
                val native = if (current()) readNative() else null
                if (native != null && native.ready && !native.loading && native.error == null && native.failure == null &&
                    desktopWindowsNativeAudioTracks(native).any { it.id == id && !it.selected }) {
                    selected = select(id)
                }
            }
            return admitted && selected
        }
    }
    fun setAutomaticSubtitleMode(mode: SubtitleDisplayMode): Boolean = held()?.let { setSubtitleMode(it,mode) } == true
    fun switchQuality(quality: Int) { held()?.playback?.changeQuality(quality) }
    fun retry() { held()?.playback?.retry() }
    fun seek(cid: Long, seconds: Double) { held()?.let { a -> if (a.native.current()?.request?.cid == cid) seekTo(seconds) } }
    fun seekTo(seconds: Double) { if (seconds.isFinite()) held()?.playback?.seekTo((seconds.coerceAtLeast(0.0)*1000).toLong()) }
    fun seekBy(seconds: Double) { if (seconds.isFinite()) held()?.let { seekTo(it.section.currentPosition/1000.0+seconds) } }
    /** Same UI callback and complete original VM action, with its rendered source/segment receipt. */
    fun executeManualSkip(expectedAssembly: DesktopOriginalVideoOwnerAssembly,
        expected: DesktopOriginalVideoAcceptedPublication,
        segment: com.android.purebilibili.data.model.response.SponsorSegment,
        action: SkipAction.ShowButton, presentationCurrent: () -> Boolean): Boolean {
        checkAudioUiDispatcher()
        val a = held()?.takeIf { it === expectedAssembly } ?: return false
        return consumeManualSponsorSkip(action, segment,
            current = { presentationCurrent() && held() === a && a.native.isCurrent(expected) &&
                (a.playback.captureDesktopPlaybackState() as? VideoPlaybackUiState.Success)?.let {
                    !it.isQualitySwitching && it.info.bvid == expected.request.bvid && it.info.cid == expected.request.cid
                } == true },
            admit = { block -> a.native.admitPlaybackDispatch(expected, block) },
            read = {
                val active = a.playback.currentSponsorSegment.value
                val shown = if (a.playback.showSkipButton.value && active != null)
                    SkipAction.ShowButton(active.endTimeMs, a.playback.currentSkipReason.value ?: active.category, active.UUID) else null
                shown to active
            }, execute = a.playback::skipCurrentSponsorSegment)
    }
    internal fun currentCastSource(expectedSourceVersion: Long): com.bilipai.desktop.data.PlaybackSource? {
        val a=held() ?: return null;val accepted=a.native.current() ?: return null;val success=currentSuccess(a) ?: return null
        if (accepted.sourceVersion!=expectedSourceVersion || accepted.request.bvid!=success.info.bvid || accepted.request.cid!=success.info.cid) return null
        return resolveCastSource(accepted,success)?.takeIf { it.authorizationReceipt==accepted.nativeSource.source.authorizationReceipt && a.native.isCurrent(accepted) }
    }
    fun checkpoint(): Boolean {
        val a=held() ?: return true
        return try { a.playback.saveCurrentPosition();a.playback.flushPlaybackHeartbeatSnapshot("checkpoint");true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { onFailure("播放记录保存失败，请检查本地隐私和存储设置");false }
    }
    fun stop() {
        val a=held() ?: return
        cancelCardResolution()
        checkpoint()
        a.native.current()?.let { expected -> a.native.admitPlaybackDispatch(expected) { a.section.nativePlayer.stopIfSourceVersion(expected.sourceVersion) } }
        lease=null;retireAssembly(a) // retire/cancel immediately; Root joins outside locks
        playlist.clearPlaylist()
    }
    fun pause() { held()?.let {
        cancelCardResolution();checkpoint();cancelPendingPlayback(it);it.section.pause()
        // An initial request has no committed Success to keep. Retiring this exact
        // assembly cancels its producers; Root drains externally before next load.
        if (it.playback.uiState.value is VideoPlaybackUiState.Loading) { lease=null;retireAssembly(it) }
    } }
    fun setInBackground(value:Boolean) { if (ownsRoot()) background.value=value }
    fun dismissError() { dismissedError.value=held()?.playback?.uiState?.value as? VideoPlaybackUiState.Error }
    internal fun updateFavoriteCountForOwner(aid:Long,expectedEpoch:Long,count:Int):Boolean {
        val a=held() ?: return false;val expected=a.native.current() ?: return false
        if(expected.accountEpoch!=expectedEpoch || currentSuccess(a)?.info?.aid!=aid)return false
        val subject=a.domains.engagement.uiState.value.subject ?: return false
        if(subject.aid!=aid || subject.bvid!=expected.request.bvid || subject.cid!=expected.request.cid)return false
        var confirmed=false
        return a.native.admitPlaybackDispatch(expected) { confirmed=a.domains.engagement.confirmDesktopFavoriteCount(subject,count) } && confirmed
    }
    /** Root only closes the facade view. It explicitly drains Assembly before a
     * new ordinary producer; no global MPV/Listen/Store is closed here. */
    override fun close() { closed=true;facadeOpen.value=false;cancelCardResolution();lease=null }
}

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
        } else combine(combine(assembly.playback.uiState, assembly.domains.engagement.uiState) { ui, engagement ->
                if (ui is VideoPlaybackUiState.Success) ui.withEngagementUiState(engagement) else ui
            }.combine(combine(dismissedError,pendingCardResolution) { dismissed, pending -> dismissed to pending }) { ui, transient -> ui to transient }, playlist.playlist, playlist.currentIndex,
            assembly.playback.showSkipButton, assembly.playback.currentSponsorSegment) { ui, items, index, showSkip, segment ->
            if (!assembly.owns() || currentAssembly() !== assembly || !ownsRoot()) DesktopPlaybackState()
            else {
                val playbackUi = ui.first
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
                    error = (playbackUi as? VideoPlaybackUiState.Error)?.takeUnless { it === ui.second.first }?.msg,
                    queue = items.map(::favoriteQueueVideoCard), queueIndex = index,
                    recovering = success?.isQualitySwitching == true,
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
    fun openVideoDetail(card: VideoCard, resumePositionMs: Long, keepMatchingSource: Boolean): Boolean {
        val a = held(); val accepted = a?.native?.current(); val success = a?.let(::currentSuccess)
        val same = accepted != null && card.preferredCid > 0 && accepted.request.bvid == card.bvid &&
            accepted.request.cid == card.preferredCid && success?.info?.bvid == card.bvid && success.info.cid == card.preferredCid
        if (keepMatchingSource && same && !success!!.isQualitySwitching && a!!.section.isOwned()) {
            if (a.native.admitPlaybackDispatch(checkNotNull(accepted)) {
                if (resumePositionMs > 0) a.playback.seekTo(resumePositionMs)
            }) return true
        }
        open(card, resumePositionMs); return false
    }
    fun openQueue(cards: List<VideoCard>, selectedIndex: Int = 0, owner: Any? = null, resumePositionMs: Long? = null) {
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
        beginCardLoad(capturedLease,selectedIndex)
    }
    private fun beginCardLoad(capturedLease: Lease, selectedIndex: Int) {
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
                    a.playback.loadVideo(resolved.bvid,cid=resolved.preferredCid,fallbackResumePositionMs=resume)
                } catch (cancelled:CancellationException) { throw cancelled }
                catch (failure:Exception) { if (current()) onFailure(failure.message ?: "视频分P解析失败") }
                finally { if (pendingCardResolution.value===currentCoroutineContext()[Job]) pendingCardResolution.value=null }
            }
            pendingCardResolution.value=job
            job.start()
            return
        }
        a.playback.loadVideo(selected.bvid, cid = selected.preferredCid,
            fallbackResumePositionMs = resume)
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
    /** EOF is consumed by original VM's existing native listener, never a second
     * observer. This entry explicitly requests original next strategy if called. */
    fun nextAtEnd() { held()?.playback?.playNextPageOrRecommended() }
    fun playPart(index: Int, position: Double = 0.0, paused: Boolean = false) {
        require(position.isFinite())
        val a = held() ?: return; val success = currentSuccess(a) ?: return
        val page = success.info.pages.getOrNull(index) ?: return
        a.playback.loadVideo(success.info.bvid, success.info.aid, force = true, autoPlay = !paused,
            cid = page.cid, fallbackResumePositionMs = (position.coerceAtLeast(0.0)*1000).toLong())
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
    fun setAutomaticSubtitleMode(mode: SubtitleDisplayMode): Boolean = held()?.let { setSubtitleMode(it,mode) } == true
    fun switchQuality(quality: Int) { held()?.playback?.changeQuality(quality) }
    fun retry() { held()?.playback?.retry() }
    fun seek(cid: Long, seconds: Double) { held()?.let { a -> if (a.native.current()?.request?.cid == cid) seekTo(seconds) } }
    fun seekTo(seconds: Double) { if (seconds.isFinite()) held()?.playback?.seekTo((seconds.coerceAtLeast(0.0)*1000).toLong()) }
    fun seekBy(seconds: Double) { if (seconds.isFinite()) held()?.let { seekTo(it.section.currentPosition/1000.0+seconds) } }
    fun executeManualSkip() { held()?.playback?.skipCurrentSponsorSegment() }
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

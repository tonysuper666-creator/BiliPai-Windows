package com.bilipai.desktop

import com.android.purebilibili.feature.video.playback.audio.*
import com.android.purebilibili.feature.video.playback.policy.shouldRefreshPremiumAudioForPlaybackSpeedChange

import com.android.purebilibili.core.plugin.SkipAction
import com.android.purebilibili.feature.plugin.*
import com.android.purebilibili.feature.video.player.*
import com.android.purebilibili.feature.video.state.PlayerErrorRecoveryAction
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.danmaku.DanmakuOverlay
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopCommunityRepository
import com.bilipai.desktop.data.VideoCard
import com.bilipai.desktop.data.VideoDetails
import com.bilipai.desktop.data.PlaybackSource as ResolvedSource
import com.bilipai.desktop.player.*
import com.bilipai.desktop.plugins.DesktopPluginRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

data class DesktopPlaybackState(
    val details: VideoDetails? = null,
    val opening: Boolean = false,
    val currentPart: Int = 0,
    val quality: Int = 80,
    val effectiveQuality: Int = 0,
    val availableQualities: List<com.bilipai.desktop.data.PlaybackQuality> = emptyList(),
    val related: List<VideoCard> = emptyList(),
    val error: String? = null,
    val queue: List<VideoCard> = emptyList(),
    val queueIndex: Int = -1,
    val recovering: Boolean = false,
    val recoveryMessage: String? = null,
    val manualSkip: SkipAction.ShowButton? = null,
    val pluginHint: String? = null,
    val playerPluginGeneration: Long? = null,
)

/** Business transport boundary also permits deterministic cancellation/ownership verification. */
internal interface DesktopPlaybackDataSource {
    val sessionEpoch: Long get() = 0L
    suspend fun videoDetails(bvid: String): VideoDetails
    suspend fun related(bvid: String): List<VideoCard>
    suspend fun playback(details: VideoDetails, index: Int, quality: Int, codecOverride: String? = null,
        forceRefresh: Boolean = false): ResolvedSource
    suspend fun playbackConfigured(details: VideoDetails, index: Int, quality: Int, playbackPreferences: PlayerPreferences,
        blockedVideoCodecs: Set<String>, codecOverride: String? = null, forceRefresh: Boolean = false): ResolvedSource =
        playback(details, index, quality, codecOverride, forceRefresh)
    suspend fun reportHeartbeat(report: DesktopHeartbeatReport): Boolean = false
}

/** Retains progress, source ownership, original recovery budgets and original queue policies across navigation. */
class DesktopPlaybackController internal constructor(
    repository: DesktopRepository,
    private val player: MpvPlayer?,
    private val playerError: String?,
    private val danmaku: DanmakuOverlay?,
    private val library: DesktopLibrary,
    private val preferences: () -> PlayerPreferences,
    scope: CoroutineScope,
    private val plugins: DesktopPluginRuntime? = null,
    dataSource: DesktopPlaybackDataSource? = null,
    community: DesktopCommunityRepository? = null,
    private val automaticSubtitles: DesktopAutomaticSubtitles? = null,
    private val onRememberAudioQuality: (Int) -> Unit = {},
    private val currentDanmakuSettings: () -> com.bilipai.desktop.danmaku.DanmakuSettings,
    publication: DesktopPlaybackPublication? = null,
) : AutoCloseable {
    private val publication = publication ?: if (dataSource == null) DesktopRepositoryPlaybackPublication(repository)
        else error("Injected playback transport requires explicit local-source publication admission")
    private val communityReports by lazy { community ?: DesktopCommunityRepository(repository) }
    private val playback = dataSource ?: object : DesktopPlaybackDataSource {
        override val sessionEpoch: Long get() = repository.sessionEpoch
        override suspend fun videoDetails(bvid: String) = repository.videoDetails(bvid)
        override suspend fun related(bvid: String) = repository.related(bvid)
        override suspend fun playback(details: VideoDetails, index: Int, quality: Int, codecOverride: String?, forceRefresh: Boolean) =
            repository.playback(details, index, quality, codecOverride, forceRefresh)
        override suspend fun playbackConfigured(details: VideoDetails, index: Int, quality: Int,
            playbackPreferences: PlayerPreferences, blockedVideoCodecs: Set<String>, codecOverride: String?, forceRefresh: Boolean) =
            repository.playback(details, index, quality, codecOverride, forceRefresh, playbackPreferences, blockedVideoCodecs)
        override suspend fun reportHeartbeat(report: DesktopHeartbeatReport) = communityReports.reportPlayHeartbeat(
            bvid = report.identity.bvid, cid = report.identity.cid, aid = report.identity.aid,
            playedTimeSec = report.snapshot.playedTimeSec, realPlayedTimeSec = report.snapshot.realPlayedTimeSec,
            startTsSec = report.startTsSec, expectedSessionEpoch = report.identity.sessionEpoch)
    }
    private val controllerScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(DesktopPlaybackState())
    private val cloudProjectionGuard = repository.dynamicCacheSessionGuard
    val state = mutableState.asStateFlow()
    private val closed = AtomicBoolean()
    private val generation = AtomicLong()
    private var request: Job? = null
    private var recovery: Job? = null
    private var pluginLoad: Job? = null
    private var ownedSourceVersion: Long? = null
    private var suspended = false
    private var handledEnd = false
    private var shuffle = ShuffleProgress()
    private var partShuffle = ShuffleProgress()
    private data class QueueOwnership(val owner: Any, val accountEpoch: Long, val requestGeneration: Long, val nativeBaseline: Long?)
    private var queueOwnership: QueueOwnership? = null
    private val blockedCodecs = mutableSetOf<String>()
    private val health = mutableMapOf<String, CdnCandidateHealth>()
    private var budget = DesktopPlaybackRecoveryBudget()
    private var inBackground = false
    private val heartbeat = DesktopPlaybackHeartbeatTracker()
    private val heartbeatReporter = DesktopHeartbeatReporter({ playback.sessionEpoch }, playback::reportHeartbeat, onAccepted = { report ->
        withContext(Dispatchers.Main) {
            val context = current?.takeIf(::owns)
            if (context != null && context.accountEpoch == report.identity.sessionEpoch && heartbeat.confirm(report) && !report.initial)
                recordCreatorWatch(context)
        }
    })
    private data class Current(val details: VideoDetails, val index: Int, val source: ResolvedSource,
        val candidates: List<PlaybackCdnCandidate>, val cdnIndex: Int, val sourceVersion: Long,
        val requestGeneration: Long, val pluginGeneration: Long? = null, val readyObserved: Boolean = false,
        val handledFailure: Long? = null, val recommendationConsumed: Boolean = false, val accountEpoch: Long = 0L,
        val cdnFallback: PlaybackCdnFallbackState = PlaybackCdnFallbackState.Inactive, val watchdogLoadId: Long = 0L)
    private var current: Current? = null
    private val nextWatchdogLoadId = AtomicLong()
    private val watchdog = DesktopPlaybackWatchdog(controllerScope, ::watchdogSnapshot, ::switchForWatchdog)
    private var pluginMuteUntilMs: Long? = null
    private var pluginMuteFromMs: Long? = null
    private data class PendingSponsorSkip(val seekId: Long, val sourceVersion: Long, val requestGeneration: Long,
        val pluginGeneration: Long, val snapshot: SponsorBlockVideoSnapshot, val segmentId: String,
        val startMs: Long, val endMs: Long, val categoryName: String, val manual: Boolean, val submittedNanos: Long)
    private var pendingSponsorSkip: PendingSponsorSkip? = null

    init {
        if (player != null) controllerScope.launch {
            player.state.collect { native ->
                val context = current ?: return@collect
                if (!owns(context)) {
                    if (ownedSourceVersion == context.sourceVersion && player.currentSourceVersion != context.sourceVersion) {
                        finishHeartbeat(context)
                        context.pluginGeneration?.let { plugins?.onVideoEnd(it) }
                        pluginLoad?.cancel(); recovery?.cancel(); current = null; ownedSourceVersion = null
                        watchdog.reset()
                        pluginMuteUntilMs = null; pluginMuteFromMs = null; pendingSponsorSkip = null
                        mutableState.update { it.copy(recovering = false, recoveryMessage = null, manualSkip = null, playerPluginGeneration = null) }
                    }
                    return@collect
                }
                completeSponsorSkip(context, native)
                if (!native.ended && handledEnd) {
                    handledEnd = false
                    current = context.copy(pluginGeneration = null, readyObserved = false)
                    beginPlugins(current!!)
                }
                if (suspended) {
                    if (!native.paused && !native.loading && !native.ended) {
                        suspended = false
                        if (context.pluginGeneration == null) beginPlugins(context)
                    } else { heartbeat.observe(native, inBackground, suspended = true); return@collect }
                }
                heartbeat.observe(native, inBackground)
                watchdogSnapshot()?.let(watchdog::observe)
                heartbeatReporter.submit(heartbeat.initialReport())
                val failure = native.failure
                if (failure != null && failure.sourceVersion == context.sourceVersion && context.handledFailure != failure.attemptId) {
                    current = context.copy(handledFailure = failure.attemptId)
                    recover(current!!, failure, native)
                    return@collect
                }
                // libmpv initialized != Media3 READY. Failed files never replenish the budget here.
                if (!context.readyObserved && isMediaReadyForRecovery(native)) {
                    budget.ready(); recordHealth(context, CdnHealthEvent.PLAYBACK_READY)
                    current = current?.copy(readyObserved = true, recommendationConsumed = true)
                    if (!context.recommendationConsumed && plugins != null) controllerScope.launch {
                        if (owns(context) && context.accountEpoch == playback.sessionEpoch) plugins.recommendations.consume(context.details.bvid)
                    }
                    mutableState.update { it.copy(recovering = false, recoveryMessage = null, error = null) }
                }
                if (native.ended) nextAtEnd()
                else if (!native.loading && native.error == null) runPositionPlugins(current ?: context, native)
            }
        }
        controllerScope.launch {
            while (isActive) {
                delay(30_000L)
                val context = current?.takeIf(::owns) ?: continue
                if (context.accountEpoch == playback.sessionEpoch) heartbeatReporter.submit(heartbeat.periodicReport())
            }
        }
    }

    fun open(card: VideoCard) { if (!closed.get()) openQueue(listOf(card), 0) }
    fun open(bvid: String) = open(VideoCard(bvid, "", "", "", 0, 0))

    fun openQueue(cards: List<VideoCard>, selectedIndex: Int = 0, owner: Any? = null) {
        if (closed.get()) return
        require(cards.size <= 10_000 && selectedIndex in cards.indices) { "播放队列为空或选中项无效" }
        val selected = cards[selectedIndex]
        require(selected.bvid.isNotBlank()) { "视频编号为空" }
        val normalized = cards.filter { it.bvid.isNotBlank() }.distinctBy { Triple(it.bvid, it.preferredCid, it.pageIndex) }
        val index = normalized.indexOfFirst { it.bvid == selected.bvid && it.preferredCid == selected.preferredCid && it.pageIndex == selected.pageIndex }
        shuffle = ShuffleProgress(); partShuffle = ShuffleProgress()
        queueOwnership = owner?.let { QueueOwnership(it, playback.sessionEpoch, generation.get(), player?.currentSourceVersion) }
        mutableState.update { it.copy(queue = normalized, queueIndex = index) }
        openQueueItem(index, useResume = true)
    }

    /** Identity tokens cannot mutate a retired queue, a different account, or a foreign native source. */
    fun ownsQueue(owner: Any): Boolean {
        val owned = queueOwnership ?: return false
        if (closed.get() || owned.owner !== owner || owned.accountEpoch != playback.sessionEpoch ||
            owned.requestGeneration != generation.get()) return false
        val context = current
        if (context != null) return owns(context)
        // Loading and a failed initial request still hold the same queue lease. A foreign
        // native takeover, changed epoch/generation, or a canceled request cannot acquire it.
        val initialRequestIsHeld = state.value.opening || state.value.error != null
        return initialRequestIsHeld && ownedSourceVersion == null && owned.nativeBaseline == player?.currentSourceVersion
    }

    /** Append/reorder without replacing the actual current item, media source, pause, or position. */
    fun updateQueueForOwner(owner: Any, cards: List<VideoCard>, selectedIndex: Int): Boolean {
        if (!ownsQueue(owner) || cards.size > 10_000 || selectedIndex !in cards.indices) return false
        val previous = state.value.queue
        val selected = previous.getOrNull(state.value.queueIndex) ?: return false
        val requested = cards[selectedIndex]
        if (queueIdentity(selected) != queueIdentity(requested) || requested.bvid.isBlank()) return false
        val normalized = cards.filter { it.bvid.isNotBlank() }.distinctBy(::queueIdentity)
        val index = normalized.indexOfFirst { queueIdentity(it) == queueIdentity(requested) }
        if (index < 0) return false
        // The unchanged original reconciler keys by bvid. Opaque local keys add CID/page identity
        // only for this pure index calculation; they never go to repository, playback, or UI.
        fun item(card: VideoCard) = PlaylistItem(queueIdentity(card), card.preferredCid, card.title,
            card.cover, card.author, duration = card.duration.toLong())
        shuffle = reconcileShuffleProgressForPlaylistUpdate(previous.map(::item), normalized.map(::item), index, shuffle)
        mutableState.update { it.copy(queue = normalized, queueIndex = index) }
        return true
    }

    /** Retry only the held queue. Initial metadata/stream failures have no current native source. */
    fun retryQueueForOwner(owner: Any): Boolean {
        if (!ownsQueue(owner) || state.value.opening || state.value.recovering) return false
        if (current != null) {
            retry()
        } else {
            val index = state.value.queueIndex
            if (index !in state.value.queue.indices) return false
            openQueueItem(index, useResume = true)
        }
        return true
    }

    fun stopQueueForOwner(owner: Any): Boolean {
        if (!ownsQueue(owner)) return false
        stop()
        return true
    }

    private fun queueIdentity(card: VideoCard): String = "${card.bvid.length}:${card.bvid}:${card.preferredCid}:${card.pageIndex}"

    private fun recordQueueRequest(expected: Long, baseline: Long?) {
        queueOwnership = queueOwnership?.takeIf { it.accountEpoch == playback.sessionEpoch }
            ?.copy(requestGeneration = expected, nativeBaseline = baseline)
    }

    private fun openQueueItem(index: Int, useResume: Boolean) {
        val card = state.value.queue.getOrNull(index) ?: return
        checkpoint()
        val cached = state.value.details?.takeIf { it.bvid == card.bvid }
        val related = state.value.related.takeIf { cached != null }.orEmpty()
        invalidate(stopNative = true, retainQueueOwner = true)
        val expected = generation.get()
        val baseline = player?.currentSourceVersion
        recordQueueRequest(expected, baseline)
        val accountEpoch = playback.sessionEpoch
        val resume = if (!useResume) card.copy(progressSeconds = 0)
            else card.takeIf { it.progressSeconds != null || it.preferredCid > 0 } ?: library.resumeCard(card.bvid)
        mutableState.update { DesktopPlaybackState(opening = true, quality = it.quality, queue = it.queue, queueIndex = index, related = related) }
        request = controllerScope.launch {
            try {
                val info = cached ?: playback.videoDetails(card.bvid)
                require(info.pages.isNotEmpty()) { "此视频没有可播放的分集" }
                val part = info.pages.indexOfFirst { it.cid == resume?.preferredCid }.takeIf { it >= 0 }
                    ?: (resume?.pageIndex ?: 0).coerceIn(info.pages.indices)
                if (!valid(expected, baseline, accountEpoch)) return@launch
                val duration = info.pages[part].duration
                val position = (resume?.progressSeconds ?: 0).toDouble().takeIf { it >= 0 && (duration <= 0 || it < duration - 2) } ?: 0.0
                library.record(info.toLibraryCard().copy(progressSeconds = position.toInt(), preferredCid = info.pages[part].cid, pageIndex = part))
                mutableState.update { it.copy(details = info, currentPart = part) }
                if (cached == null) controllerScope.launch {
                    try { playback.related(info.bvid).let { result -> if (expected == generation.get() && !closed.get()) mutableState.update { it.copy(related = result) } } }
                    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                }
                load(info, part, position, false, expected, baseline, accountEpoch)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failRequest(expected, failure, "无法打开视频") }
        }
    }

    fun playPart(index: Int, position: Double = 0.0, paused: Boolean = false) {
        if (closed.get() || !position.isFinite()) return
        val info = state.value.details ?: return
        if (index !in info.pages.indices) return
        checkpoint(); invalidate(stopNative = true, retainQueueOwner = true)
        val expected = generation.get(); val baseline = player?.currentSourceVersion
        recordQueueRequest(expected, baseline)
        val accountEpoch = playback.sessionEpoch
        val queueMatch = state.value.queue.indexOfFirst { it.bvid == info.bvid && it.preferredCid == info.pages[index].cid }
        mutableState.update { it.copy(currentPart = index, queueIndex = queueMatch.takeIf { it >= 0 } ?: it.queueIndex,
            error = null, opening = true, manualSkip = null, recovering = false, recoveryMessage = null, pluginHint = null, playerPluginGeneration = null) }
        request = controllerScope.launch {
            try { load(info, index, position.coerceAtLeast(0.0), paused, expected, baseline, accountEpoch) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failRequest(expected, failure, "播放失败") }
        }
    }

    private suspend fun load(info: VideoDetails, index: Int, position: Double, paused: Boolean, expected: Long, baseline: Long?, accountEpoch: Long) = coroutineScope {
        val settings = preferences().let { it.copy(speed = it.preferredSpeed) }
        val source = playback.playbackConfigured(info, index, state.value.quality, settings, blockedCodecs.toSet())
        if (!valid(expected, baseline, accountEpoch)) return@coroutineScope
        val native = player ?: throw IllegalStateException(playerError ?: "播放器未能初始化")
        native.applyPreferences(preferences().let { it.copy(speed = it.preferredSpeed) })
        val (resolved, candidates, fallback) = prepareResolved(source)
        val callerJob = currentCoroutineContext()[Job]
        val retained = publication.ownedSource(resolved.toNative(position, paused), { callerJob?.isCancelled != true &&
            !closed.get() && expected == generation.get() && playback.sessionEpoch == accountEpoch })
        val version = publication.admit(retained, { callerJob?.isActive == true && valid(expected, baseline, accountEpoch) }) {
            native.loadVersioned(retained)
        }
        ownedSourceVersion = version; suspended = false; handledEnd = false; budget = DesktopPlaybackRecoveryBudget()
        current = Current(info, index, resolved, candidates, 0, version, expected, accountEpoch = accountEpoch,
            cdnFallback = fallback, watchdogLoadId = nextWatchdogLoadId.incrementAndGet())
        launch {
            if (!closed.get() && expected == generation.get() && playback.sessionEpoch == accountEpoch &&
                ownedSourceVersion == version && native.currentSourceVersion == version && current?.sourceVersion == version) {
                danmaku?.applySettings(currentDanmakuSettings())
                danmaku?.load(info.pages[index].cid, info.aid, info.pages[index].duration.toDouble(), expectedSourceVersion = version,
                    maskSource=com.bilipai.desktop.danmaku.DesktopOwnedWebMaskSource(info.bvid,info.pages[index].cid,version,accountEpoch,
                        stillOwned={current?.let {owns(it) && it.sourceVersion==version && it.accountEpoch==accountEpoch && it.details.bvid==info.bvid && it.details.pages[it.index].cid==info.pages[index].cid}==true && native.ownsSourceVersion(version)},
                        metadata={bvid,cid->communityReports.playerMetadata(bvid,cid)}))
            }
        }
        heartbeat.begin(DesktopHeartbeatIdentity(info.bvid, info.pages[index].cid, info.aid, accountEpoch), position)
        mutableState.update { it.copy(opening = false, effectiveQuality = resolved.quality, availableQualities = resolved.availableQualities) }
        watchdogSnapshot()?.let(watchdog::loaded)
        beginPlugins(current!!)
        automaticSubtitles?.load(info.bvid, info.pages[index].cid, version, settings.subtitleAutoPreference,
            settings.muted || settings.volume <= 0.0)
    }

    /** Hardware changes are native; codec changes refresh the same CID while preserving ownership/progress/subtitles. */
    fun onPlaybackPreferencesChanged(previous: PlayerPreferences, next: PlayerPreferences) {
        val context = current?.takeIf(::owns) ?: return
        val normalized = next.normalized()
        if (previous.hardwareDecodeEnabled != normalized.hardwareDecodeEnabled)
            player?.setHardwareDecodingEnabled(normalized.hardwareDecodeEnabled, context.sourceVersion)
        if (previous.subtitleAutoPreference != normalized.subtitleAutoPreference)
            automaticSubtitles?.load(context.details.bvid, context.details.pages[context.index].cid, context.sourceVersion,
                normalized.subtitleAutoPreference, normalized.muted || normalized.volume <= 0.0)
        val requested = context.source.audioSelection?.requestedPreferenceId
            ?: resolveRequestedAudioQuality(normalized.defaultAudioQuality, normalized.lastSelectedAudioQuality)
        if (previous.videoCodecPreference != normalized.videoCodecPreference ||
            previous.videoSecondCodecPreference != normalized.videoSecondCodecPreference) {
            reloadSource(state.value.effectiveQuality.takeIf { it > 0 } ?: state.value.quality,
                forceRefresh = false, preserveCodec = false)
        } else if (shouldRefreshPremiumAudioForPlaybackSpeedChange(requested, previous.speed.toFloat(), normalized.speed.toFloat())) {
            reloadSource(state.value.effectiveQuality.takeIf { it > 0 } ?: state.value.quality, forceRefresh = false)
        }
        // The original default audio setting is read at load time; it does not overwrite the current video's explicit selection.
    }

    fun selectAudioQuality(preferenceId: Int) {
        if (current?.takeIf(::owns) == null) return
        val normalized = normalizeAudioQualityPreference(preferenceId)
        reloadSource(state.value.effectiveQuality.takeIf { it > 0 } ?: state.value.quality,
            forceRefresh = false, audioOverride = normalized, rememberAudio = true)
    }

    fun setAutomaticSubtitleMode(mode: com.android.purebilibili.feature.video.subtitle.SubtitleDisplayMode): Boolean =
        automaticSubtitles?.setDisplayMode(mode) == true

    fun notifyUserSubtitleTrackSelection() { current?.takeIf(::owns)?.let { automaticSubtitles?.onUserTrackSelection(it.sourceVersion) } }

    private fun playbackSettingsFor(context: Current, audioOverride: Int? = null): PlayerPreferences {
        val preferences = preferences()
        return preferences.copy(speed = player?.state?.value?.speed ?: preferences.speed,
            defaultAudioQuality = audioOverride ?: effectiveAudioPreferenceAfterFailure(context.source.audioSelection)
                ?: resolveRequestedAudioQuality(preferences.defaultAudioQuality, preferences.lastSelectedAudioQuality))
    }

    fun switchQuality(quality: Int) {
        if (closed.get() || quality <= 0) return
        mutableState.update { it.copy(quality = quality) }
        reloadSource(quality, forceRefresh = false)
    }

    /** Explicit user retry starts a fresh bounded recovery attempt while retaining the selected CID and subtitles. */
    fun retry() {
        if (closed.get()) return
        suspended = false
        reloadSource(state.value.effectiveQuality.takeIf { it > 0 } ?: state.value.quality, forceRefresh = true)
    }

    private fun reloadSource(quality: Int, forceRefresh: Boolean, preserveCodec: Boolean = true,
        audioOverride: Int? = null, rememberAudio: Boolean = false) {
        val context = current?.takeIf(::owns) ?: run { playPart(state.value.currentPart); return }
        val native = player?.state?.value ?: return
        checkpoint(); request?.cancel(); recovery?.cancel()
        val expected = generation.incrementAndGet()
        recordQueueRequest(expected, player?.currentSourceVersion)
        current = context.copy(requestGeneration = expected)
        if (context.pluginGeneration == null) beginPlugins(current!!)
        mutableState.update { it.copy(opening = true, error = null, recovering = false, recoveryMessage = null) }
        request = controllerScope.launch {
            try {
                val refreshed = playback.playbackConfigured(context.details, context.index, quality,
                    playbackSettingsFor(context, audioOverride), blockedCodecs.toSet(),
                    if (preserveCodec) context.source.videoCodecFamily else null, forceRefresh)
                val retained = if (audioOverride == null) retainDesktopPremiumAudioFallback(refreshed, context.source.audioSelection) else refreshed
                val (source, candidates, fallback) = prepareResolved(retained)
                val active = current ?: return@launch
                if (!owns(active) || expected != generation.get()) return@launch
                val latest = player?.state?.value ?: native
                val callerJob = currentCoroutineContext()[Job]
                if (publication.admit(source.toNative(latest.positionSeconds, latest.paused),
                        { callerJob?.isActive == true && owns(active) && expected == generation.get() }) {
                    player?.recoverSource(active.sourceVersion, publication.ownedSource(source.toNative(latest.positionSeconds, latest.paused),
                            { callerJob?.isCancelled != true && owns(active) && expected == generation.get() }),
                        latest.positionSeconds, latest.paused) == true
                }) {
                    if (rememberAudio && audioOverride != null) onRememberAudioQuality(audioOverride)
                    budget = DesktopPlaybackRecoveryBudget()
                    suspended = false
                    current = active.copy(source = source, candidates = candidates, cdnIndex = 0, readyObserved = false, handledFailure = null,
                        cdnFallback = fallback, watchdogLoadId = nextWatchdogLoadId.incrementAndGet())
                    mutableState.update { it.copy(opening = false, effectiveQuality = source.quality, availableQualities = source.availableQualities) }
                    watchdogSnapshot()?.let(watchdog::loaded)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failRequest(expected, failure, "切换画质失败") }
        }
    }

    val hasPrevious: Boolean get() = target(previous = true, commitShuffle = false) != null
    val hasNext: Boolean get() = target(previous = false, commitShuffle = false) != null
    fun previous() { target(true, true)?.let(::playTarget) }
    fun next() { target(false, true)?.let(::playTarget) }
    private data class Target(val queue: Int? = null, val part: Int? = null)
    private fun target(previous: Boolean, commitShuffle: Boolean): Target? {
        val value = state.value; val info = value.details ?: return null
        if (closed.get() || value.opening || value.currentPart !in info.pages.indices) return null
        val mode = preferences().playbackMode
        val explicit = value.queue.getOrNull(value.queueIndex)?.preferredCid?.let { it > 0 && it == info.pages[value.currentPart].cid } == true
        val queueFirst = explicit || (mode == PlaybackMode.SHUFFLE && value.queue.size > 1)
        fun advance(size: Int, index: Int, progress: ShuffleProgress, update: (ShuffleProgress) -> Unit): Int? {
            if (mode == PlaybackMode.SHUFFLE) {
                if (previous) return progress.history.getOrNull(progress.historyIndex - 1)?.also {
                    if (commitShuffle) update(progress.copy(historyIndex = progress.historyIndex - 1))
                }
                if (!commitShuffle) return (0 until size).firstOrNull { it != index }
                val result = advanceShuffleProgress(size, index, progress) { it.random(Random.Default) }
                update(result.progress); return result.nextIndex
            }
            val wrap = mode == PlaybackMode.REPEAT_ALL
            return if (previous) resolveLinearPlayPreviousIndex(size, index, wrap) else resolveLinearPlayNextIndex(size, index, wrap)
        }
        if (!queueFirst) {
            val part = advance(info.pages.size, value.currentPart, partShuffle) { partShuffle = it }
            // A cross-video queue owns wrapping; only a single-video queue wraps its own parts.
            if (part != null && (value.queue.size <= 1 || (previous && part < value.currentPart) || (!previous && part > value.currentPart))) return Target(part = part)
        }
        val queue = advance(value.queue.size, value.queueIndex, shuffle) { shuffle = it } ?: return null
        return Target(queue = queue)
    }
    private fun playTarget(target: Target) {
        if (target.queue != null) { partShuffle = ShuffleProgress(); openQueueItem(target.queue, useResume = false) }
        else target.part?.let { playPart(it) }
    }

    fun nextAtEnd() {
        val context = current?.takeIf(::owns) ?: return
        if (handledEnd || player?.state?.value?.ended != true || state.value.opening || suspended) return
        handledEnd = true
        watchdog.cancel()
        mutableState.update { it.copy(manualSkip = null, playerPluginGeneration = null) }
        if (pluginMuteUntilMs != null) {
            player?.setMuted(preferences().muted); pluginMuteUntilMs = null; pluginMuteFromMs = null
        }
        context.pluginGeneration?.let { plugins?.onVideoEnd(it) }
        if (preferences().playbackMode == PlaybackMode.STOP_AFTER_CURRENT) return
        if (preferences().playbackMode == PlaybackMode.REPEAT_ONE) {
            if (!publication.isCurrent(context.source.toNative(0.0, false))) return
            publication.admit(context.source.toNative(0.0, false), { owns(context) }) { player?.replay() }
            handledEnd = false; current = context.copy(pluginGeneration = null); beginPlugins(current!!); return
        }
        target(false, true)?.let(::playTarget)
    }

    fun seek(cid: Long, seconds: Double) {
        val info = state.value.details ?: return
        val index = info.pages.indexOfFirst { it.cid == cid }
        if (index < 0) return
        if (index == state.value.currentPart) seekTo(seconds) else playPart(index, seconds)
    }
    /** Cloud membership changes project into the existing video's raw server model. */
    internal fun updateFavoriteCountForOwner(aid: Long, expectedEpoch: Long, count: Int): Boolean {
        val owner = cloudProjectionGuard.dynamicCacheOwner()?.takeIf { it.epoch == expectedEpoch } ?: return false
        var changed = false
        cloudProjectionGuard.withCurrentDynamicCacheOwner(owner) {
            if (closed.get() || playback.sessionEpoch != expectedEpoch) return@withCurrentDynamicCacheOwner
            val context = current?.takeIf(::owns) ?: return@withCurrentDynamicCacheOwner
            if (context.accountEpoch != expectedEpoch || context.details.aid != aid) return@withCurrentDynamicCacheOwner
            val info = mutableState.value.details?.takeIf { it.aid == aid } ?: return@withCurrentDynamicCacheOwner
            val raw = info.raw ?: return@withCurrentDynamicCacheOwner
            val updated = info.copy(raw = raw.copy(stat = raw.stat.copy(favorite = count.coerceAtLeast(0))))
            current = context.copy(details = updated)
            mutableState.update { value -> if (value.details === info) value.copy(details = updated) else value }
            changed = mutableState.value.details === updated
        }
        return changed
    }
    fun seekTo(seconds: Double) { submitUserSeek(seconds) }
    private fun submitUserSeek(seconds: Double): Long? {
        val context = current?.takeIf(::owns) ?: return null
        if (!seconds.isFinite()) return null
        val target = seconds.coerceAtLeast(0.0)
        val seekId = player?.seekToTracked(target) ?: return null
        restorePluginMute((target * 1_000).toLong())
        context.pluginGeneration?.let { plugins?.onUserSeek((target * 1_000).toLong(), it) }
        return seekId
    }
    fun seekBy(seconds: Double) { if (seconds.isFinite()) player?.state?.value?.let { seekTo(it.positionSeconds + seconds) } }
    fun executeManualSkip() {
        val action = state.value.manualSkip ?: return
        val context = current?.takeIf(::owns) ?: return
        val segment = plugins?.sponsorSegments()?.firstOrNull { it.UUID == action.segmentId } ?: return
        val token = context.pluginGeneration ?: return
        val seekId = submitUserSeek(action.skipToMs / 1_000.0) ?: return
        pendingSponsorSkip = pendingSkip(context, token, seekId, segment.UUID, segment.startTimeMs, action.skipToMs, segment.categoryName, manual = true)
        mutableState.update { it.copy(manualSkip = null, pluginHint = action.label) }
    }

    private fun recover(context: Current, failure: PlayerFailure, native: PlayerState) {
        if (!recoverable(context, failure)) return
        watchdog.cancel()
        recovery?.cancel()
        if (recoverPremiumAudio(context, failure, native)) return
        recordHealth(context, CdnHealthEvent.PLAYER_ERROR)
        val action = budget.action(failure, context.cdnIndex + 1 < context.candidates.size)
        if (action == PlayerErrorRecoveryAction.GIVE_UP) {
            mutableState.update { it.copy(recovering = false, recoveryMessage = null, error = failure.safeMessage) }; return
        }
        val delayMs = budget.consume(action)
        mutableState.update { it.copy(recovering = true, error = null, recoveryMessage = when(action) {
            PlayerErrorRecoveryAction.SWITCH_CDN -> "正在切换备用线路（${budget.cdnSwitches}/2）"
            PlayerErrorRecoveryAction.RETRY_NETWORK -> "正在刷新播放地址（${budget.retries}/3）"
            PlayerErrorRecoveryAction.RETRY_DECODER_FALLBACK -> "正在尝试兼容编码与软件解码"
            else -> "正在重新载入视频"
        }) }
        recovery = controllerScope.launch {
            try {
                if (delayMs > 0) delay(delayMs)
                if (!recoverable(context, failure)) return@launch
                var source = context.source
                var candidates = context.candidates
                var cdn = context.cdnIndex
                var fallbackState = context.cdnFallback
                var software = false
                when(action) {
                    PlayerErrorRecoveryAction.SWITCH_CDN -> {
                        cdn++; val selected = candidates[cdn]
                        source = source.copy(videoUrl = selected.videoUrl, audioUrl = selected.audioUrl)
                        fallbackState = fallbackState.advanceFallback(selected.videoUrl, selected.audioUrl)
                    }
                    PlayerErrorRecoveryAction.RETRY_NETWORK -> {
                        source = playback.playbackConfigured(context.details, context.index, context.source.quality.takeIf { it > 0 } ?: state.value.quality,
                            playbackSettingsFor(context), blockedCodecs.toSet(),
                            codecOverride = context.source.videoCodecFamily, forceRefresh = true)
                        source = retainDesktopPremiumAudioFallback(source, context.source.audioSelection)
                        prepareResolved(source).let { source = it.first; candidates = it.second; fallbackState = it.third }; cdn = 0
                    }
                    PlayerErrorRecoveryAction.RETRY_DECODER_FALLBACK -> {
                        val failed = resolvePlaybackVideoCodec(source.videoUrl, source.cachedDashData?.video.orEmpty()) ?: normalizeCodecFamilyKey(source.videoCodecFamily)
                        if (failed == AV1_CODEC_KEY) blockedCodecs.add(AV1_CODEC_KEY)
                        val fallback = resolveNextVideoCodecFallback(failed, preferences().videoSecondCodecPreference, isHevcSupported = true,
                            isAv1Supported = resolveEffectiveAv1Support(true, blockedCodecs))?.takeIf { failed != null }
                        if (fallback != null) {
                            val fresh = playback.playbackConfigured(context.details, context.index, source.quality.takeIf { it > 0 } ?: state.value.quality,
                                playbackSettingsFor(context), blockedCodecs.toSet(),
                                codecOverride = fallback, forceRefresh = true)
                            if (normalizeCodecFamilyKey(fresh.videoCodecFamily) == fallback) {
                                prepareResolved(retainDesktopPremiumAudioFallback(fresh, context.source.audioSelection)).let {
                                    source = it.first; candidates = it.second; fallbackState = it.third
                                }; cdn = 0
                            } else software = true
                        } else software = true
                    }
                    else -> { }
                }
                if (!recoverable(context, failure)) return@launch
                val latest = player?.state?.value ?: native
                val callerJob = currentCoroutineContext()[Job]
                val accepted = publication.admit(source.toNative(latest.positionSeconds, latest.paused),
                    { callerJob?.isActive == true && recoverable(context, failure) }) {
                    player?.recoverSource(context.sourceVersion, publication.ownedSource(source.toNative(latest.positionSeconds, latest.paused),
                            { callerJob?.isCancelled != true && owns(context) }),
                        latest.positionSeconds, latest.paused, forceSoftwareDecoding = software, expectedFailureAttemptId = failure.attemptId) == true
                }
                if (accepted) {
                    current = (current ?: context).copy(source = source, candidates = candidates, cdnIndex = cdn,
                        readyObserved = false, handledFailure = null, cdnFallback = fallbackState,
                        watchdogLoadId = nextWatchdogLoadId.incrementAndGet())
                    watchdogSnapshot()?.let(watchdog::loaded)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (owns(context) && context.requestGeneration == generation.get()) mutableState.update {
                    it.copy(recovering = false, recoveryMessage = null, error = safeError(error, "播放恢复失败"))
                }
            }
        }
    }

    /** The original audio role policy runs before video/network budgets; no generic decoder guess is permitted. */
    private fun recoverPremiumAudio(context: Current, failure: PlayerFailure, native: PlayerState): Boolean {
        when (val plan = resolveDesktopPremiumAudioRecovery(context.source, failure, native.speed)) {
            DesktopPremiumAudioRecoveryPlan.NotApplicable -> return false
            is DesktopPremiumAudioRecoveryPlan.Unavailable -> {
                player?.pauseIfSourceVersion(context.sourceVersion, failure.attemptId)
                if (owns(context)) mutableState.update { it.copy(recovering = false, recoveryMessage = null,
                    error = "当前设备无法解码该 Hi-Res 音轨，且没有可回退的 AAC 音轨") }
            }
            is DesktopPremiumAudioRecoveryPlan.Ready -> {
                val nativePlayer = player ?: return true
                if (!publication.isCurrent(plan.source.toNative(0.0, true))) return true
                val accepted = publication.admit(plan.source.toNative(0.0, true),
                    { recoverable(context, failure) && context.accountEpoch == playback.sessionEpoch }) {
                    applyDesktopPremiumAudioRecovery(nativePlayer, failure, plan,
                        nativePublication = publication.ownedSource(plan.source.toNative(0.0, true), { owns(context) }).nativePublication) { recoverable(context, failure) }
                }
                if (accepted && owns(context)) {
                    val actual = nativePlayer.currentSourceSnapshot()?.takeIf { it.sourceVersion == context.sourceVersion }
                        ?: return true
                    val source = plan.source.copy(videoUrl = actual.source.videoUrl, audioUrl = actual.source.audioUrl)
                    // Keep the exact active video/CDN; candidates may change audio only on later recovery.
                    val active = PlaybackCdnCandidate(source.videoUrl, source.audioUrl, PlaybackCdnCandidateSource.ORIGINAL)
                    val candidates = (listOf(active) + authorizedPlaybackCandidates(source, health))
                        .distinctBy { it.videoUrl to it.audioUrl }
                    current = (current ?: context).copy(source = source, candidates = candidates, cdnIndex = 0,
                        readyObserved = false, handledFailure = null, cdnFallback = PlaybackCdnFallbackState.Inactive,
                        watchdogLoadId = nextWatchdogLoadId.incrementAndGet())
                    mutableState.update { it.copy(recovering = true, error = null,
                        recoveryMessage = "当前设备无法稳定解码 Hi-Res，已临时切换至 AAC") }
                    watchdogSnapshot()?.let(watchdog::loaded)
                } else if (recoverable(context, failure)) {
                    nativePlayer.pauseIfSourceVersion(context.sourceVersion, failure.attemptId)
                    mutableState.update { it.copy(recovering = false, recoveryMessage = null,
                        error = "Hi-Res 解码失败，AAC 回退也未能完成") }
                }
            }
        }
        return true
    }

    private fun prepareResolved(source: ResolvedSource): Triple<ResolvedSource, List<PlaybackCdnCandidate>, PlaybackCdnFallbackState> {
        val rewritten = (plugins?.rewritePlaybackSource(source) ?: source).copy(authorizationReceipt = source.authorizationReceipt)
        val ranked = authorizedPlaybackCandidates(rewritten, health)
        // The original enabled CDN plugin already applies the user's preference and persistent health ranking.
        val candidates = if (rewritten !== source) ranked.sortedBy {
            if (it.videoUrl == rewritten.videoUrl && it.audioUrl == rewritten.audioUrl) 0 else 1
        } else ranked
        val selected = candidates.firstOrNull()
        val resolved = rewritten.copy(videoUrl = selected?.videoUrl ?: rewritten.videoUrl, audioUrl = selected?.audioUrl ?: rewritten.audioUrl)
        val fallback = if (source.progressiveSegments.isNotEmpty()) PlaybackCdnFallbackState.Inactive else buildPlaybackCdnFallbackState(
            resolved.videoUrl, resolved.audioUrl, source.videoUrl, source.audioUrl, regionLabel = null,
            audioFallbackUrl = source.audioAlternatives.firstOrNull(), fallbackCandidates = authorizedPlaybackCandidates(source))
        return Triple(resolved, candidates, fallback)
    }

    private fun watchdogSnapshot(): DesktopWatchdogSnapshot? {
        val context = current?.takeIf(::owns) ?: return null
        val native = player?.state?.value ?: return null
        return DesktopWatchdogSnapshot(DesktopWatchdogIdentity(context.sourceVersion, context.requestGeneration, context.watchdogLoadId),
            native, context.cdnIndex, context.candidates.size, context.cdnFallback, context.source.audioUrl != null,
            enabled = !suspended && !state.value.opening)
    }

    private fun switchForWatchdog(snapshot: DesktopWatchdogSnapshot, action: DesktopWatchdogAction) {
        val context = current?.takeIf(::owns) ?: return
        if (snapshot.identity != DesktopWatchdogIdentity(context.sourceVersion, context.requestGeneration, context.watchdogLoadId)) return
        val nativePlayer = player ?: return
        val native = nativePlayer.state.value
        if (suspended || native.paused || native.ended || native.failure != null || native.error != null) return
        val target = when (action) {
            is DesktopWatchdogAction.FirstFrameFallback -> PlaybackCdnCandidate(action.nextState.selectedVideoUrl,
                action.nextState.selectedAudioUrl, PlaybackCdnCandidateSource.ORIGINAL)
            is DesktopWatchdogAction.StallSwitch -> context.candidates.getOrNull(action.index) ?: return
        }
        recordHealth(context, when (action) {
            is DesktopWatchdogAction.FirstFrameFallback -> if (action.audioMissing) CdnHealthEvent.AUDIO_TRACK_MISSING else CdnHealthEvent.FIRST_FRAME_TIMEOUT
            is DesktopWatchdogAction.StallSwitch -> CdnHealthEvent.BUFFERING
        })
        val source = context.source.copy(videoUrl = target.videoUrl, audioUrl = target.audioUrl)
        if (!publication.isCurrent(source.toNative(0.0, true))) return
        if (publication.admit(source.toNative(native.positionSeconds, native.paused), { owns(context) }) {
            nativePlayer.recoverSource(context.sourceVersion, publication.ownedSource(source.toNative(native.positionSeconds, native.paused), { owns(context) }),
                native.positionSeconds, native.paused)
        }) {
            val candidates = (context.candidates + target).distinctBy { it.videoUrl to it.audioUrl }
            val index = candidates.indexOfFirst { it.videoUrl == target.videoUrl && it.audioUrl == target.audioUrl }
            val fallback = when (action) {
                is DesktopWatchdogAction.FirstFrameFallback -> action.nextState
                is DesktopWatchdogAction.StallSwitch -> context.cdnFallback.advanceFallback(target.videoUrl, target.audioUrl)
            }
            current = context.copy(source = source, candidates = candidates, cdnIndex = index, cdnFallback = fallback,
                readyObserved = false, handledFailure = null, watchdogLoadId = nextWatchdogLoadId.incrementAndGet())
            mutableState.update { it.copy(recovering = true, error = null, recoveryMessage = "正在切换备用线路") }
            watchdogSnapshot()?.let(watchdog::loaded)
        }
    }

    private fun pendingSkip(context: Current, token: Long, seekId: Long, segmentId: String, startMs: Long,
        endMs: Long, categoryName: String, manual: Boolean): PendingSponsorSkip {
        val info = context.details
        val snapshot = SponsorBlockVideoSnapshot(info.title, info.bvid, info.pages[context.index].cid, info.cover,
            info.author, info.raw?.owner?.face.orEmpty(), info.authorMid)
        return PendingSponsorSkip(seekId, context.sourceVersion, context.requestGeneration, token, snapshot,
            segmentId, startMs, endMs, categoryName, manual, System.nanoTime())
    }
    private suspend fun completeSponsorSkip(context: Current, native: PlayerState) {
        val pending = pendingSponsorSkip ?: return
        if (pending.sourceVersion != context.sourceVersion || pending.requestGeneration != context.requestGeneration ||
            pending.pluginGeneration != context.pluginGeneration || System.nanoTime() - pending.submittedNanos > 15_000_000_000L) {
            pendingSponsorSkip = null; return
        }
        if (native.seekCompletedId != pending.seekId) return
        pendingSponsorSkip = null
        if (pending.manual) plugins?.markSponsorSkipped(pending.segmentId)
        try {
            plugins?.onSponsorSkipCompleted(pending.pluginGeneration, pending.snapshot, pending.segmentId,
                pending.startMs, pending.endMs, pending.categoryName, pending.manual)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutableState.update { it.copy(pluginHint = safeError(error, "跳过记录保存失败")) } }
    }

    private fun restorePluginMute(positionMs: Long) {
        val until = pluginMuteUntilMs ?: return
        if (positionMs >= until || positionMs < (pluginMuteFromMs ?: 0L)) {
            player?.setMuted(preferences().muted); pluginMuteUntilMs = null; pluginMuteFromMs = null
        }
    }

    internal fun currentCastSource(expectedSourceVersion: Long): ResolvedSource? = current?.takeIf {
        owns(it) && it.sourceVersion == expectedSourceVersion && it.accountEpoch == playback.sessionEpoch &&
            publication.isCurrent(it.source.toNative(0.0, true))
    }?.source

    private fun recoverable(context: Current, failure: PlayerFailure): Boolean = owns(context) && !suspended &&
        failure.sourceVersion == context.sourceVersion && player?.state?.value?.failure?.attemptId == failure.attemptId
    private fun owns(context: Current): Boolean = !closed.get() && context.requestGeneration == generation.get() &&
        ownedSourceVersion == context.sourceVersion && player?.currentSourceVersion == context.sourceVersion &&
        context.accountEpoch == playback.sessionEpoch
    private fun valid(expected: Long, baseline: Long?, accountEpoch: Long): Boolean = !closed.get() && expected == generation.get() &&
        (player == null || baseline == player.currentSourceVersion) && playback.sessionEpoch == accountEpoch
    private fun recordHealth(context: Current, event: CdnHealthEvent) {
        val host = hostFromCdnUrl(context.source.videoUrl).takeIf(String::isNotBlank) ?: return
        plugins?.recordPlaybackCdnEvent(context.source.videoUrl, event)
        health[host] = recordCdnHealthEvent(health[host] ?: CdnCandidateHealth(host), event, System.currentTimeMillis())
    }
    private fun beginPlugins(context: Current) {
        val runtime = plugins ?: return
        pluginLoad?.cancel()
        pluginLoad = controllerScope.launch {
            val token = runtime.onVideoLoad(context.details.bvid, context.details.pages[context.index].cid)
            if (owns(context)) {
                current = current?.copy(pluginGeneration = token)
                mutableState.update { it.copy(playerPluginGeneration = token) }
            }
        }
    }
    private suspend fun runPositionPlugins(context: Current, native: PlayerState) {
        val positionMs = (native.positionSeconds * 1_000).toLong()
        restorePluginMute(positionMs)
        if (native.paused) return
        val token = context.pluginGeneration ?: return
        val actions = plugins?.onPositionUpdate(token, positionMs).orEmpty()
        if (!owns(context)) return
        var button: SkipAction.ShowButton? = null
        for (action in actions) when (action) {
            is SkipAction.SkipTo -> {
                restorePluginMute(action.positionMs)
                val seekId = player?.seekToTracked(action.positionMs / 1_000.0)
                val segment = plugins?.sponsorSegments()?.firstOrNull { it.UUID == action.segmentId }
                if (seekId != null && segment != null) pendingSponsorSkip = pendingSkip(context, token, seekId, segment.UUID,
                    action.startMs ?: segment.startTimeMs, action.positionMs, action.categoryName ?: segment.categoryName, manual = false)
                mutableState.update { it.copy(pluginHint = action.reason) }
            }
            is SkipAction.ShowButton -> button = action
            is SkipAction.Mute -> { pluginMuteFromMs = positionMs; pluginMuteUntilMs = action.untilMs; player?.setMuted(true); mutableState.update { it.copy(pluginHint = action.reason) } }
            SkipAction.None -> { }
        }
        mutableState.update { it.copy(manualSkip = button) }
    }
    /** A history failure must not prevent a source from pausing, closing or releasing. */
    fun checkpoint(): Boolean {
        val context = current?.takeIf(::owns) ?: return true
        val native = player?.state?.value ?: return true
        if (native.loading || native.error != null || native.durationSeconds <= 0) return true
        return try {
            library.checkpoint(context.details.bvid, context.details.pages[context.index].cid, context.index, native.positionSeconds)
            true // Incognito deliberately suppresses this write and remains a successful no-op.
        } catch (_: Exception) {
            // JSON contents, account paths and exception URLs must not enter product errors.
            mutableState.update { it.copy(error = "播放记录保存失败，请检查本地隐私和存储设置") }
            false
        }
    }
    private fun recordCreatorWatch(context: Current) {
        val delta = heartbeat.takeCreatorWatchDelta()
        if (delta <= 0 || context.accountEpoch != playback.sessionEpoch || context.details.authorMid <= 0) return
        try { plugins?.recordCreatorWatch(context.details.authorMid, context.details.author, delta) }
        catch (_: Exception) { mutableState.update { it.copy(pluginHint = "观看偏好保存失败") } }
    }
    private fun finishHeartbeat(context: Current, submit: Boolean = true): DesktopHeartbeatReport? {
        val final = heartbeat.finalReport()
        recordCreatorWatch(context)
        heartbeat.reset()
        if (submit) heartbeatReporter.submit(final)
        return final
    }
    private fun invalidate(stopNative: Boolean, flushReport: Boolean = true, retainQueueOwner: Boolean = false): DesktopHeartbeatReport? {
        if (!retainQueueOwner) queueOwnership = null
        watchdog.reset()
        val finalReport = current?.let { finishHeartbeat(it, submit = flushReport) }
        generation.incrementAndGet(); request?.cancel(); recovery?.cancel(); pluginLoad?.cancel(); danmaku?.retireWebMaskSource()
        current?.pluginGeneration?.let { plugins?.onVideoEnd(it) }
        val version = ownedSourceVersion
        if (stopNative && version != null && player?.currentSourceVersion == version) {
            danmaku?.setComments(emptyList()); player?.stopIfSourceVersion(version)
        }
        current = null; ownedSourceVersion = null; pluginMuteUntilMs = null; pluginMuteFromMs = null; pendingSponsorSkip = null; suspended = false; handledEnd = false
        return finalReport
    }
    fun stop() {
        if (closed.get()) return
        checkpoint(); invalidate(stopNative = true)
        mutableState.update { DesktopPlaybackState(quality = it.quality) }
    }
    fun pause() {
        if (closed.get()) return
        watchdog.cancel()
        checkpoint(); request?.cancel(); recovery?.cancel(); pluginLoad?.cancel()
        val expected = generation.incrementAndGet()
        val context = current
        if (context != null && ownedSourceVersion == context.sourceVersion && player?.currentSourceVersion == context.sourceVersion) {
            heartbeat.observe(player.state.value.copy(paused = true), inBackground, suspended = true)
            heartbeatReporter.submit(heartbeat.finalReport())
            context.pluginGeneration?.let { plugins?.onVideoEnd(it) }
            current = context.copy(requestGeneration = expected, pluginGeneration = null, handledFailure = null)
            recordQueueRequest(expected, player.currentSourceVersion)
            suspended = true; player?.setPaused(true)
        } else {
            context?.let {
                finishHeartbeat(it)
                it.pluginGeneration?.let { token -> plugins?.onVideoEnd(token) }
            }
            current = null; ownedSourceVersion = null; pendingSponsorSkip = null; queueOwnership = null
            pluginMuteUntilMs = null; pluginMuteFromMs = null
        }
        mutableState.update { it.copy(opening = false, recovering = false, recoveryMessage = null, manualSkip = null,
            pluginHint = null, playerPluginGeneration = null) }
    }
    /** A visible PiP remains foreground; the host supplies minimization/background state without stopping native playback. */
    fun setInBackground(value: Boolean) {
        if (closed.get() || inBackground == value) return
        inBackground = value
        val context = current?.takeIf(::owns) ?: return
        val native = player?.state?.value ?: return
        heartbeat.observe(native, value, suspended)
        if (context.accountEpoch == playback.sessionEpoch) heartbeatReporter.submit(heartbeat.initialReport())
    }
    fun dismissError() { mutableState.update { it.copy(error = null) } }
    private fun failRequest(expected: Long, failure: Exception, fallback: String) {
        if (!closed.get() && expected == generation.get()) mutableState.update { it.copy(opening = false, error = safeError(failure, fallback)) }
    }
    private fun safeError(error: Exception, fallback: String): String = PlayerDiagnostics().apply { reset(current?.source?.toNative(0.0, true)) }
        .sanitize(error.message ?: fallback)
    override fun close() {
        if (closed.get()) return
        checkpoint()
        val final = invalidate(stopNative = true, flushReport = false)
        automaticSubtitles?.close()
        closed.set(true); controllerScope.cancel()
        heartbeatReporter.close(final)
        mutableState.update { DesktopPlaybackState(quality = it.quality) }
    }
}

private fun ResolvedSource.toNative(position: Double, paused: Boolean) = PlaybackSource(videoUrl, audioUrl,
    referer = referer, cookieHeader = cookieHeader, title = title, startPositionSeconds = position,
    startPaused = paused, progressiveSegments = progressiveSegments, authorizationReceipt = authorizationReceipt)
private fun VideoDetails.toLibraryCard() = VideoCard(bvid, title, cover, author, playCount,
    pages.firstOrNull()?.duration?.toInt() ?: 0, authorMid = authorMid)

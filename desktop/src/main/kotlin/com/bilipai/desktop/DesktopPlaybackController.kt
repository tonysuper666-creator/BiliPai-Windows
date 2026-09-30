package com.bilipai.desktop

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
) : AutoCloseable {
    private val communityReports by lazy { community ?: DesktopCommunityRepository(repository) }
    private val playback = dataSource ?: object : DesktopPlaybackDataSource {
        override val sessionEpoch: Long get() = repository.sessionEpoch
        override suspend fun videoDetails(bvid: String) = repository.videoDetails(bvid)
        override suspend fun related(bvid: String) = repository.related(bvid)
        override suspend fun playback(details: VideoDetails, index: Int, quality: Int, codecOverride: String?, forceRefresh: Boolean) =
            repository.playback(details, index, quality, codecOverride, forceRefresh)
        override suspend fun reportHeartbeat(report: DesktopHeartbeatReport) = communityReports.reportPlayHeartbeat(
            bvid = report.identity.bvid, cid = report.identity.cid, aid = report.identity.aid,
            playedTimeSec = report.snapshot.playedTimeSec, realPlayedTimeSec = report.snapshot.realPlayedTimeSec,
            startTsSec = report.startTsSec, expectedSessionEpoch = report.identity.sessionEpoch)
    }
    private val controllerScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(DesktopPlaybackState())
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
        val handledFailure: Long? = null, val recommendationConsumed: Boolean = false, val accountEpoch: Long = 0L)
    private var current: Current? = null
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

    fun openQueue(cards: List<VideoCard>, selectedIndex: Int = 0) {
        if (closed.get()) return
        require(cards.size <= 10_000 && selectedIndex in cards.indices) { "播放队列为空或选中项无效" }
        val selected = cards[selectedIndex]
        require(selected.bvid.isNotBlank()) { "视频编号为空" }
        val normalized = cards.filter { it.bvid.isNotBlank() }.distinctBy { Triple(it.bvid, it.preferredCid, it.pageIndex) }
        val index = normalized.indexOfFirst { it.bvid == selected.bvid && it.preferredCid == selected.preferredCid && it.pageIndex == selected.pageIndex }
        shuffle = ShuffleProgress(); partShuffle = ShuffleProgress()
        mutableState.update { it.copy(queue = normalized, queueIndex = index) }
        openQueueItem(index, useResume = true)
    }

    private fun openQueueItem(index: Int, useResume: Boolean) {
        val card = state.value.queue.getOrNull(index) ?: return
        checkpoint()
        val cached = state.value.details?.takeIf { it.bvid == card.bvid }
        val related = state.value.related.takeIf { cached != null }.orEmpty()
        invalidate(stopNative = true)
        val expected = generation.get()
        val baseline = player?.currentSourceVersion
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
        checkpoint(); invalidate(stopNative = true)
        val expected = generation.get(); val baseline = player?.currentSourceVersion
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
        launch { if (expected == generation.get()) { danmaku?.applySettings(preferences().danmaku); danmaku?.load(info.pages[index].cid, info.aid, info.pages[index].duration.toDouble()) } }
        val source = playback.playback(info, index, state.value.quality)
        if (!valid(expected, baseline, accountEpoch)) return@coroutineScope
        val native = player ?: throw IllegalStateException(playerError ?: "播放器未能初始化")
        native.applyPreferences(preferences().let { it.copy(speed = it.preferredSpeed) })
        val (resolved, candidates) = prepareResolved(source)
        val version = native.loadVersioned(resolved.toNative(position, paused))
        ownedSourceVersion = version; suspended = false; handledEnd = false; budget = DesktopPlaybackRecoveryBudget()
        current = Current(info, index, resolved, candidates, 0, version, expected, accountEpoch = accountEpoch)
        heartbeat.begin(DesktopHeartbeatIdentity(info.bvid, info.pages[index].cid, info.aid, accountEpoch), position)
        mutableState.update { it.copy(opening = false, effectiveQuality = resolved.quality, availableQualities = resolved.availableQualities) }
        beginPlugins(current!!)
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

    private fun reloadSource(quality: Int, forceRefresh: Boolean) {
        val context = current?.takeIf(::owns) ?: run { playPart(state.value.currentPart); return }
        val native = player?.state?.value ?: return
        checkpoint(); request?.cancel(); recovery?.cancel()
        val expected = generation.incrementAndGet()
        current = context.copy(requestGeneration = expected)
        if (context.pluginGeneration == null) beginPlugins(current!!)
        mutableState.update { it.copy(opening = true, error = null, recovering = false, recoveryMessage = null) }
        request = controllerScope.launch {
            try {
                val (source, candidates) = prepareResolved(playback.playback(context.details, context.index, quality, context.source.videoCodecFamily, forceRefresh))
                val active = current ?: return@launch
                if (!owns(active) || expected != generation.get()) return@launch
                val latest = player?.state?.value ?: native
                if (player?.recoverSource(active.sourceVersion, source.toNative(latest.positionSeconds, latest.paused),
                        latest.positionSeconds, latest.paused) == true) {
                    budget = DesktopPlaybackRecoveryBudget()
                    suspended = false
                    current = active.copy(source = source, candidates = candidates, cdnIndex = 0, readyObserved = false, handledFailure = null)
                    mutableState.update { it.copy(opening = false, effectiveQuality = source.quality, availableQualities = source.availableQualities) }
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
        mutableState.update { it.copy(manualSkip = null, playerPluginGeneration = null) }
        if (pluginMuteUntilMs != null) {
            player?.setMuted(preferences().muted); pluginMuteUntilMs = null; pluginMuteFromMs = null
        }
        context.pluginGeneration?.let { plugins?.onVideoEnd(it) }
        if (preferences().playbackMode == PlaybackMode.STOP_AFTER_CURRENT) return
        if (preferences().playbackMode == PlaybackMode.REPEAT_ONE) {
            handledEnd = false; player?.replay(); current = context.copy(pluginGeneration = null); beginPlugins(current!!); return
        }
        target(false, true)?.let(::playTarget)
    }

    fun seek(cid: Long, seconds: Double) {
        val info = state.value.details ?: return
        val index = info.pages.indexOfFirst { it.cid == cid }
        if (index < 0) return
        if (index == state.value.currentPart) seekTo(seconds) else playPart(index, seconds)
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
        recovery?.cancel(); recordHealth(context, CdnHealthEvent.PLAYER_ERROR)
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
                var software = false
                when(action) {
                    PlayerErrorRecoveryAction.SWITCH_CDN -> {
                        cdn++; val selected = candidates[cdn]
                        source = source.copy(videoUrl = selected.videoUrl, audioUrl = selected.audioUrl)
                    }
                    PlayerErrorRecoveryAction.RETRY_NETWORK -> {
                        source = playback.playback(context.details, context.index, context.source.quality.takeIf { it > 0 } ?: state.value.quality,
                            codecOverride = context.source.videoCodecFamily, forceRefresh = true)
                        prepareResolved(source).let { source = it.first; candidates = it.second }; cdn = 0
                    }
                    PlayerErrorRecoveryAction.RETRY_DECODER_FALLBACK -> {
                        val failed = resolvePlaybackVideoCodec(source.videoUrl, source.cachedDashData?.video.orEmpty()) ?: normalizeCodecFamilyKey(source.videoCodecFamily)
                        if (failed == AV1_CODEC_KEY) blockedCodecs.add(AV1_CODEC_KEY)
                        val fallback = resolveNextVideoCodecFallback(failed, AVC_CODEC_KEY, isHevcSupported = true,
                            isAv1Supported = resolveEffectiveAv1Support(true, blockedCodecs))?.takeIf { failed != null }
                        if (fallback != null) {
                            val fresh = playback.playback(context.details, context.index, source.quality.takeIf { it > 0 } ?: state.value.quality,
                                codecOverride = fallback, forceRefresh = true)
                            if (normalizeCodecFamilyKey(fresh.videoCodecFamily) == fallback) {
                                prepareResolved(fresh).let { source = it.first; candidates = it.second }; cdn = 0
                            } else software = true
                        } else software = true
                    }
                    else -> { }
                }
                if (!recoverable(context, failure)) return@launch
                val latest = player?.state?.value ?: native
                val accepted = player?.recoverSource(context.sourceVersion, source.toNative(latest.positionSeconds, latest.paused),
                    latest.positionSeconds, latest.paused, forceSoftwareDecoding = software, expectedFailureAttemptId = failure.attemptId) == true
                if (accepted) current = (current ?: context).copy(source = source, candidates = candidates, cdnIndex = cdn,
                    readyObserved = false, handledFailure = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (owns(context) && context.requestGeneration == generation.get()) mutableState.update {
                    it.copy(recovering = false, recoveryMessage = null, error = safeError(error, "播放恢复失败"))
                }
            }
        }
    }

    private fun prepareResolved(source: ResolvedSource): Pair<ResolvedSource, List<PlaybackCdnCandidate>> {
        val rewritten = plugins?.rewritePlaybackSource(source) ?: source
        val ranked = authorizedPlaybackCandidates(rewritten, health)
        // The original enabled CDN plugin already applies the user's preference and persistent health ranking.
        val candidates = if (rewritten !== source) ranked.sortedBy {
            if (it.videoUrl == rewritten.videoUrl && it.audioUrl == rewritten.audioUrl) 0 else 1
        } else ranked
        val selected = candidates.firstOrNull()
        return rewritten.copy(videoUrl = selected?.videoUrl ?: rewritten.videoUrl, audioUrl = selected?.audioUrl ?: rewritten.audioUrl) to candidates
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

    private fun recoverable(context: Current, failure: PlayerFailure): Boolean = owns(context) && !suspended &&
        player?.state?.value?.failure?.attemptId == failure.attemptId
    private fun owns(context: Current): Boolean = !closed.get() && context.requestGeneration == generation.get() &&
        ownedSourceVersion == context.sourceVersion && player?.currentSourceVersion == context.sourceVersion
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
    fun checkpoint() {
        val context = current?.takeIf(::owns) ?: return
        val native = player?.state?.value ?: return
        if (native.loading || native.error != null || native.durationSeconds <= 0) return
        library.checkpoint(context.details.bvid, context.details.pages[context.index].cid, context.index, native.positionSeconds)
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
    private fun invalidate(stopNative: Boolean, flushReport: Boolean = true): DesktopHeartbeatReport? {
        val finalReport = current?.let { finishHeartbeat(it, submit = flushReport) }
        generation.incrementAndGet(); request?.cancel(); recovery?.cancel(); pluginLoad?.cancel()
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
        checkpoint(); request?.cancel(); recovery?.cancel(); pluginLoad?.cancel()
        val expected = generation.incrementAndGet()
        val context = current
        if (context != null && ownedSourceVersion == context.sourceVersion && player?.currentSourceVersion == context.sourceVersion) {
            heartbeat.observe(player.state.value.copy(paused = true), inBackground, suspended = true)
            heartbeatReporter.submit(heartbeat.finalReport())
            context.pluginGeneration?.let { plugins?.onVideoEnd(it) }
            current = context.copy(requestGeneration = expected, pluginGeneration = null, handledFailure = null)
            suspended = true; player?.setPaused(true)
        } else {
            context?.let {
                finishHeartbeat(it)
                it.pluginGeneration?.let { token -> plugins?.onVideoEnd(token) }
            }
            current = null; ownedSourceVersion = null; pendingSponsorSkip = null
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
        closed.set(true); controllerScope.cancel()
        heartbeatReporter.close(final)
        mutableState.update { DesktopPlaybackState(quality = it.quality) }
    }
}

private fun ResolvedSource.toNative(position: Double, paused: Boolean) = PlaybackSource(videoUrl, audioUrl,
    referer = referer, cookieHeader = cookieHeader, title = title, startPositionSeconds = position,
    startPaused = paused, progressiveSegments = progressiveSegments)
private fun VideoDetails.toLibraryCard() = VideoCard(bvid, title, cover, author, playCount,
    pages.firstOrNull()?.duration?.toInt() ?: 0, authorMid = authorMid)

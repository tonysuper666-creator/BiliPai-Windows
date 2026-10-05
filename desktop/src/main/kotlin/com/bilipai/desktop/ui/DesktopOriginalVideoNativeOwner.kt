package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.player.ShuffleProgress
import com.bilipai.desktop.data.VideoCard
import com.bilipai.desktop.data.VideoDetails
import com.bilipai.desktop.data.PlaybackSource as ResolvedSource
import com.bilipai.desktop.player.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A transient copy of the OLD Controller's real queue, not a playlist authority.
 * Root must switch existing Favorite/Listen/dashboard clients to the new owner at
 * the same handoff. A different native source/account cannot reuse this token.
 */
internal class DesktopOrdinaryPlaybackQueueHandoff(
    cards: List<VideoCard>,
    val selectedIndex: Int,
    val owner: Any?,
    shuffle: ShuffleProgress,
    partShuffle: ShuffleProgress,
) {
    val cards = cards.toList()
    val shuffle = shuffle.copy(history = shuffle.history.toList(), cyclePlayed = shuffle.cyclePlayed.toSet())
    val partShuffle = partShuffle.copy(history = partShuffle.history.toList(), cyclePlayed = partShuffle.cyclePlayed.toSet())
    init { require(selectedIndex in this.cards.indices) }
    override fun toString() = "DesktopOrdinaryPlaybackQueueHandoff(size=${cards.size}, selectedIndex=$selectedIndex)"
}

/** The old controller uses native mute, whereas original VM Sponsor actions use
 * volume. Keep its real interval and restore value explicitly during adoption. */
internal data class DesktopOrdinaryPluginMuteHandoff(
    val fromMs: Long,
    val untilMs: Long,
    val restoreMuted: Boolean,
) {
    init { require(fromMs >= 0L && untilMs >= 0L) }
}

/** Snapshot returned only AFTER the old request/recovery/native observers have
 * drained and the same-source native actor barrier completed OUTSIDE Store/UI
 * locks. The original raw ViewInfo and selected DASH/audio metadata remain in the
 * existing details/source models. No URLs/headers/cookies enter diagnostics.
 *
 * The shared DesktopSubtitleAssets stays Root-owned. Old automatic-subtitle work
 * is canceled/joined, but no current MPV external track/files are cleared here.
 */
internal class DesktopOrdinaryPlaybackHandoff(
    val player: MpvPlayer,
    val details: VideoDetails,
    val selectedPart: Int,
    val resolvedSource: ResolvedSource,
    val nativeSource: OwnedPlaybackSourceSnapshot,
    val readback: PlayerState,
    val queue: DesktopOrdinaryPlaybackQueueHandoff,
    val playerPluginGeneration: Long?,
    val suspended: Boolean,
    val pluginMute: DesktopOrdinaryPluginMuteHandoff?,
) {
    val request = run {
        require(selectedPart in details.pages.indices)
        PlaybackRequest.create(details.bvid, details.aid, details.pages[selectedPart].cid)
    }
    val accountEpoch = checkNotNull(nativeSource.source.authorizationReceipt).accountEpoch
    init {
        require(selectedPart in details.pages.indices)
        requireNotNull(details.raw) { "Original raw video detail is required for handoff" }
        require(resolvedSource.authorizationReceipt == nativeSource.source.authorizationReceipt)
        require(nativeSource.sourceVersion > 0L)
        require(!readback.loading && !readback.ended && readback.error == null)
        require(readback.videoCodec != null || readback.audioCodec != null)
    }
    override fun toString() = "DesktopOrdinaryPlaybackHandoff(sourceVersion=${nativeSource.sourceVersion}, selectedPart=$selectedPart)"
}

/** Actual publication metadata for this owner. The source is an immutable MPV
 * snapshot and contains credentials; this object deliberately has safe logging.
 */
internal class DesktopOriginalVideoAcceptedPublication(
    val request: PlaybackRequest,
    val nativeSource: OwnedPlaybackSourceSnapshot,
) {
    val sourceVersion get() = nativeSource.sourceVersion
    val accountEpoch get() = checkNotNull(nativeSource.source.authorizationReceipt).accountEpoch
    override fun toString() = "DesktopOriginalVideoAcceptedPublication(sourceVersion=$sourceVersion)"
}

/** Initial Load has two lifetimes. Before MPV's actual successful loadfile ACK,
 * the same request Job and generation still own queued native publication. After
 * ACK only the accepted entry/source lease owns replay/Canvas reconstruction.
 * MPV calls the default hook only after a real loadfile + playlist entry readback;
 * an admitted stale/no-op command must NOT consume this first-request guard.
 * Hook is one atomic flag write: no callback/IO/lock or business projection.
 */
internal class DesktopOriginalVideoInitialPublication(
    private val publication: DesktopPlaybackPublication,
    private val source: PlaybackSource,
    private val requestJob: Job,
    private val isRequestCurrent: () -> Boolean,
    private val ownsAccepted: () -> Boolean,
    private val withEntryAdmission: ((() -> Unit) -> Boolean),
) : DesktopNativePlaybackPublication {
    private val consumed = AtomicBoolean(false)
    private fun current(): Boolean = ownsAccepted() &&
        (consumed.get() || (!requestJob.isCancelled && isRequestCurrent()))

    override fun admit(command: () -> Unit): Boolean = try {
        publication.admit(source, ::current) {
            if (!withEntryAdmission {
                if (!current()) throw CancellationException("Initial ordinary publication retired")
                command()
            }) throw CancellationException("Initial ordinary entry retired")
        }
        true
    } catch (_: CancellationException) { false }

    internal fun isTransportCurrent(): Boolean = current()
    override fun onLoadCommandAccepted() { consumed.set(true) }
}

/** One retained ordinary-video entry's view of the SAME MPV and existing atomic
 * Repository publication. This does not instantiate a native actor/HTTP client,
 * and the AtomicReference is only the entry's accepted-source lease. MPV's real
 * sourceVersion and the existing authorization receipt remain authoritative.
 *
 * withEntryAdmission is entry-only; publication supplies Store -> entry order.
 * onAccepted is a short in-memory projection, not native cleanup, disk or join.
 * MPV's actual adoptPublication is used after its native drain barrier.
 */
internal class DesktopOriginalVideoNativeOwner(
    val player: MpvPlayer,
    private val publication: DesktopPlaybackPublication,
    private val currentEpoch: () -> Long,
    private val currentUserMuted: () -> Boolean,
    private val isEntryCurrent: () -> Boolean,
    private val withEntryAdmission: ((() -> Unit) -> Boolean),
    private val onAccepted: (DesktopOriginalVideoAcceptedPublication) -> Unit,
    private val mediaByteAdmission: (DesktopOriginalVideoAcceptedPublication, () -> Boolean) ->
        com.bilipai.desktop.player.cache.DesktopMediaByteAdmission,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val accepted = AtomicReference<DesktopOriginalVideoAcceptedPublication?>()
    private data class InheritedMute(val lease: DesktopOriginalVideoAcceptedPublication,
        val interval: DesktopOrdinaryPluginMuteHandoff)
    private val inheritedMute = AtomicReference<InheritedMute?>()

    private fun entryCurrent() = !closed.get() && isEntryCurrent()
    private fun assertEntry() {
        if (!entryCurrent()) throw CancellationException("Original video native owner retired")
    }
    private fun owns(value: DesktopOriginalVideoAcceptedPublication): Boolean =
        entryCurrent() && value.accountEpoch == currentEpoch() &&
            accepted.get() === value && player.ownsSourceSnapshot(value.nativeSource) &&
            publication.isCurrent(value.nativeSource.source)

    private fun retainedSource(source: PlaybackSource, stillOwned: () -> Boolean): PlaybackSource =
        source.copy(nativePublication = DesktopNativePlaybackPublication { command ->
            try {
                publication.admit(source, stillOwned) {
                    if (!withEntryAdmission {
                        assertEntry()
                        if (!stillOwned()) throw CancellationException("Retained ordinary publication retired")
                        command()
                    }) throw CancellationException("Retained ordinary entry retired")
                }
                true
            } catch (_: CancellationException) { false }
        })

    private fun bindAcceptedTransport(value: DesktopOriginalVideoAcceptedPublication,
        previous: DesktopOriginalVideoAcceptedPublication?, stillOwned: () -> Boolean) {
        val transport = value.nativeSource.source.nativeTransport ?: return
        try {
            val admission = mediaByteAdmission(value, stillOwned)
            val newPublication = checkNotNull(value.nativeSource.source.nativePublication)
            if (previous?.nativeSource?.source?.nativeTransport === transport) {
                if (!transport.adopt(value.sourceVersion, checkNotNull(previous.nativeSource.source.nativePublication),
                        newPublication, admission)) throw CancellationException("Native byte handoff retired")
            } else transport.attach(value.sourceVersion, newPublication, admission)
        } catch (failure: Throwable) {
            accepted.compareAndSet(value, null)
            transport.retire(value.sourceVersion)
            player.stopIfSourceVersion(value.sourceVersion)
            throw failure
        }
    }

    fun current(): DesktopOriginalVideoAcceptedPublication? = accepted.get()?.takeIf(::owns)

    fun isCurrent(expected: DesktopOriginalVideoAcceptedPublication): Boolean = owns(expected)

    /** A UI action captures this exact accepted object before waiting for its
     * existing owner coroutine. Recheck it atomically when queuing the seek or
     * making another short in-memory change. No waiting or IO runs in this gate. */
    fun admitPlaybackDispatch(expected: DesktopOriginalVideoAcceptedPublication, action: () -> Unit): Boolean {
        if (!owns(expected)) return false
        var admitted = false
        try {
            publication.admit(expected.nativeSource.source, { owns(expected) }) {
                if (!withEntryAdmission {
                    if (!owns(expected)) throw CancellationException("Original playback dispatch retired")
                    if (!player.admitSourceSnapshot(expected.nativeSource) {
                        if (!owns(expected)) throw CancellationException("Original playback dispatch source retired")
                        action()
                        admitted = true
                    }) throw CancellationException("Original playback dispatch source changed")
                }) throw CancellationException("Original playback dispatch entry retired")
            }
        } catch (_: CancellationException) { return false }
        return admitted
    }

    /** Queue submission is not completion. The existing position observer may
     * use this exact ticket after MPV playback-restart, with one atomic check of
     * accepted identity, account/entry and the actual source/readback. */
    fun completedSeekPositionMs(expected: DesktopOriginalVideoAcceptedPublication,
        submission: DesktopOriginalNativeSeekSubmission): Long? {
        if (submission.sourceVersion != expected.sourceVersion || submission.operationId <= 0L) return null
        var positionMs: Long? = null
        if (!admitPlaybackDispatch(expected) {
            val native = player.state.value
            val position = native.seekCompletedPositionSeconds
            if (!native.loading && native.error == null && native.failure == null &&
                native.seekCompletedId == submission.operationId && position != null && position.isFinite() && position >= 0.0) {
                positionMs = (position * 1_000.0).toLong()
            }
        }) return null
        return positionMs
    }

    /** Initial request acceptance. source MUST already carry that operation's
     * immutable authorization. A request generation guard is REQUIRED separately
     * from the long-lived entry guard. The actual request Job is required and only
     * isCancelled is read, so successful completion can finish the queued Load.
     * Actual native ACK consumes both transient checks; baseline blocks takeover.
     */
    fun publish(request: PlaybackRequest, source: PlaybackSource,
        expectedBaselineVersion: Long, requestJob: Job, isRequestCurrent: () -> Boolean): DesktopOriginalVideoAcceptedPublication {
        assertEntry()
        val receipt = checkNotNull(source.authorizationReceipt) { "Original ordinary playback receipt is required" }
        if (receipt.accountEpoch != currentEpoch()) throw CancellationException("Original playback account retired")
        var result: DesktopOriginalVideoAcceptedPublication? = null
        publication.admit(source, { entryCurrent() && !requestJob.isCancelled && isRequestCurrent() }) {
            if (!withEntryAdmission {
                assertEntry()
                if (requestJob.isCancelled || !isRequestCurrent() || player.currentSourceVersion != expectedBaselineVersion)
                    throw CancellationException("Original playback request/native baseline retired")
                lateinit var next: DesktopOriginalVideoAcceptedPublication
                val initial = DesktopOriginalVideoInitialPublication(publication, source, requestJob,
                    isRequestCurrent, { owns(next) }, withEntryAdmission)
                val retained = source.copy(nativePublication = initial)
                val version = player.loadVersionedWithMuted(retained, currentUserMuted())
                next = DesktopOriginalVideoAcceptedPublication(request,
                    checkNotNull(player.currentSourceSnapshot()).also { check(it.sourceVersion == version) })
                accepted.set(next)
                bindAcceptedTransport(next, null) { owns(next) && initial.isTransportCurrent() }
                inheritedMute.set(null)
                result = next
                onAccepted(next)
            }) throw CancellationException("Original playback entry retired")
        }
        return checkNotNull(result)
    }

    /** No seek/load/pause/subtitle command. Root drains old Jobs and the real MPV
     * command barrier FIRST, outside admission, then invokes this exact transfer.
     * Old publication identity is checked by the required native ABI as well.
     */
    fun adopt(handoff: DesktopOrdinaryPlaybackHandoff): DesktopOriginalVideoAcceptedPublication? {
        assertEntry()
        require(handoff.player === player)
        if (handoff.accountEpoch != currentEpoch()) return null
        val before = handoff.nativeSource
        var result: DesktopOriginalVideoAcceptedPublication? = null
        try {
            publication.admit(before.source, ::entryCurrent) {
                withEntryAdmission {
                    assertEntry()
                    lateinit var next: DesktopOriginalVideoAcceptedPublication
                    val replacement = retainedSource(before.source) { owns(next) }
                    if (!player.adoptPublication(before.sourceVersion, before.source, replacement)) return@withEntryAdmission
                    next = DesktopOriginalVideoAcceptedPublication(handoff.request,
                        checkNotNull(player.currentSourceSnapshot()).also { check(it.sourceVersion == before.sourceVersion) })
                    accepted.set(next)
                    before.source.nativeTransport?.let { transport ->
                        try {
                            if (!transport.adopt(next.sourceVersion, checkNotNull(before.source.nativePublication),
                                    checkNotNull(next.nativeSource.source.nativePublication), mediaByteAdmission(next) { owns(next) }))
                                throw CancellationException("Native byte handoff retired")
                        } catch (failure: Throwable) {
                            accepted.compareAndSet(next, null); transport.retire(next.sourceVersion)
                            player.stopIfSourceVersion(next.sourceVersion); throw failure
                        }
                    }
                    inheritedMute.set(handoff.pluginMute?.let { InheritedMute(next, it) })
                    result = next
                    onAccepted(next)
                }
            }
        } catch (_: CancellationException) { return null }
        return result
    }

    /** Context-free CDN/recovery replacements use a live accepted-source lease,
     * never a completed request Job. prepare is REQUIRED same-authority native
     * source preparation carrying the accepted receipt and headers.
     */
    fun acceptedMedia(prepare: (DesktopOriginalVideoAcceptedPublication) -> DesktopOriginalVideoMediaPort): DesktopOriginalVideoMediaPort =
        acceptedMedia(prepare) { true }

    /** Optional concrete presenter admission is evaluated in the SAME final
     * Store -> entry -> native gate. It is not a new source authority. */
    internal fun acceptedMedia(prepare: (DesktopOriginalVideoAcceptedPublication) -> DesktopOriginalVideoMediaPort,
        isPresenterCurrent: () -> Boolean): DesktopOriginalVideoMediaPort = acceptedMedia(prepare, null, isPresenterCurrent)

    internal fun acceptedMedia(prepare: (DesktopOriginalVideoAcceptedPublication) -> DesktopOriginalVideoMediaPort,
        desktopFailure: DesktopOriginalNativeRecoveryTicket?, isPresenterCurrent: () -> Boolean): DesktopOriginalVideoMediaPort {
        var lease = current() ?: throw CancellationException("No owned accepted ordinary source")
        var failureConsumed = false
        if (desktopFailure != null && desktopFailure.source !== lease)
            throw CancellationException("Native recovery accepted source replaced")
        fun callerCurrent() = desktopFailure == null || desktopFailure.caller?.isActive == true
        if (!callerCurrent() || (desktopFailure != null && player.state.value.failure?.attemptId != desktopFailure.failureAttemptId))
            throw CancellationException("Native recovery caller/attempt retired before preparation")
        if (!isPresenterCurrent()) throw CancellationException("Accepted presenter retired before preparation")
        val delegate = prepare(lease)
        fun current() = callerCurrent() && owns(lease) && isPresenterCurrent() && (desktopFailure == null ||
            if (failureConsumed) player.state.value.failure == null else
            player.state.value.failure?.let { it.sourceVersion == lease.sourceVersion &&
                it.attemptId == desktopFailure.failureAttemptId } == true)
        fun checkCurrent() { if (!current()) throw CancellationException("Accepted ordinary source/presenter retired") }
        return object : DesktopOriginalNativeRecoveryMediaPort {
            override fun admitRecoveryAction(action: () -> Unit): Boolean {
                checkCurrent()
                var applied = false
                publication.admit(lease.nativeSource.source, ::current) {
                    if (!withEntryAdmission {
                        if (!player.admitSourceSnapshot(lease.nativeSource) {
                            checkCurrent(); action(); applied = true
                        }) throw CancellationException("Recovery completion source retired")
                    }) throw CancellationException("Recovery completion entry retired")
                }
                return applied
            }
            override fun withPlaybackIntent(startPositionMs:Long,playWhenReady:Boolean,action:()->Unit) {
                checkCurrent()
                delegate.withPlaybackIntent(startPositionMs,playWhenReady) {
                    checkCurrent(); action()
                }
            }
            override fun prepareLegacyDash(videoUrl:String,audioUrl:String?,cdnCacheKeysByUrl:Map<String,String>):PlaybackSource {
                checkCurrent(); return delegate.prepareLegacyDash(videoUrl,audioUrl,cdnCacheKeysByUrl)
            }
            override fun prepareAdaptiveDash(source:com.android.purebilibili.core.player.dash.AdaptiveDashPlaybackSource,
                cdnCacheKeysByUrl:Map<String,String>):PlaybackSource? {
                checkCurrent(); return delegate.prepareAdaptiveDash(source,cdnCacheKeysByUrl)
            }
            override fun prepareProgressive(url:String):PlaybackSource {
                checkCurrent(); return delegate.prepareProgressive(url)
            }
            override fun accept(source:PlaybackSource) {
                checkCurrent()
                if (source.authorizationReceipt != lease.nativeSource.source.authorizationReceipt)
                    throw CancellationException("Accepted recovery receipt changed")
                publication.admit(source, ::current) {
                    if (!withEntryAdmission {
                        val previous = lease
                        if (!player.admitSourceSnapshot(previous.nativeSource) {
                        checkCurrent() // Caller/complete source/attempt after taking the actual MPV lock.
                        lateinit var next: DesktopOriginalVideoAcceptedPublication
                        val retained = retainedSource(source) { owns(next) && isPresenterCurrent() }
                        if (!player.recoverSource(previous.sourceVersion,retained,
                                positionSeconds = source.startPositionSeconds,paused = source.startPaused,
                                expectedFailureAttemptId = desktopFailure?.failureAttemptId?.takeUnless { failureConsumed }))
                            throw CancellationException("Accepted ordinary recovery retired")
                        next = DesktopOriginalVideoAcceptedPublication(previous.request,
                            checkNotNull(player.currentSourceSnapshot()).also { check(it.sourceVersion == previous.sourceVersion) })
                        accepted.set(next)
                        bindAcceptedTransport(next, previous) { owns(next) && isPresenterCurrent() }
                        inheritedMute.get()?.takeIf { it.lease === previous }?.let { mute ->
                            inheritedMute.compareAndSet(mute, InheritedMute(next, mute.interval))
                        }
                        // Only this operation's own accepted transition is retained.
                        // Another source/recovery/attempt still retires current().
                        lease = next
                        failureConsumed = desktopFailure != null
                        onAccepted(next)
                        }) throw CancellationException("Accepted ordinary recovery source changed")
                    }) throw CancellationException("Accepted ordinary entry retired")
                }
            }
        }
    }

    /** Cache-error fallback is the original remote semantic source. Required failed
     * attempt and accepted identity prevent retrying a replaced/recovered native load. */
    fun recoverDirectAfterCacheError(expected: DesktopOriginalVideoAcceptedPublication,
        positionSeconds: Double, paused: Boolean, expectedFailureAttemptId: Long): Boolean {
        if (!owns(expected) || expected.nativeSource.source.nativeTransport == null ||
            expectedFailureAttemptId <= 0L || !positionSeconds.isFinite() || positionSeconds < 0.0) return false
        var recovered = false
        try {
            publication.admit(expected.nativeSource.source, { owns(expected) }) {
                withEntryAdmission {
                    if (!owns(expected) || player.state.value.failure?.attemptId != expectedFailureAttemptId) return@withEntryAdmission
                    lateinit var next: DesktopOriginalVideoAcceptedPublication
                    val direct = retainedSource(expected.nativeSource.source.copy(nativeTransport = null,
                        startPositionSeconds = positionSeconds, startPaused = paused)) { owns(next) }
                    if (!player.recoverSource(expected.sourceVersion, direct, positionSeconds, paused,
                            expectedFailureAttemptId = expectedFailureAttemptId)) return@withEntryAdmission
                    next = DesktopOriginalVideoAcceptedPublication(expected.request,
                        checkNotNull(player.currentSourceSnapshot()).also { check(it.sourceVersion == expected.sourceVersion) })
                    accepted.set(next)
                    inheritedMute.get()?.takeIf { it.lease === expected }?.let { previous ->
                        inheritedMute.compareAndSet(previous, InheritedMute(next, previous.interval))
                    }
                    onAccepted(next); recovered = true
                }
            }
        } catch (_: CancellationException) { return false }
        return recovered
    }

    /** The real native loopback may buffer forever after an origin IOException,
     * without MPV generating a PlayerFailure attempt. Consume its fixed lease
     * event through the existing full-owner observer, including loading/paused.
     * No fake native failure, latest-account lookup or second recovery poller. */
    fun observeByteCacheFailure(): Boolean {
        val expected = current() ?: return false
        val transport = expected.nativeSource.source.nativeTransport ?: return false
        val failure = transport.lease.latestNativeFailure() ?: return false
        return recoverDirectAfterByteFailure(expected, failure)
    }

    internal fun recoverDirectAfterByteFailure(expected: DesktopOriginalVideoAcceptedPublication,
        failure: com.bilipai.desktop.player.cache.DesktopNativeByteFailure): Boolean {
        val transport = expected.nativeSource.source.nativeTransport ?: return false
        fun currentFailure(): Boolean = owns(expected) && transport.lease.ownsNativeFailure(failure) &&
            failure.sourceVersion == expected.sourceVersion &&
            failure.stamp.publication === expected.nativeSource.source.nativePublication &&
            failure.stamp.receipt == expected.nativeSource.source.authorizationReceipt
        if (!currentFailure()) return false
        var recovered = false
        try {
            publication.admit(expected.nativeSource.source, ::currentFailure) {
                withEntryAdmission {
                    if (!currentFailure()) return@withEntryAdmission
                    player.admitSourceSnapshot(expected.nativeSource) {
                        if (!currentFailure()) return@admitSourceSnapshot
                        val readback = player.state.value
                        val position = readback.positionSeconds
                        if (!position.isFinite() || position < 0.0) return@admitSourceSnapshot
                        // Requested pause remains real even when buffering has no
                        // nativePaused readback. Never infer autoplay from null.
                        val paused = readback.paused
                        lateinit var next: DesktopOriginalVideoAcceptedPublication
                        val direct = retainedSource(expected.nativeSource.source.copy(nativeTransport = null,
                            startPositionSeconds = position, startPaused = paused)) { owns(next) }
                        if (!player.recoverSource(expected.sourceVersion, direct, position, paused)) return@admitSourceSnapshot
                        next = DesktopOriginalVideoAcceptedPublication(expected.request,
                            checkNotNull(player.currentSourceSnapshot()).also { check(it.sourceVersion == expected.sourceVersion) })
                        accepted.set(next)
                        inheritedMute.get()?.takeIf { it.lease === expected }?.let { previous ->
                            inheritedMute.compareAndSet(previous, InheritedMute(next, previous.interval))
                        }
                        onAccepted(next); recovered = true
                    }
                }
            }
        } catch (_: CancellationException) { return false }
        return recovered
    }

    /** Called by the existing full-owner position observer, with no new poller.
     * Read the actual native clock; clear only after its mute readback matches.
     * Publication and native version/revision both guard the queued restoration. */
    fun observeInheritedPluginMute(): Boolean {
        val inherited = inheritedMute.get() ?: return true
        val lease = inherited.lease
        if (!owns(lease)) return false
        val native = player.state.value
        if (native.loading || native.error != null || native.failure != null ||
            (!native.ended && native.nativePaused == null) || !native.positionSeconds.isFinite()) return false
        val positionMs = (native.positionSeconds * 1_000.0).toLong()
        if (!native.ended && positionMs < inherited.interval.untilMs && positionMs >= inherited.interval.fromMs) return false
        return try {
            var restored = false
            publication.admit(lease.nativeSource.source, { owns(lease) }) {
                withEntryAdmission {
                    if (inheritedMute.get() !== inherited || !owns(lease)) return@withEntryAdmission
                    val restoreMuted = currentUserMuted()
                    if (player.state.value.muted == restoreMuted) {
                        restored = inheritedMute.compareAndSet(inherited, null)
                    } else player.setMutedIfSourceVersion(lease.sourceVersion, restoreMuted)
                }
            }
            restored
        } catch (_: CancellationException) { false }
    }

    /** Admission retirement only; Root cancelAndJoin and optional owned stop are
     * explicit outside locks. Never stop the shared MPV from this fa莽ade close. */
    override fun close() {
        val retire = {
            closed.set(true)
            accepted.getAndSet(null)?.let { value -> value.nativeSource.source.nativeTransport
                ?.retire(value.sourceVersion, value.nativeSource.source.nativePublication) }
            inheritedMute.set(null)
        }
        if (!withEntryAdmission(retire)) retire()
    }
}

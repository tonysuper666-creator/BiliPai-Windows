package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.playback.session.PlaybackSessionState
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel
import com.android.purebilibili.data.repository.FollowStateChange
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.BiliApiException
import com.bilipai.desktop.player.*
import com.bilipai.desktop.player.cache.DesktopMediaByteAdmission
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Required views of existing Root effects. This object cannot instantiate a queue,
 * Mini/PiP player, cache, download manager, account, transport or settings store.
 * Root must pass its same retained authorities, including all playlist rows/cursor.
 */
internal class DesktopOriginalVideoOwnerEffects(
    val mini: DesktopOriginalVideoOwnerMini,
    val playlist: DesktopOriginalVideoOwnerPlaylist,
    val download: DesktopOriginalVideoOwnerDownload,
    val network: DesktopOriginalVideoOwnerNetwork,
    val cache: DesktopOriginalVideoOwnerCache,
    val analytics: DesktopOriginalVideoOwnerAnalytics,
    val crash: DesktopOriginalVideoOwnerCrash,
    val danmaku: DesktopOriginalVideoOwnerDanmaku,
    val background: StateFlow<Boolean>,
)

/** Concrete construction of the installed whole original VM, UseCase environment,
 * four domains and one extended view of the SAME native player. Required factories
 * bind the installed byte-cache/request and Runtime bridge; they are not defaults.
 * No original owner is constructed lazily in a conditional comments/Audio tab.
 *
 * entryScope must be a Root-owned child of its retained route scope. Root holds this
 * assembly while the video is covered by another route or is playing in Audio/PiP.
 * Closing a Compose body is not the lifetime of this object.
 */
internal class DesktopOriginalVideoOwnerAssembly private constructor(
    private val repository: DesktopRepository,
    private val capturedEpoch: Long,
    private val entryScope: CoroutineScope,
    private val stillEntryOwned: () -> Boolean,
    private val commitIfEntryCurrent: ((() -> Unit) -> Boolean),
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val entryJob = checkNotNull(entryScope.coroutineContext[Job])
    lateinit var native: DesktopOriginalVideoNativeOwner
        private set
    lateinit var invocations: DesktopOriginalVideoPlaybackInvocationPorts
        private set
    lateinit var section: DesktopOriginalMpvSectionControl
        private set
    lateinit var playback: VideoPlaybackViewModel
        private set
    lateinit var domains: DesktopOriginalVideoDomainOwners
        private set
    lateinit var environment: DesktopOriginalVideoPlaybackOwnerEnvironment
        private set

    fun owns(): Boolean = !closed.get() && entryJob.isActive &&
        repository.sessionEpoch == capturedEpoch && stillEntryOwned()
    private fun assertOwned() { if (!owns()) throw CancellationException("Original video assembly retired") }
    private fun commit(action: () -> Unit): Boolean {
        if (!owns()) return false
        return try {
            repository.withPrimaryPlaybackAdmission(capturedEpoch, ::owns) {
                var applied = false
                commitIfEntryCurrent { if (owns()) { action(); applied = true } } && applied
            }
        } catch (_: CancellationException) { false }
        catch (_: BiliApiException) { false }
    }

    /** Actual original SessionState, read once inside a real invocation. CID 0 can
     * legitimately precede metadata resolution; the media factory must use the
     * installed captureDesktopOriginalResolvedMediaRequest at synchronous accept.
     * This method does not manufacture another request or increment a generation.
     */
    fun captureLoadState(): PlaybackSessionState {
        assertOwned()
        return playback.captureDesktopLoadState()
    }

    /** Root drains the OLD controller/producers/barrier first. A non-null result
     * is required before it atomically switches all clients to this assembly.
     * This does not fabricate Success from the old controller's partial UI model.
     */
    fun adopt(handoff: DesktopOrdinaryPlaybackHandoff): DesktopOriginalVideoAcceptedPublication? {
        assertOwned()
        return native.adopt(handoff)
    }

    /** After adoption Root's real Mini projection identifies handoff BVID/CID.
     * The original new-VM/same-native branch fetches complete metadata and skips
     * prepare, preserving the actual inherited pause/position. No fake UI seed.
     */
    fun loadInheritedDetails(handoff: DesktopOrdinaryPlaybackHandoff) {
        assertOwned()
        val accepted = native.current() ?: throw CancellationException("Native adoption is required")
        check(accepted.sourceVersion == handoff.nativeSource.sourceVersion && accepted.request == handoff.request)
        playback.loadVideo(handoff.request.bvid, handoff.request.aid,
            autoPlay = !(handoff.readback.nativePaused ?: handoff.readback.paused),
            cid = handoff.request.cid,
            fallbackResumePositionMs = (handoff.readback.positionSeconds.coerceAtLeast(0.0) * 1000).toLong())
    }

    /** Root retires its route gate first, then calls this OUTSIDE all Store/entry/
     * native gates. Every cleanup is attempted even if original close rejects a
     * stale memory write. Global subtitle assets, MPV and Window are not closed.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var first: Throwable? = null
        fun clean(action: () -> Unit) {
            try { action() } catch (failure: Throwable) {
                if (first == null) first = failure else first!!.addSuppressed(failure)
            }
        }
        if (::playback.isInitialized) clean(playback::close)
        if (::invocations.isInitialized) clean(invocations::close)
        if (::domains.isInitialized) clean(domains::close)
        if (::native.isInitialized) clean(native::close)
        entryJob.cancel()
        first?.let { throw it }
    }

    /** Join is outside monitors. A caller cancellation cannot skip drainage of
     * this route's producer Job. Root must await this before constructing a new
     * same-native owner; failure/timeout at its boundary blocks a second producer.
     */
    suspend fun closeAndJoin(timeoutMs: Long): Boolean {
        require(timeoutMs > 0L)
        check(currentCoroutineContext()[Job] !== entryJob) { "Root must join from its external shutdown scope" }
        var failure: Throwable? = null
        try { close() } catch (closedFailure: Throwable) { failure = closedFailure }
        val joined = withContext(NonCancellable) {
            withTimeoutOrNull(timeoutMs) { entryJob.join(); true } ?: false
        }
        failure?.let { throw it }
        return joined
    }

    companion object {
        fun create(
            context: DesktopPluginContext,
            settings: DesktopOriginalPlayerSettingsContext,
            repository: DesktopRepository,
            capturedEpoch: Long,
            entryScope: CoroutineScope,
            stillEntryOwned: () -> Boolean,
            commitIfEntryCurrent: ((() -> Unit) -> Boolean),
            player: MpvPlayer,
            publication: DesktopPlaybackPublication,
            currentUserMuted: () -> Boolean,
            sourceVersions: StateFlow<Long?>,
            onAccepted: (DesktopOriginalVideoAcceptedPublication) -> Unit,
            mediaByteAdmission: (DesktopOriginalVideoAcceptedPublication, () -> Boolean) -> DesktopMediaByteAdmission,
            captureInvocation: suspend (PlaybackSessionState, DesktopOriginalVideoNativeOwner) -> DesktopOriginalVideoPlaybackInvocation,
            status: DesktopOriginalVideoPlaybackStatus,
            prepareAcceptedMedia: (DesktopOriginalVideoAcceptedPublication) -> DesktopOriginalVideoMediaPort,
            createPlugins: (DesktopOriginalVideoNativeOwner, DesktopOriginalVideoPlaybackInvocationPorts) -> DesktopOriginalVideoOwnerPlugins,
            confirmFollow: (FollowStateChange) -> Unit,
            feedback: (String) -> Unit,
            readHasPrimarySession: () -> Boolean,
            readHasPrimaryAccessToken: () -> Boolean,
            readPrimaryMid: () -> Long?,
            readOwnedCooldown: (Long) -> Long,
            progress: DesktopOriginalVideoProgressPort,
            capabilities: DesktopOriginalVideoPlaybackCapabilities,
            applyPreferredVolume: (DesktopOriginalMpvSectionControl) -> Unit,
            dashSegmentRequestsEnabled: () -> Boolean,
            logSeek: (Long, Long, Long, Long) -> Unit,
            diagnosticLoggingEnabled: () -> Boolean,
            resumeEndedSource: () -> Unit,
            root: DesktopOriginalCommentRootOwner,
            interactionAnalytics: DesktopOriginalVideoInteractionAnalytics,
            effects: DesktopOriginalVideoOwnerEffects,
            todayWatchFeedback: DesktopTodayWatchFeedbackWriteBinding,
        ): DesktopOriginalVideoOwnerAssembly {
            require(settings.pluginContext.store === context.store) { "The actual global player store is required" }
            if (repository.sessionEpoch != capturedEpoch || root.operations.expectedEpoch != capturedEpoch ||
                !entryScope.isActive || !root.scope.isActive || !root.isOwned() || !stillEntryOwned())
                throw CancellationException("Original video assembly construction retired")
            val owner = DesktopOriginalVideoOwnerAssembly(repository, capturedEpoch, entryScope,
                stillEntryOwned, commitIfEntryCurrent)
            try {
                owner.native = DesktopOriginalVideoNativeOwner(player, publication, { repository.sessionEpoch },
                    currentUserMuted, owner::owns, commitIfEntryCurrent, onAccepted, mediaByteAdmission)
                owner.invocations = DesktopOriginalVideoPlaybackInvocationPorts(entryScope, owner::owns,
                    capture = { captureInvocation(owner.captureLoadState(), owner.native) }, status,
                    acceptedMedia = { owner.native.acceptedMedia(prepareAcceptedMedia) },
                    acceptedFailureMedia = { ticket -> owner.native.acceptedMedia(prepareAcceptedMedia, ticket) { true } })
                val repositoryView = DesktopOriginalVideoOwnerRepositoryView(owner.invocations, readOwnedCooldown)
                val account = DesktopOriginalVideoOwnerAccountView(owner.invocations, readHasPrimarySession,
                    readHasPrimaryAccessToken, readPrimaryMid)
                val actions = DesktopOriginalVideoOwnerActionView(owner.invocations, entryScope,
                    interactionAnalytics, confirmFollow, feedback)
                val plugins = createPlugins(owner.native, owner.invocations)
                val useCase = DesktopOriginalVideoPlaybackUseCaseEnvironment(context, repositoryView,
                    actions, progress, capabilities, owner.invocations.media, applyPreferredVolume,
                    emoteMap = { root.emotes.ensureLoaded() }, updatePrimaryVip = account::updatePrimaryVip,
                    dashSegmentRequestsEnabled, logSeek, owner::owns)
                val comments = object : DesktopOriginalVideoOwnerComments {
                    override suspend fun getEmotePackages() = root.operations.getBgmEmotePackages()
                    override suspend fun searchMentionUsers(keyword: String) = root.operations.searchMentionUsers(keyword)
                    override suspend fun addComment(aid: Long, message: String, rootId: Long, parent: Long,
                        pictures: List<com.android.purebilibili.data.model.response.ReplyPicture>, syncToDynamic: Boolean) =
                        root.requests.addCommentForSubject(aid, 1, message, rootId, parent, pictures, syncToDynamic)
                    override suspend fun uploadCommentPicture(source: String, index: Int) = root.requests.uploadCommentPicture(source, index)
                }
                owner.environment = DesktopOriginalVideoPlaybackOwnerEnvironment(entryScope, settings,
                    owner.invocations, repositoryView, DesktopOriginalVideoOwnerNotesView(owner.invocations),
                    actions, useCase, actions.interactionUseCase, account, effects.mini,
                    effects.playlist, plugins, effects.download, effects.network, effects.cache,
                    { expected ->
                        if (owner.native.isCurrent(expected) && expected.nativeSource.source.nativeTransport != null)
                            DesktopOriginalCdnRangeCapture(expected, owner.native::isCurrent)
                        else null // actual direct source or retired lease; no fake cache success
                    }, effects.analytics, effects.crash, comments, effects.danmaku,
                    effects.background, owner::owns, owner::commit, todayWatchFeedback)
                owner.playback = VideoPlaybackViewModel(owner.environment)
                owner.section = DesktopOriginalMpvSectionControl(player,
                    { owner.native.current()?.sourceVersion }, owner::owns,
                    { action -> owner.native.current()?.let { owner.native.admitPlaybackDispatch(it, action) } ?: false },
                    diagnosticLoggingEnabled,
                    ensurePreparedSource = { checkNotNull(owner.native.current()) { "Actual prepared publication required" } },
                    resumeEndedSource, logSeek, entryScope, sourceVersions,
                    { action -> owner.native.current()?.let { owner.native.admitPlaybackDispatch(it, action) } ?: false })
                owner.domains = DesktopOriginalVideoDomainOwners.create(context, root, capturedEpoch,
                    owner::owns, owner::commit, { owner.playback.uiState.value }, interactionAnalytics)
                // Original attach has no media loading side effect. Preferred volume
                // is admitted only when an actual source belongs to this Section.
                owner.playback.attachPlayer(owner.section)
                owner.assertOwned()
                return owner
            } catch (failure: Throwable) {
                try { owner.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }
}

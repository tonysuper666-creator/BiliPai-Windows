package com.bilipai.desktop.ui

import com.android.purebilibili.core.plugin.PlayerPlugin
import com.android.purebilibili.core.plugin.Plugin
import com.android.purebilibili.core.plugin.SkipAction
import com.android.purebilibili.data.model.response.SponsorSegment
import com.android.purebilibili.feature.plugin.*
import com.bilipai.desktop.plugins.DesktopPlayerPluginDispatch
import com.bilipai.desktop.plugins.DesktopPluginRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass

/** A view of the retained Runtime and canonical NativeOwner. The generation port
 * reads only the token returned by the real Runtime load (or inherited handoff).
 * This adapter does not create a plugin, player, actor, scope or generation.
 */
internal class DesktopOriginalVideoOwnerPluginBridge(
    private val runtime: DesktopPluginRuntime,
    private val native: DesktopOriginalVideoNativeOwner,
    private val invocations: DesktopOriginalVideoPlaybackInvocationPorts,
    private val currentRuntimeGeneration: (DesktopOriginalVideoAcceptedPublication) -> Long?,
    private val captureCurrentRequestAdmission: () -> ((() -> Unit) -> Boolean),
    private val acceptedPlaybackCalls: (DesktopOriginalVideoAcceptedPublication) -> Call.Factory,
) : DesktopOriginalVideoOwnerPlugins {
    private data class Binding(val expected: DesktopOriginalVideoAcceptedPublication, val dispatch: DesktopPlayerPluginDispatch)
    // A captured view of the actual Runtime token, retained for close after the
    // entry dies. Runtime's exact identity/CAS remains the only retirement owner.
    private val lastBinding = AtomicReference<Binding?>()
    override val sponsorBlock get() = runtime.originalSponsorBlock

    override fun enabledPlugins(): List<Plugin> = runtime.plugins.value.filter { it.enabled }.map { it.plugin }
    override fun enabledPlayerPlugins(): List<PlayerPlugin> = enabledPlugins().filterIsInstance<PlayerPlugin>()
    override fun <T : Plugin> enabledPlugins(type: KClass<T>): List<T> =
        enabledPlugins().filter { type.isInstance(it) }.map { @Suppress("UNCHECKED_CAST") (it as T) }

    private fun capture(expected: DesktopOriginalVideoAcceptedPublication,
        additionalCurrent: () -> Boolean = { true }): DesktopPlayerPluginDispatch? {
        val generation = currentRuntimeGeneration(expected) ?: return null
        val dispatch = runtime.capturePlaybackPluginDispatch(expected.request.bvid, expected.request.cid, generation,
            stillOwned = { native.isCurrent(expected) && additionalCurrent() },
            admission = { action ->
                native.admitPlaybackDispatch(expected) {
                    if (!additionalCurrent()) throw CancellationException("Playback action replaced")
                    action()
                }
            }) ?: return null
        if (!native.admitPlaybackDispatch(expected) {
            if (!runtime.isPlaybackPluginDispatchCurrent(dispatch)) throw CancellationException("Playback plugins retired")
            lastBinding.set(Binding(expected, dispatch))
        }) return null
        return dispatch
    }

    private fun requireDispatch(expected: DesktopOriginalVideoAcceptedPublication,
        additionalCurrent: () -> Boolean = { true }): DesktopPlayerPluginDispatch =
        capture(expected, additionalCurrent) ?: throw CancellationException("Captured playback plugins retired")

    override fun capturePlaybackDispatch(): DesktopOriginalVideoAcceptedPublication? {
        val expected = native.current() ?: return null
        return expected.takeIf { capture(it) != null }
    }
    override fun isPlaybackDispatchCurrent(expected: DesktopOriginalVideoAcceptedPublication): Boolean = capture(expected) != null
    override fun admitPlaybackDispatch(expected: DesktopOriginalVideoAcceptedPublication, action: () -> Unit): Boolean {
        val dispatch = capture(expected) ?: return false
        return native.admitPlaybackDispatch(expected) {
            if (!runtime.isPlaybackPluginDispatchCurrent(dispatch)) throw CancellationException("Playback plugins retired")
            action()
        }
    }
    override fun observeInheritedPluginMute() {
        // This original observer runs before plugin/isPlaying/loading filters.
        native.observeByteCacheFailure()
        native.observeInheritedPluginMute()
    }
    override fun observeCdnTransferPlayback() {
        if (CdnTransferRuntime.enabled) native.current()?.let { expected ->
            native.admitPlaybackDispatch(expected) {
                val state = native.player.state.value
                val seconds = state.bufferedForwardSeconds?.takeIf { it.isFinite() && it >= 0.0 }
                if (!state.ended && state.error == null && state.failure == null) {
                    val video = state.videoBitrateBps?.coerceAtLeast(0) ?: 0L
                    val audio = state.audioBitrateBps?.coerceAtLeast(0) ?: 0L
                    val bitrate = if (video > Long.MAX_VALUE - audio) Long.MAX_VALUE else video + audio
                    CdnTransferRuntime.playback(seconds?.let { (it * 1_000).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong() } ?: 0L, bitrate)
                }
            }
        }
    }

    override suspend fun ensureSponsorLoaded(expected: DesktopOriginalVideoAcceptedPublication, bvid: String, cid: Long) {
        currentCoroutineContext().ensureActive()
        if (expected.request.bvid != bvid || expected.request.cid != cid)
            throw CancellationException("Sponsor subject changed")
        val dispatch = requireDispatch(expected)
        // The same Runtime already awaited its real onVideoLoad. An adopted
        // generation must keep the original provider's segments and skipped IDs.
        runtime.mutatePlaybackPlugin(dispatch, sponsorBlock, allowDisabledSponsor = false) { Unit }
    }
    override suspend fun onSponsorDisabled(expected: DesktopOriginalVideoAcceptedPublication) {
        runtime.mutatePlaybackPlugin(requireDispatch(expected), sponsorBlock, allowDisabledSponsor = true) {
            sponsorBlock.onVideoEnd()
        }
    }
    override suspend fun onPositionUpdate(expected: DesktopOriginalVideoAcceptedPublication,
        plugin: PlayerPlugin, positionMs: Long): SkipAction? =
        runtime.runPlaybackPluginCallback(requireDispatch(expected), plugin) { plugin.onPositionUpdate(positionMs) }
    override suspend fun onUserSeek(expected: DesktopOriginalVideoAcceptedPublication, plugin: PlayerPlugin, positionMs: Long) {
        runtime.mutatePlaybackPlugin(requireDispatch(expected), plugin, allowDisabledSponsor = false) { plugin.onUserSeek(positionMs) }
    }
    override fun onVideoEnd(plugin: PlayerPlugin) {
        val old = lastBinding.get() ?: return
        if (old.dispatch.providers.none { it === plugin }) return
        runtime.retirePlaybackPluginDispatch(old.dispatch)
    }
    override suspend fun markSponsorSkipped(expected: DesktopOriginalVideoAcceptedPublication,
        plugin: SponsorBlockPlugin, segmentId: String): SponsorSegment? =
        runtime.mutatePlaybackPlugin(requireDispatch(expected), plugin, allowDisabledSponsor = false) {
            if (plugin !== sponsorBlock) throw CancellationException("Foreign Sponsor provider")
            plugin.markAsSkipped(segmentId)
        }
    override suspend fun voteSponsorSegment(expected: DesktopOriginalVideoAcceptedPublication,
        plugin: SponsorBlockPlugin, segmentId: String, vote: Int): Result<Unit> =
        runtime.runPlaybackPluginCallback(requireDispatch(expected), plugin) {
            if (plugin !== sponsorBlock) throw CancellationException("Foreign Sponsor provider")
            plugin.voteOnCommunitySegment(segmentId, vote)
        }
    override suspend fun submitSponsorSegment(expected: DesktopOriginalVideoAcceptedPublication,
        plugin: SponsorBlockPlugin, bvid: String, cid: Long, videoDurationSeconds: Float,
        startMs: Long, endMs: Long, category: String, actionType: String): Result<List<SponsorSegment>> {
        if (bvid != expected.request.bvid || cid != expected.request.cid)
            throw CancellationException("Sponsor submission subject changed")
        return runtime.runPlaybackPluginCallback(requireDispatch(expected), plugin) {
            if (plugin !== sponsorBlock) throw CancellationException("Foreign Sponsor provider")
            plugin.submitCommunitySegment(bvid, cid, videoDurationSeconds, startMs, endMs, category, actionType)
        }
    }

    override suspend fun recordSponsorSkip(expected: DesktopOriginalVideoAcceptedPublication,
        submission: DesktopOriginalNativeSeekSubmission, record: SponsorBlockSkipRecord) {
        if (record.bvid != expected.request.bvid || record.cid != expected.request.cid ||
            submission.sourceVersion != expected.sourceVersion || submission.operationId <= 0L) return
        val captured = requireDispatch(expected)
        // The old Windows owner expires unconfirmed skip bookkeeping after15s.
        // Observe the SAME native StateFlow in this existing caller coroutine;
        // queued seek alone never authorizes a history record or optional ping.
        val completed = withTimeoutOrNull(15_000L) {
            native.player.state.first { state ->
                !runtime.isPlaybackPluginDispatchCurrent(captured) || !native.isCurrent(expected) ||
                    state.seekCompletedId >= submission.operationId || state.loading || state.error != null || state.failure != null
            }
            native.completedSeekPositionMs(expected, submission)
        } ?: return
        if (completed < 0L) return
        val exact = requireDispatch(expected) { native.completedSeekPositionMs(expected, submission) != null }
        runtime.runPlaybackPluginCallback(exact, sponsorBlock) {
            SponsorBlockInsightStore.appendRecord(runtime.context, record)
            currentCoroutineContext().ensureActive()
            if (!runtime.isPlaybackPluginDispatchCurrent(exact) ||
                native.completedSeekPositionMs(expected, submission) == null)
                throw CancellationException("Sponsor upload seek owner retired")
            // Only the retained original provider decides optional upload consent.
            sponsorBlock.uploadViewedSegmentIfEnabled(record.segmentId)
        }
    }

    private suspend fun <T> newRequestMutation(plugin: Plugin, action: () -> T): T {
        val repository = invocations.requireRequestRepository()
        val admission = captureCurrentRequestAdmission()
        fun current(): Boolean = try { invocations.requireRequestRepository() === repository }
            catch (_: CancellationException) { false }
        return runtime.mutateCapturedPlaybackPluginRequest(plugin, ::current, admission, action)
    }
    override suspend fun rewritePlaybackCandidates(plugin: PlaybackCdnPlugin, videoUrls: List<String>, audioUrls: List<String>) =
        newRequestMutation(plugin) { plugin.rewritePlaybackCandidates(videoUrls, audioUrls) }
    override suspend fun buildPlaybackCdnDiagnostics(expected: DesktopOriginalVideoAcceptedPublication?,
        plugin: PlaybackCdnPlugin, videoUrls: List<String>, sources: List<PlaybackCdnCandidateSource>): List<CdnLineDiagnostic> =
        if (expected == null) newRequestMutation(plugin) { plugin.buildPlaybackCdnDiagnostics(videoUrls, sources) }
        else runtime.mutatePlaybackPlugin(requireDispatch(expected), plugin, allowDisabledSponsor = false) {
            plugin.buildPlaybackCdnDiagnostics(videoUrls, sources)
        }
    override suspend fun probePlaybackCdnCandidates(expected: DesktopOriginalVideoAcceptedPublication,
        plugin: PlaybackCdnPlugin, videoUrls: List<String>, sources: List<PlaybackCdnCandidateSource>): List<CdnLineDiagnostic> {
        val dispatch = requireDispatch(expected)
        val calls = acceptedPlaybackCalls(expected)
        return runtime.runPlaybackPluginCallback(dispatch, plugin, calls) { plugin.probePlaybackCdnCandidates(videoUrls, sources) }
    }
    override suspend fun recordPlaybackCdnEvent(expected: DesktopOriginalVideoAcceptedPublication,
        plugin: PlaybackCdnPlugin, url: String, event: CdnHealthEvent) {
        runtime.mutatePlaybackPlugin(requireDispatch(expected), plugin, allowDisabledSponsor = false) { plugin.recordPlaybackCdnEvent(url, event) }
    }
    override suspend fun isAdaptivePrefetchEnabled(expected: DesktopOriginalVideoAcceptedPublication, plugin: PlaybackCdnPlugin): Boolean =
        runtime.mutatePlaybackPlugin(requireDispatch(expected), plugin, allowDisabledSponsor = false) { plugin.isAdaptivePrefetchEnabled() }
}

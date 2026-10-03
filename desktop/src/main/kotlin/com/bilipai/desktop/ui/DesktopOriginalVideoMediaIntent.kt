package com.bilipai.desktop.ui

import com.android.purebilibili.core.player.dash.AdaptiveDashPlaybackSource
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionState
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.CancellationException

/** A value from this exact original synchronous play call, not player readback,
 * settings default or an account/request generation. It never survives the span. */
internal data class DesktopOriginalVideoMediaIntent(val startPositionMs: Long, val playWhenReady: Boolean) {
    init { require(startPositionMs >= 0L) }
}

/** CPU preparation may run here. All URLs/headers/receipt/transport are produced
 * by required same-root captured delegates; this class only stamps actual intent.
 * No Store/native admission, IO, coroutine launch, cache or player is created.
 * The non-suspending span and finally restore are the full lexical lifetime. */
internal class DesktopOriginalVideoMediaIntentView(
    private val legacy: (String, String?, Map<String, String>) -> PlaybackSource,
    private val adaptive: (AdaptiveDashPlaybackSource, Map<String, String>) -> PlaybackSource?,
    private val progressive: (String) -> PlaybackSource,
    private val publish: (PlaybackSource, DesktopOriginalVideoMediaIntent) -> Unit,
) : DesktopOriginalVideoMediaPort {
    private val intent = ThreadLocal<DesktopOriginalVideoMediaIntent?>()
    override fun withPlaybackIntent(startPositionMs: Long, playWhenReady: Boolean, action: () -> Unit) {
        val previous = intent.get()
        intent.set(DesktopOriginalVideoMediaIntent(startPositionMs.coerceAtLeast(0L), playWhenReady))
        try { action() }
        finally { if (previous == null) intent.remove() else intent.set(previous) }
    }
    private fun captured(): DesktopOriginalVideoMediaIntent = intent.get()
        ?: error("Original synchronous playback intent is required")
    private fun stamp(source: PlaybackSource): PlaybackSource = captured().let {
        source.copy(startPositionSeconds = it.startPositionMs / 1000.0, startPaused = !it.playWhenReady)
    }
    override fun prepareLegacyDash(videoUrl: String, audioUrl: String?, cdnCacheKeysByUrl: Map<String, String>): PlaybackSource {
        captured(); return stamp(legacy(videoUrl, audioUrl, cdnCacheKeysByUrl))
    }
    override fun prepareAdaptiveDash(source: AdaptiveDashPlaybackSource, cdnCacheKeysByUrl: Map<String, String>): PlaybackSource? {
        captured(); return adaptive(source, cdnCacheKeysByUrl)?.let(::stamp)
    }
    override fun prepareProgressive(url: String): PlaybackSource { captured(); return stamp(progressive(url)) }
    override fun accept(source: PlaybackSource) { val fixed = captured(); publish(stamp(source), fixed) }
}

/** Read this actual original SessionState at synchronous accept after the original
 * success block assigns currentCid. Initial requested CID=0 is never published as
 * the resolved media subject. Root NativeOwner rechecks this fixed token/subject
 * inside its existing Store->entry publication, alongside actual request Job.
 * This is a transient receipt of original state, not another session authority. */
internal fun captureDesktopOriginalResolvedMediaRequest(
    state: PlaybackSessionState,
    expectedRequest: PlaybackRequest,
    expectedLoadRequestToken: Long,
): PlaybackRequest {
    if (state.currentLoadRequestToken != expectedLoadRequestToken ||
        state.currentBvid != expectedRequest.bvid || state.currentRequest != expectedRequest ||
        state.currentCid <= 0L || (expectedRequest.cid > 0L && state.currentCid != expectedRequest.cid)) {
        throw CancellationException("Original resolved playback subject replaced")
    }
    return expectedRequest.copy(cid = state.currentCid)
}

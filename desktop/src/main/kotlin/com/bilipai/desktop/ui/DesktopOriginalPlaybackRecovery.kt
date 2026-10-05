package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.state.PlayerErrorRecoveryAction
import com.android.purebilibili.feature.video.state.decidePlayerErrorRecovery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.swing.Swing

internal data class DesktopOriginalPlaybackRecoveryState(
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val failure: PlaybackFailure? = null,
    val message: String? = null,
    val recoveryStage: String? = null,
)

/** Actual accepted source + native terminal attempt, used only by automatic
 * premium/CDN preparation. Normal requests keep their existing media port.
 */
internal class DesktopOriginalNativeRecoveryTicket(
    val source: DesktopOriginalVideoAcceptedPublication,
    val failureAttemptId: Long,
    val caller: Job? = null,
) {
    init { require(failureAttemptId > 0L) }
    fun forCaller(value: Job) = DesktopOriginalNativeRecoveryTicket(source, failureAttemptId, value)
}

/** Only the original accepted-media operation implements this port. It admits
 * short state/command completion against its exact own successor, never latest. */
internal interface DesktopOriginalNativeRecoveryMediaPort : DesktopOriginalVideoMediaPort {
    fun admitRecoveryAction(action: () -> Unit): Boolean
}

/** Required capture of a real VM request or accepted native failure. [current] must
 * check the full source/request identity and failure episode, not only a version.
 * [execute] routes to the original VM actions; it performs no work inside admission.
 */
internal class DesktopOriginalPlaybackRecoveryTicket(
    val identity: Any,
    val evidence: DesktopOriginalPlaybackFailureEvidence,
    val hasCdnAlternatives: Boolean,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val current: () -> Boolean,
    val execute: (PlayerErrorRecoveryAction, Long, Boolean) -> Boolean,
)

/** The original v029 bounded recovery consumption, owned by ONE installed VM.
 * No transport, player, account or preferences authority is created here. READY
 * clears reporting only; retry budgets survive until an explicit new user load.
 */
internal class DesktopOriginalPlaybackRecovery(
    private val scope: CoroutineScope,
    private val admit: (() -> Unit) -> Boolean,
    private val requireCurrent: () -> Unit,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : AutoCloseable {
    private val mutableState = MutableStateFlow(DesktopOriginalPlaybackRecoveryState())
    val state: StateFlow<DesktopOriginalPlaybackRecoveryState> = mutableState
    private var pending: Job? = null
    private var identity: Any? = null
    private var episode = 0L
    private var generation = 0L
    private var retries = 0
    private var cdnSwitches = 0
    private var audioFallback = false
    private var closed = false

    fun beginLoad(recovering: Boolean) {
        requireCurrent()
        if (closed) return
        // A retry invoked by pending itself must not cancel its own caller.
        if (!recovering) { pending?.cancel(); pending = null; retries = 0; cdnSwitches = 0; audioFallback = false }
        generation++
        identity = null
        admit { mutableState.value = if (recovering) mutableState.value.copy(status = PlaybackStatus.Recovering)
            else DesktopOriginalPlaybackRecoveryState(status = PlaybackStatus.Loading) }
    }

    fun ready() {
        if (closed) return
        admit {
            identity = null
            mutableState.value = DesktopOriginalPlaybackRecoveryState(status = PlaybackStatus.Ready)
        }
    }

    fun recoveryFailed(failedIdentity: Any) {
        if (closed) return
        admit { if (identity === failedIdentity) mutableState.value = mutableState.value.copy(
            status = PlaybackStatus.Failed, recoveryStage = null) }
    }

    fun fail(ticket: DesktopOriginalPlaybackRecoveryTicket): Boolean {
        require(ticket.positionMs >= 0L)
        if (closed || !scope.isActive || !ticket.current()) return false
        var action = PlayerErrorRecoveryAction.GIVE_UP
        var accepted = false
        val admitted = admit {
            if (!closed && scope.isActive && ticket.current() && identity !== ticket.identity) {
                accepted = true
                pending?.cancel(); pending = null
                identity = ticket.identity
                episode++
                mutableState.value = DesktopOriginalPlaybackRecoveryState(PlaybackStatus.Failed,
                    PlaybackFailure(ticket.evidence.reason, ticket.evidence.code, episode), ticket.evidence.message)
                if (ticket.evidence.automaticRecovery && !(ticket.evidence.premiumAudio && audioFallback)) action = decidePlayerErrorRecovery(
                    errorCode = ticket.evidence.code, hasCdnAlternatives = ticket.hasCdnAlternatives,
                    retryCount = retries, maxRetries = 3, cdnSwitchCount = cdnSwitches, maxCdnSwitches = 2,
                    isDecoderLikeFailure = ticket.evidence.decoder,
                    isPremiumAudioFailure = ticket.evidence.premiumAudio && !audioFallback)
            }
        }
        if (!admitted || !accepted) return false
        if (action == PlayerErrorRecoveryAction.GIVE_UP) return true
        val capturedGeneration = generation
        val capturedEpisode = episode
        val stage = when (action) {
            PlayerErrorRecoveryAction.SWITCH_CDN -> "正在切换播放线路"
            PlayerErrorRecoveryAction.RETRY_NETWORK -> "正在重试网络连接"
            PlayerErrorRecoveryAction.RETRY_DECODER_FALLBACK -> "正在尝试备用视频编码"
            PlayerErrorRecoveryAction.FALLBACK_PREMIUM_AUDIO -> "正在尝试兼容音轨"
            else -> "正在恢复播放"
        }
        val delayMs = when (action) {
            PlayerErrorRecoveryAction.SWITCH_CDN -> { cdnSwitches++; 500L }
            PlayerErrorRecoveryAction.RETRY_NETWORK -> { retries++; (1000L * (1 shl (retries - 1))).coerceAtMost(8000L) }
            PlayerErrorRecoveryAction.FALLBACK_PREMIUM_AUDIO -> { audioFallback = true; 0L }
            else -> { retries++; 0L }
        }
        fun current() = !closed && scope.isActive && generation == capturedGeneration && episode == capturedEpisode &&
            identity === ticket.identity && ticket.current()
        val job = scope.launch {
            // Listener callbacks may still hold existing publication gates. Never run IO/action inline there.
            yield()
            try {
                if (!current()) return@launch
                if (!admit { if (current()) mutableState.value = mutableState.value.copy(
                        status = PlaybackStatus.Recovering, recoveryStage = stage) }) return@launch
                if (delayMs > 0L) wait(delayMs)
                withContext(Dispatchers.Swing) {
                    currentCoroutineContext().ensureActive()
                    requireCurrent()
                    // loadVideo's synchronous beginning belongs to the existing UI event thread.
                    // The action then uses the original entry invocation/native final publication guards.
                    if (current() && !ticket.execute(action, ticket.positionMs, ticket.playWhenReady)) {
                        admit { if (current()) mutableState.value = mutableState.value.copy(
                            status = PlaybackStatus.Failed, recoveryStage = null) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                admit { if (current()) mutableState.value = mutableState.value.copy(status = PlaybackStatus.Failed,
                    recoveryStage = null) }
            } finally {
                if (pending === currentCoroutineContext()[Job]) pending = null
            }
        }
        pending = job
        return true
    }

    override fun close() { closed = true; generation++; pending?.cancel(); pending = null; identity = null }
}

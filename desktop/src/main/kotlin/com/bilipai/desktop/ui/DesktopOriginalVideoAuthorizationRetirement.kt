package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.playback.session.PlaybackSessionState
import com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt
import kotlinx.coroutines.CancellationException
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean

/** A transient notification from one real SPI/VIP mutation, not another owner or
 * credential authority. Root supplies the exact Assembly reference and its gate.
 * The immutable original request object/token survive that load's expected CE;
 * a new request (including an equal-valued ABA), account or entry cannot consume it.
 * State reads and original retry run on the existing Root EDT, outside monitors. */
internal class DesktopOriginalVideoAuthorizationRetirement(
    val owner: Any,
    val receipt: DesktopPlaybackAuthorizationReceipt,
    private val capturedState: PlaybackSessionState,
    private val isOwnerCurrent: () -> Boolean,
    private val currentState: () -> PlaybackSessionState,
    private val retry: () -> Unit,
) {
    private val consumed = AtomicBoolean(false)

    fun isCurrent(): Boolean {
        if (!isOwnerCurrent()) return false
        val request = capturedState.currentRequest ?: return false
        return try {
            val now = currentState()
            isOwnerCurrent() && now.currentRequest === request &&
                now.currentLoadRequestToken == capturedState.currentLoadRequestToken &&
                now.currentBvid == capturedState.currentBvid
        } catch (_: CancellationException) { false }
    }

    fun recaptureIfCurrent(): Boolean {
        check(EventQueue.isDispatchThread()) { "Original authorization recapture belongs to the Root EDT" }
        if (!consumed.compareAndSet(false, true) || !isCurrent()) return false
        retry()
        return true
    }
}

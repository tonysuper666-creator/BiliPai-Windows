package com.bilipai.desktop.player

/** One Canvas handoff. Source authority remains in the original publication and
 * MpvPlayer's complete immutable snapshot; this object only carries presentation
 * progress and the native worker's temporary restoration cursor. */
internal class DesktopNativePresentationTransfer(
    val source: OwnedPlaybackSourceSnapshot,
    val revision: Long,
    val oldWindowId: Long,
    private val workerHasTerminated: () -> Boolean,
) {
    enum class Phase { REQUESTED, CAPTURED, PEER_RELEASED, ATTACHED, ACKNOWLEDGED, RETIRED }
    @Volatile var phase = Phase.REQUESTED
        private set
    private var cursor: DesktopNativePresentationResume? = null
    @Volatile var hasReleasedPeer: Boolean = false
        private set
    @Volatile var hasAttachedPeer: Boolean = false
        private set
    val workerReleased: Boolean get() = workerHasTerminated()
    val resume: DesktopNativePresentationResume? get() = cursor
    val isRetired: Boolean get() = phase == Phase.RETIRED

    fun matches(expected: OwnedPlaybackSourceSnapshot, playbackRevision: Long): Boolean =
        !isRetired && source.sourceVersion == expected.sourceVersion && source.source == expected.source && revision == playbackRevision

    // Mutations are called under the existing MpvPlayer lock, never an extra lock.
    fun capture(positionSeconds: Double, paused: Boolean, pauseSerial: Long): Boolean {
        if (phase != Phase.REQUESTED || !positionSeconds.isFinite() || positionSeconds < 0) return false
        cursor = DesktopNativePresentationResume(positionSeconds, paused, pauseSerial)
        phase = Phase.CAPTURED
        return true
    }
    fun peerReleased(): Boolean {
        if (phase !in setOf(Phase.CAPTURED, Phase.RETIRED) || cursor == null || !workerReleased) return false
        hasReleasedPeer = true
        if (!isRetired) phase = Phase.PEER_RELEASED
        return true
    }
    fun attach(existingPeer: Boolean = false): Boolean {
        if (!workerReleased || phase != (if (existingPeer) Phase.CAPTURED else Phase.PEER_RELEASED)) return false
        phase = Phase.ATTACHED
        hasAttachedPeer = true
        return true
    }
    fun pause(paused: Boolean, serial: Long) {
        val previous = cursor ?: return
        if (phase in setOf(Phase.CAPTURED, Phase.PEER_RELEASED, Phase.ATTACHED) && serial >= previous.pauseSerial)
            cursor = previous.copy(paused = paused, pauseSerial = serial)
    }
    fun seek(id: Long, seconds: Double, relative: Boolean, durationSeconds: Double = 0.0): Boolean {
        val previous = cursor ?: return false
        if (phase !in setOf(Phase.CAPTURED, Phase.PEER_RELEASED, Phase.ATTACHED) || !seconds.isFinite()) return false
        val position = (if (relative) previous.positionSeconds + seconds else seconds).coerceAtLeast(0.0)
            .let { if (durationSeconds.isFinite() && durationSeconds > 0) it.coerceAtMost(durationSeconds) else it }
        cursor = previous.copy(positionSeconds = position, seekId = id)
        return true
    }
    fun acknowledge(positionSeconds: Double, paused: Boolean): Boolean {
        val expected = cursor ?: return false
        if (phase != Phase.ATTACHED || !positionSeconds.isFinite() ||
            kotlin.math.abs(positionSeconds - expected.positionSeconds) > 0.75 || paused != expected.paused) return false
        phase = Phase.ACKNOWLEDGED
        return true
    }
    /** Terminal playback cannot produce a cursor/ACK. The intentional outgoing
     * worker exit after CAPTURED must preserve that already captured cursor. */
    fun retireTerminatedWorker(): Boolean {
        if (phase != Phase.REQUESTED && phase != Phase.ATTACHED) return false
        retire()
        return true
    }
    /** Called only after source admission has rejected this handoff. A newly
     * created floating peer still requires worker drain before it can unmount,
     * even when its original transfer never reached attach(). */
    fun cancelledFloatingPeerNeedsDisposal(hasFloatingWindow: Boolean, returning: Boolean, restoreRequested: Boolean): Boolean =
        hasFloatingWindow && (returning || restoreRequested || !hasAttachedPeer)
    fun retire() { phase = Phase.RETIRED }
    override fun toString() = "DesktopNativePresentationTransfer(phase=$phase, sourceVersion=${source.sourceVersion})"
}

internal data class DesktopNativePresentationResume(
    val positionSeconds: Double, val paused: Boolean, val pauseSerial: Long, val seekId: Long? = null,
) {
    fun fileOptions(original: Map<String, String>): Map<String, String> = original + mapOf(
        "start" to positionSeconds.toString(), "pause" to if (paused) "yes" else "no",
    )
}

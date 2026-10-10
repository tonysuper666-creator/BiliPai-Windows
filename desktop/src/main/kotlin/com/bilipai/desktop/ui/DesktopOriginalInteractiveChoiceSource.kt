package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.playback.session.PlaybackSessionState

/** A captured Windows click permission for the retained original interactive VM.
 * Root supplies its exact accepted publication, permanent metadata lease/current
 * account checks and existing Factory presentation admission. No default gate,
 * networking, timer, branch state or native authority is created here.
 *
 * Keep this binding outside the conditional visible choice panel: hiding that
 * panel is part of the original choice, and must not retire its metadata lease.
 * It admits only the old source BEFORE submission. The actual PageTransition
 * proof owns a legitimate new CID and delayed native ACK after that handoff. */
internal class DesktopOriginalInteractiveChoiceSource(
    val sourceOwner: DesktopOriginalVideoAcceptedPublication,
    private val original: PlaybackSessionState,
    private val captureLoadState: () -> PlaybackSessionState,
    private val stillOwned: () -> Boolean,
    private val admission: (() -> Unit) -> Boolean,
) {
    init {
        require(original.currentRequest != null && original.currentBvid.isNotBlank() && original.currentCid > 0L)
        require(sourceOwner.request.bvid == original.currentBvid && sourceOwner.request.cid == original.currentCid)
    }
    private fun sameLoad(state: PlaybackSessionState): Boolean =
        state.currentRequest === original.currentRequest &&
            state.currentLoadRequestToken == original.currentLoadRequestToken &&
            state.currentBvid == original.currentBvid && state.currentCid == original.currentCid
    fun matches(state: PlaybackSessionState, expected: DesktopOriginalVideoAcceptedPublication): Boolean =
        sourceOwner === expected && sameLoad(state) && isCurrent()
    fun isCurrent(): Boolean = stillOwned() && sameLoad(captureLoadState())
    /** Short synchronous admission only. Never hold this permission across HTTP,
     * and never recheck the old source AFTER an action legally publishes its child. */
    fun commit(action: () -> Unit): Boolean {
        if (!isCurrent()) return false
        var applied = false
        val admitted = admission {
            if (isCurrent()) { action(); applied = true }
        }
        return admitted && applied
    }
    override fun toString() = "DesktopOriginalInteractiveChoiceSource(sourceVersion=${sourceOwner.sourceVersion})"
}

package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.viewmodel.VideoEngagementUiState
import com.android.purebilibili.feature.video.viewmodel.VideoSubjectSnapshot

internal enum class DesktopWindowsVideoFeedbackKind { LIKE, DISLIKE, SHARE, COIN, TRIPLE }

/** Immutable metadata on the existing presentation, never a source authority. */
internal class DesktopWindowsVideoFeedbackSource(
    val accepted: DesktopOriginalVideoAcceptedPublication,
    val subject: VideoSubjectSnapshot,
) {
    init { require(accepted.request.bvid == subject.bvid && accepted.request.cid == subject.cid) }
}

/** Published together with the original confirmed per-channel state. The
 * presentation owns account/entry/source permission; a finished protocol job is
 * not needed to keep its already-confirmed decoration alive. */
class DesktopWindowsVideoFeedbackOrigin private constructor(
    internal val source: DesktopWindowsVideoFeedbackSource,
    internal val kind: DesktopWindowsVideoFeedbackKind,
    internal val instanceId: Long,
    private val presentation: DesktopOriginalVideoEngagementPresentation,
) {
    internal fun isCurrent(accepted: DesktopOriginalVideoAcceptedPublication, subject: VideoSubjectSnapshot): Boolean =
        source.accepted === accepted && source.subject == subject && presentation.isOwned()

    internal fun admitCurrent(accepted: DesktopOriginalVideoAcceptedPublication, subject: VideoSubjectSnapshot, action: () -> Unit): Boolean {
        if (!isCurrent(accepted, subject)) return false
        var applied = false
        return presentation.admit {
            if (isCurrent(accepted, subject)) { action(); applied = true }
        } && applied
    }

    companion object {
        /** Called only inside the existing VM's confirmed commit/CAS transform. */
        internal fun capture(kind: DesktopWindowsVideoFeedbackKind, instanceId: Long, subject: VideoSubjectSnapshot?): DesktopWindowsVideoFeedbackOrigin? {
            if (instanceId <= 0 || subject == null) return null
            val presentation = DesktopOriginalVideoEngagementPresentation.capture() ?: return null
            val source = presentation.feedbackSource ?: return null
            if (source.subject != subject || !presentation.isOwned()) return null
            return DesktopWindowsVideoFeedbackOrigin(source, kind, instanceId, presentation)
        }
    }
}

/** Read the exact reference published by the original owner, not a projection
 * with a regenerated id or a new receipt at render time. */
internal fun VideoEngagementUiState.desktopFeedbackOrigin(kind: DesktopWindowsVideoFeedbackKind): DesktopWindowsVideoFeedbackOrigin? = when (kind) {
    DesktopWindowsVideoFeedbackKind.LIKE -> desktopLikeFeedbackOrigin.takeIf { likeBurstVisible && it?.instanceId == likeBurstId }
    DesktopWindowsVideoFeedbackKind.TRIPLE -> desktopTripleFeedbackOrigin.takeIf { tripleCelebrationVisible && it?.instanceId == tripleCelebrationId }
    else -> desktopMaidFeedbackOrigin.takeIf { it != null && maidAction?.name == kind.name && it.kind == kind && it.instanceId == maidActionId }
}

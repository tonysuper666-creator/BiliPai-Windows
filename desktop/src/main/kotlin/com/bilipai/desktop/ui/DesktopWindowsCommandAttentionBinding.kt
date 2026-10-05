package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.viewmodel.VideoEngagementUiState
import com.android.purebilibili.feature.video.viewmodel.VideoEngagementViewModel
import com.android.purebilibili.feature.video.viewmodel.VideoSubjectSnapshot
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Original ATTENTION callbacks on the already retained engagement owner.
 * Root supplies the immutable accepted publication and its real Store -> entry
 * presentation permit. This class owns neither HTTP nor a second state cache.
 */
internal class DesktopWindowsCommandAttentionBinding(
    val sourceLease: DesktopOriginalVideoAcceptedPublication,
    private val engagement: VideoEngagementViewModel,
    private val subject: VideoSubjectSnapshot,
    private val stillOwned: () -> Boolean,
    withAdmission: (() -> Unit) -> Boolean,
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    val state: StateFlow<VideoEngagementUiState> get() = engagement.uiState
    private val presentation = DesktopOriginalVideoEngagementPresentation(sourceLease, subject, ::isOwned, withAdmission)

    fun isOwned(): Boolean = alive.get() && stillOwned() &&
        sourceLease.request.bvid == subject.bvid && sourceLease.request.cid == subject.cid &&
        subject.aid > 0L && subject.ownerMid > 0L && state.value.subject == subject

    private fun captureState(): VideoEngagementUiState? {
        var captured: VideoEngagementUiState? = null
        if (!presentation.admit { captured = state.value.takeIf { it.subject == subject } }) return null
        return captured
    }

    fun follow(): Boolean {
        val captured = captureState() ?: return false
        if (captured.isFollowing || !isOwned()) return false
        // Original launch/HTTP is outside admission. The captured context checks
        // again at job start, every original protocol checkpoint and final commit.
        engagement.toggleFollow(subject.ownerMid, currentlyFollowing = false, presentation = presentation)
        return true
    }

    fun triple(): Boolean {
        val captured = captureState() ?: return false
        if (!isOwned()) return false
        engagement.doTripleAction(subject.aid, subject.bvid, captured.isLiked,
            captured.coinCount, captured.isFavorited, presentation = presentation)
        return true
    }

    override fun close() { alive.set(false) }
}

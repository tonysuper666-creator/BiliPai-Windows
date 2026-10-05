package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.feature.video.screen.VideoDetailFollowGroupDialog
import com.android.purebilibili.feature.video.viewmodel.VideoEngagementUiState
import com.android.purebilibili.feature.video.viewmodel.VideoEngagementViewModel
import com.android.purebilibili.feature.video.viewmodel.VideoSubjectSnapshot
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit ordinary actions use the same original engagement owner, including
 * its guest guard, partial triple result, feedback and account/source admission. */
internal class DesktopWindowsVideoEngagementBinding private constructor(
    val sourceOwner: DesktopOriginalVideoAcceptedPublication,
    private val engagement: VideoEngagementViewModel,
    val subject: VideoSubjectSnapshot,
    private val stillOwned: () -> Boolean,
    private val stillFeedbackOwned: () -> Boolean,
    admission: (() -> Unit) -> Boolean,
    private val feedbackSurvivesUiClose: Boolean,
) : AutoCloseable {
    constructor(sourceOwner: DesktopOriginalVideoAcceptedPublication, engagement: VideoEngagementViewModel,
        subject: VideoSubjectSnapshot, stillOwned: () -> Boolean, admission: (() -> Unit) -> Boolean) :
        this(sourceOwner, engagement, subject, stillOwned, stillOwned, admission, false)
    constructor(sourceOwner: DesktopOriginalVideoAcceptedPublication, engagement: VideoEngagementViewModel,
        subject: VideoSubjectSnapshot, stillOwned: () -> Boolean, stillFeedbackOwned: () -> Boolean,
        admission: (() -> Unit) -> Boolean) :
        this(sourceOwner, engagement, subject, stillOwned, stillFeedbackOwned, admission, true)
    private val alive = AtomicBoolean(true)
    val state: StateFlow<VideoEngagementUiState> get() = engagement.uiState
    private val presentation = DesktopOriginalVideoEngagementPresentation(sourceOwner, subject, ::isOwned, admission,
        ::feedbackLifetimeOwned, admission)
    private fun feedbackLifetimeOwned(): Boolean = (feedbackSurvivesUiClose || alive.get()) && stillFeedbackOwned() && subject.aid > 0L && subject.ownerMid > 0L &&
        sourceOwner.request.bvid == subject.bvid && sourceOwner.request.cid == subject.cid && state.value.subject == subject
    fun isFeedbackOwned(): Boolean = alive.get() && presentation.isFeedbackOwned()

    fun isOwned(): Boolean = alive.get() && stillOwned() && subject.aid > 0L && subject.ownerMid > 0L &&
        sourceOwner.request.bvid == subject.bvid && sourceOwner.request.cid == subject.cid && state.value.subject == subject
    private fun capture(): VideoEngagementUiState? {
        var value: VideoEngagementUiState? = null
        if (!presentation.admit { value = state.value.takeIf { it.subject == subject } }) return null
        return value
    }
    fun toggleFollow(): Boolean {
        val value = capture() ?: return false
        if (!isOwned()) return false
        engagement.toggleFollow(subject.ownerMid, value.isFollowing, presentation = presentation)
        return true
    }
    fun triple(): Boolean {
        val value = capture() ?: return false
        if (!isOwned()) return false
        engagement.doTripleAction(subject.aid, subject.bvid, value.isLiked, value.coinCount,
            value.isFavorited, presentation = presentation)
        return true
    }

    /** Original ordinary protocol work stays outside the captured permission. */
    fun like(): Boolean {
        val value = capture() ?: return false
        if (!isOwned()) return false
        engagement.toggleLikeWithDesktopPresentation(subject.aid, subject.bvid,
            value.isLiked, presentation = presentation)
        return true
    }
    fun coin(count: Int, alsoLike: Boolean): Boolean {
        capture() ?: return false
        if (!isOwned()) return false
        engagement.doCoinWithDesktopPresentation(count, alsoLike, presentation = presentation)
        return true
    }
    fun feedback(kind: DesktopWindowsVideoFeedbackKind): DesktopWindowsVideoFeedbackOrigin? {
        var selected: DesktopWindowsVideoFeedbackOrigin? = null
        presentation.admitFeedback {
            val expected = state.value.desktopFeedbackOrigin(kind)
            if (isFeedbackOwned() && expected != null) expected.admitCurrent(sourceOwner, subject) {
                if (state.value.desktopFeedbackOrigin(kind) === expected) selected = expected
            }
        }
        return selected
    }

    /** No native/window work inside this existing Store -> entry permit. Only
     * the original VM's matching instance can complete or be dismissed. */
    fun completeFeedback(expected: DesktopWindowsVideoFeedbackOrigin): Boolean = consumeFeedback(expected, completed = true)
    fun cancelFeedback(expected: DesktopWindowsVideoFeedbackOrigin): Boolean = consumeFeedback(expected, completed = false)
    private fun consumeFeedback(expected: DesktopWindowsVideoFeedbackOrigin, completed: Boolean): Boolean {
        var applied = false
        val admitted = presentation.admitFeedback {
            if (isFeedbackOwned() && state.value.desktopFeedbackOrigin(expected.kind) === expected)
                expected.admitCurrent(sourceOwner, subject) {
                if (state.value.desktopFeedbackOrigin(expected.kind) !== expected) return@admitCurrent
                when (expected.kind) {
                    DesktopWindowsVideoFeedbackKind.LIKE -> engagement.dismissLikeBurst(expected.instanceId)
                    DesktopWindowsVideoFeedbackKind.TRIPLE -> if (completed)
                        engagement.completeTripleCelebration(expected.instanceId)
                    else engagement.cancelTripleCelebration(expected.instanceId)
                    else -> engagement.dismissMaidAction(expected.instanceId)
                }
                applied = true
            }
        }
        return admitted && applied
    }
    fun admit(action: () -> Unit): Boolean = presentation.admit(action)
    fun admitFeedback(action: () -> Unit): Boolean = presentation.admitFeedback { if (isFeedbackOwned()) action() } && isFeedbackOwned()
    override fun close() { alive.set(false); presentation.retireFeedbackIfInvalid() }
}

/** Full original group dialog, inside the existing fixed-size owned native
 * window. Its inner Compose alert has no heavyweight Canvas below it. */
@Composable
internal fun DesktopWindowsVideoFollowGroupSection(
    assembly: DesktopOriginalVideoOwnerAssembly,
    binding: DesktopWindowsVideoEngagementBinding?,
) {
    val request by assembly.playback.desktopFollowGroupRequest.collectAsState()
    val visible by assembly.playback.followGroupDialogVisible.collectAsState()
    val saving by assembly.playback.isSavingFollowGroups.collectAsState()
    val captured = request ?: return
    if (binding == null || captured.sourceOwner !== binding.sourceOwner ||
        captured.targetMid != binding.subject.ownerMid || !visible || !captured.isOwned() || !binding.isOwned()) return
    key(captured) {
        val latestSaving by rememberUpdatedState(saving)
        // Retire only this exact dialog. Old disposal cannot clear a successor.
        DisposableEffect(captured) {
            onDispose {
                captured.dispatch { assembly.playback.dismissFollowGroupDialog() }
                captured.close()
            }
        }
        val dismiss = {
            if (!latestSaving && binding.admit {})
                captured.dispatch { assembly.playback.dismissFollowGroupDialog() }
            Unit
        }
        DesktopWindowsPlayerDialog("设置关注分组", dismiss, preferredHeightDp = 520) {
            VideoDetailFollowGroupDialog(assembly.playback) { action ->
                // VM methods perform the final exact-request commit themselves.
                // Dispatch can launch IO, so it stays outside binding admission.
                binding.admit {} && captured.dispatch(action)
            }
        }
    }
}

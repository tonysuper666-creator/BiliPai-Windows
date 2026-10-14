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
    private val admission: (() -> Unit) -> Boolean,
    private val feedbackSurvivesUiClose: Boolean,
) : AutoCloseable {
    constructor(sourceOwner: DesktopOriginalVideoAcceptedPublication, engagement: VideoEngagementViewModel,
        subject: VideoSubjectSnapshot, stillOwned: () -> Boolean, admission: (() -> Unit) -> Boolean) :
        this(sourceOwner, engagement, subject, stillOwned, stillOwned, admission, false)
    constructor(sourceOwner: DesktopOriginalVideoAcceptedPublication, engagement: VideoEngagementViewModel,
        subject: VideoSubjectSnapshot, stillOwned: () -> Boolean, stillFeedbackOwned: () -> Boolean,
        admission: (() -> Unit) -> Boolean) :
        this(sourceOwner, engagement, subject, stillOwned, stillFeedbackOwned, admission, true)
    fun mountBrandFeedback(events: com.android.purebilibili.core.events.BrandSuccessEvents) {
        presentation.mountBrandFeedback(events)
    }
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

    /** Create no client or model copy: this request only scopes the original dialog. */
    fun openCoinDialog(): Boolean {
        val value = capture() ?: return false
        if (!isOwned() || value.coinCount >= value.coinLimit) return false
        val request = DesktopWindowsVideoCoinDialogRequest(sourceOwner, subject, presentation, admission)
        try {
            engagement.openCoinDialogWithDesktopRequest(request)
        } finally {
            if (state.value.desktopCoinDialogRequest !== request) request.close()
        }
        return state.value.desktopCoinDialogRequest === request && state.value.coinDialogVisible && request.isOwned()
    }
    fun dismissCoinDialog(request: DesktopWindowsVideoCoinDialogRequest): Boolean {
        if (request.sourceOwner !== sourceOwner || request.subject != subject) return false
        var dismissed = false
        return request.admit {
            val current = state.value
            if (current.desktopCoinDialogRequest === request && current.coinDialogVisible &&
                current.subject == subject) {
                engagement.setCoinDialogVisible(false)
                dismissed = true
            }
        } && dismissed
    }
    fun coin(request: DesktopWindowsVideoCoinDialogRequest, count: Int, alsoLike: Boolean): Boolean {
        val value = capture() ?: return false
        if (!isOwned() || request.sourceOwner !== sourceOwner || request.subject != subject ||
            value.desktopCoinDialogRequest !== request || !value.coinDialogVisible ||
            !request.isOwned() || count !in 1..(value.coinLimit - value.coinCount).coerceIn(0, value.coinLimit)) return false
        // The original VM consumes this exact request at final admission. Its API
        // and confirmed feedback retain the existing presentation after UI close.
        engagement.doCoinWithDesktopPresentation(count, alsoLike, presentation = presentation, request = request)
        // The original model can reject "also like" while its state is unknown.
        // Report acceptance only after its synchronous admission consumed this request.
        return state.value.desktopCoinDialogRequest !== request && !request.isOwned()
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

/** Public shell because the original UI state is public; all authority stays internal.
 * Identity is the generation. No sourceVersion equivalence, API or independent state owner. */
class DesktopWindowsVideoCoinDialogRequest internal constructor(
    internal val sourceOwner: DesktopOriginalVideoAcceptedPublication,
    internal val subject: VideoSubjectSnapshot,
    private val presentation: DesktopOriginalVideoEngagementPresentation,
    private val ownerAdmission: (() -> Unit) -> Boolean,
) : AutoCloseable {
    private val retired = AtomicBoolean(false)
    internal val context get() = presentation.context
    internal fun isOwned(): Boolean = !retired.get() && presentation.isOwned()
    internal fun matchesPresentation(expected: DesktopOriginalVideoEngagementPresentation?): Boolean =
        presentation === expected
    internal fun admit(action: () -> Unit): Boolean {
        if (!isOwned()) return false
        var applied = false
        return presentation.admit {
            if (isOwned()) { action(); applied = true }
        } && applied
    }
    /** Caller already holds existing Store -> entry admission; do not acquire a new lock. */
    internal fun retireUnderAdmission() { retired.set(true) }
    override fun close() {
        // Even when a UI binding has closed, serialize retirement with a currently
        // admitted publication through the existing owner gate. A retired source
        // rejects that gate and cannot publish, so atomic retirement then suffices.
        if (!ownerAdmission { retireUnderAdmission() }) retireUnderAdmission()
    }
}

/** Whole fixed original UI in the existing owned native dialog. */
@Composable
internal fun DesktopWindowsVideoCoinSection(binding: DesktopWindowsVideoEngagementBinding?) {
    val currentBinding = binding ?: return
    val state by currentBinding.state.collectAsState()
    val request = state.desktopCoinDialogRequest ?: return
    if (!state.coinDialogVisible || request.sourceOwner !== currentBinding.sourceOwner ||
        request.subject != currentBinding.subject || state.subject != currentBinding.subject ||
        !currentBinding.isOwned() || !request.isOwned()) return
    key(request) {
        DisposableEffect(request) {
            // An old disposal retires only its own token, never clears a successor.
            onDispose { request.close() }
        }
        val dismiss = { currentBinding.dismissCoinDialog(request); Unit }
        DesktopWindowsPlayerDialog("投币", dismiss, preferredHeightDp = 390,
            presentationVisible = request.isOwned()) {
            com.android.purebilibili.feature.video.ui.components.CoinDialog(
                visible = state.coinDialogVisible,
                currentCoinCount = state.coinCount,
                userBalance = state.userCoinBalance,
                maxCoins = state.coinLimit,
                onDismiss = dismiss,
                onConfirm = { count, alsoLike -> currentBinding.coin(request, count, alsoLike); Unit },
            )
        }
    }
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

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
internal class DesktopWindowsVideoEngagementBinding(
    val sourceOwner: DesktopOriginalVideoAcceptedPublication,
    private val engagement: VideoEngagementViewModel,
    val subject: VideoSubjectSnapshot,
    private val stillOwned: () -> Boolean,
    admission: (() -> Unit) -> Boolean,
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    val state: StateFlow<VideoEngagementUiState> get() = engagement.uiState
    private val presentation = DesktopOriginalVideoEngagementPresentation(::isOwned, admission)

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
    fun admit(action: () -> Unit): Boolean = presentation.admit(action)
    override fun close() { alive.set(false) }
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

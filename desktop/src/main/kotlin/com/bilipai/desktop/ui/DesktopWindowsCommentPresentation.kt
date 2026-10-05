package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.core.ui.AppContentDialogLayoutPolicy
import com.android.purebilibili.core.ui.resolveAppCompactContentDialogLayoutPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Window
import kotlin.coroutines.coroutineContext

/** UI lifetime only. The required admission remains the existing factory's
 * account/entry/full accepted-source permit; this object creates no source owner. */
internal class DesktopWindowsCommentPresentation(
    val sourceLease: Any,
    nativeOwner: Window?,
    private val current: () -> Boolean,
    private val admission: (() -> Unit) -> Boolean,
) : AutoCloseable {
    private val active = MutableStateFlow(true)
    private var owner: Window? = nativeOwner
    val nativeOwner: Window? get() = owner
    val alive: StateFlow<Boolean> = active
    fun isCurrent(): Boolean = active.value && current()
    fun canPresentNative(): Boolean = isCurrent() && owner?.isShowing == true
    fun dispatch(action: () -> Unit): Boolean {
        if (!isCurrent()) return false
        var applied = false
        return admission { if (isCurrent()) { action(); applied = true } } && applied
    }
    fun allowsEffect(): Boolean = dispatch {}
    override fun close() { active.value = false; owner = null }
    suspend fun <T> awaitEffect(action: suspend () -> T): T {
        coroutineContext.ensureActive()
        if (!allowsEffect()) throw CancellationException("Comment presentation retired")
        val result = action() // IO never runs under the presentation admission.
        coroutineContext.ensureActive()
        if (!allowsEffect()) throw CancellationException("Comment presentation retired")
        return result
    }
}

/** Null retains all legacy/dynamic presentation behavior. Ordinary video
 * supplies the exact captured native parent and source-bound UI lease. */
internal val LocalDesktopWindowsCommentPresentation =
    staticCompositionLocalOf<DesktopWindowsCommentPresentation?> { null }

@Composable
internal fun DesktopWindowsCommentAlertDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    shape: Shape? = null,
    containerColor: Color? = null,
    tonalElevation: Dp? = null,
    properties: DialogProperties = DialogProperties(),
    contentLayout: AppContentDialogLayoutPolicy = resolveAppCompactContentDialogLayoutPolicy(),
) {
    val presentation = LocalDesktopWindowsCommentPresentation.current
    val original: @Composable () -> Unit = {
        AppAlertDialog(onDismissRequest, modifier, icon, title, text, confirmButton,
            dismissButton, shape, containerColor, tonalElevation, properties, contentLayout)
    }
    if (presentation == null) original()
    else if (presentation.canPresentNative()) {
        CompositionLocalProvider(LocalDesktopWindowsPlayerWindow provides presentation.nativeOwner) {
            DesktopWindowsPlayerDialog("评论操作", { presentation.dispatch(onDismissRequest) },
                dismissOnEscape = properties.dismissOnBackPress, preferredHeightDp = 520,
                content = original)
        }
    }
}

@Composable
internal fun DesktopWindowsCommentTextSelectionSheet(
    onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    DesktopWindowsCommentAlertDialog(onDismissRequest = onDismissRequest,
        text = { Column(content = content) }, confirmButton = {})
}

/** Called only by the sole original ImagePreviewOverlayHost. Its original
 * complete content/pager is unchanged and request/native parent is captured. */
@Composable
internal fun DesktopWindowsCommentImagePreviewWindow(
    presentation: DesktopWindowsCommentPresentation?,
    onDismissRequest: () -> Unit,
    properties: DialogProperties,
    content: @Composable () -> Unit,
) {
    if (presentation == null) Dialog(onDismissRequest, properties = properties, content = content)
    else if (presentation.canPresentNative()) {
        CompositionLocalProvider(LocalDesktopWindowsPlayerWindow provides presentation.nativeOwner) {
            DesktopWindowsPlayerDialog("评论图片", { presentation.dispatch(onDismissRequest) },
                dismissOnEscape = properties.dismissOnBackPress, content = content)
        }
    }
}

/** These decorators narrow new UI effects without changing the original
 * already-dispatched same-AID comment mutation protocol or its request owner. */
internal fun desktopWindowsCommentPlatform(
    base: DesktopCommentPlatform, presentation: DesktopWindowsCommentPresentation,
): DesktopCommentPlatform = object : DesktopCommentPlatform by base {
    override fun isOwned() = base.isOwned() && presentation.isCurrent()
    override fun pickCommentImages(maxItems: Int, onSelected: (List<String>) -> Unit) {
        if (presentation.allowsEffect() && base.isOwned()) base.pickCommentImages(maxItems) {
            presentation.dispatch { if (base.isOwned()) onSelected(it) }
        }
    }
    override fun showFeedback(message: String) {
        if (presentation.allowsEffect() && base.isOwned()) base.showFeedback(message)
    }
    override fun copyText(text: String, label: String) {
        if (presentation.allowsEffect() && base.isOwned()) base.copyText(text, label)
    }
    override fun shareText(text: String, title: String) {
        if (presentation.allowsEffect() && base.isOwned()) base.shareText(text, title)
    }
    override suspend fun videoTitle(bvid: String) = presentation.awaitEffect { base.videoTitle(bvid) }
    override suspend fun translateReply(type: Long, oid: Long, rpid: Long) =
        presentation.awaitEffect { base.translateReply(type, oid, rpid) }
    override suspend fun blockUser(mid: Long, name: String, face: String,
        relationSource: com.android.purebilibili.data.repository.BlockedUpRelationSource) =
        presentation.awaitEffect { base.blockUser(mid, name, face, relationSource) }
    override suspend fun saveCommentImage(spec: com.android.purebilibili.feature.video.ui.components.ReplyCommentImageSpec) =
        presentation.awaitEffect { base.saveCommentImage(spec) }
}

internal fun desktopWindowsCommentGallery(
    base: DesktopDynamicCardPlatform, presentation: DesktopWindowsCommentPresentation,
): DesktopDynamicCardPlatform = object : DesktopDynamicCardPlatform by base {
    override fun isOwned() = base.isOwned() && presentation.isCurrent()
    override fun copyText(text: String) { if (presentation.allowsEffect() && base.isOwned()) base.copyText(text) }
    override fun shareText(text: String) { if (presentation.allowsEffect() && base.isOwned()) base.shareText(text) }
    override fun showFeedback(message: String) { if (presentation.allowsEffect() && base.isOwned()) base.showFeedback(message) }
    override fun openLink(url: String) { if (presentation.allowsEffect() && base.isOwned()) base.openLink(url) }
    override suspend fun searchUp(name: String) = presentation.awaitEffect { base.searchUp(name) }
    override suspend fun getVoteInfo(voteId: Long) = presentation.awaitEffect { base.getVoteInfo(voteId) }
    override suspend fun submitVote(voteId: Long, optionIndexes: List<Int>, dynamicId: String) =
        presentation.awaitEffect { base.submitVote(voteId, optionIndexes, dynamicId) }
    override suspend fun saveImage(url: String) = presentation.awaitEffect { base.saveImage(url) }
    override suspend fun saveImages(urls: List<String>) = presentation.awaitEffect { base.saveImages(urls) }
    override suspend fun saveMotionPhoto(imageUrl: String, videoUrl: String) = presentation.awaitEffect { base.saveMotionPhoto(imageUrl, videoUrl) }
    override suspend fun saveLivePhotoVideo(videoUrl: String) = presentation.awaitEffect { base.saveLivePhotoVideo(videoUrl) }
    override suspend fun shareImage(url: String) = presentation.awaitEffect { base.shareImage(url) }
    override suspend fun getShareTargets(size: Int) = presentation.awaitEffect { base.getShareTargets(size) }
    override suspend fun getMessageSessions(size: Int) = presentation.awaitEffect { base.getMessageSessions(size) }
    override suspend fun fetchMessageUserInfo(mid: Long) = presentation.awaitEffect { base.fetchMessageUserInfo(mid) }
    override suspend fun sendDynamicShare(receiverId: Long, content: String) = presentation.awaitEffect { base.sendDynamicShare(receiverId, content) }
}

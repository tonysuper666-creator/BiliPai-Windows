// GENERATED from app/src/main/java/com/android/purebilibili/feature/video/ui/components/SubReplySheet.kt; do not edit.
// LF-normalized SHA-256: 67d3e57c804671f100f3294048296e87aafd287035294aa455df5e6eca9934bc
package com.bilipai.desktop.ui
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicReplySession
import com.android.purebilibili.feature.dynamic.components.*
import com.android.purebilibili.feature.video.ui.components.SubReplyDetailContent
import com.android.purebilibili.feature.video.viewmodel.*
import kotlinx.coroutines.ensureActive

/** Complete original thread contents. Android predictive-back/window dragging
 * is deliberately a separate native-container integration, not fake progress. */
@Composable internal fun DesktopOriginalDynamicThreadContent(
    session: DesktopOriginalDynamicReplySession,
    currentMid: Long,
    onUserClick: (Long) -> Unit,
    onImagePreview: ((List<String>, Int, ImagePreviewSourceAnchor?, ImagePreviewTextContent?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val platform = LocalDesktopCommentBindings.current
    val state by session.subReplyState.collectAsState()
    val rootReply = state.rootReply ?: return
    if (!state.visible || !session.isOwned() || !platform.isOwned()) return
    val catalog = platform.emotes
    val key = catalog.currentSessionKey()
    var emoteMap by remember(key) { mutableStateOf(catalog.snapshot()) }
    LaunchedEffect(key) {
        val loaded = catalog.ensureLoaded()
        ensureActive()
        if (session.isOwned() && platform.isOwned()) emoteMap = loaded
    }
    val showUpFlag = false
    val onLoadMore = session::loadMoreSubReplies
    val onSortModeChange = session::setSubReplySortMode
    val onDismiss = session::closeSubReply
    val onRootCommentClick: (() -> Unit)? = null
    val onTimestampClick: ((Long) -> Unit)? = null
    val onReplyClick: ((ReplyItem) -> Unit)? = { session.closeSubReply(); session.startCommentReply(it) }
    val onDissolveStart: ((Long) -> Unit)? = session::startSubDissolve
    val onDeleteComment: ((Long) -> Unit)? = { session.deleteDynamicComment(it) { _, message -> if (platform.isOwned()) platform.showFeedback(message) } }
    val onCommentLike: ((Long) -> Unit)? = { session.likeComment(it) }
    val likedComments: Set<Long> = emptySet()
    val onCommentHate: ((Long) -> Unit)? = { session.hateComment(it) }
    val hatedComments: Set<Long> = emptySet()
    val onUrlClick: ((String) -> Unit)? = null
    val showIdentityDecorations = true
    val onAvatarClick: ((String) -> Unit)? = { it.toLongOrNull()?.let(onUserClick) }
SubReplyDetailContent(
    headerDragModifier = Modifier,
    rootReply = rootReply,
    subReplies = state.items,
    sortMode = state.sortMode,
    error = state.error,
    onSortModeChange = onSortModeChange,
    remoteReplyCount = state.totalCount,
    isLoading = state.isLoading,
    isEnd = state.isEnd,
    emoteMap = emoteMap,
    onLoadMore = onLoadMore,
    onDismiss = onDismiss,
    onRootCommentClick = onRootCommentClick,
    onTimestampClick = onTimestampClick,
    upMid = state.upMid,
    showUpFlag = showUpFlag,
    onImagePreview = onImagePreview,
    onReplyClick = onReplyClick,
    // [新增] 消散动画相关
    dissolvingIds = state.dissolvingIds,
    currentMid = currentMid,
    onDissolveStart = onDissolveStart,
    onDeleteComment = onDeleteComment,
    onCommentLike = onCommentLike,
    likedComments = likedComments,
    onCommentHate = onCommentHate,
    hatedComments = hatedComments,
    onUrlClick = onUrlClick,
    showIdentityDecorations = showIdentityDecorations,
    onAvatarClick = onAvatarClick,
    targetReplyId = state.targetReplyId,
    modifier = modifier
)
}

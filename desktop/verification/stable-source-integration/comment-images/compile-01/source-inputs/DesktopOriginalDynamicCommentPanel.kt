// GENERATED from app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicDetailScreen.kt; do not edit.
// LF-normalized SHA-256: ab0d6cf956ba9915445f421f299ea48e14a7561edd2a609973700531bf0f67b4
package com.bilipai.desktop.ui
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.android.purebilibili.core.theme.LocalAppUiStyle
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicReplySession
import com.android.purebilibili.feature.dynamic.components.*
import kotlinx.coroutines.ensureActive

/** Source-selected raw comment contents/composer; no Root card/items/cache or
 * account is constructed. The caller supplies a finite viewport and opens the
 * separately exposed thread content in its actual native container. */
@Composable internal fun DesktopOriginalDynamicCommentPanel(
    item: DynamicItem,
    session: DesktopOriginalDynamicReplySession,
    currentMid: Long?,
    onUserClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val platform = LocalDesktopCommentBindings.current
    if (!platform.isOwned() || !session.isOwned()) return
    val comments by session.comments.collectAsState()
    val commentsLoading by session.commentsLoading.collectAsState()
    val commentsLoadingMore by session.commentsLoadingMore.collectAsState()
    val commentTotalCount by session.commentTotalCount.collectAsState()
    val commentSortMode by session.dynamicCommentSortMode.collectAsState()
    val commentReplyTarget by session.commentReplyTarget.collectAsState()
    var showImagePreview by remember { mutableStateOf(false) }
    var previewImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var previewInitialIndex by remember { mutableIntStateOf(0) }
    var previewSourceRect by remember { mutableStateOf<ImagePreviewSourceAnchor?>(null) }
    var previewTextContent by remember { mutableStateOf<ImagePreviewTextContent?>(null) }
val commentContent: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
    item(key = "dynamic_detail_comment_header") {
        DynamicInlineCommentHeader(
            totalCount = commentTotalCount,
            sortMode = commentSortMode,
            onSortModeChange = session::setDynamicCommentSortMode,
        )
    }
    dynamicInlineCommentItems(
        comments = comments,
        isLoading = commentsLoading,
        isLoadingMore = commentsLoadingMore,
        onViewReplies = { reply -> session.openSubReply(reply) },
        onReply = { reply -> session.startCommentReply(reply) },
        onLike = { reply -> session.likeComment(reply.rpid) },
        onHate = { reply ->
            session.hateComment(reply.rpid) { _, message ->
                if (platform.isOwned()) platform.showFeedback(message)
            }
        },
        dynamicAuthorMid = item.modules.module_author?.mid ?: 0L,
        currentUserMid = currentMid,
        onDelete = { reply ->
            session.deleteDynamicComment(reply.rpid) { _, message ->
                if (platform.isOwned()) platform.showFeedback(message)
            }
        },
        onToggleTop = { reply ->
            session.toggleDynamicCommentTop(reply) { _, message ->
                if (platform.isOwned()) platform.showFeedback(message)
            }
        },
        onReport = { reply, reason ->
            session.reportDynamicComment(reply.rpid, reason) { _, message ->
                if (platform.isOwned()) platform.showFeedback(message)
            }
        },
        onUserClick = onUserClick,
        onImagePreview = { images, index, sourceRect, textContent ->
            previewImages = images
            previewInitialIndex = index
            previewSourceRect = sourceRect
            previewTextContent = textContent
            showImagePreview = true
        },
    )
}
val commentComposer: @Composable (Modifier) -> Unit = { modifier ->
    DynamicInlineCommentComposer(
        onPostComment = { message, images, onResult ->
            session.postComment(item.id_str, message, images) { success, toastMessage ->
                if (platform.isOwned()) platform.showFeedback(toastMessage)
                onResult(success)
            }
        },
        replyTargetUname = commentReplyTarget?.uname,
        onClearReplyTarget = session::clearCommentReplyTarget,
        liquidGlassEnabled = false,
        backdrop = null,
        modifier = modifier,
    )
}

    Column(modifier = modifier) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) { commentContent() }
        AppHorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        AppSurface(modifier = Modifier.fillMaxWidth(),
            color = resolveDesktopOriginalReplyBottomBarColor(LocalAppUiStyle.current, MaterialTheme.colorScheme),
            tonalElevation = 0.dp, shadowElevation = 0.dp) {
            commentComposer(Modifier.fillMaxWidth().padding(horizontal = AppSpacingTokens.Large, vertical = AppSpacingTokens.Medium))
        }
    }
    if (showImagePreview && previewImages.isNotEmpty()) {
        ImagePreviewDialog(images = previewImages, initialIndex = previewInitialIndex,
            sourceRect = previewSourceRect?.rect, sourceRects = previewSourceRect?.galleryRects.orEmpty(),
            sourceCornerRadiusDp = previewSourceRect?.cornerRadiusDp ?: AppShapes.containerCornerDp(ContainerLevel.Field).value,
            textContent = previewTextContent, onDismiss = { showImagePreview = false; previewTextContent = null })
    }
}
internal fun resolveDesktopOriginalReplyBottomBarColor(
    uiStyle: AppUiStyle,
    colorScheme: ColorScheme,
): Color = when (uiStyle) {
    AppUiStyle.MIUIX -> colorScheme.background
    AppUiStyle.MATERIAL3 -> colorScheme.surface
}

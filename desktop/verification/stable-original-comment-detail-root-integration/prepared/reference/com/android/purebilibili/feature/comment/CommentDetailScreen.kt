package com.android.purebilibili.feature.comment

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.bilipai.desktop.ui.LocalDesktopCommentBindings
import com.android.purebilibili.feature.video.viewmodel.DesktopVideoCommentRequests
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.AdaptiveLoadingIndicator
import com.android.purebilibili.core.ui.AppTopBar
import com.android.purebilibili.core.ui.ImmersiveAppScaffold as AppScaffold
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppIconButton
import com.android.purebilibili.core.ui.rememberAppBackIcon
import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.repository.resolveCommentFraudLightMessage
import com.android.purebilibili.data.repository.shouldShowCommentFraudResultDialog
import com.android.purebilibili.feature.video.ui.components.CommentFraudResultDialog
import com.android.purebilibili.feature.dynamic.components.ImagePreviewDialog
import com.android.purebilibili.feature.dynamic.components.ImagePreviewSourceAnchor
import com.android.purebilibili.feature.dynamic.components.ImagePreviewTextContent
import com.android.purebilibili.feature.message.feed.MessageFeedError
import com.android.purebilibili.feature.video.ui.components.CommentInputDialog
import com.android.purebilibili.feature.video.ui.components.SubReplyDetailContent
import com.android.purebilibili.feature.video.ui.components.rememberVideoCommentAppearance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommentDetailScreen(
    oid: Long,
    rootId: Long,
    targetId: Long = 0L,
    type: Int = 1,
    enterUri: String = "",
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    viewModel: CommentDetailViewModel,
    requests: DesktopVideoCommentRequests,
    isCurrentPage: Boolean = true,
    admitUiAction: ((() -> Unit) -> Boolean) = { action -> action(); true },
) {
    fun uiAction(action: () -> Unit) { if (isCurrentPage) admitUiAction(action) }
    val subReplyState by viewModel.subReplyState.collectAsState()
    val likedComments by viewModel.likedComments.collectAsState()
    val hatedComments by viewModel.hatedComments.collectAsState()
    val showCommentInput by viewModel.showCommentInput.collectAsState()
    val replyingTo by viewModel.replyingTo.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val fraudResult by viewModel.fraudResult.collectAsState()
    val platform = LocalDesktopCommentBindings.current

    LaunchedEffect(fraudResult, isCurrentPage) {
        if (!isCurrentPage) return@LaunchedEffect
        val result = fraudResult ?: return@LaunchedEffect
        resolveCommentFraudLightMessage(result.status)?.let { message -> uiAction {
            platform.showFeedback(message)
            viewModel.dismissFraudResult()
        } }
    }

    fraudResult?.let { result ->
        if (isCurrentPage && shouldShowCommentFraudResultDialog(result.status)) {
            CommentFraudResultDialog(
                status = result.status,
                onDismiss = { uiAction(viewModel::dismissFraudResult) },
                onDeleteComment = if (result.status == CommentFraudStatus.SHADOW_BANNED) {
                    { uiAction { viewModel.startDissolve(result.rpid) } }
                } else null
            )
        }
    }

    var previewImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var previewInitialIndex by remember { mutableIntStateOf(0) }
    var previewSourceRect by remember { mutableStateOf<ImagePreviewSourceAnchor?>(null) }
    var previewTextContent by remember { mutableStateOf<ImagePreviewTextContent?>(null) }
    var showImagePreview by remember { mutableStateOf(false) }

    val currentMid = requests.currentMid()
    val appearance = rememberVideoCommentAppearance()

    LaunchedEffect(oid, rootId, type, targetId) {
        viewModel.loadInitial(
            oid = oid,
            rootId = rootId,
            type = type,
            targetReplyId = targetId
        )
    }

    com.android.purebilibili.core.ui.LocalNavigationBackHandler(enabled = isCurrentPage) {
        if (subReplyState.conversationAnchor != null) {
            uiAction(viewModel::closeConversation)
        } else {
            onBack()
        }
    }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = "评论详情",
                navigationIcon = {
                    AppIconButton(onClick = onBack) {
                        AppIcon(rememberAppBackIcon(), contentDescription = "返回")
                    }
                },
                actions = {
                    if (enterUri.isNotBlank()) {
                        AppIconButton(
                            onClick = { onOpenLink(enterUri) }
                        ) {
                            AppIcon(
                                imageVector = Icons.Outlined.OpenInBrowser,
                                contentDescription = "前往"
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .background(appearance.panelColor)
        ) {
            val rootReply = subReplyState.rootReply
            when {
                subReplyState.isLoading && rootReply == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        AdaptiveLoadingIndicator()
                    }
                }
                subReplyState.error != null && rootReply == null -> {
                    MessageFeedError(
                        text = subReplyState.error ?: "加载失败",
                        onRetry = {
                            uiAction { viewModel.loadInitial(
                                oid = oid,
                                rootId = rootId,
                                type = type,
                                targetReplyId = targetId
                            ) }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                rootReply != null -> {
                    SubReplyDetailContent(
                        rootReply = rootReply,
                        subReplies = subReplyState.items,
                        sortMode = subReplyState.sortMode,
                        error = subReplyState.error,
                        isLoading = subReplyState.isLoading,
                        isEnd = subReplyState.isEnd,
                        emoteMap = emptyMap(),
                        onLoadMore = { uiAction(viewModel::loadMore) },
                        onSortModeChange = { mode -> uiAction { viewModel.setSortMode(mode) } },
                        onDismiss = onBack,
                        applyStatusBarPadding = false,
                        onRootCommentClick = { uiAction { viewModel.showReplyInput(rootReply) } },
                        onReplyClick = { reply -> uiAction { viewModel.showReplyInput(reply) } },
                        onConversationClick = { reply -> uiAction { viewModel.openConversation(reply) } },
                        onConversationBack = { uiAction(viewModel::closeConversation) },
                        isConversationMode = subReplyState.conversationAnchor != null,
                        dissolvingIds = subReplyState.dissolvingIds,
                        currentMid = currentMid,
                        onDissolveStart = { id -> uiAction { viewModel.startDissolve(id) } },
                        onDeleteComment = { id -> uiAction { viewModel.deleteComment(id) } },
                        onCheckCommentFraud = if (type == 1) { reply -> uiAction { viewModel.checkCommentFraud(reply) } } else null,
                        onCommentLike = { id -> uiAction { viewModel.likeComment(id) } },
                        onCommentHate = { id -> uiAction { viewModel.hateComment(id) } },
                        likedComments = likedComments,
                        hatedComments = hatedComments,
                        onUrlClick = onOpenLink,
                        showIdentityDecorations = true,
                        onAvatarClick = { midStr ->
                            midStr.toLongOrNull()?.let(onUserClick)
                        },
                        remoteReplyCount = subReplyState.totalCount,
                        targetReplyId = targetId,
                        onImagePreview = { images, index, rect, textContent -> uiAction {
                            previewImages = images
                            previewInitialIndex = index
                            previewSourceRect = rect
                            previewTextContent = textContent
                            showImagePreview = true
                        } },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            if (isCurrentPage && showImagePreview && previewImages.isNotEmpty()) {
                ImagePreviewDialog(
                    images = previewImages,
                    initialIndex = previewInitialIndex,
                    sourceRect = previewSourceRect?.rect,
                    sourceRects = previewSourceRect?.galleryRects.orEmpty(),
                    sourceCornerRadiusDp = previewSourceRect?.cornerRadiusDp
                        ?: AppShapes.containerCornerDp(ContainerLevel.Field).value,
                    textContent = previewTextContent,
                    onDismiss = { uiAction {
                        showImagePreview = false
                        previewTextContent = null
                    } }
                )
            }

            CommentInputDialog(
                visible = isCurrentPage && showCommentInput,
                onDismiss = { uiAction(viewModel::hideReplyInput) },
                onSend = { text, uris, sync ->
                    uiAction { viewModel.sendReply(text, uris, sync) }
                },
                isSending = isSending,
                replyToName = replyingTo?.member?.uname
            )
        }
    }
}

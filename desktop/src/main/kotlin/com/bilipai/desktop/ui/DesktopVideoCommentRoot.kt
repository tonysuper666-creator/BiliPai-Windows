package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.DesktopOriginalReplySettings
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.comment.CommentDetailScreen
import com.android.purebilibili.feature.comment.CommentDetailViewModel
import com.android.purebilibili.feature.dynamic.components.ImagePreviewSourceAnchor
import com.android.purebilibili.feature.dynamic.components.ImagePreviewTextContent
import com.android.purebilibili.feature.video.screen.*
import com.android.purebilibili.feature.video.ui.components.*
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.settings.LocalDesktopDynamicTimelinePreferences
import kotlinx.coroutines.*

private data class DesktopVideoCommentDetailRoute(val root: Long, val target: Long)

/** Full original raw video comments and composer, bound to the existing playback page owner. */
@Composable internal fun DesktopVideoCommentRootHost(
    info: ViewInfo,
    videoTags: List<VideoTag>,
    repository: DesktopRepository,
    community: DesktopCommunityRepository,
    fraud: DesktopCommentFraudRoot,
    isVideoPlaying: Boolean,
    stillOwned: () -> Boolean,
    currentVideoPositionMsProvider: () -> Long,
    onTimestampClick: (Long) -> Unit,
    onUserClick: (Long) -> Unit,
    onLinkClick: (String) -> Unit,
    onLogin: () -> Unit,
    modifier: Modifier,
    auxiliaryContent: (@Composable (DesktopOriginalCommentRootOwner) -> Unit)? = null,
    initialCommentRootRpid: Long = 0L,
    initialCommentTargetRpid: Long = 0L,
    commentRouteOpenId: Long = 0L,
) {
    val currentInfo by rememberUpdatedState(info)
    val currentPosition by rememberUpdatedState(currentVideoPositionMsProvider)
    val currentSeek by rememberUpdatedState(onTimestampClick)
    val currentUserClick by rememberUpdatedState(onUserClick)
    val currentLinkClick by rememberUpdatedState(onLinkClick)
    val currentLogin by rememberUpdatedState(onLogin)
    // Same sole owner: a distinct routed comment/openId disposes the old composition scope,
    // preview, detail route and every owned request rather than transferring a stale thread.
    val routeIdentity = Triple(info.aid, commentRouteOpenId, initialCommentRootRpid to initialCommentTargetRpid)
    DesktopOriginalCommentRootBindings(repository, community, fraud, routeIdentity, stillOwned, modifier) { owner ->
        auxiliaryContent?.invoke(owner)
        val context = checkNotNull(LocalDesktopDynamicTimelinePreferences.current).context
        val platform = LocalDesktopCommentBindings.current
        val gallery = LocalDesktopDynamicCardBindings.current
        val viewModel = remember(owner) { VideoCommentViewModel(owner.scope, owner.requests) }
        val composer = remember(owner) { DesktopOriginalVideoCommentComposer(owner.scope, owner.requests,
            { currentInfo }, owner.operations::getBgmEmotePackages, owner.operations::searchMentionUsers, owner.feedback) }
        val commentState by viewModel.commentState.collectAsState()
        val subReplyState by viewModel.subReplyState.collectAsState()
        val preferredSort by remember(context) { DesktopOriginalReplySettings.getCommentDefaultSortMode(context) }
            .collectAsState(DesktopOriginalReplySettings.getCommentDefaultSortModeSync(context))
        val fraudEnabled by remember(context) { DesktopOriginalReplySettings.getCommentFraudDetectionEnabled(context) }.collectAsState(true)
        val decorations by remember(context) { DesktopOriginalReplySettings.getCommentMemberDecorationsEnabled(context) }.collectAsState(false)
        val nativeHeader = !LocalAppThemeConfig.current.liquidGlassEnabled
        var emoteMap by remember(owner) { mutableStateOf(owner.emotes.snapshot()) }
        val listState = rememberLazyListState()
        var searchVisible by remember(owner) { mutableStateOf(false) }
        var preview by remember(owner) { mutableStateOf<Triple<List<String>,Int,Pair<ImagePreviewSourceAnchor?,ImagePreviewTextContent?>>?>(null) }
        var detailRoute by remember(owner) { mutableStateOf<DesktopVideoCommentDetailRoute?>(null) }
        var hasHandledCommentRootFromRoute by remember(owner) { mutableStateOf(false) }
        fun owned() = owner.isOwned()
        fun imagePreview(images:List<String>,index:Int,anchor:ImagePreviewSourceAnchor?,text:ImagePreviewTextContent?) {
            if (owned()) preview = Triple(images,index,anchor to text)
        }
        fun openComposer(reply:ReplyItem?) {
            if (!owned()) return
            if (repository.account.value == null) { currentLogin(); return }
            if (reply == null) composer.openRootCommentComposer()
            else { composer.setReplyingTo(reply); composer.showCommentInputDialog() }
        }
        LaunchedEffect(owner, info.aid, info.owner.mid, info.stat.reply, preferredSort) {
            if (owned()) {
                viewModel.init(info.aid, info.owner.mid,
                    CommentSortMode.fromApiMode(preferredSort), info.stat.reply, commentType = 1)
                if (initialCommentRootRpid > 0L && !hasHandledCommentRootFromRoute) {
                    val openStarted = viewModel.openSubReplyFromRoute(
                        rootReplyId = initialCommentRootRpid,
                        targetReplyId = initialCommentTargetRpid
                    )
                    if (openStarted) hasHandledCommentRootFromRoute = true
                }
            }
        }
        LaunchedEffect(owner, initialCommentRootRpid, initialCommentTargetRpid,
            commentState.replies, commentState.isRepliesLoading, subReplyState.visible) {
            if (owned() && initialCommentRootRpid > 0L && !hasHandledCommentRootFromRoute && !subReplyState.visible) {
                val rootReply = commentState.replies.firstOrNull { it.rpid == initialCommentRootRpid }
                if (rootReply != null) {
                    viewModel.openSubReply(rootReply, initialCommentTargetRpid)
                    hasHandledCommentRootFromRoute = true
                } else if (!commentState.isRepliesLoading) {
                    val openStarted = viewModel.openSubReplyFromRoute(
                        rootReplyId = initialCommentRootRpid,
                        targetReplyId = initialCommentTargetRpid
                    )
                    if (openStarted) hasHandledCommentRootFromRoute = true
                }
            }
        }
        LaunchedEffect(owner) {
            val loaded = owner.emotes.ensureLoaded()
            ensureActive()
            if (!owned()) throw CancellationException("Video comment emotes retired")
            emoteMap = loaded
        }
        val activeDetail = detailRoute
        if (activeDetail != null) key(activeDetail) {
            val detailScope = remember { CoroutineScope(owner.scope.coroutineContext + SupervisorJob(owner.scope.coroutineContext[Job])) }
            val detailViewModel = remember { CommentDetailViewModel(detailScope, owner.requests) }
            DisposableEffect(detailScope) { onDispose { detailScope.cancel() } }
            CommentDetailScreen(info.aid, activeDetail.root, activeDetail.target, 1,
                onBack = { detailRoute = null }, onOpenLink = { if (owned()) currentLinkClick(it) },
                onUserClick = { if (owned()) currentUserClick(it) }, viewModel = detailViewModel, requests = owner.requests)
        } else Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { openComposer(null) }, enabled = commentState.canInputComment) { AppText(commentState.rootInputHint) }
            if (subReplyState.visible) {
                VideoInlineSubReplyDetailContent(subReplyState, commentState, emoteMap,
                    info.pages.firstOrNull { it.cid == info.cid }?.duration?.times(1000L),
                    onLoadMore = viewModel::loadMoreSubReplies, onRefresh = viewModel::refreshSubReplies, onSortModeChange = viewModel::setSubReplySortMode,
                    onDismiss = viewModel::closeSubReply,
                    onRootCommentClick = { subReplyState.rootReply?.let { if (owned()) detailRoute = DesktopVideoCommentDetailRoute(it.rpid, subReplyState.targetReplyId) } },
                    onTimestampClick = { if (owned()) currentSeek(it) }, onImagePreview = ::imagePreview,
                    onReplyClick = ::openComposer, onConversationClick = viewModel::openSubReplyConversation,
                    onConversationBack = viewModel::closeSubReplyConversation,
                    onDissolveStart = viewModel::startSubDissolve, onDeleteComment = viewModel::deleteSubComment,
                    onCheckCommentFraud = viewModel::checkCommentFraud, onCommentLike = viewModel::likeComment,
                    onCommentHate = viewModel::hateComment, onReportComment = { id,reason -> viewModel.reportComment(id,reason) },
                    onUrlClick = { if (owned()) currentLinkClick(it) }, showIdentityDecorations = decorations,
                    onAvatarClick = { id -> id.toLongOrNull()?.let { if (owned()) currentUserClick(it) } },
                    modifier = Modifier.weight(1f))
            } else VideoCommentTab(listState, Modifier.weight(1f), info, commentState.replies,
                commentState.replyCount, emoteMap, commentState.isRepliesLoading, commentState.isRepliesRefreshing, commentState.repliesError, commentState.isRepliesEnd,
                commentState.voteCard, videoTags, onUpClick = { if (owned()) currentUserClick(it) },
                onSubReplyClick = viewModel::openSubReply, onCommentReplyClick = ::openComposer,
                onLoadMoreReplies = viewModel::loadComments, onRefreshReplies = viewModel::refreshComments, onImagePreview = ::imagePreview,
                onTimestampClick = { if (owned()) currentSeek(it) }, contentPadding = PaddingValues(bottom = 12.dp),
                currentMid = commentState.currentMid, showUpFlag = commentState.showUpFlag,
                dissolvingIds = commentState.dissolvingIds, onDeleteComment = viewModel::deleteComment,
                onDissolveStart = viewModel::startDissolve, onCommentLike = viewModel::likeComment,
                onCommentHate = viewModel::hateComment, likedComments = commentState.likedComments,
                hatedComments = commentState.hatedComments, onCommentUrlClick = { if (owned()) currentLinkClick(it) },
                onReportComment = { id,reason -> viewModel.reportComment(id,reason) },
                onToggleTopComment = viewModel::toggleTopComment, onCheckCommentFraud = viewModel::checkCommentFraud,
                showIdentityDecorations = decorations,
                lightweightCommentRendering = shouldUseLightweightCommentRendering(1,isVideoPlaying,listState.isScrollInProgress),
                sortMode = commentState.sortMode, onSortModeChange = viewModel::setSortMode,
                showNativeSortHeader = nativeHeader, showSortControlInHeader = true,
                onSearchClick = { if (owned()) searchVisible = true })
        }
        DesktopOriginalVideoCommentInputOverlay(composer, commentState, { if (owned()) currentPosition() else 0L })
        VideoDetailCommentFraudOverlayAdapter(platform, composer, viewModel, info.aid, fraudEnabled)
        if (searchVisible) CommentSearchSheet(replies = commentState.replies, upMid = info.owner.mid,
            onCommentClick = { if (owned()) { searchVisible = false; viewModel.openSubReply(it) } },
            onSubReplyClick = { rootReply -> if (owned()) { searchVisible = false; viewModel.openSubReply(rootReply) } },
            onDismiss = { searchVisible = false })
        preview?.let { (images,index,anchorAndText) ->
            val anchor = anchorAndText.first
            com.android.purebilibili.feature.dynamic.components.ImagePreviewDialog(images,index,
                sourceRect = anchor?.rect, sourceRects = anchor?.galleryRects ?: emptyMap(), sourceKey = anchor?.sourceKey,
                sourceCornerRadiusDp = anchor?.cornerRadiusDp ?: 0f, textContent = anchorAndText.second,
                onDismiss = { preview = null })
        }
    }
}

package com.android.purebilibili.feature.video.screen
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.ui.components.*
import com.android.purebilibili.feature.video.viewmodel.CommentSortMode
import com.android.purebilibili.feature.dynamic.components.ImagePreviewSourceAnchor
import com.android.purebilibili.feature.dynamic.components.ImagePreviewTextContent

@Composable
internal fun VideoCommentTab(
    listState: LazyListState,
    modifier: Modifier,
    info: ViewInfo,
    replies: List<ReplyItem>,
    replyCount: Int,
    emoteMap: Map<String, String>,
    isRepliesLoading: Boolean,
    isRepliesEnd: Boolean,
    voteCard: ReplyVoteCard?,
    videoTags: List<VideoTag>,
    onUpClick: (Long) -> Unit,
    onSubReplyClick: (ReplyItem, Long) -> Unit,
    onCommentReplyClick: (ReplyItem) -> Unit,
    onLoadMoreReplies: () -> Unit,
    onImagePreview: (List<String>, Int, ImagePreviewSourceAnchor?, ImagePreviewTextContent?) -> Unit,
    onTimestampClick: ((Long) -> Unit)?,
    contentPadding: PaddingValues,
    // [新增] 参数
    currentMid: Long,
    showUpFlag: Boolean,
    dissolvingIds: Set<Long>,
    onDeleteComment: (Long) -> Unit,
    onDissolveStart: (Long) -> Unit,
    // [新增] 点赞回调
    onCommentLike: (Long) -> Unit,
    onCommentHate: (Long) -> Unit,
    likedComments: Set<Long>,
    hatedComments: Set<Long>,
    onCommentUrlClick: (String) -> Unit,
    onReportComment: (Long, Int) -> Unit,
    onToggleTopComment: (ReplyItem) -> Unit,
    onCheckCommentFraud: (ReplyItem) -> Unit,
    showIdentityDecorations: Boolean,
    lightweightCommentRendering: Boolean,
    sortMode: CommentSortMode = CommentSortMode.HOT,
    onSortModeChange: (CommentSortMode) -> Unit = {},
    showNativeSortHeader: Boolean = false,
    showSortControlInHeader: Boolean = false,
    showHeader: Boolean = true,
    floatingHeaderContentPadding: Dp = 0.dp,
    onSearchClick: (() -> Unit)? = null,
) {
    val commentAppearance = rememberVideoCommentAppearance()
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val shouldLoadMore by remember(
        listState,
        replies.size,
        replyCount,
        isRepliesLoading,
        isRepliesEnd
    ) {
        derivedStateOf {
            shouldLoadMoreVideoComments(
                lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
                totalItemsCount = listState.layoutInfo.totalItemsCount,
                isLoading = isRepliesLoading,
                // 置顶/热评会额外插入列表，已渲染条数不能推断服务端分页已结束。
                isEnd = isRepliesEnd
            )
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            onLoadMoreReplies()
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        if (showHeader && showSortControlInHeader) {
            if (showNativeSortHeader) {
                CommentSortHeader(
                    count = replyCount,
                    sortMode = sortMode,
                    onSortModeChange = onSortModeChange,
                    onSearchClick = onSearchClick,
                )
            } else {
                CommentListHeader(
                    count = replyCount,
                    title = "${sortMode.label}评论",
                )
            }
        } else if (showHeader) {
            CommentListHeader(
                count = replyCount,
                title = "${sortMode.label}评论",
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    start = contentPadding.calculateStartPadding(layoutDirection),
                    top = contentPadding.calculateTopPadding() + floatingHeaderContentPadding,
                    end = contentPadding.calculateEndPadding(layoutDirection),
                    bottom = contentPadding.calculateBottomPadding(),
                )
            ) {
            voteCard?.let { card ->
                item(key = "inline_vote_${card.voteId}") {
                    VideoCommentVoteCard(
                        card = card,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }
            if (isRepliesLoading && replies.isEmpty()) {
                item {
                    com.android.purebilibili.core.ui.skeleton.CommentListColumnSkeleton()
                }
            } else if (replies.isEmpty() && voteCard == null) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        // replyCount 来自详情/游标 all_count：>0 却列表空 = 最热链路空成功，勿误报「暂无」
                        AppText(
                            text = if (replyCount > 0) {
                                "评论暂时无法加载，可切换「最新」或稍后重试"
                            } else {
                                "暂无评论"
                            },
                            color = commentAppearance.secondaryTextColor
                        )
                    }
                }
            } else {
                items(
                    items = replies,
                    key = { it.rpid },
                    contentType = { resolveReplyItemContentType(it) }
                ) { reply ->
                    // [新增] 使用 DissolvableVideoCard 包裹
                    com.bilipai.desktop.ui.DesktopReplyDissolvableContainer(
                        isDissolving = reply.rpid in dissolvingIds,
                        onDissolveComplete = { onDeleteComment(reply.rpid) },
                        cardId = "comment_${reply.rpid}",
                        modifier = Modifier.padding(bottom = 1.dp) // 小间距防止裁剪
                    ) {
                        ReplyItemView(
                            showUpFlag = showUpFlag,
                            item = reply,
                            upMid = info.owner.mid,
                            emoteMap = emoteMap,
                            lightweightMode = lightweightCommentRendering,
                            showIdentityDecorations = showIdentityDecorations,
                            onClick = {},
                            onSubClick = onSubReplyClick,
                            onTimestampClick = onTimestampClick,
                            maxTimestampMs = info.pages.firstOrNull { it.cid == info.cid }?.duration?.times(1000L)
                                ?: info.pages.firstOrNull()?.duration?.times(1000L),
                            onImagePreview = { images, index, rect, textContent ->
                                onImagePreview(images, index, rect, textContent)
                            },
                            // [新增] 点赞事件
                            onLikeClick = { onCommentLike(reply.rpid) },
                            onHateClick = { onCommentHate(reply.rpid) },
                            onReplyClick = { onCommentReplyClick(reply) },
                            onReportClick = { reason -> onReportComment(reply.rpid, reason) },
                            canToggleTop = shouldShowReplyTopAction(
                                currentMid = currentMid,
                                upMid = info.owner.mid,
                                item = reply
                            ),
                            onToggleTopClick = { onToggleTopComment(reply) },
                            // [修复] 正确传递点赞状态 (API数据 或 本地乐观更新)
                            isLiked = reply.action == 1 || reply.rpid in likedComments,
                            isHated = reply.action == 2 || reply.rpid in hatedComments,
                            // [新增] 仅当评论 mid 与当前登录用户 mid 一致时显示删除按钮
                            onDeleteClick = if (currentMid > 0 && reply.mid == currentMid) {
                                { onDissolveStart(reply.rpid) }
                            } else null,
                            onCheckFraudClick = if (currentMid > 0 && reply.mid == currentMid) {
                                { onCheckCommentFraud(reply) }
                            } else null,
                            // [新增] URL 点击跳转
                            onUrlClick = onCommentUrlClick,
                            // [新增] 头像点击
                            onAvatarClick = { mid -> mid.toLongOrNull()?.let { onUpClick(it) } }
                        )
                    }
                }

                // 加载更多
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            isRepliesLoading -> AdaptiveLoadingIndicator()
                            isRepliesEnd -> {
                                AppText("—— end ——", color = commentAppearance.secondaryTextColor, style = MaterialTheme.typography.bodySmall)
                            }
                            // 当 shouldLoadMore 为 true 时才显示加载指示器
                            shouldLoadMore -> AdaptiveLoadingIndicator()
                        }
                    }
                }
            }
            }

        }
    }
}

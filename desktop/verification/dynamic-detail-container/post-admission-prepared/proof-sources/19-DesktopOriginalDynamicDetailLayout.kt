// Original source app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicDetailScreen.kt
// LF SHA256 eb0eefcbfb12d5ee5e06d312095797fcf67cbf7264fb32040cd4391f38d1d0cf
package com.bilipai.desktop.ui
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.theme.*
import com.android.purebilibili.core.util.*
import com.android.purebilibili.core.ui.transition.resolvePredictiveBackBlurFrame
import com.android.purebilibili.core.store.DesktopDynamicCardSettings.DynamicDetailImageLayout
import com.android.purebilibili.data.model.response.DynamicItem
import com.android.purebilibili.feature.dynamic.*
import com.android.purebilibili.feature.dynamic.components.*
import com.android.purebilibili.feature.video.ui.components.*
import com.bilipai.desktop.appearance.LocalDesktopStrings
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

internal sealed interface DesktopOriginalDynamicDetailUiState {
    data object Loading : DesktopOriginalDynamicDetailUiState
    data class Success(val item: DynamicItem) : DesktopOriginalDynamicDetailUiState
    data class Error(val message: String) : DesktopOriginalDynamicDetailUiState
}

/** Root owns the detail read/confirmed mutations. This original screen consumes its
 * current raw item, original Reply session and the existing complete CardHost. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DesktopOriginalDynamicDetailLayout(
    dynamicId: String,
    uiState: DesktopOriginalDynamicDetailUiState,
    session: DesktopOriginalDynamicReplySession,
    defaultDetailImageLayout: DynamicDetailImageLayout,
    liquidGlassEnabled: Boolean,
    currentMid: Long?,
    openCommentRootRpid: Long = 0L,
    openCommentTargetRpid: Long = 0L,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onUserClick: (Long) -> Unit,
    card: @Composable (DynamicDetailImageLayout, onCommentClick: () -> Unit) -> Unit,
) {
    key(dynamicId, session) {
    val platform = LocalDesktopCommentBindings.current
    if (!platform.isOwned() || !session.isOwned()) return
    val strings = LocalDesktopStrings.current
    val screenTitle = strings["dynamic_detail_title"]
    val backLabel = strings["common_back"]
    val retryLabel = strings["common_retry"]
    val detailCommentBackdrop = if (liquidGlassEnabled) rememberLayerBackdrop() else null
    var detailImageLayoutOverrideName by rememberSaveable(dynamicId) { mutableStateOf<String?>(null) }
    val effectiveDetailImageLayout = remember(detailImageLayoutOverrideName,defaultDetailImageLayout) {
        detailImageLayoutOverrideName?.let { name -> DynamicDetailImageLayout.entries.firstOrNull { it.name == name } }
            ?: defaultDetailImageLayout
    }
    val comments by session.comments.collectAsState()
    val commentsLoading by session.commentsLoading.collectAsState()
    val commentsLoadingMore by session.commentsLoadingMore.collectAsState()
    val commentTotalCount by session.commentTotalCount.collectAsState()
    val commentSortMode by session.dynamicCommentSortMode.collectAsState()
    val subReplyState by session.subReplyState.collectAsState()
    val commentReplyTarget by session.commentReplyTarget.collectAsState()
    var subReplyCoveredBlurProgress by remember { mutableFloatStateOf(0f) }
    val detailListState = rememberLazyListState()
    val commentListState = rememberLazyListState()
    val useSplitLayout = LocalWindowSizeClass.current.shouldUseSplitLayout
    val detailScrollScope = rememberCoroutineScope()
    var showImagePreview by remember { mutableStateOf(false) }
    var previewImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var previewInitialIndex by remember { mutableIntStateOf(0) }
    var previewSourceRect by remember { mutableStateOf<ImagePreviewSourceAnchor?>(null) }
    var previewTextContent by remember { mutableStateOf<ImagePreviewTextContent?>(null) }
    ImmersiveAppScaffold(
        blurContentReady = uiState !is DesktopOriginalDynamicDetailUiState.Loading,
        topBar = {
            val canToggleImageLayout = (uiState as? DesktopOriginalDynamicDetailUiState.Success)
                ?.let { shouldShowDynamicDetailImageLayoutToggle(it.item) } == true
            AppTopBar(
                title = screenTitle,
                navigationIcon = {
                    AppIconButton(onClick = onBack) {
                        AppIcon(rememberAppBackIcon(), contentDescription = backLabel)
                    }
                },
                actions = {
                    if (canToggleImageLayout) {
                        val nextImageLayout = toggleDynamicDetailImageLayout(effectiveDetailImageLayout)
                        AppIconButton(
                            onClick = {
                                detailImageLayoutOverrideName = nextImageLayout.name
                            }
                        ) {
                            AppIcon(
                                imageVector = if (effectiveDetailImageLayout ==
                                    DynamicDetailImageLayout.EXPANDED
                                ) {
                                    rememberAppGridLayoutIcon()
                                } else {
                                    rememberAppListLayoutIcon()
                                },
                                contentDescription = "切换图片展示（当前：${effectiveDetailImageLayout.label}）",
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        when (val state = uiState) {
            DesktopOriginalDynamicDetailUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    AdaptiveLoadingIndicator()
                }
            }

            is DesktopOriginalDynamicDetailUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AppSpacingTokens.Medium)
                    ) {
                        AppText(
                            text = state.message,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AppButton(onClick = { onRetry() }) {
                            AppText(retryLabel)
                        }
                    }
                }
            }
            is DesktopOriginalDynamicDetailUiState.Success -> {
                val commentTargetKey = remember(state.item) {
                    val basic = state.item.basic
                    "${state.item.id_str}_${basic?.comment_id_str}_${basic?.comment_type}"
                }
                LaunchedEffect(
                    commentTargetKey,
                    openCommentRootRpid,
                    openCommentTargetRpid
                ) {
                    session.openCommentSheet(
                        item = state.item,
                        rootReplyId = openCommentRootRpid,
                        targetReplyId = openCommentTargetRpid
                    )
                }

                LaunchedEffect(detailListState, commentListState, useSplitLayout) {
                    snapshotFlow {
                        // 分栏时右栏列表负责触底加载，竖屏时主列表负责。
                        // 加载中/条数变化必须在 snapshotFlow 内读取，不能当 LaunchedEffect key，
                        // 否则 loading footer 插进列表会反复重启 effect，底部评论框跟着闪。
                        val activeState = if (useSplitLayout) commentListState else detailListState
                        val lastVisibleIndex = activeState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                        val itemCount = activeState.layoutInfo.totalItemsCount
                        // A fast desktop request may publish rows before LazyColumn remeasures.
                        // Never compare a new raw page with the old skeleton/header geometry.
                        desktopDynamicCommentSlotsMeasured(itemCount, comments.size, useSplitLayout) &&
                        shouldLoadMoreDynamicDetailComments(
                            lastVisibleIndex = lastVisibleIndex,
                            itemCount = itemCount,
                            loadedCount = comments.size,
                            totalCount = commentTotalCount,
                            isLoading = commentsLoading,
                            isLoadingMore = commentsLoadingMore,
                        )
                    }
                        .distinctUntilChanged()
                        .filter { it }
                        .collect {
                            session.loadMoreComments()
                        }
                }

                //  [新增] 卡片与评论内容提取，供分栏/单列两种布局复用
                val cardContent: LazyListScope.() -> Unit = {
                    item {
                        card(effectiveDetailImageLayout) {
                            detailScrollScope.launch {
                                if (useSplitLayout) commentListState.animateScrollToItem(0)
                                else detailListState.animateScrollToItem(1)
                            }
                        }
                    }
                }
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
                        dynamicAuthorMid = state.item.modules.module_author?.mid ?: 0L,
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
                    DesktopOriginalDynamicDetailComposer(
                        onPostComment = { message ->
                            session.postComment(state.item.id_str, message) { _, toastMessage ->
                                if (platform.isOwned()) platform.showFeedback(toastMessage)
                            }
                        },
                        replyTargetUname = commentReplyTarget?.uname,
                        onClearReplyTarget = session::clearCommentReplyTarget,
                        liquidGlassEnabled = liquidGlassEnabled,
                        backdrop = detailCommentBackdrop,
                        modifier = modifier,
                    )
                }
                val floatingCommentComposer = shouldUseFloatingLiquidBottomInputBar(
                    androidNativeLiquidGlassEnabled = liquidGlassEnabled,
                )
                val commentContentBottomPadding = resolveBottomInputBarContentBottomPadding(
                    showBar = true,
                    floatingLiquidGlass = floatingCommentComposer,
                    showActionButtonsFallback = false,
                )

                val coveredBlurProgress = if (subReplyState.visible) subReplyCoveredBlurProgress else 0f
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            renderEffect = null
                            if (coveredBlurProgress > 0f &&
                                desktopDetailRenderEffectsSupported()
                            ) {
                                val blurFrame = resolvePredictiveBackBlurFrame(
                                    progress = coveredBlurProgress,
                                )
                                renderEffect = if (blurFrame.blurRadiusPx > 0.5f) {
                                    androidx.compose.ui.graphics.BlurEffect(
                                        blurFrame.blurRadiusPx,
                                        blurFrame.blurRadiusPx,
                                        edgeTreatment = androidx.compose.ui.graphics.TileMode.Clamp,
                                    )
                                } else {
                                    null
                                }
                            }
                        }
                ) {
                    if (useSplitLayout) {
                        //  [新增] 大屏/横屏：左卡片 + 右评论（对齐 BiliPai 横屏分栏）
                        AppSplitLayout(
                            primaryRatio = 0.5f,
                            modifier = Modifier
                                .padding(bottom = paddingValues.calculateBottomPadding())
                                .consumeWindowInsets(paddingValues),
                            primaryContent = {
                                LazyColumn(
                                    state = detailListState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .responsiveContentWidth(maxWidth = resolveDynamicFeedMaxWidth()),
                                    contentPadding = PaddingValues(top = paddingValues.calculateTopPadding(), bottom = AppSpacingTokens.Large + AppSpacingTokens.ExtraSmall)
                                ) {
                                    cardContent()
                                }
                            },
                            secondaryContent = {
                                if (floatingCommentComposer) {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        LazyColumn(
                                            state = commentListState,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .then(
                                                    if (detailCommentBackdrop != null) {
                                                        Modifier.layerBackdrop(detailCommentBackdrop)
                                                    } else {
                                                        Modifier
                                                    }
                                                ),
                                            contentPadding = PaddingValues(top = paddingValues.calculateTopPadding(), bottom = commentContentBottomPadding),
                                        ) {
                                            commentContent()
                                        }
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .fillMaxWidth()
                                                .imePadding()
                                                .padding(horizontal = AppSpacingTokens.ExtraLarge)
                                                .padding(bottom = AppSpacingTokens.Medium),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            commentComposer(
                                                Modifier
                                                    .widthIn(max = 360.dp)
                                                    .fillMaxWidth(),
                                            )
                                        }
                                    }
                                } else {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        LazyColumn(
                                            state = commentListState,
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(top = paddingValues.calculateTopPadding(), bottom = commentContentBottomPadding),
                                        ) {
                                            commentContent()
                                        }
                                        Column(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .fillMaxWidth()
                                                .imePadding()
                                        ) {
                                            AppHorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                            AppSurface(
                                                modifier = Modifier.fillMaxWidth(),
                                                color = resolveDesktopOriginalReplyBottomBarColor(LocalAppUiStyle.current, MaterialTheme.colorScheme),
                                                tonalElevation = 0.dp,
                                                shadowElevation = 0.dp,
                                            ) {
                                                commentComposer(
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(
                                                            horizontal = AppSpacingTokens.Large,
                                                            vertical = AppSpacingTokens.Medium,
                                                        )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = paddingValues.calculateBottomPadding())
                                .consumeWindowInsets(paddingValues)
                                .responsiveContentWidth(maxWidth = resolveDynamicFeedMaxWidth())
                        ) {
                            LazyColumn(
                                state = detailListState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(
                                        if (floatingCommentComposer && detailCommentBackdrop != null) {
                                            Modifier.layerBackdrop(detailCommentBackdrop)
                                        } else {
                                            Modifier
                                        }
                                    ),
                                contentPadding = PaddingValues(top = paddingValues.calculateTopPadding(), bottom = commentContentBottomPadding),
                            ) {
                                cardContent()
                                commentContent()
                            }
                            if (floatingCommentComposer) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .imePadding()
                                        .padding(horizontal = AppSpacingTokens.ExtraLarge)
                                        .padding(bottom = AppSpacingTokens.Medium),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    commentComposer(
                                        Modifier
                                            .widthIn(max = 360.dp)
                                            .fillMaxWidth(),
                                    )
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .imePadding()
                                ) {
                                    AppHorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    AppSurface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = resolveDesktopOriginalReplyBottomBarColor(LocalAppUiStyle.current, MaterialTheme.colorScheme),
                                        tonalElevation = 0.dp,
                                        shadowElevation = 0.dp,
                                    ) {
                                        commentComposer(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(
                                                    horizontal = AppSpacingTokens.Large,
                                                    vertical = AppSpacingTokens.Medium,
                                                ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                DynamicSubReplyPreviewHost(
                    state = subReplyState,
                    onDismiss = session::closeSubReply,
                    onLoadMore = session::loadMoreSubReplies,
                    onSortModeChange = session::setSubReplySortMode,
                    onUserClick = onUserClick,
                    onReplyClick = { reply ->
                        session.closeSubReply()
                        session.startCommentReply(reply)
                    },
                    onCommentLike = { rpid -> session.likeComment(rpid) },
                    onCommentHate = { rpid ->
                        session.hateComment(rpid) { _, message ->
                            if (platform.isOwned()) platform.showFeedback(message)
                        }
                    },
                    currentMid = currentMid ?: 0L,
                    onDeleteComment = { rpid ->
                        session.deleteDynamicComment(rpid) { _, message ->
                            if (platform.isOwned()) platform.showFeedback(message)
                        }
                    },
                    onCoveredBlurProgressChange = { progress ->
                        subReplyCoveredBlurProgress = progress
                    },
                )

                if (showImagePreview && previewImages.isNotEmpty()) {
                    ImagePreviewDialog(images=previewImages,initialIndex=previewInitialIndex,
                        sourceRect=previewSourceRect?.rect,sourceRects=previewSourceRect?.galleryRects.orEmpty(),
                        sourceCornerRadiusDp=previewSourceRect?.cornerRadiusDp ?: AppShapes.containerCornerDp(ContainerLevel.Field).value,
                        textContent=previewTextContent,onDismiss={showImagePreview=false;previewTextContent=null})
                }
            }
        }
    }
}
}

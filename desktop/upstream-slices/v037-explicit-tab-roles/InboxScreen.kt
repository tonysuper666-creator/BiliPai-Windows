// 私信收件箱页面
package com.android.purebilibili.feature.message
import com.android.purebilibili.core.ui.animation.jiggleOnDissolve
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppText

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import com.android.purebilibili.core.ui.components.AppLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.feature.message.MessageAppScaffold as AppScaffold
import com.android.purebilibili.core.ui.AppTopBar
import com.android.purebilibili.core.ui.rememberAppSemanticVisualPolicy
import coil3.compose.AsyncImage
import com.android.purebilibili.core.ui.AdaptivePullToRefreshBox
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.core.ui.components.AppButton
import com.android.purebilibili.core.ui.components.AppDropdownMenu
import com.android.purebilibili.core.ui.components.AppDropdownMenuItem
import com.android.purebilibili.core.ui.components.AppSegmentOption
import com.android.purebilibili.core.ui.components.AppThemeAdaptiveTabRow
import com.android.purebilibili.core.ui.components.AppIconButton
import com.android.purebilibili.core.ui.components.AppOutlinedButton
import com.android.purebilibili.core.ui.components.AppSurface
import com.android.purebilibili.core.ui.components.AppSnackbar
import com.android.purebilibili.core.ui.components.AppTextButton
import com.android.purebilibili.core.ui.rememberAppBackIcon
import com.android.purebilibili.data.model.response.SessionItem
import com.android.purebilibili.core.ui.AppSurfaceTokens
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.core.ui.skeleton.ContentSkeletonBlock
import com.android.purebilibili.core.ui.skeleton.rememberStaticSkeletonBlockColor
import com.android.purebilibili.core.util.resolveSingleColumnFeedMaxWidth
import com.android.purebilibili.core.util.responsiveContentWidth

/**
 * 会话行的 lazy item 身份。骨架行与真实会话行共用同一个 contentType，加载完成时
 * LazyList 才能在原地替换 item，而不是「整批删除 + 整批新增」重建可视区。
 */
internal const val MESSAGE_SESSION_ROW_CONTENT_TYPE = "message_session_row"

/** 首屏会话骨架行数。与真实首屏页大小同量级，避免加载完成后列表长度突变。 */
private const val MESSAGE_SESSION_SKELETON_ROW_COUNT = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onBack: () -> Unit,
    onTopItemClick: (MessageCenterDestination) -> Unit,
    onSessionClick: (talkerId: Long, sessionType: Int, userName: String) -> Unit,
    viewModel: InboxViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var lastAutoLoadEndTs by remember { mutableLongStateOf(0L) }
    var pendingRemoveSession by remember { mutableStateOf<SessionItem?>(null) }
    var dissolvingSessionKeys by remember { mutableStateOf(setOf<String>()) }
    var pendingInterceptSession by remember { mutableStateOf<SessionItem?>(null) }
    var showClearDustbinConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.sessions.firstOrNull()?.talker_id, uiState.isRefreshing, uiState.isLoading) {
        if (uiState.isRefreshing || uiState.isLoading) {
            lastAutoLoadEndTs = 0L
        }
    }

    AppScaffold(
        blurContentReady = !uiState.isLoading,
        topBar = {
            AppTopBar(
                title = "消息",
                subtitle = uiState.unreadData?.let { unread ->
                    totalPrivateUnreadCount(unread).takeIf { it > 0 }?.let { "私信 $it 条未读" }
                },
                navigationIcon = {
                    AppIconButton(onClick = onBack) {
                        AppIcon(rememberAppBackIcon(), contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .responsiveContentWidth(maxWidth = resolveSingleColumnFeedMaxWidth())
        ) {
            // 骨架与真实内容共用同一个 LazyColumn：加载期只把会话行换成骨架，
            // 「消息分类」「私信会话」两个头部始终是真实组件（标题固定，不依赖数据），
            // 因此数据到达时不会出现「整块头部凭空出现」造成的整页下移。
            AdaptivePullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.refresh() },
                indicatorTopInset = paddingValues.calculateTopPadding()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = paddingValues.calculateTopPadding() + 8.dp, bottom = paddingValues.calculateBottomPadding() + 8.dp)
                ) {
                    item(key = "message_center_shortcut_row") {
                        MessageCenterTopShortcutRow(
                            items = buildMessageCenterTopItems(uiState.feedUnreadData),
                            onItemClick = onTopItemClick
                        )
                    }

                    item(key = "message_session_category_row") {
                        MessageSessionCategoryRow(
                            items = buildMessageSessionCategoryItems(uiState.unreadData),
                            selectedCategory = uiState.selectedCategory,
                            onCategoryClick = { viewModel.selectCategory(it) }
                        )
                    }

                    if (uiState.selectedCategory == MessageSessionCategory.Dustbin) {
                        item(key = "message_dustbin_action_row") {
                            DustbinActionRow(
                                isOperating = uiState.isBatchOperating,
                                onMarkRead = { viewModel.markDustbinRead() },
                                onClear = { showClearDustbinConfirm = true }
                            )
                        }
                    }

                    when {
                        uiState.isLoading -> {
                            items(
                                count = MESSAGE_SESSION_SKELETON_ROW_COUNT,
                                key = { index -> "message_session_skeleton_$index" },
                                contentType = { MESSAGE_SESSION_ROW_CONTENT_TYPE }
                            ) { index ->
                                SessionListItemSkeleton(
                                    slotIndex = index,
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }

                        uiState.error != null -> {
                            item(key = "message_inbox_error") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillParentMaxHeight(0.6f),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    AppText(uiState.error ?: "加载失败")
                                    Spacer(modifier = Modifier.height(8.dp))
                                    AppButton(onClick = { viewModel.loadSessions() }) {
                                        AppText("重试")
                                    }
                                }
                            }
                        }

                        uiState.sessions.isEmpty() -> {
                            item(key = "message_inbox_empty") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillParentMaxHeight(0.5f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AppText(
                                        text = "暂无私信",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        else -> {
                            items(
                                items = uiState.sessions,
                                key = InboxSessionPaginationPolicy::resolveSessionKey,
                                contentType = { MESSAGE_SESSION_ROW_CONTENT_TYPE }
                            ) { session ->
                                if (
                                    uiState.hasMore &&
                                    uiState.endTs > 0L &&
                                    uiState.endTs != lastAutoLoadEndTs &&
                                    session == uiState.sessions.lastOrNull() &&
                                    !uiState.isLoadingMore
                                ) {
                                    LaunchedEffect(session.talker_id, session.session_type, uiState.endTs) {
                                        lastAutoLoadEndTs = uiState.endTs
                                        viewModel.loadMoreSessions()
                                    }
                                }
                                val userInfo = uiState.userInfoMap[session.talker_id]
                                val sessionKey = InboxSessionPaginationPolicy.resolveSessionKey(session)
                                val isSessionDissolving = sessionKey in dissolvingSessionKeys
                                com.android.purebilibili.core.ui.animation.MaybeDissolvableVideoCard(
                                    isDissolving = isSessionDissolving,
                                    onDissolveComplete = {
                                        dissolvingSessionKeys = dissolvingSessionKeys - sessionKey
                                        viewModel.removeSession(session)
                                    },
                                    cardId = sessionKey,
                                    preset = com.android.purebilibili.core.ui.animation.DissolveAnimationPreset.TELEGRAM_FAST,
                                    modifier = Modifier
                                        .jiggleOnDissolve(
                                            cardId = sessionKey,
                                            isCurrentCardDissolving = isSessionDissolving,
                                        )
                                        .animateItem(),
                                ) {
                                    SessionListItem(
                                        session = session,
                                        userInfo = userInfo,
                                        onClick = {
                                            val userName = InboxUserInfoResolver.resolveDisplayName(
                                                cached = userInfo,
                                                session = session
                                            )
                                            onSessionClick(session.talker_id, session.session_type, userName)
                                        },
                                        onRemove = { pendingRemoveSession = session },
                                        onToggleTop = { viewModel.toggleTop(session) },
                                        onToggleDnd = { viewModel.toggleDnd(session) },
                                        onToggleIntercept = {
                                            if (session.is_intercept == 1) {
                                                viewModel.toggleIntercept(session)
                                            } else {
                                                pendingInterceptSession = session
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (uiState.hasMore && !uiState.isLoading) {
                        item(key = "message_inbox_load_more") {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                if (uiState.isLoadingMore) {
                                    com.android.purebilibili.core.ui.CutePersonLoadingIndicator(
                                        size = 24.dp
                                    )
                                } else {
                                    AppTextButton(onClick = { viewModel.loadMoreSessions() }) {
                                        AppText("加载更多")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            uiState.operationError?.let { error ->
                AppSnackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        AppTextButton(onClick = { viewModel.clearOperationError() }) {
                            AppText("知道了")
                        }
                    }
                ) {
                    AppText(error)
                }
            }
        }
    }

    pendingRemoveSession?.let { session ->
        AppAlertDialog(
            onDismissRequest = { pendingRemoveSession = null },
            title = { AppText("删除会话") },
            text = { AppText("会话会从列表中移除，但不会删除聊天记录。") },
            confirmButton = {
                AppTextButton(
                    onClick = {
                        // Dissolve first; actual removal happens onDissolveComplete.
                        dissolvingSessionKeys =
                            dissolvingSessionKeys + InboxSessionPaginationPolicy.resolveSessionKey(session)
                        pendingRemoveSession = null
                    }
                ) {
                    AppText("删除")
                }
            },
            dismissButton = {
                AppTextButton(onClick = { pendingRemoveSession = null }) {
                    AppText("取消")
                }
            }
        )
    }

    pendingInterceptSession?.let { session ->
        AppAlertDialog(
            onDismissRequest = { pendingInterceptSession = null },
            title = { AppText("移入拦截") },
            text = { AppText("后续这类会话会进入拦截分类，仍可在拦截列表中查看和恢复。") },
            confirmButton = {
                AppTextButton(
                    onClick = {
                        viewModel.toggleIntercept(session)
                        pendingInterceptSession = null
                    }
                ) {
                    AppText("移入")
                }
            },
            dismissButton = {
                AppTextButton(onClick = { pendingInterceptSession = null }) {
                    AppText("取消")
                }
            }
        )
    }

    if (showClearDustbinConfirm) {
        AppAlertDialog(
            onDismissRequest = { showClearDustbinConfirm = false },
            title = { AppText("清空拦截会话") },
            text = { AppText("所有拦截会话会从列表中移除，聊天记录仍由服务端保留。") },
            confirmButton = {
                AppTextButton(
                    onClick = {
                        viewModel.clearDustbinSessions()
                        showClearDustbinConfirm = false
                    }
                ) {
                    AppText("清空")
                }
            },
            dismissButton = {
                AppTextButton(onClick = { showClearDustbinConfirm = false }) {
                    AppText("取消")
                }
            }
        )
    }
}

@Composable
private fun MessageCenterTopShortcutRow(
    items: List<MessageCenterTopItem>,
    onItemClick: (MessageCenterDestination) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        val columns = if (maxWidth >= 820.dp) 4 else 2

        Column {
            AppText(
                text = "消息分类",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            items.chunked(columns).forEachIndexed { rowIndex, rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowItems.forEach { item ->
                        MessageCenterShortcutCard(
                            item = item,
                            modifier = Modifier.weight(1f),
                            onClick = { onItemClick(item.destination) }
                        )
                    }

                    repeat(columns - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                if (rowIndex != items.chunked(columns).lastIndex) {
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun MessageCenterShortcutCard(
    item: MessageCenterTopItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val useGroupedListCards = rememberAppSemanticVisualPolicy().prefersGroupedListCards
    AppSurface(
        modifier = modifier
            .height(if (useGroupedListCards) 88.dp else 96.dp)
            .clickable(onClick = onClick),
        shape = AppShapes.container(ContainerLevel.Card),
        color = if (useGroupedListCards) AppSurfaceTokens.surfaceContainer() else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = if (useGroupedListCards) {
            androidx.compose.foundation.BorderStroke(
                0.8.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
            )
        } else {
            null
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppText(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start
                )

                if (item.unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = AppShapes.container(ContainerLevel.Pill)
                            )
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        AppText(
                            text = if (item.unreadCount > 99) "99+" else item.unreadCount.toString(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            AppText(
                text = if (item.unreadCount > 0) "${item.unreadCount} 条" else "查看",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start
            )
        }
    }
}

@Composable
private fun MessageSessionCategoryRow(
    items: List<MessageSessionCategoryItem>,
    selectedCategory: MessageSessionCategory,
    onCategoryClick: (MessageSessionCategory) -> Unit
) {
    val uiStyle = LocalAppUiStyle.current
    val rowHorizontalPadding = if (uiStyle == AppUiStyle.MATERIAL3) 0.dp else 16.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        AppText(
            text = "私信会话",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
        )

        val options = remember(items) {
            items.map { item ->
                AppSegmentOption(
                    value = item.category,
                    label = if (item.unreadCount > 0) {
                        "${item.category.title} ${if (item.unreadCount > 99) "99+" else item.unreadCount}"
                    } else {
                        item.category.title
                    },
                )
            }
        }
        AppThemeAdaptiveTabRow(
            role = com.android.purebilibili.core.ui.components.AppTabRowRole.SECONDARY,
            options = options,
            selectedValue = selectedCategory,
            onSelectionChange = onCategoryClick,
            scrollable = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = rowHorizontalPadding),
        )
    }
}

@Composable
private fun DustbinActionRow(
    isOperating: Boolean,
    onMarkRead: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppOutlinedButton(
            onClick = onMarkRead,
            enabled = !isOperating,
            modifier = Modifier.weight(1f)
        ) {
            AppText("全部已读")
        }

        AppOutlinedButton(
            onClick = onClear,
            enabled = !isOperating,
            modifier = Modifier.weight(1f)
        ) {
            AppText("清空拦截")
        }
    }
}

@Composable
private fun MessageUnreadBadge(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
            .background(
                color = MaterialTheme.colorScheme.primary,
                shape = AppShapes.container(ContainerLevel.Chip)
            )
            .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        AppText(
            text = text,
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 会话行骨架。
 *
 * 尺寸严格对齐 [SessionListItem]：分组卡片模式外层 12dp/3dp、行内 16dp/10dp；非分组模式
 * 外层 0、行内 16dp/12dp；头像 48dp。头像 48dp 高于两侧文字列，因此行高由头像决定，
 * 骨架与真实行高一致——这是消除「换内容时行位置跳」的关键。
 *
 * 颜色用 [rememberStaticSkeletonBlockColor] 固定色，不参与呼吸脉冲：呼吸中的灰块与随后的
 * 内容淡入叠加，观感上就是「闪」。
 */
@Composable
private fun SessionListItemSkeleton(
    modifier: Modifier = Modifier,
    slotIndex: Int = 0,
) {
    val useGroupedListCards = rememberAppSemanticVisualPolicy().prefersGroupedListCards
    val blockColor = rememberStaticSkeletonBlockColor()
    AppSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (useGroupedListCards) 12.dp else 0.dp,
                vertical = if (useGroupedListCards) 3.dp else 0.dp
            ),
        shape = if (useGroupedListCards) {
            AppShapes.container(ContainerLevel.Card)
        } else {
            RectangleShape
        },
        color = if (useGroupedListCards) {
            AppSurfaceTokens.surfaceContainer()
        } else {
            Color.Transparent
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = if (useGroupedListCards) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ContentSkeletonBlock(
                color = blockColor,
                shape = CircleShape,
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                ContentSkeletonBlock(
                    color = blockColor,
                    modifier = Modifier
                        .fillMaxWidth(if (slotIndex % 3 == 0) 0.40f else 0.30f)
                        .height(16.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                ContentSkeletonBlock(
                    color = blockColor,
                    modifier = Modifier
                        .fillMaxWidth(if (slotIndex % 2 == 0) 0.72f else 0.58f)
                        .height(14.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                ContentSkeletonBlock(
                    color = blockColor,
                    modifier = Modifier
                        .width(40.dp)
                        .height(12.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                ContentSkeletonBlock(
                    color = blockColor,
                    shape = CircleShape,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun SessionListItem(
    session: SessionItem,
    userInfo: UserBasicInfo? = null,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onToggleTop: () -> Unit,
    onToggleDnd: () -> Unit,
    onToggleIntercept: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val useGroupedListCards = rememberAppSemanticVisualPolicy().prefersGroupedListCards

    val displayName = InboxUserInfoResolver.resolveDisplayName(
        cached = userInfo,
        session = session
    )
    val displayAvatar = InboxUserInfoResolver.resolveDisplayAvatar(
        cached = userInfo,
        session = session
    )

    AppSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = if (useGroupedListCards) 12.dp else 0.dp, vertical = if (useGroupedListCards) 3.dp else 0.dp),
        shape = if (useGroupedListCards) {
            AppShapes.container(ContainerLevel.Card)
        } else {
            RectangleShape
        },
        color = when {
            useGroupedListCards && session.top_ts > 0 -> AppSurfaceTokens.secondaryContainer().copy(alpha = 0.55f)
            useGroupedListCards -> AppSurfaceTokens.surfaceContainer()
            session.top_ts > 0 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            else -> Color.Transparent
        }
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (useGroupedListCards) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            AsyncImage(
                model = displayAvatar,
                contentDescription = "头像",
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )

            if (session.unread_count > 0) {
                val badgeText = when {
                    session.unread_count > 99 -> "99+"
                    else -> session.unread_count.toString()
                }
                MessageUnreadBadge(
                    text = badgeText,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppText(
                    text = displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (session.top_ts > 0) {
                    Spacer(modifier = Modifier.width(AppSpacingTokens.ExtraSmall))
                    AppText(
                        text = "置顶",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                AppShapes.container(ContainerLevel.Tag)
                            )
                            .padding(horizontal = AppSpacingTokens.ExtraSmall, vertical = 1.dp)
                    )
                }

                if (session.is_dnd == 1) {
                    Spacer(modifier = Modifier.width(4.dp))
                    MessageSmallFlag(text = "免打扰")
                }

                if (session.is_intercept == 1) {
                    Spacer(modifier = Modifier.width(4.dp))
                    MessageSmallFlag(text = "已拦截")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            AppText(
                text = MessagePreviewParser.parseSessionPreview(
                    content = session.last_msg?.content,
                    msgType = session.last_msg?.msg_type ?: 1
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            AppText(
                text = formatTime(session.last_msg?.timestamp ?: 0),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Box {
                AppIconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    AppIcon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AppDropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    AppDropdownMenuItem(
                        text = { AppText(if (session.top_ts > 0) "取消置顶" else "置顶") },
                        onClick = {
                            showMenu = false
                            onToggleTop()
                        }
                    )
                    AppDropdownMenuItem(
                        text = { AppText(if (session.is_dnd == 1) "关闭免打扰" else "开启免打扰") },
                        onClick = {
                            showMenu = false
                            onToggleDnd()
                        }
                    )
                    if (session.session_type == 1) {
                        AppDropdownMenuItem(
                            text = { AppText(if (session.is_intercept == 1) "移出拦截" else "移入拦截") },
                            onClick = {
                                showMenu = false
                                onToggleIntercept()
                            }
                        )
                    }
                    AppDropdownMenuItem(
                        text = { AppText("删除会话") },
                        onClick = {
                            showMenu = false
                            onRemove()
                        }
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun MessageSmallFlag(text: String) {
    AppText(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                AppShapes.container(ContainerLevel.Tag)
            )
            .padding(horizontal = AppSpacingTokens.ExtraSmall, vertical = 1.dp)
    )
}

// 会话行组合期热路径：共享 formatter，避免每行新建 SimpleDateFormat。
// 仅主线程（Compose 组合）调用，不涉及 SimpleDateFormat 的线程安全问题。
private val inboxDayFormatter = SimpleDateFormat("MM-dd", Locale.getDefault())
private val inboxYearDayFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
private val inboxClockFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
private val inboxTimeCalendar = Calendar.getInstance()

/**
 * 会话列表相对时间，与 PiliPlus `DateFormatUtils.dateFormat` 对齐：
 * 刚刚 / N分钟前 / N小时前 / 昨天 HH:mm / N天前 / 同年 MM-dd / 跨年 yyyy-MM-dd。
 */
private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val nowMillis = System.currentTimeMillis()
    val date = Date(timestamp * 1000)
    val diffMinutes = ((nowMillis - date.time) / 60_000L).toInt()
    if (diffMinutes < 1) return "刚刚"
    if (diffMinutes < 60) return "${diffMinutes}分钟前"
    val diffHours = diffMinutes / 60
    if (diffHours < 24) return "${diffHours}小时前"

    val calendar = inboxTimeCalendar
    val today = (calendar.clone() as Calendar).apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    calendar.time = date
    val dateDay = (calendar.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val dayDiff = ((today.timeInMillis - dateDay.timeInMillis) / 86_400_000L).toInt()
    if (dayDiff == 1) return "昨天 ${inboxClockFormatter.format(date)}"
    if (dayDiff < 4) return "${dayDiff}天前"
    return if (today.get(Calendar.YEAR) == calendar.get(Calendar.YEAR)) {
        inboxDayFormatter.format(date)
    } else {
        inboxYearDayFormatter.format(date)
    }
}

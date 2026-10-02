package com.android.purebilibili.feature.message.feed
import androidx.compose.runtime.collectAsState
import com.bilipai.desktop.ui.LocalDesktopMessagePageOwner
import com.bilipai.desktop.ui.rememberDesktopMessageImagePicker
import com.android.purebilibili.core.ui.LocalNavigationBackHandler as BackHandler

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import com.android.purebilibili.core.ui.components.AppIcon
import androidx.compose.material3.MaterialTheme
import com.android.purebilibili.core.ui.components.AppText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.ImmersiveAppScaffold as AppScaffold
import com.android.purebilibili.core.ui.AppTopBar
import com.android.purebilibili.core.ui.AdaptivePullToRefreshBox
import com.android.purebilibili.core.ui.rememberAppBackIcon
import com.android.purebilibili.core.ui.components.AppIconButton
import com.android.purebilibili.data.model.response.MessageFeedReplyItem
import com.bilipai.desktop.ui.DesktopMessagePageAdmission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReplyMeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val items: List<MessageFeedReplyItem> = emptyList(),
    val cursor: Long? = null,
    val cursorTime: Long? = null,
    val hasMore: Boolean = false,
    val error: String? = null
)

internal class ReplyMeViewModel(private val owner: DesktopMessagePageAdmission) {
    private val _uiState = owner.stateFlow(ReplyMeUiState())
    val uiState: StateFlow<ReplyMeUiState> = _uiState.asStateFlow()

    init {
        loadInitial()
    }

    fun loadInitial() {
        owner.launchRead("ReplyMeViewModel-list") {
            _uiState.value = _uiState.value.copy(isLoading = true, isLoadingMore = false, isRefreshing = false, error = null)
            owner.requests.getReplyFeed().fold(
                onSuccess = { data ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        items = data.items.orEmpty(),
                        cursor = data.cursor?.id,
                        cursorTime = data.cursor?.time,
                        hasMore = data.cursor?.isEnd != true && !data.items.isNullOrEmpty()
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "加载失败"
                    )
                }
            )
        }
    }

    fun refresh() {
        owner.launchRead("ReplyMeViewModel-list") {
            _uiState.value = _uiState.value.copy(isLoading = false, isLoadingMore = false, isRefreshing = true, error = null)
            owner.requests.getReplyFeed().fold(
                onSuccess = { data ->
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false,
                        items = data.items.orEmpty(),
                        cursor = data.cursor?.id,
                        cursorTime = data.cursor?.time,
                        hasMore = data.cursor?.isEnd != true && !data.items.isNullOrEmpty()
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false,
                        error = error.message ?: "刷新失败"
                    )
                }
            )
        }
    }

    fun loadMore() {
        val current = _uiState.value
        if (current.isLoadingMore || !current.hasMore) return
        owner.launchRead("ReplyMeViewModel-more", dependsOn = "ReplyMeViewModel-list") {
            _uiState.value = current.copy(isLoadingMore = true)
            owner.requests.getReplyFeed(cursor = current.cursor, cursorTime = current.cursorTime).fold(
                onSuccess = { data ->
                    _uiState.value = _uiState.value.copy(
                        isLoadingMore = false,
                        items = current.items + data.items.orEmpty(),
                        cursor = data.cursor?.id,
                        cursorTime = data.cursor?.time,
                        hasMore = data.cursor?.isEnd != true && !data.items.isNullOrEmpty()
                    )
                },
                onFailure = {
                    _uiState.value = _uiState.value.copy(isLoadingMore = false)
                }
            )
        }
    }

    fun remove(id: Long) {
        owner.launchMutation("ReplyMeViewModel-remove") {
            owner.requests.deleteFeedItem(type = 1, id = id).onSuccess {
                _uiState.value = _uiState.value.copy(
                    items = _uiState.value.items.filterNot { it.id == id }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReplyMeScreen(
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    onOpenSpace: (Long) -> Unit,
    viewModel: ReplyMeViewModel = LocalDesktopMessagePageOwner.current.replyMe
) {
    val uiState by viewModel.uiState.collectAsState()

    AppScaffold(
        blurContentReady = !uiState.isLoading,
        topBar = {
            AppTopBar(
                title = "回复我的",
                navigationIcon = {
                    AppIconButton(onClick = onBack) {
                        AppIcon(rememberAppBackIcon(), contentDescription = "返回")
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            when {
                uiState.isLoading -> com.android.purebilibili.core.ui.skeleton.ContentMediaListSkeleton(
                    modifier = Modifier.fillMaxSize(),
                    useUserRow = true,
                    itemCount = 8,
                )
                uiState.error != null -> MessageFeedError(
                    text = uiState.error ?: "加载失败",
                    onRetry = viewModel::loadInitial,
                    modifier = Modifier.fillMaxSize()
                )
                uiState.items.isEmpty() -> MessageFeedEmpty(
                    text = "暂无回复消息",
                    modifier = Modifier.fillMaxSize()
                )
                // Scaffold body already below topBar.
                else -> AdaptivePullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = viewModel::refresh,
                    indicatorTopInset = paddingValues.calculateTopPadding(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = paddingValues.calculateTopPadding() + 12.dp, bottom = paddingValues.calculateBottomPadding() + 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(uiState.items, key = { it.id }) { item ->
                            ReplyMeCard(
                                item = item,
                                onClick = {
                                    item.item?.let { content ->
                                        buildMessageFeedCommentNavigationLink(
                                            nativeUri = content.nativeUri,
                                            uri = content.uri,
                                            businessId = content.businessId,
                                            subjectId = content.subjectId,
                                            rootId = content.rootId,
                                            sourceId = content.sourceId,
                                            targetId = content.targetId,
                                            business = content.business
                                        )?.let(onOpenLink)
                                    }
                                },
                                onUserClick = {
                                    item.user?.mid?.takeIf { it > 0 }?.let(onOpenSpace)
                                },
                                onRemove = { viewModel.remove(item.id) }
                            )
                        }
                        item {
                            MessageFeedLoadMore(
                                isLoadingMore = uiState.isLoadingMore,
                                hasMore = uiState.hasMore,
                                onLoadMore = viewModel::loadMore
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplyMeCard(
    item: MessageFeedReplyItem,
    onClick: () -> Unit,
    onUserClick: () -> Unit,
    onRemove: () -> Unit
) {
    MessageFeedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            MessageFeedAvatar(
                avatarUrl = item.user?.avatar.orEmpty(),
                modifier = Modifier.clickable(onClick = onUserClick)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                AppText(
                    text = buildString {
                        append(item.user?.nickname.orEmpty().ifBlank { "用户" })
                        append(if (item.isMulti == 1) " 等人" else "")
                        append(" 回复了你的")
                        append(item.item?.business.orEmpty().ifBlank { "内容" })
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                item.item?.sourceContent?.takeIf { it.isNotBlank() }?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    AppText(text = it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                firstNonBlank(item.item?.targetReplyContent, item.item?.rootReplyContent)?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    AppText(
                        text = "| $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppText(
                        text = formatMessageFeedTime(item.replyTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    AppText(
                        text = "删除",
                        modifier = Modifier.clickable(onClick = onRemove),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

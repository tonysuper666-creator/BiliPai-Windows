package com.android.purebilibili.feature.video.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.purebilibili.core.network.NetworkModule
import com.android.purebilibili.core.store.TokenManager
import com.android.purebilibili.core.ui.AppModalBottomSheet
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.core.ui.components.AppButton
import com.android.purebilibili.core.ui.components.AppCheckbox
import com.android.purebilibili.core.ui.components.AppListItem
import com.android.purebilibili.core.ui.components.AppSearchField
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.data.model.response.FollowingUser
import com.android.purebilibili.data.repository.MessageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoShareToFollowingDialog(
    payload: VideoSharePayload,
    onDismiss: () -> Unit,
    onSuccess: (Int) -> Unit,
) {
    val selfMid = TokenManager.midCache?.takeIf { it > 0L }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isLandscape = isLandscapeVideoShare()
    val sheetBounce = rememberVideoShareSheetBounce(sheetState, isLandscape)
    var followings by remember(payload.bvid) { mutableStateOf<List<FollowingUser>>(emptyList()) }
    var selectedIds by remember(payload.bvid) { mutableStateOf<Set<Long>>(emptySet()) }
    var searchQuery by remember(payload.bvid) { mutableStateOf("") }
    var nextPage by remember(payload.bvid) { mutableStateOf(1) }
    var total by remember(payload.bvid) { mutableStateOf(0) }
    var loading by remember(payload.bvid) { mutableStateOf(false) }
    var sending by remember(payload.bvid) { mutableStateOf(false) }
    var error by remember(payload.bvid) { mutableStateOf<String?>(null) }
    var sendStatus by remember(payload.bvid) { mutableStateOf<String?>(null) }
    val listMaxHeight = (LocalConfiguration.current.screenHeightDp - 320).coerceIn(180, 440).dp

    suspend fun loadNextPage() {
        if (selfMid == null || loading) return
        loading = true
        error = null
        try {
            val response = NetworkModule.api.getFollowings(selfMid, pn = nextPage, ps = 50)
            if (response.code != 0 || response.data == null) {
                error = response.message.ifBlank { "关注列表加载失败 (${response.code})" }
                return
            }
            val data = requireNotNull(response.data)
            val page = data.list.orEmpty().filter { it.mid > 0L && it.mid != selfMid }
            followings = (followings + page).distinctBy(FollowingUser::mid)
            total = data.total
            nextPage++
            if (page.isEmpty() || nextPage > (total + 49) / 50) total = followings.size
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "关注列表加载失败"
        } finally {
            loading = false
        }
    }

    suspend fun loadRemainingPages() {
        do {
            val currentPage = nextPage
            loadNextPage()
        } while (error == null && nextPage > currentPage && followings.size < total)
    }

    LaunchedEffect(selfMid, payload.bvid) {
        if (selfMid != null) loadRemainingPages()
    }

    val visibleUsers = remember(followings, searchQuery) {
        followings.filter { searchQuery.isBlank() ||
            it.uname.contains(searchQuery.trim(), ignoreCase = true) ||
            it.mid.toString().contains(searchQuery.trim()) }
    }
    val recipients = remember(selectedIds, followings, selfMid) {
        resolveVideoShareRecipientIds(selectedIds, followings, selfMid ?: 0L)
    }
    AppModalBottomSheet(
        onDismissRequest = { if (!sending) onDismiss() },
        sheetState = sheetState,
        presentationOverride = videoSharePresentation(isLandscape),
        sheetSurfaceModifier = sheetBounce,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(
                horizontal = AppSpacingTokens.Large,
                vertical = AppSpacingTokens.Small,
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacingTokens.Small),
        ) {
            AppText("分享给 B 站好友", style = MaterialTheme.typography.headlineSmall)
            AppText("从已关注的人中多选，发送视频标题和链接到私信", style = MaterialTheme.typography.bodySmall)
            if (selfMid == null) {
                AppText("请先登录 B 站账号")
            } else {
                AppSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = "搜索关注用户",
                    modifier = Modifier.fillMaxWidth(),
                )
                AppText(
                    "已加载 ${followings.size}/${total.coerceAtLeast(followings.size)} 人，已选 ${recipients.size} 人",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = listMaxHeight)) {
                    items(visibleUsers, key = FollowingUser::mid) { user ->
                        AppListItem(
                            headlineContent = { AppText(user.uname.ifBlank { "用户${user.mid}" }) },
                            leadingContent = {
                                AsyncImage(
                                    model = user.face,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp).clip(CircleShape),
                                    contentScale = ContentScale.Crop,
                                )
                            },
                            trailingContent = {
                                AppCheckbox(
                                    checked = user.mid in selectedIds,
                                    onCheckedChange = { checked ->
                                        if (!sending) selectedIds = if (checked) selectedIds + user.mid
                                            else selectedIds - user.mid
                                    },
                                )
                            },
                            modifier = Modifier.clickable(enabled = !sending) {
                                selectedIds = if (user.mid in selectedIds) selectedIds - user.mid
                                    else selectedIds + user.mid
                            },
                        )
                    }
                    if (loading) item { AppText("正在加载关注列表…") }
                    if (error != null) item { AppText(error.orEmpty()) }
                    if (!loading && error != null) {
                        item {
                            AppButton(
                                onClick = { scope.launch { loadRemainingPages() } },
                                enabled = !sending,
                                modifier = Modifier.fillMaxWidth(),
                            ) { AppText("重试加载关注") }
                        }
                    }
                    if (!loading && followings.isEmpty() && error == null) {
                        item { AppText("暂无关注用户") }
                    }
                    if (!loading && visibleUsers.isEmpty() && followings.isNotEmpty()) {
                        item { AppText(if (loading) "继续搜索中…" else "没有匹配的关注用户") }
                    }
                }
                sendStatus?.let { AppText(it, style = MaterialTheme.typography.bodySmall) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacingTokens.Small)) {
                AppButton(
                    onClick = onDismiss,
                    enabled = !sending,
                    modifier = Modifier.weight(1f),
                ) { AppText("取消") }
                AppButton(
                    onClick = {
                        if (sending || recipients.isEmpty()) return@AppButton
                        sending = true
                        sendStatus = null
                        scope.launch {
                            val failed = mutableSetOf<Long>()
                            var sent = 0
                            var firstFailure: String? = null
                            recipients.forEachIndexed { index, receiverId ->
                                val result = MessageRepository.sendTextMessage(
                                    receiverId = receiverId,
                                    content = payload.text,
                                )
                                if (result.isSuccess) {
                                    sent++
                                } else {
                                    failed += receiverId
                                    if (firstFailure == null) {
                                        firstFailure = result.exceptionOrNull()?.message
                                    }
                                }
                                if (index < recipients.lastIndex) delay(300)
                            }
                            sending = false
                            if (failed.isEmpty()) {
                                onSuccess(sent)
                                onDismiss()
                            } else {
                                selectedIds = failed
                                sendStatus = "已发送 $sent 人，${failed.size} 人未发送" +
                                    firstFailure?.let { "（$it）" }.orEmpty() +
                                    "；可重试选中的失败对象"
                            }
                        }
                    },
                    enabled = recipients.isNotEmpty() && !sending,
                    modifier = Modifier.weight(1f),
                ) { AppText(if (sending) "发送中…" else "发送给 ${recipients.size} 人") }
            }
        }
    }
}

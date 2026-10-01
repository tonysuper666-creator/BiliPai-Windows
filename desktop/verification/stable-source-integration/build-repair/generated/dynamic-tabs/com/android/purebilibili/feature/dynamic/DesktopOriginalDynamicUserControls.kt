// GENERATED from app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicScreen.kt; do not edit.
// LF-normalized SHA-256: f3f74942a9ce57a2dfcbbc0312fdd0ae45ffb92add60e8ce67411cc9a28c2a44
package com.android.purebilibili.feature.dynamic
import androidx.compose.runtime.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.feature.dynamic.components.*
import coil3.compose.AsyncImage
import coil3.request.crossfade
import coil3.compose.LocalPlatformContext as LocalContext
@Composable
internal fun HorizontalUserList(
    users: List<SidebarUser>,
    selectedUserId: Long?,
    selfUid: Long = 0L,
    listState: androidx.compose.foundation.lazy.LazyListState,
    showHiddenUsers: Boolean,
    hiddenCount: Int,
    uplistUpdateMids: Set<Long> = emptySet(),
    onUserClick: (Long?) -> Unit,
    onToggleShowHidden: () -> Unit,
    onTogglePin: (Long) -> Unit,
    onToggleHidden: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    // 移除 Surface，直接使用 LazyRow 配合传入的 modifier，实现背景透明
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(
            horizontal = resolveDynamicHorizontalUserListHorizontalPadding(),
            vertical = resolveHorizontalUserListVerticalPaddingDp().dp
        ),
        horizontalArrangement = Arrangement.spacedBy(resolveDynamicHorizontalUserListSpacing()),
        modifier = modifier
    ) {
            if (hiddenCount > 0 || showHiddenUsers) {
                item(key = "hidden_toggle") {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .padding(AppSpacingTokens.ExtraSmall)
                            .combinedClickable(
                                onClick = onToggleShowHidden,
                                onLongClick = onToggleShowHidden
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .size(AppSpacingTokens.TripleExtraLarge)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            AppIcon(
                                imageVector = if (showHiddenUsers) {
                                    rememberAppVisibilityOnIcon()
                                } else {
                                    rememberAppVisibilityOffIcon()
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(AppSpacingTokens.ExtraSmall))
                        AppText(
                            text = if (showHiddenUsers) "隐藏中" else "显示隐藏",
                            fontSize = MaterialTheme.typography.labelSmall.fontSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }

            // UP 主头像列表
            items(users, key = { it.uid }) { user ->
                val isSelected = isDynamicUpPanelItemSelected(selectedUserId, user.uid)
                val isShortcut = isDynamicUpPanelShortcut(user.uid, selfUid)
                var showMenu by remember { mutableStateOf(false) }
                val displayName = if (user.isHidden) {
                    "${user.name}(隐)"
                } else {
                    user.name
                }

                Box {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .combinedClickable(
                                onClick = { onUserClick(user.uid) },
                                onLongClick = { if (!isShortcut) showMenu = true }
                            )
                            .padding(AppSpacingTokens.ExtraSmall)
                            .alpha(if (user.isHidden) 0.5f else 1f)
                    ) {
                        Box {
                            val hasUpdate = user.uid in uplistUpdateMids
                            Box(
                                modifier = Modifier
                                    .size(AppSpacingTokens.TripleExtraLarge)
                                    .clip(CircleShape)
                                    .then(
                                        when {
                                            isSelected -> Modifier.border(
                                                AppSpacingTokens.Micro,
                                                MaterialTheme.colorScheme.primary,
                                                CircleShape
                                            )
                                            // 有新动态的 UP：主题色圆环提示，比单独的小红点更显眼
                                            hasUpdate -> Modifier.border(
                                                2.dp,
                                                MaterialTheme.colorScheme.primary,
                                                CircleShape
                                            )
                                            else -> Modifier
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = coil3.request.ImageRequest.Builder(LocalContext.current)
                                        .data(user.face.let { if (it.startsWith("http://")) it.replace("http://", "https://") else it })
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            // UP 未读提示点：有新动态时显示主题色小圆点
                            if (hasUpdate) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(AppSpacingTokens.Small)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                        if (shouldShowDynamicUserLiveBadge(user.isLive)) {
                            DynamicUserLiveBadge(modifier = Modifier.padding(top = AppSpacingTokens.Micro))
                        }
                        Spacer(modifier = Modifier.height(AppSpacingTokens.ExtraSmall))
                        AppText(
                            displayName,
                            fontSize = MaterialTheme.typography.labelSmall.fontSize,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            // 预留高度已覆盖名称基线；此处再放宽名字宽度上限，
                            // 避免较长昵称在窄视口下被过早省略号截断。
                            // LazyRow 仍会在屏幕边缘自然裁切超出视口的内容。
                            modifier = Modifier.widthIn(
                                min = AppSpacingTokens.TripleExtraLarge + AppSpacingTokens.Large,
                                max = 128.dp,
                            )
                        )
                    }

                    AppDropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        AppDropdownMenuItem(
                            text = { AppText(if (user.isPinned) "取消置顶" else "置顶") },
                            onClick = {
                                showMenu = false
                                onTogglePin(user.uid)
                            }
                        )
                        AppDropdownMenuItem(
                            text = { AppText(if (user.isHidden) "取消隐藏" else "隐藏") },
                            onClick = {
                                showMenu = false
                                onToggleHidden(user.uid)
                            }
                        )
                    }
                }
            }
        }
    }

@Composable
internal fun DynamicSelectedUserFeedHeader(
    userName: String,
    selectedFilter: DynamicUserContentFilter,
    onFilterSelected: (DynamicUserContentFilter) -> Unit,
    onOpenUser: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacingTokens.Large, vertical = AppSpacingTokens.Small),
        verticalArrangement = Arrangement.spacedBy(AppSpacingTokens.ExtraSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(
                text = when {
                    userName == "我" -> "我的动态"
                    userName.isNotBlank() -> "$userName 的动态"
                    else -> "UP 动态"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            com.android.purebilibili.core.ui.components.AppTextButton(onClick = onOpenUser) {
                AppText("查看主页")
            }
        }
        val filters = DynamicUserContentFilter.entries
        DynamicAdaptiveSegmentedControl(
            items = filters.map(DynamicUserContentFilter::label),
            selectedIndex = filters.indexOf(selectedFilter).coerceAtLeast(0),
            onSelected = { index -> filters.getOrNull(index)?.let(onFilterSelected) },
            itemWidth = 96.dp,
            height = AppChromeSizeTokens.MinimumTouchTarget,
            indicatorHeight = 42.dp,
            labelFontSize = MaterialTheme.typography.labelLarge.fontSize,
            modifier = Modifier.width(304.dp),
        )
    }
}

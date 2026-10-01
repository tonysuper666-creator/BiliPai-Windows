package com.android.purebilibili.feature.home.components
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.navigation.ScreenRoutes
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Search

enum class BottomNavItem(
    val label: String,
    val labelRes: String,
    val contentDescriptionRes: String,
    val legacyAliases: List<String> = emptyList(),
    val route: String // [新增] 路由地址
) {
    HOME(
        "推荐",
        "bottom_nav_home",
        "bottom_nav_home",
        emptyList(),
        ScreenRoutes.Home.route
    ),
    DYNAMIC(
        "动态",
        "bottom_nav_dynamic",
        "bottom_nav_dynamic",
        emptyList(),
        ScreenRoutes.Dynamic.route
    ),
    STORY(
        "短视频",
        "bottom_nav_story",
        "bottom_nav_story",
        emptyList(),
        ScreenRoutes.Story.baseRoute
    ),
    HISTORY(
        "历史",
        "bottom_nav_history",
        "bottom_nav_history_desc",
        listOf("历史记录"),
        ScreenRoutes.History.route
    ),
    LISTEN_VIDEO(
        "听视频",
        "bottom_nav_listen_video",
        "bottom_nav_listen_video_desc",
        listOf("音乐"),
        ScreenRoutes.ListenVideo.route
    ),
    PROFILE(
        "我的",
        "bottom_nav_profile",
        "bottom_nav_profile_desc",
        listOf("个人中心"),
        ScreenRoutes.Profile.route
    ),
    FAVORITE(
        "收藏",
        "bottom_nav_favorite",
        "bottom_nav_favorite_desc",
        listOf("收藏夹"),
        ScreenRoutes.Favorite.route
    ),
    LIVE(
        "直播",
        "bottom_nav_live",
        "bottom_nav_live",
        emptyList(),
        ScreenRoutes.LiveList.route
    ),
    WATCHLATER(
        "稍后看",
        "bottom_nav_watch_later",
        "bottom_nav_watch_later_desc",
        listOf("稍后再看"),
        ScreenRoutes.WatchLater.route
    ),
    SETTINGS(
        "设置",
        "bottom_nav_settings",
        "bottom_nav_settings",
        emptyList(),
        ScreenRoutes.Settings.route
    ),
    PLUGINS(
        "插件",
        "plugins_center_title",
        "plugins_center_title",
        listOf("插件中心"),
        ScreenRoutes.PluginsSettings.createRoute()
    )
}

internal enum class SharedFloatingBottomBarIconStyle {
    MATERIAL,
    MIUIX
}

internal fun normalizeBottomBarLabelMode(requestedLabelMode: Int): Int = when (requestedLabelMode) {
    0, 1, 2 -> requestedLabelMode
    else -> 0
}

internal fun resolveBiliPaiFloatingBottomBarWidth(
    containerWidth: Dp,
    itemCount: Int,
    minEdgePadding: Dp,
    labelMode: Int = 0,
    cornerRadius: Dp = AppSpacingTokens.DoubleExtraLarge
): Dp {
    val safeItemCount = itemCount.coerceAtLeast(1)
    val contentPadding = AppSpacingTokens.ExtraSmall
    val minimumItemWidth = cornerRadius.coerceAtLeast(AppSpacingTokens.None) * 2
    val contentPreferredItemWidth = when (normalizeBottomBarLabelMode(labelMode)) {
        1 -> minimumItemWidth
        2 -> AppSpacingTokens.TripleExtraLarge + AppSpacingTokens.Large + AppSpacingTokens.ExtraSmall
        else -> AppSpacingTokens.TripleExtraLarge + AppSpacingTokens.ExtraLarge + AppSpacingTokens.ExtraSmall
    }
    val preferredItemWidth = maxOf(contentPreferredItemWidth, minimumItemWidth)
    val preferredWidth = (preferredItemWidth * safeItemCount) + (contentPadding * 2)
    val minimumWidth = (minimumItemWidth * safeItemCount) + (contentPadding * 2)
    val widthCap = (containerWidth - (minEdgePadding * 2)).coerceAtLeast(minimumWidth)
    return minOf(preferredWidth, widthCap).coerceAtMost(containerWidth)
}

internal fun resolveMaterialBottomBarIcon(
    item: BottomNavItem,
    selected: Boolean
): ImageVector = when (item) {
    BottomNavItem.HOME -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
    BottomNavItem.DYNAMIC -> if (selected) Icons.Filled.Notifications else Icons.Outlined.NotificationsNone
    BottomNavItem.STORY -> if (selected) Icons.Filled.PlayCircle else Icons.Outlined.PlayCircleOutline
    BottomNavItem.HISTORY -> if (selected) Icons.Filled.History else Icons.Outlined.History
    BottomNavItem.LISTEN_VIDEO -> if (selected) Icons.Filled.LibraryMusic else Icons.Outlined.LibraryMusic
    BottomNavItem.PROFILE -> if (selected) Icons.Filled.Person else Icons.Outlined.Person
    BottomNavItem.FAVORITE -> if (selected) Icons.Filled.CollectionsBookmark else Icons.Outlined.CollectionsBookmark
    BottomNavItem.LIVE -> if (selected) Icons.Filled.LiveTv else Icons.Outlined.LiveTv
    BottomNavItem.WATCHLATER -> if (selected) Icons.Filled.WatchLater else Icons.Outlined.WatchLater
    BottomNavItem.SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
    BottomNavItem.PLUGINS -> if (selected) Icons.Filled.Extension else Icons.Outlined.Extension
}

@Composable
internal fun resolveHomeNavigationBarIcon(
    item: BottomNavItem,
    selected: Boolean
): ImageVector = resolveMiuixBottomNavigationIcon(item, selected)

@Composable
internal fun resolveSharedBottomBarIcon(
    item: BottomNavItem,
    selected: Boolean,
    iconStyle: SharedFloatingBottomBarIconStyle
): ImageVector = when (iconStyle) {
    SharedFloatingBottomBarIconStyle.MATERIAL -> resolveMaterialBottomBarIcon(item, selected)
    SharedFloatingBottomBarIconStyle.MIUIX -> resolveHomeNavigationBarIcon(item, selected)
}

@Composable
internal fun BiliPaiBottomBarSearchVisualContent(
    expanded: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    contentColor: Color,
    accentColor: Color,
    iconScale: () -> Float,
    fieldAlpha: () -> Float,
    interactive: Boolean,
    iconStyle: SharedFloatingBottomBarIconStyle,
    pendingUserImeRequest: Boolean = false,
    onUserImeRequestConsumed: () -> Unit = {}
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val currentOnUserImeRequestConsumed by rememberUpdatedState(onUserImeRequestConsumed)
    // 仅用户点按请求 IME；滚动/自动展开只展开几何，不拉起键盘。
    LaunchedEffect(pendingUserImeRequest, expanded, interactive) {
        if (!shouldRequestBottomBarSearchIme(pendingUserImeRequest)) return@LaunchedEffect
        if (expanded && interactive) {
            runCatching { focusRequester.requestFocus() }
            currentOnUserImeRequestConsumed()
        }
    }
    LaunchedEffect(expanded) {
        if (!expanded) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (expanded) AppSpacingTokens.Large else AppSpacingTokens.None),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(AppChromeSizeTokens.MinimumTouchTarget)
                .then(
                    if (expanded && interactive) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onSubmit
                        )
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            AppIcon(
                imageVector = if (iconStyle == SharedFloatingBottomBarIconStyle.MIUIX) {
                    MiuixIcons.Search
                } else {
                    Icons.Outlined.Search
                },
                contentDescription = "搜索",
                tint = contentColor,
                modifier = Modifier
                    .size(AppSpacingTokens.ExtraLarge)
                    .graphicsLayer {
                        val scale = iconScale()
                        scaleX = scale
                        scaleY = scale
                    }
            )
        }
        if (expanded) {
            Spacer(modifier = Modifier.width(AppSpacingTokens.Small + AppSpacingTokens.Micro))
            if (interactive) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = contentColor),
                    cursorBrush = SolidColor(accentColor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .weight(1f)
                        .graphicsLayer { alpha = fieldAlpha() },
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isBlank()) {
                                AppText(
                                    text = "搜索",
                                    color = contentColor.copy(alpha = 0.45f),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer { alpha = fieldAlpha() },
                    contentAlignment = Alignment.CenterStart
                ) {
                    AppText(
                        text = query.ifBlank { "搜索" },
                        color = if (query.isBlank()) {
                            contentColor.copy(alpha = 0.45f)
                        } else {
                            contentColor
                        },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

internal fun shouldRequestBottomBarSearchIme(
    pendingUserImeRequest: Boolean
): Boolean = pendingUserImeRequest

/**
 * 列表滚动期间若搜索框仍持有焦点，系统可能再次拉起 IME；
 * 滚动开始即清焦点，保证「只有点按才弹输入法」。
 */

@Composable
internal fun resolveBottomNavItemLabel(
    item: BottomNavItem,
    customLabels: Map<String, String> = emptyMap(),
): String = customLabels[item.name]?.takeIf(String::isNotBlank) ?: com.bilipai.desktop.appearance.LocalDesktopStrings.current[item.labelRes]

@Composable
internal fun resolveBottomNavItemContentDescription(item: BottomNavItem): String =
    com.bilipai.desktop.appearance.LocalDesktopStrings.current[item.contentDescriptionRes]

internal fun resolveBottomNavItemLookupKeys(item: BottomNavItem): Set<String> {
    return linkedSetOf(
        item.name,
        item.name.lowercase(),
        item.name.uppercase(),
        item.route,
        item.route.lowercase(),
        item.route.uppercase(),
        item.label,
        item.label.lowercase(),
        *item.legacyAliases.toTypedArray()
    )
}

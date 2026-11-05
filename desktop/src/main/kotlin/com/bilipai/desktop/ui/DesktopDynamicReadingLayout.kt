package com.bilipai.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppContentColors
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.ui.rememberAppDynamicIcon
import com.android.purebilibili.feature.dynamic.*
import com.android.purebilibili.feature.dynamic.components.DynamicUserLiveBadge
import com.android.purebilibili.feature.dynamic.components.rememberDynamicUserAvatarRequest
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_HOVER_PERCENT
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_RADIUS_ROW_DP
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_ROW_NAV_DP
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_SELECTED_FILL_PERCENT

internal data class DesktopDynamicReadingGeometry(val column: Float, val columnX: Float, val asideX: Float?)

internal fun desktopDynamicReadingGeometry(width: Float, supportsAside: Boolean): DesktopDynamicReadingGeometry {
    val w = width.coerceAtLeast(0f)
    val aside = supportsAside && w >= 992f
    val column = if (aside) (w - 352f).coerceIn(640f, 760f)
        else if (w < 688f) w else (w - 48f).coerceAtMost(760f)
    val start = (w - column - if (aside) 304f else 0f) / 2f
    return DesktopDynamicReadingGeometry(column, start, if (aside) start + column + 24f else null)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun DesktopDynamicFollowingAside(
    users: List<SidebarUser>, selectedUid: Long?, selfUid: Long, unread: Set<Long>,
    hiddenCount: Int, showHidden: Boolean, onSelect: (Long?) -> Unit,
    onShowHidden: () -> Unit, onPin: (Long) -> Unit, onHide: (Long) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "heading") { AppText("关注的 UP 主", style = MaterialTheme.typography.titleSmall) }
        items(users, key = { it.uid }) { user ->
            val selected = isDynamicUpPanelItemSelected(selectedUid, user.uid)
            val shortcut = isDynamicUpPanelShortcut(user.uid, selfUid)
            var menu by remember(user.uid) { mutableStateOf(false) }
            val available = appGlassMenuAvailable()
            val interaction = remember(user.uid) { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            AppGlassMenuAnchor(onOpen = { menu = true }, enabled = !shortcut) {
                Row(Modifier.fillMaxWidth().height(DESKTOP_KIT_ROW_NAV_DP.dp).clip(RoundedCornerShape(DESKTOP_KIT_RADIUS_ROW_DP.dp))
                    .hoverable(interaction)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (selected) DESKTOP_KIT_SELECTED_FILL_PERCENT / 100f else if (hovered) DESKTOP_KIT_HOVER_PERCENT / 100f else 0f))
                    .combinedClickable(onClick = { onSelect(user.uid) }, onLongClick = if (available || shortcut) null else ({ menu = true }))
                    .alpha(if (user.isHidden) .5f else 1f).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        if (isDynamicUpPanelAllShortcut(user.uid)) {
                            AppIcon(rememberAppDynamicIcon(), contentDescription = user.name, tint = AppContentColors.accent())
                        } else if (selfUid > 0 && user.uid == selfUid) {
                            DesktopDynamicSelfAvatar(user.face, selected)
                        } else {
                            PageAwareAsyncImage(model = rememberDynamicUserAvatarRequest(user.face), contentDescription = null,
                                modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                        }
                        if (user.uid in unread) Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(CircleShape).background(AppContentColors.accent()))
                    }
                    Spacer(Modifier.width(8.dp))
                    AppText(if (user.isHidden) "${user.name}(隐)" else user.name, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    if (shouldShowDynamicUserLiveBadge(user.isLive)) DynamicUserLiveBadge()
                }
                AppDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    AppDropdownMenuItem(text = { AppText(if (user.isPinned) "取消置顶" else "置顶") }, onClick = { menu = false; onPin(user.uid) })
                    AppDropdownMenuItem(text = { AppText(if (user.isHidden) "取消隐藏" else "隐藏") }, onClick = { menu = false; onHide(user.uid) })
                }
            }
        }
        if (hiddenCount > 0 || showHidden) item(key = "hidden_toggle") {
            AppText(if (showHidden) "隐藏中" else "显示隐藏 ($hiddenCount)", modifier = Modifier.clickable(onClick = onShowHidden).padding(8.dp))
        }
    }
}

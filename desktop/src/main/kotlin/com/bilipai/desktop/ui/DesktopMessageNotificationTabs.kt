package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppContentColors
import com.android.purebilibili.core.ui.AppSurfaceTokens
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.appActionClickable
import com.android.purebilibili.feature.message.MessageCenterDestination
import com.android.purebilibili.feature.message.MessageCenterTopItem

internal fun desktopMessageNotificationTabTag(destination: MessageCenterDestination) = "message_notification_tab_${destination.name}"

/** Spec §6/§7 (K7): the four notification entries as one row of 32dp capsule tabs; unread counts
 * in the theme primary. A click is the entry's own [onItemClick], so navigation is unchanged. */
@Composable
internal fun DesktopMessageNotificationTabs(
    items: List<MessageCenterTopItem>,
    onItemClick: (MessageCenterDestination) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            Row(
                modifier = Modifier.height(32.dp).clip(CircleShape).background(AppSurfaceTokens.surfaceContainer())
                    .appActionClickable(role = Role.Tab) { onItemClick(item.destination) }
                    .testTag(desktopMessageNotificationTabTag(item.destination))
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(item.title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                if (item.unreadCount > 0) Box(
                    Modifier.defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                        .background(AppContentColors.accent(), CircleShape).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText(if (item.unreadCount > 99) "99+" else item.unreadCount.toString(),
                        color = AppContentColors.onAccent(), style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

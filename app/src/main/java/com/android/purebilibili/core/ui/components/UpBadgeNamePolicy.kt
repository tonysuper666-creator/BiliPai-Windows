package com.android.purebilibili.core.ui.components

import androidx.compose.ui.graphics.Color
import com.android.purebilibili.core.util.FormatUtils

/**
 * UP 主名字颜色规则（空间页与视频详情页统一）：
 * 有效年度大会员 → B 站粉，有效月度大会员 → 主题次色，其余 → 常规前景色。
 */
internal fun resolveUpNameColor(
    vipStatus: Int,
    vipType: Int,
    onSurface: Color,
    secondary: Color,
): Color = when {
    vipStatus == 1 && vipType == 2 -> Color(0xFFFB7299)
    vipStatus == 1 -> secondary
    else -> onSurface
}

internal fun resolveUpStatsText(
    followerCount: Int?,
    videoCount: Int?
): String? {
    val parts = mutableListOf<String>()
    val safeFollowerCount = followerCount?.takeIf { it > 0 }
    val safeVideoCount = videoCount?.takeIf { it > 0 }

    if (safeFollowerCount != null) {
        parts += "粉丝 ${FormatUtils.formatStat(safeFollowerCount.toLong())}"
    }
    if (safeVideoCount != null) {
        parts += "视频 ${FormatUtils.formatStat(safeVideoCount.toLong())}"
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

internal fun shouldRenderUpBadgeTrailingSlot(
    hasTrailingContent: Boolean,
    reserveTrailingSlot: Boolean
): Boolean {
    return hasTrailingContent || reserveTrailingSlot
}

internal fun shouldRenderUserUpBadge(showUpBadge: Boolean): Boolean {
    return showUpBadge
}

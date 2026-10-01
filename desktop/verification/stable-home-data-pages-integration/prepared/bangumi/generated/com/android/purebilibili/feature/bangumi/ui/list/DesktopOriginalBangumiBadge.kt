// 文件路径: feature/bangumi/ui/list/BangumiListComponents.kt
package com.android.purebilibili.feature.bangumi.ui.list
import com.android.purebilibili.core.ui.components.AppText

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.AppSurfaceTokens
import com.android.purebilibili.core.ui.ContainerLevel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.purebilibili.core.ui.components.AppSurface
import com.android.purebilibili.core.ui.components.AppTextButton
import com.android.purebilibili.feature.bangumi.resolveBangumiCoverBadgeColors

@Composable
fun BangumiBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary
) {
    val colorScheme = MaterialTheme.colorScheme
    val badgeColors = remember(
        containerColor,
        colorScheme.onPrimary,
        colorScheme.surface,
        colorScheme.onSurface,
    ) {
        resolveBangumiCoverBadgeColors(
            primary = containerColor,
            onPrimary = colorScheme.onPrimary,
            surface = colorScheme.surface,
            onSurface = colorScheme.onSurface,
        )
    }
    AppSurface(
        modifier = modifier,
        color = badgeColors.containerColor,
        shape = AppShapes.container(ContainerLevel.Tag)
    ) {
        AppText(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = badgeColors.contentColor,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

package com.android.purebilibili.feature.video.ui.components
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppSegmentOption
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton
import com.android.purebilibili.core.ui.components.AppThemeAdaptiveTabRow
import com.android.purebilibili.core.ui.components.AppTabRowIndicatorPresentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.android.purebilibili.core.ui.components.AppIconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.purebilibili.core.ui.AppChromeSizeTokens
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.feature.home.components.BottomBarLiquidSegmentedControl
import com.android.purebilibili.feature.video.viewmodel.CommentSortMode
import top.yukonga.miuix.kmp.blur.Backdrop as MiuixBackdrop
import kotlin.math.ceil

/**
 * 评论排序分段控件，放置在详情页顶栏的“评论”标签右侧。
 */
@Composable
fun CommentSortFilterBar(
    sortMode: CommentSortMode,
    onSortModeChange: (CommentSortMode) -> Unit,
    modifier: Modifier = Modifier,
    miuixBackdrop: MiuixBackdrop? = null,
    liquidGlassEffectsEnabled: Boolean = true,
    onSearchClick: (() -> Unit)? = null,
) {
    val sortModes = remember { listOf(CommentSortMode.HOT, CommentSortMode.NEWEST) }
    val spec = remember(sortModes.size) {
        resolveCommentSortSegmentedControlSpec(itemCount = sortModes.size)
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onSearchClick != null) {
            AppIconButton(
                onClick = onSearchClick,
                modifier = Modifier.size(spec.heightDp.dp),
            ) {
                AppIcon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "搜索评论",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier.requiredWidth((spec.itemWidthDp * sortModes.size).dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            CommentSegmentedControl(
                items = sortModes.map { it.label },
                selectedIndex = sortModes.indexOf(sortMode).coerceAtLeast(0),
                onScaleChange = { index ->
                    sortModes.getOrNull(index)?.let(onSortModeChange)
                },
                modifier = Modifier.fillMaxWidth(),
                miuixBackdrop = miuixBackdrop,
                liquidGlassEffectsEnabled = liquidGlassEffectsEnabled,
            )
        }
    }
}

/**
 * Bottom-bar matched segmented control.
 */
@Composable
fun CommentSegmentedControl(
    items: List<String>,
    selectedIndex: Int,
    onScaleChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    miuixBackdrop: MiuixBackdrop? = null,
    liquidGlassEffectsEnabled: Boolean = true,
) {
    val spec = remember(items.size) {
        resolveCommentSortSegmentedControlSpec(itemCount = items.size)
    }
    BottomBarLiquidSegmentedControl(
        items = items,
        selectedIndex = selectedIndex,
        onSelected = onScaleChange,
        itemWidth = spec.itemWidthDp.dp,
        height = spec.heightDp.dp,
        indicatorHeight = spec.indicatorHeightDp.dp,
        labelFontSize = 13.sp,
        modifier = modifier,
        miuixBackdrop = miuixBackdrop,
        liquidGlassEffectsEnabled = liquidGlassEffectsEnabled,
        dragSelectionEnabled = items.size > 1,
        tapPressRefractionEnabled = true,
        // The detail header owns a fixed 66dp-per-item width. Keep native Miuix items
        // evenly divided when its non-glass outer track is transparent.
        forceEqualWidth = true,
    )
}

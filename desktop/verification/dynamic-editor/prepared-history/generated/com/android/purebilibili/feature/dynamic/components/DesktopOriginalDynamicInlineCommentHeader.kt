// GENERATED from app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicCommentSheet.kt; do not edit.
// LF-normalized SHA-256: cbc5bbcabd8ef19c6c73fb13dca6ff7248bf208cc8128a6dd240fc9f93233c05
package com.android.purebilibili.feature.dynamic.components
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppChromeSizeTokens
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.feature.dynamic.resolveDynamicCommentCountLabel
import com.android.purebilibili.feature.video.viewmodel.CommentSortMode
import top.yukonga.miuix.kmp.blur.Backdrop as MiuixBackdrop
@Composable
private fun DynamicCommentSortControl(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    miuixBackdrop: MiuixBackdrop? = null,
) {
    if (items.isEmpty()) return
    val spec = remember(items.size) {
        resolveDynamicCommentSortControlSpec(itemCount = items.size)
    }
    Box(
        modifier = modifier.requiredWidth((spec.itemWidthDp * items.size).dp),
    ) {
        DynamicAdaptiveSegmentedControl(
            items = items,
            selectedIndex = selectedIndex,
            onSelected = onSelected,
            itemWidth = spec.itemWidthDp.dp,
            height = spec.heightDp.dp,
            indicatorHeight = spec.indicatorHeightDp.dp,
            labelFontSize = MaterialTheme.typography.bodySmall.fontSize,
            // Keep the renderer's fillMaxWidth() inside the fixed-width outer box so
            // the whole latest/hottest control remains aligned to the header's end.
            modifier = Modifier.fillMaxWidth(),
            backdrop = miuixBackdrop,
        )
    }
}

internal data class DynamicCommentSortControlSpec(
    val itemWidthDp: Int,
    val heightDp: Int,
    val indicatorHeightDp: Int,
)

internal fun resolveDynamicCommentSortControlSpec(itemCount: Int) = DynamicCommentSortControlSpec(
    // Keep the beta.36 dynamic comment layout: two-option sorting tabs are 66dp each.
    itemWidthDp = if (itemCount >= 4) 56 else 66,
    heightDp = AppChromeSizeTokens.BottomBarMatchedSegmentedControlHeightDp,
    indicatorHeightDp = AppChromeSizeTokens.BottomBarMatchedSegmentedIndicatorHeightDp,
)

@Composable
fun DynamicInlineCommentHeader(
    totalCount: Int,
    sortMode: CommentSortMode,
    onSortModeChange: (CommentSortMode) -> Unit,
    miuixBackdrop: MiuixBackdrop? = null,
) {
    val sortModes = remember { listOf(CommentSortMode.HOT, CommentSortMode.NEWEST) }
    val sortModeLabels = remember(sortModes) { sortModes.map { it.label } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacingTokens.Large, vertical = AppSpacingTokens.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(
            text = resolveDynamicCommentCountLabel(totalCount),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            DynamicCommentSortControl(
                items = sortModeLabels,
                selectedIndex = sortModes.indexOf(sortMode).coerceAtLeast(0),
                onSelected = { index ->
                    sortModes.getOrNull(index)?.let(onSortModeChange)
                },
                modifier = Modifier.align(Alignment.CenterEnd),
                // Null deliberately selects the shared control's mounted local source. Do not
                // manufacture an unrecorded Backdrop here or sample the LazyColumn containing
                // this header, which would be invalid/recursive on Xiaomi's native renderer.
                miuixBackdrop = miuixBackdrop,
            )
        }
    }
    AppHorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

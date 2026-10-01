package com.android.purebilibili.core.ui.skeleton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyListItems
@Composable
fun CommentListSkeleton(
    modifier: Modifier = Modifier,
    itemCount: Int = 6,
    contentPadding: PaddingValues = PaddingValues(vertical = 4.dp),
) {
    val pulse = rememberContentSkeletonPulse()
    val blockColor = rememberContentSkeletonBlockColor(pulse)
    val skeletonKeys = List(itemCount.coerceAtLeast(0)) { it }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        userScrollEnabled = false,
    ) {
        lazyListItems(
            items = skeletonKeys,
            key = { "comment_list_skeleton_$it" },
            contentType = { "comment_list_skeleton" },
        ) {
            CommentListItemSkeleton(blockColor = blockColor)
        }
    }
}

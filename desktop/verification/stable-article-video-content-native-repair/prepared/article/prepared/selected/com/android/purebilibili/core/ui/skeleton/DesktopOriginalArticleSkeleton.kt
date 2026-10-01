package com.android.purebilibili.core.ui.skeleton
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyListItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
@Composable
fun ArticleDetailSkeleton(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
) {
    val pulse = rememberContentSkeletonPulse()
    val blockColor = rememberContentSkeletonBlockColor(pulse)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        userScrollEnabled = false,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ContentSkeletonBlock(blockColor, Modifier.fillMaxWidth(0.92f).height(28.dp))
            Spacer(Modifier.height(10.dp))
            ContentSkeletonBlock(blockColor, Modifier.fillMaxWidth(0.68f).height(28.dp))
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ContentSkeletonBlock(blockColor, Modifier.size(42.dp), CircleShape)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    ContentSkeletonBlock(blockColor, Modifier.fillMaxWidth(0.36f).height(14.dp))
                    Spacer(Modifier.height(7.dp))
                    ContentSkeletonBlock(blockColor, Modifier.fillMaxWidth(0.52f).height(12.dp))
                }
            }
        }
        lazyListItems(List(7) { it }) { index ->
            ContentSkeletonBlock(
                blockColor,
                Modifier
                    .fillMaxWidth(if (index % 3 == 2) 0.72f else 1f)
                    .height(16.dp),
            )
        }
    }
}

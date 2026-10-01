package com.android.purebilibili.core.ui.skeleton

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as lazyGridItems
import androidx.compose.foundation.lazy.items as lazyListItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel

@Composable
fun ContentVideoGridSkeletonFixedColumns(
    columns: Int,
    modifier: Modifier = Modifier,
    coverAspectRatio: Float = 16f / 10f,
    rows: Int = 4,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    spacing: Dp = 8.dp,
) {
    val safeColumns = columns.coerceAtLeast(1)
    val skeletonKeys = List(safeColumns * rows.coerceAtLeast(1)) { it }
    val pulse = rememberContentSkeletonPulse()
    val blockColor = rememberContentSkeletonBlockColor(pulse)
    LazyVerticalGrid(
        columns = GridCells.Fixed(safeColumns),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
        userScrollEnabled = false,
        modifier = modifier.fillMaxSize(),
    ) {
        lazyGridItems(
            items = skeletonKeys,
            key = { "content_video_fixed_skeleton_$it" },
            contentType = { "content_video_fixed_skeleton" },
        ) {
            ContentVideoGridItemSkeleton(
                coverAspectRatio = coverAspectRatio,
                blockColor = blockColor,
            )
        }
    }
}

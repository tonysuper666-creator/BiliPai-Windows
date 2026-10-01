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
fun MediaListRowSkeleton(
    modifier: Modifier = Modifier,
    coverWidth: Dp = 128.dp,
    coverAspectRatio: Float = 16f / 10f,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    contentGap: Dp = 12.dp,
    blockColor: Color? = null,
) {
    val pulse = if (blockColor == null) rememberContentSkeletonPulse() else 0f
    val color = blockColor ?: rememberContentSkeletonBlockColor(pulse)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContentSkeletonBlock(
            color = color,
            shape = AppShapes.container(ContainerLevel.Chip),
            modifier = Modifier
                .width(coverWidth)
                .aspectRatio(coverAspectRatio),
        )
        Spacer(modifier = Modifier.width(contentGap))
        Column(modifier = Modifier.weight(1f)) {
            ContentSkeletonBlock(
                color = color,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .height(16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            ContentSkeletonBlock(
                color = color,
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .height(12.dp),
            )
            Spacer(modifier = Modifier.height(6.dp))
            ContentSkeletonBlock(
                color = color,
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(12.dp),
            )
        }
    }
}

@Composable
fun UserListRowSkeleton(
    modifier: Modifier = Modifier,
    avatarSize: Dp = 48.dp,
    blockColor: Color? = null,
) {
    val pulse = if (blockColor == null) rememberContentSkeletonPulse() else 0f
    val color = blockColor ?: rememberContentSkeletonBlockColor(pulse)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContentSkeletonBlock(
            color = color,
            shape = CircleShape,
            modifier = Modifier.size(avatarSize),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            ContentSkeletonBlock(
                color = color,
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .height(14.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            ContentSkeletonBlock(
                color = color,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(12.dp),
            )
        }
    }
}

@Composable
fun ContentMediaListSkeleton(
    modifier: Modifier = Modifier,
    itemCount: Int = 8,
    contentPadding: PaddingValues = PaddingValues(vertical = 8.dp),
    useUserRow: Boolean = false,
    mediaRowCoverWidth: Dp = 128.dp,
    mediaRowCoverAspectRatio: Float = 16f / 10f,
    mediaRowContentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    mediaRowContentGap: Dp = 12.dp,
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
            key = { "content_media_list_skeleton_$it" },
            contentType = { if (useUserRow) "user_row_skeleton" else "media_row_skeleton" },
        ) {
            if (useUserRow) {
                UserListRowSkeleton(blockColor = blockColor)
            } else {
                MediaListRowSkeleton(
                    coverWidth = mediaRowCoverWidth,
                    coverAspectRatio = mediaRowCoverAspectRatio,
                    contentPadding = mediaRowContentPadding,
                    contentGap = mediaRowContentGap,
                    blockColor = blockColor,
                )
            }
        }
    }
}

@Composable
fun ContentVideoGridSkeleton(
    modifier: Modifier = Modifier,
    minItemWidth: Dp = 160.dp,
    coverAspectRatio: Float = 16f / 10f,
    itemCount: Int = 8,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    horizontalSpacing: Dp = 8.dp,
    verticalSpacing: Dp = 8.dp,
) {
    val pulse = rememberContentSkeletonPulse()
    val blockColor = rememberContentSkeletonBlockColor(pulse)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = minItemWidth),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        userScrollEnabled = false,
        modifier = modifier.fillMaxSize(),
    ) {
        val skeletonKeys = List(itemCount.coerceAtLeast(0)) { it }
        lazyGridItems(
            items = skeletonKeys,
            key = { "content_video_grid_skeleton_$it" },
            contentType = { "content_video_grid_skeleton" },
        ) {
            ContentVideoGridItemSkeleton(
                coverAspectRatio = coverAspectRatio,
                blockColor = blockColor,
            )
        }
    }
}

@Composable
fun ContentVideoGridItemSkeleton(
    coverAspectRatio: Float = 4f / 3f,
    blockColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        ContentSkeletonBlock(
            color = blockColor,
            shape = AppShapes.container(ContainerLevel.Chip),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(coverAspectRatio),
        )
        Spacer(modifier = Modifier.height(8.dp))
        ContentSkeletonBlock(
            color = blockColor,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(16.dp),
        )
        Spacer(modifier = Modifier.height(6.dp))
        ContentSkeletonBlock(
            color = blockColor,
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(12.dp),
        )
    }
}

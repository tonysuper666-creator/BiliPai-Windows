// GENERATED from app/src/main/java/com/android/purebilibili/core/ui/skeleton/ContentLoadingSkeletons.kt; pinned LF SHA-256 26bae6dfaa2f92b4418f37012821ca1a6c0bf5a7fcb82a47cd0369c86dc4b5f2
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
fun PosterDetailSkeleton(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    val pulse = rememberContentSkeletonPulse()
    val blockColor = rememberContentSkeletonBlockColor(pulse)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        userScrollEnabled = false,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row {
                ContentSkeletonBlock(
                    blockColor,
                    Modifier.width(132.dp).aspectRatio(0.75f),
                    AppShapes.container(ContainerLevel.Card),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ContentSkeletonBlock(blockColor, Modifier.fillMaxWidth(0.9f).height(24.dp))
                    ContentSkeletonBlock(blockColor, Modifier.fillMaxWidth(0.58f).height(14.dp))
                    ContentSkeletonBlock(blockColor, Modifier.fillMaxWidth(0.72f).height(14.dp))
                    ContentSkeletonBlock(blockColor, Modifier.widthIn(min = 96.dp).fillMaxWidth(0.48f).height(40.dp))
                }
            }
        }
        item { ContentSkeletonBlock(blockColor, Modifier.fillMaxWidth().height(44.dp)) }
        item { ContentSkeletonBlock(blockColor, Modifier.fillMaxWidth(0.34f).height(20.dp)) }
        lazyListItems(List(4) { it }) { index ->
            ContentSkeletonBlock(
                blockColor,
                Modifier.fillMaxWidth(if (index == 3) 0.66f else 1f).height(15.dp),
            )
        }
    }
}

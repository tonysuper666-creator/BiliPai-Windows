package com.android.purebilibili.feature.video.screen

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.purebilibili.core.ui.AdaptivePullToRefreshBox

/** Isolates the refresh indicator's composition-time inset read from the comment rows. */
@Composable
internal fun VideoCommentRefreshContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    enabled: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    AdaptivePullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        enabled = enabled,
        indicatorTopInset = contentPadding.calculateTopPadding(),
        modifier = modifier,
        content = content,
    )
}

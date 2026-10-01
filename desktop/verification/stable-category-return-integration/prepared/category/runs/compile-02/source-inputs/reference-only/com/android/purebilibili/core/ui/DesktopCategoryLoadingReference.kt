package com.android.purebilibili.core.ui
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
@Composable
fun CutePersonLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    strokeWidth: Dp = 2.dp,
    size: Dp? = null,
) {
    AdaptiveLoadingIndicator(
        modifier = modifier,
        size = size,
        color = color,
        strokeWidth = strokeWidth,
    )
}

package com.android.purebilibili.feature.video.ui.section
import androidx.compose.runtime.Composable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.feature.video.ui.feedback.resolveVideoActionTint
import com.android.purebilibili.feature.video.ui.feedback.resolveVideoActionCountTint

@Composable
fun TripleProgressIcon(
    icon: ImageVector,
    text: String,
    progress: Float,
    progressColor: Color,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val inactiveTint = MaterialTheme.colorScheme.onSurfaceVariant
    val iconTint = resolveVideoActionTint(
        isActive = isActive,
        activeColor = progressColor,
        inactiveColor = inactiveTint
    )
    val textTint = resolveVideoActionCountTint(
        isActive = isActive,
        activeColor = progressColor,
        inactiveColor = inactiveTint
    )
    val iconSize = 24.dp
    val ringSize = iconSize
    val strokeWidth = 2.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier.size(ringSize),
            contentAlignment = Alignment.Center
        ) {
            // 进度环
            if (progress > 0f) {
                Canvas(modifier = Modifier.size(ringSize)) {
                    val stroke = strokeWidth.toPx()
                    val diameter = size.minDimension - stroke
                    val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)

                    // 背景环
                    drawArc(
                        color = progressColor.copy(alpha = 0.2f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = Size(diameter, diameter),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )

                    // 进度环
                    drawArc(
                        color = progressColor,
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = topLeft,
                        size = Size(diameter, diameter),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }

            // 图标
            AppIcon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(iconSize)
            )
        }

        Spacer(modifier = Modifier.height(2.dp))
        AppText(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = textTint,
            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1
        )
    }
}

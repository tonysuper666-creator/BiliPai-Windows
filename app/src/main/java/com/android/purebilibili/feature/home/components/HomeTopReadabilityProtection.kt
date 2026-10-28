package com.android.purebilibili.feature.home.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.ProgressiveBlur

/** 分类文字背后保留保护平台，搜索区域较轻，尾部在控件下方渐隐。 */
internal fun homeTopReadabilityStops(
    contentBottom: Float,
    tabTop: Float,
    tabBottom: Float,
    fadeBottom: Float,
    transition: Float,
    baseAlpha: Float,
    tabVisibility: Float,
): List<Pair<Float, Float>> {
    val end = fadeBottom.coerceAtLeast(1f)
    val contentEnd = contentBottom.coerceIn(0f, end)
    val top = tabTop.coerceIn(0f, contentEnd)
    val bottom = tabBottom.coerceIn(top, contentEnd)
    val edge = transition.coerceAtLeast(1f)
    val base = baseAlpha.coerceIn(0f, 1f)
    val peak = maxOf(base, 0.65f * tabVisibility.coerceIn(0f, 1f))
    val hasTabs = bottom > top && tabVisibility > 0f
    val tabFadeEnd = if (bottom >= contentEnd - 1f) end else (bottom + edge).coerceAtMost(end)
    val positions = listOf(
        0f, (top - edge).coerceAtLeast(0f), top, bottom,
        (bottom + edge).coerceAtMost(end), contentEnd,
        contentEnd + (end - contentEnd) * 0.5f, end,
    ).distinct().sorted()
    return positions.map { y ->
        val baseWeight = if (y <= contentEnd) 1f else {
            ((end - y) / (end - contentEnd).coerceAtLeast(1f)).coerceIn(0f, 1f)
        }
        val tabWeight = when {
            !hasTabs -> 0f
            y < top -> (1f - (top - y) / edge).coerceIn(0f, 1f)
            y <= bottom -> 1f
            else -> ((tabFadeEnd - y) / (tabFadeEnd - bottom).coerceAtLeast(1f)).coerceIn(0f, 1f)
        }
        y / end to maxOf(base * baseWeight, peak * tabWeight)
    }
}

internal fun Modifier.homeTopReadabilityProtection(
    color: Color,
    contentBottom: Dp,
    tabTop: Dp,
    tabBottom: Dp,
    baseAlpha: Float,
    tabVisibility: Float,
): Modifier = drawWithCache {
    val stops = homeTopReadabilityStops(
        contentBottom = contentBottom.toPx(),
        tabTop = tabTop.toPx(),
        tabBottom = tabBottom.toPx(),
        fadeBottom = size.height,
        transition = 16.dp.toPx(),
        baseAlpha = baseAlpha,
        tabVisibility = tabVisibility,
    )
    val brush = Brush.verticalGradient(
        colorStops = stops.map { (position, alpha) -> position to color.copy(alpha = alpha) }.toTypedArray(),
        endY = size.height,
    )
    onDrawBehind { drawRect(brush) }
}

/** 仅供首页非玻璃连续顶栏使用，清晰端始终落在分类文字下方。 */
internal fun homeTopReadabilityBlurGradient(
    gradient: ProgressiveBlur,
    tabBottomFraction: Float,
    clearEndFraction: Float,
): ProgressiveBlur {
    val start = maxOf(gradient.startFraction, tabBottomFraction.coerceIn(0f, 0.98f))
    val end = maxOf(gradient.endFraction, clearEndFraction, start + 0.01f).coerceAtMost(1f)
    return gradient.copy(startFraction = start, endFraction = end)
}

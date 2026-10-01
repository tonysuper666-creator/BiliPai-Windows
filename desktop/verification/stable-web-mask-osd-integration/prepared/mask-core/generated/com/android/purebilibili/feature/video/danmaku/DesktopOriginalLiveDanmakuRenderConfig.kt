package com.android.purebilibili.feature.video.danmaku
import com.android.purebilibili.danmaku.engine.DanmakuRenderConfig
import com.bilipai.desktop.danmaku.DanmakuSettings
import com.bilipai.desktop.danmaku.DesktopOriginalDanmakuRenderPlatform

internal fun resolveDesktopOriginalLiveDanmakuRenderConfig(danmakuSettings:DanmakuSettings,viewWidthPx:Int,viewHeightPx:Int,safeDisplayArea:Float,platform:DesktopOriginalDanmakuRenderPlatform):DanmakuRenderConfig {
            val textSize = DEFAULT_DANMAKU_TEXT_SIZE_PX *
                danmakuSettings.fontScale.coerceIn(0.3f, 2f)
            val strokeWidth = if(danmakuSettings.strokeEnabled)danmakuSettings.strokeWidth.coerceAtLeast(0f) else 0f
            return (
                DanmakuRenderConfig(
                    alpha = (danmakuSettings.opacity.coerceIn(0f, 1f) * 255).toInt(),
                    textSizePx = textSize,
                    typeface = resolveDanmakuTypeface(danmakuSettings.fontWeight, platform),
                    strokeWidthPx = strokeWidth,
                    scrollDurationMs = resolveDanmakuScrollDurationMillis(
                        scrollDurationSeconds = danmakuSettings.scrollDurationSeconds,
                        speedFactor = danmakuSettings.speedFactor,
                        scrollFixedVelocity = danmakuSettings.scrollFixedVelocity,
                        viewportWidthPx = viewWidthPx
                    ),
                    lineHeightPx = textSize * danmakuSettings.lineHeight.coerceIn(0.8f, 2.2f),
                    lineCount = resolveDanmakuVisibleLineCount(
                        visibleHeightPx = viewHeightPx.toFloat(),
                        areaRatioHint = safeDisplayArea,
                        fontSize = textSize,
                        strokeWidth = strokeWidth,
                        strokeEnabled = strokeWidth > 0f,
                        lineHeight = danmakuSettings.lineHeight,
                        massiveMode = danmakuSettings.massiveMode
                    ),
                    pinnedDurationMs = resolveDanmakuPinnedDurationMillis(
                        danmakuSettings.staticDurationSeconds
                    )
                )
            )
}

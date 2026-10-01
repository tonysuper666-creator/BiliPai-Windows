package com.android.purebilibili.danmaku.engine
import java.awt.Font as Typeface

data class DanmakuRenderConfig(
    val alpha: Int = 255,
    val textSizePx: Float = 48f,
    val typeface: Typeface,
    val strokeWidthPx: Float = 2.75f,
    val strokeColor: Int = 0x61000000,
    val scrollDurationMs: Long = 7_000L,
    val lineHeightPx: Float = 64f,
    val lineMarginPx: Float = 18f,
    val viewportScale: Float = 1f,
    val lineCount: Int = 8,
    val topMarginPx: Float = 0f,
    val bottomMarginPx: Float = 0f,
    val pinnedDurationMs: Long = 4_000L,
    val playSpeedPercent: Int = 100,
    val maskEnabled: Boolean = false
)

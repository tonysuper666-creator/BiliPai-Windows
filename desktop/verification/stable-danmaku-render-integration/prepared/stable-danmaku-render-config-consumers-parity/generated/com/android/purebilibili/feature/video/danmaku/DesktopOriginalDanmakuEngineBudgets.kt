package com.android.purebilibili.feature.video.danmaku
import com.android.purebilibili.danmaku.engine.DanmakuRenderConfig

/** Exact original updateConfig expressions, exposed as explicit Windows consumer policies; not a replacement engine. */
internal fun desktopOriginalDanmakuPinnedLineCount(config:DanmakuRenderConfig):Int = if (config.lineCount <= 4) 0 else (config.lineCount / 2).coerceAtLeast(1)

internal fun desktopOriginalDanmakuItemMargin(config:DanmakuRenderConfig):Float = 24f * config.viewportScale

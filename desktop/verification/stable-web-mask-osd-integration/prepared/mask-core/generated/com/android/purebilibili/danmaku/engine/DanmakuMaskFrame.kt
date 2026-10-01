package com.android.purebilibili.danmaku.engine
import com.bilipai.desktop.danmaku.DesktopWebMaskPath as Path

data class DanmakuMaskFrame(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val path: Path,
    val sourceWidth: Int,
    val sourceHeight: Int
)

package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.data.repository.CreatorCardStats
import com.bilipai.desktop.plugins.DesktopPluginContext

/** Supplied by the current detail entry on Root's existing account/transport owner. */
internal interface DesktopOriginalVideoInfoBindings {
    val context: DesktopPluginContext
    suspend fun getCreatorCardStats(mid: Long): Result<CreatorCardStats>
}

internal val LocalDesktopOriginalVideoInfoBindings = staticCompositionLocalOf<DesktopOriginalVideoInfoBindings> {
    error("Original video information requires the current Root detail owner")
}

/** Android uptime's monotonic origin is not observable by the original delta tracker. */
internal object DesktopOriginalVideoGestureClock {
    fun uptimeMillis(): Long = System.nanoTime() / 1_000_000L
}

package com.android.purebilibili.core.ui.performance
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext
internal fun resolvePanelFrameRateOverrideLabel(refreshRate: Float?): String {
    val rate = refreshRate ?: return ""
    if (rate <= 0f) return ""
    val rounded = rate.roundToInt().toFloat()
    val rateText = if (abs(rate - rounded) < 0.05f) {
        rounded.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", rate)
    }
    return "$rateText Hz（面板）"
}

private const val PANEL_FRAME_RATE_SAMPLE_INTERVAL_MS = 500L

@Composable
internal fun rememberPanelFrameRateLabel(): String {
    val activity = LocalDesktopOriginalPlayerSettingsContext.current.videoOverlay
    var refreshRate by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(activity) {
        while (true) {
            refreshRate = activity.readPanelRefreshRate() ?: 0f
            delay(PANEL_FRAME_RATE_SAMPLE_INTERVAL_MS)
        }
    }
    return resolvePanelFrameRateOverrideLabel(refreshRate.takeIf { it > 0f })
}

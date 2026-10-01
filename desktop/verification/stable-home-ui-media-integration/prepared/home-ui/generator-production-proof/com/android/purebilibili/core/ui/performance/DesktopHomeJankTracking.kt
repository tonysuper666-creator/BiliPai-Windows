package com.android.purebilibili.core.ui.performance
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.ScrollableState
import kotlinx.coroutines.CoroutineScope
import com.bilipai.desktop.ui.*

@Composable
fun rememberMetricsStateHolder(): DesktopHomeMetricHolder = LocalDesktopHomeMetricHolder.current

@Composable
fun TrackJank(
    vararg keys: Any?,
    reportMetric: suspend CoroutineScope.(state: DesktopHomeMetricHolder) -> Unit
) {
    val metrics = rememberMetricsStateHolder()
    LaunchedEffect(metrics, *keys) {
        reportMetric(metrics)
    }
}

@Composable
fun TrackScrollJank(
    scrollableState: ScrollableState,
    stateName: String
) {
    TrackJank(scrollableState, stateName) { metricsHolder ->
        snapshotFlow { scrollableState.isScrollInProgress }
            .collect { isScrollInProgress ->
                metricsHolder.state?.apply {
                    if (isScrollInProgress) {
                        putState(stateName, "Scrolling=true")
                    } else {
                        removeState(stateName)
                    }
                }
            }
    }
}

@Composable
fun TrackJankStateValue(
    stateName: String,
    stateValue: String?
) {
    val metrics = rememberMetricsStateHolder()
    SideEffect {
        metrics.state?.apply {
            if (stateValue.isNullOrBlank()) {
                removeState(stateName)
            } else {
                putState(stateName, stateValue)
            }
        }
    }
    DisposableEffect(metrics, stateName) {
        onDispose {
            metrics.state?.removeState(stateName)
        }
    }
}

@Composable
fun TrackJankStateFlag(
    stateName: String,
    isActive: Boolean,
    activeValue: String = "true"
) {
    TrackJankStateValue(
        stateName = stateName,
        stateValue = if (isActive) activeValue else null
    )
}
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.feature.home.*
import com.bilipai.desktop.data.DesktopRepository
import java.util.concurrent.atomic.AtomicBoolean

/** Original stable weekly page. Route/account lifetime owns only its load task. */
@Composable
fun DesktopWeeklySeriesScreen(
    requests: DesktopWeeklySeriesRequests,
    repository: DesktopRepository,
    initialNumber: Int? = null,
    onBack: () -> Unit,
    onVideoClick: (VideoItem, List<VideoItem>) -> Unit,
    modifier: Modifier = Modifier,
    isClosing: () -> Boolean = { false },
) {
    val account by repository.account.collectAsState()
    val epoch by repository.sessionEpochFlow.collectAsState()
    val capturedEpoch = epoch
    val capturedMid = account?.mid
    val memory = LocalDesktopBrowseMemory.current
    val savedState = remember(memory, capturedMid, capturedEpoch) {
        memory?.screen(listOf("weekly-series-number", capturedMid, capturedEpoch)) { DesktopWeeklySeriesSavedState() }
            ?: DesktopWeeklySeriesSavedState()
    }
    val scope = rememberCoroutineScope()
    val latestIsClosing by rememberUpdatedState(isClosing)
    val latestBack by rememberUpdatedState(onBack)
    val latestVideo by rememberUpdatedState(onVideoClick)
    val alive = remember(requests, repository, capturedMid, capturedEpoch) { AtomicBoolean(true) }
    val owns = remember(alive, repository, capturedMid, capturedEpoch) {
        { alive.get() && !latestIsClosing() && repository.sessionEpoch == capturedEpoch && repository.account.value?.mid == capturedMid }
    }
    val viewModel = remember(requests, savedState, scope, owns) { WeeklySeriesViewModel(savedState, requests, scope, owns) }
    DisposableEffect(viewModel) { onDispose { alive.set(false); viewModel.close() } }
    key(alive) {
        WeeklySeriesScreen(initialNumber, onBack = { if (owns()) latestBack() },
            onVideoClick = { video, list -> if (owns()) latestVideo(video, list) },
            modifier = modifier, viewModel = viewModel)
    }
}

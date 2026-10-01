package com.android.purebilibili.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.core.ui.AppScaffold
import com.android.purebilibili.core.ui.AppTopBar
import com.android.purebilibili.core.ui.AppSurfaceTokens
import com.android.purebilibili.core.ui.rememberAppBackIcon
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.feature.home.components.cards.ElegantVideoCard

@Composable
internal fun WeeklySeriesScreen(
    initialNumber: Int?,
    onBack: () -> Unit,
    onVideoClick: (VideoItem, List<VideoItem>) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WeeklySeriesViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, initialNumber) { viewModel.initialize(initialNumber) }
    WeeklySeriesContent(
        state = state,
        onBack = onBack,
        onVideoClick = { video -> onVideoClick(video, state.videos) },
        onSelect = viewModel::select,
        onRetry = viewModel::retry,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeeklySeriesContent(
    state: WeeklySeriesUiState,
    onBack: () -> Unit,
    onVideoClick: (VideoItem) -> Unit,
    onSelect: (Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPeriods by rememberSaveable { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.number) { gridState.scrollToItem(0) }
    AppScaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = "每周必看",
                navigationIcon = {
                    AppIconButton(onClick = onBack) { AppIcon(rememberAppBackIcon(), contentDescription = "返回") }
                },
                actions = {
                    AppTextButton(onClick = { showPeriods = true }, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) {
                        AppText(state.number?.let { "第${it}期 · 选期" } ?: "选择期数")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier
            .fillMaxSize()
            .padding(padding)
        ) {
            if (state.label.isNotBlank() || state.subject.isNotBlank()) {
                AppText(
                    listOf(state.label, state.subject).filter { it.isNotBlank() }.joinToString("\n"),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            if (state.reminder.isNotBlank()) {
                AppText(state.reminder, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = AppSurfaceTokens.onSurfaceVariantSummary())
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppCircularProgressIndicator() }
                state.error != null -> Column(
                    Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    AppText(state.error)
                    AppTextButton(onClick = onRetry) { AppText("重试") }
                }
                state.videos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppText("本期暂无视频") }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp), state = gridState,
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(state.videos, key = { index, video -> "${video.bvid}:$index" }) { index, video ->
                        ElegantVideoCard(video = video, index = index, animationEnabled = false,
                            glassEnabled = false, blurEnabled = false,
                            onClick = { _, _ -> onVideoClick(video) })
                    }
                }
            }
        }
    }
    if (showPeriods) {
        AppAlertDialog(
            onDismissRequest = { showPeriods = false },
            title = { AppText("选择期数") },
            text = {
                if (state.periods.isEmpty()) {
                    Column {
                        AppText(state.periodsError ?: "暂无可选期数")
                        AppTextButton(onClick = { showPeriods = false; onRetry() }) { AppText("重试") }
                    }
                } else {
                    val periodListState = rememberLazyListState(
                        initialFirstVisibleItemIndex = state.periods.indexOfFirst { it.number == state.number }.coerceAtLeast(0)
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), state = periodListState) {
                        items(state.periods, key = { it.number }) { period ->
                            AppSingleChoiceRow(
                                selected = period.number == state.number,
                                onClick = { showPeriods = false; onSelect(period.number) }
                            ) {
                                AppText(period.name.ifBlank { "第${period.number}期" }, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            },
            confirmButton = { AppTextButton(onClick = { showPeriods = false }) { AppText("关闭") } }
        )
    }
}

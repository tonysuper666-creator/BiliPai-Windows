// GENERATED from app/src/main/java/com/android/purebilibili/feature/home/WeeklySeriesViewModel.kt; do not edit.
// LF-normalized SHA-256: 0d62f01e9fb55151333b5c7de117f0251e47d926f414901a517bf3bdac30f8df
package com.android.purebilibili.feature.home

import com.android.purebilibili.data.model.response.PopularSeriesPeriod
import com.android.purebilibili.data.model.response.VideoItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class WeeklySeriesUiState(
    val number: Int? = null,
    val periods: List<PopularSeriesPeriod> = emptyList(),
    val videos: List<VideoItem> = emptyList(),
    val label: String = "",
    val subject: String = "",
    val reminder: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val periodsError: String? = null,
)

internal fun resolveWeeklyInitialNumber(requested: Int?, periods: List<PopularSeriesPeriod>): Int? =
    requested?.takeIf { it > 0 } ?: periods.maxOfOrNull { it.number }?.takeIf { it > 0 }

internal class WeeklySeriesViewModel(
    private val savedState: DesktopWeeklySeriesSavedState,
    private val requests: DesktopWeeklySeriesRequests,
    private val desktopScope: CoroutineScope,
    private val stillOwned: () -> Boolean = { true },
) : AutoCloseable {
    private val mutableState = MutableStateFlow(WeeklySeriesUiState())
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var initialized = false
    @Volatile private var closed = false
    @Volatile private var desktopGeneration = 0L
    private fun desktopOwned() = !closed && stillOwned()
    override fun close() {
        closed = true
        desktopGeneration++
        loadJob?.cancel()
    }

    fun initialize(initialNumber: Int?) {
        if (!desktopOwned() || initialized) return
        initialized = true
        load(savedState.get<Int>("weeklyNumber") ?: initialNumber)
    }

    fun select(number: Int) {
        if (number > 0 && number != state.value.number) load(number)
    }

    fun retry() = load(state.value.number)

    private fun load(requested: Int?) {
        if (!desktopOwned()) return
        val desktopRequest = ++desktopGeneration
        loadJob?.cancel()
        loadJob = desktopScope.launch {
            fun ensureDesktopOwned() {
                if (!desktopOwned() || desktopGeneration != desktopRequest) {
                    throw CancellationException("Weekly series owner retired")
                }
            }
            coroutineContext.ensureActive()
            ensureDesktopOwned()
            mutableState.update { it.copy(loading = true, error = null, videos = emptyList(), label = "", subject = "", reminder = "") }
            var periods = state.value.periods
            if (periods.isEmpty()) {
                requests.getWeeklyPeriods().also { coroutineContext.ensureActive(); ensureDesktopOwned() }.fold(
                    onSuccess = { loaded ->
                        periods = loaded
                        mutableState.update { it.copy(periods = loaded, periodsError = null) }
                    },
                    onFailure = { failure ->
                        mutableState.update { it.copy(periodsError = failure.message ?: "期数加载失败") }
                    }
                )
            }
            val number = resolveWeeklyInitialNumber(requested, periods)
            if (number == null) {
                mutableState.update { it.copy(loading = false, error = it.periodsError ?: "暂无每周必看") }
                return@launch
            }
            savedState["weeklyNumber"] = number
            mutableState.update { it.copy(number = number) }
            requests.getWeeklyPeriod(number).also { coroutineContext.ensureActive(); ensureDesktopOwned() }.fold(
                onSuccess = { data ->
                    val videos = data.list.orEmpty().map { it.toVideoItem() }.filter { it.bvid.isNotBlank() }
                    mutableState.update {
                        it.copy(loading = false, videos = videos, label = data.config?.label.orEmpty(),
                            subject = data.config?.subject.orEmpty(), reminder = data.reminder)
                    }
                },
                onFailure = { failure ->
                    mutableState.update { it.copy(loading = false, error = failure.message ?: "本期加载失败") }
                }
            )
        }
    }
}

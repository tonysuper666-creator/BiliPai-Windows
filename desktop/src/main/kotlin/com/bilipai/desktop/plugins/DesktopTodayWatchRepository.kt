package com.bilipai.desktop.plugins

import com.android.purebilibili.core.plugin.*
import com.android.purebilibili.core.store.TodayWatchFeedbackStore
import com.android.purebilibili.core.store.TodayWatchProfileStore
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.feature.home.*
import com.android.purebilibili.feature.plugin.TodayWatchCandidatePoolMode
import com.android.purebilibili.feature.plugin.TodayWatchPlugin
import com.android.purebilibili.feature.plugin.TodayWatchPluginMode
import com.android.purebilibili.data.repository.resolveHistoryCursorQuery
import com.bilipai.desktop.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

data class DesktopTodayWatchState(val plan: TodayWatchPlan? = null, val loading: Boolean = false,
    val notice: String? = null, val error: String? = null)

/** Only platform orchestration: original history DTOs, feedback and planner remain authoritative. */
class DesktopTodayWatchRepository(private val runtime: DesktopPluginRuntime,
    private val repository: DesktopRepository?, private val discovery: DesktopDiscoveryRepository?) {
    private val generation = AtomicLong()
    private val mutation = Mutex()
    private val consumed = mutableSetOf<String>()
    private var accountEpoch: Long? = null
    private var historyCache = emptyList<VideoItem>()
    private var historyLoadedAt = 0L
    private var expandedCache: TodayWatchExpandedCandidateCache? = null
    @Volatile private var stopped = false
    private val _state = MutableStateFlow(DesktopTodayWatchState())
    val state: StateFlow<DesktopTodayWatchState> = _state.asStateFlow()

    suspend fun reload(forceHistory: Boolean = false): Unit = withContext(Dispatchers.IO) {
        check(!stopped) { "推荐服务已停止" }
        val repository = repository ?: error("推荐服务尚未初始化")
        val discovery = discovery ?: error("推荐服务尚未初始化")
        val epoch = repository.sessionEpoch
        val token = generation.incrementAndGet()
        mutation.withLock { if (accountEpoch != epoch) clearForAccount(epoch) }
        fun current() = !stopped && epoch == repository.sessionEpoch && shouldApplyTodayWatchBuildResult(token, generation.get())
        if (runtime.plugins.value.none { it.plugin.id == TodayWatchPlugin.PLUGIN_ID && it.enabled }) {
            _state.value = DesktopTodayWatchState(error = "请先启用今日推荐插件")
            return@withContext
        }
        _state.value = _state.value.copy(loading = true, error = null, notice = null)
        try {
            val config = runtime.todayWatch.configState.value
            val account = repository.account.value?.mid
            val feedback = discovery.feedback(account).value
            val blocked = discovery.blockedCreators(account).value
            fun filter(videos: List<VideoItem>) = runtime.filterFeedItems(filterHomeVideosByNotInterestedFeedback(videos.filter {
                it.bvid.isNotBlank() && it.title.isNotBlank() && it.owner.mid !in blocked
            }, feedback.dislikedBvids, feedback.dislikedCreatorMids, feedback.dislikedKeywords), FeedKind.HOME_RECOMMEND).distinctBy { it.bvid }
            val local = filter(discovery.page(DiscoverySection.RECOMMEND).items)
            if (!current()) return@withContext
            val notices = mutableListOf<String>()
            val history = try { loadHistory(repository, config.historySampleLimit, forceHistory, epoch) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { notices += "历史记录不可用，已按当前推荐生成"; emptyList() }
            if (!current()) return@withContext
            val context = discovery.recommendationContext(account)
            val creatorSignals = TodayWatchProfileStore.getCreatorSignals(context, limit = config.historySampleLimit / 4)
                .map { RecommendationCreatorSignal(it.mid, it.name, it.score, it.watchCount) }
            val personal = TodayWatchFeedbackStore.getSnapshot(context)
            val consumedSnapshot = mutation.withLock { consumed.toSet() }
            fun build(candidates: List<VideoItem>) = runtime.buildRecommendations(RecommendationRequest(
                candidates, history, creatorSignals,
                RecommendationFeedbackSignals(consumedSnapshot, personal.dislikedBvids, personal.dislikedCreatorMids, personal.dislikedKeywords),
                RecommendationSceneSignals(config.linkEyeCareSignal && runtime.eyeProtection.isNightModeActive.value),
                if (config.currentMode == TodayWatchPluginMode.LEARN) RecommendationMode.LEARN else RecommendationMode.RELAX,
                config.queueBuildLimit, config.upRankLimit, config.recommendationStrategy
            )).firstOrNull { it.sourcePluginId == TodayWatchPlugin.PLUGIN_ID }?.toTodayWatchPlan()
            val first = build(local)
            if (current()) _state.value = DesktopTodayWatchState(first, notice = notices.joinToString("；").ifBlank { null })
            if (config.candidatePoolMode != TodayWatchCandidatePoolMode.EXPANDED || local.isEmpty()) return@withContext
            val signature = buildTodayWatchCandidateSignature(local)
            val cached = expandedCache
            val expanded = if (canReuseTodayWatchExpandedCache(cached, signature, System.currentTimeMillis())) cached!!.candidates else {
                try {
                    filter(discovery.page(DiscoverySection.RECOMMEND, page = 2).items).also {
                        if (current()) expandedCache = TodayWatchExpandedCandidateCache(signature, it, System.currentTimeMillis())
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { notices += "扩充候选失败，已使用本地候选"; emptyList() }
            }
            if (current()) _state.value = DesktopTodayWatchState(build(mergeTodayWatchCandidates(local, expanded)), notice = notices.joinToString("；").ifBlank { null })
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) { if (current()) _state.value = _state.value.copy(error = error.message ?: "推荐生成失败") }
        finally { if (current()) _state.value = _state.value.copy(loading = false) }
    }

    private suspend fun loadHistory(repository: DesktopRepository, limit: Int, force: Boolean, epoch: Long): List<VideoItem> {
        val size = limit.coerceIn(20, 120)
        val now = System.currentTimeMillis()
        if (!force && now - historyLoadedAt in 0..600_000 && historyCache.size >= size) return historyCache.take(size)
        repository.ensureSession()
        val list = mutableListOf<VideoItem>()
        var cursor: CloudHistoryCursor? = null
        repeat(3) {
            if (list.size >= size) return@repeat
            check(epoch == repository.sessionEpoch) { "账号已变化" }
            val query = resolveHistoryCursorQuery(cursor?.max ?: 0, cursor?.viewAt ?: 0, cursor?.business)
            val response = DesktopPluginRepositoryBinding.api.getHistoryList(ps = 50, max = query.max, viewAt = query.viewAt, business = query.business, type = null)
            if (response.code != 0) throw BiliApiException(response.code, response.message)
            val page = response.data ?: error("历史响应为空")
            list += page.list.orEmpty().map { it.toVideoItem() }
            val next = page.cursor
            if (next == null || next.max <= 0 || page.list.isNullOrEmpty()) {
                check(epoch == repository.sessionEpoch) { "账号已变化" }
                return list.filter { it.bvid.isNotBlank() }.distinctBy { it.bvid }.also {
                    historyCache = it; historyLoadedAt = now
                }.take(size)
            }
            cursor = CloudHistoryCursor(next.max, next.view_at, next.business)
        }
        check(epoch == repository.sessionEpoch) { "账号已变化" }
        return list.filter { it.bvid.isNotBlank() }.distinctBy { it.bvid }.also {
            historyCache = it; historyLoadedAt = now
        }.take(size)
    }

    suspend fun consume(bvid: String): Boolean = mutation.withLock {
        val plan = _state.value.plan ?: return@withLock false
        val update = consumeVideoFromTodayWatchPlan(plan, bvid, runtime.todayWatch.configState.value.queuePreviewLimit)
        if (update.consumedApplied) { consumed += bvid; _state.value = _state.value.copy(plan = update.updatedPlan) }
        update.shouldRefill
    }
    private fun clearForAccount(epoch: Long) {
        accountEpoch = epoch; consumed.clear(); historyCache = emptyList(); historyLoadedAt = 0L; expandedCache = null
        _state.value = DesktopTodayWatchState()
    }
    internal suspend fun accountChanged(epoch: Long) = mutation.withLock { if (accountEpoch != epoch) { generation.incrementAndGet(); clearForAccount(epoch) } }
    internal suspend fun shutdownForRestore() { stopped = true; generation.incrementAndGet(); mutation.withLock { _state.value = _state.value.copy(loading = false) } }
}

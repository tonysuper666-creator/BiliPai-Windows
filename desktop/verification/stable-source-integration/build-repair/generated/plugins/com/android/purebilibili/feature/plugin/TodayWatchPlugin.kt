// GENERATED from app/src/main/java/com/android/purebilibili/feature/plugin/TodayWatchPlugin.kt; do not edit.
// LF-normalized SHA-256: 043f356998e8bac5f4d3d17759767b80b7c47a7d11787d5b4e134b00d61b8193
package com.android.purebilibili.feature.plugin

import com.android.purebilibili.core.plugin.PluginCapability
import com.android.purebilibili.core.plugin.PluginCapabilityManifest
import com.android.purebilibili.core.plugin.PluginManager
import com.android.purebilibili.core.plugin.PluginStore
import com.android.purebilibili.core.plugin.RecommendationAction
import com.android.purebilibili.core.plugin.RecommendationCreatorSignal
import com.android.purebilibili.core.plugin.RecommendationGroup
import com.android.purebilibili.core.plugin.RecommendationGroupItem
import com.android.purebilibili.core.plugin.RecommendationMode
import com.android.purebilibili.core.plugin.RecommendationPluginApi
import com.android.purebilibili.core.plugin.RecommendationRequest
import com.android.purebilibili.core.plugin.RecommendationResult
import com.android.purebilibili.core.plugin.RecommendationStrategy
import com.android.purebilibili.core.plugin.RecommendedVideo
import com.android.purebilibili.core.store.TodayWatchFeedbackStore
import com.android.purebilibili.core.store.TodayWatchProfileStore
import com.bilipai.desktop.plugins.DesktopPluginLog as Logger
import com.android.purebilibili.feature.home.TodayWatchCreatorSignal
import com.android.purebilibili.feature.home.TodayWatchMode
import com.android.purebilibili.feature.home.TodayWatchPenaltySignals
import com.android.purebilibili.feature.home.buildTodayWatchPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "TodayWatchPlugin"

@Serializable
enum class TodayWatchPluginMode {
    RELAX,
    LEARN
}

@Serializable
enum class TodayWatchCandidatePoolMode {
    LOCAL,
    EXPANDED
}

@Serializable
data class TodayWatchPluginConfig(
    val currentMode: TodayWatchPluginMode = TodayWatchPluginMode.RELAX,
    val recommendationStrategy: RecommendationStrategy = RecommendationStrategy.BALANCED,
    val candidatePoolMode: TodayWatchCandidatePoolMode = TodayWatchCandidatePoolMode.LOCAL,
    val upRankLimit: Int = 5,
    val queueBuildLimit: Int = 20,
    val queuePreviewLimit: Int = 6,
    val historySampleLimit: Int = 80,
    val linkEyeCareSignal: Boolean = true,
    val showUpRank: Boolean = true,
    val showReasonHint: Boolean = true,
    val enableWaterfallAnimation: Boolean = true,
    val waterfallExponent: Float = 1.38f,
    val collapsed: Boolean = false,
    val refreshTriggerToken: Long = 0L
)

class TodayWatchPlugin(private val personalizationContext: () -> com.bilipai.desktop.plugins.DesktopPluginContext) : RecommendationPluginApi {

    override val id: String = PLUGIN_ID
    override val name: String = "今日推荐单"
    override val description: String = "本地分析观看历史，生成可定制推荐队列"
    override val version: String = "1.1.0"
    override val author: String = "BiliPai项目组"
    override val capabilityManifest: PluginCapabilityManifest = PluginCapabilityManifest(
        pluginId = id,
        displayName = name,
        version = version,
        apiVersion = 1,
        entryClassName = "com.android.purebilibili.feature.plugin.TodayWatchPlugin",
        capabilities = setOf(
            PluginCapability.RECOMMENDATION_CANDIDATES,
            PluginCapability.LOCAL_HISTORY_READ,
            PluginCapability.LOCAL_FEEDBACK_READ
        )
    )

    private val ioScope = com.bilipai.desktop.plugins.DesktopPluginScopeRegistry.create("com/android/purebilibili/feature/plugin/TodayWatchPlugin.kt:1", Dispatchers.IO)
    private var config: TodayWatchPluginConfig = TodayWatchPluginConfig()
    private val _configState = MutableStateFlow(config)
    val configState: StateFlow<TodayWatchPluginConfig> = _configState.asStateFlow()

    override suspend fun onEnable() {
        loadConfigSuspend()
        Logger.d(TAG, "今日推荐单插件已启用")
    }

    override suspend fun onDisable() {
        Logger.d(TAG, "今日推荐单插件已禁用")
    }

    fun updateConfig(transform: (TodayWatchPluginConfig) -> TodayWatchPluginConfig) {
        val updated = normalizeConfig(transform(config))
        if (updated == config) return
        config = updated
        _configState.value = updated
        saveConfig()
    }

    fun setCurrentMode(mode: TodayWatchPluginMode) {
        updateConfig { it.copy(currentMode = mode) }
    }

    override fun buildRecommendations(request: RecommendationRequest): RecommendationResult {
        val plan = buildTodayWatchPlan(
            historyVideos = request.historyVideos,
            candidateVideos = request.candidateVideos,
            mode = request.mode.toTodayWatchMode(),
            eyeCareNightActive = request.sceneSignals.eyeCareNightActive,
            nowEpochSec = request.sceneSignals.nowEpochSec,
            upRankLimit = request.groupLimit,
            queueLimit = request.queueLimit,
            strategy = request.strategy,
            creatorSignals = request.creatorSignals.map { it.toTodayWatchCreatorSignal() },
            penaltySignals = TodayWatchPenaltySignals(
                consumedBvids = request.feedbackSignals.consumedBvids,
                dislikedBvids = request.feedbackSignals.dislikedBvids,
                dislikedCreatorMids = request.feedbackSignals.dislikedCreatorMids,
                dislikedKeywords = request.feedbackSignals.dislikedKeywords
            )
        )
        return RecommendationResult(
            sourcePluginId = id,
            mode = request.mode,
            items = plan.videoQueue.map { video ->
                RecommendedVideo(
                    video = video,
                    score = plan.scoreByBvid[video.bvid] ?: 0.0,
                    confidence = plan.confidenceByBvid[video.bvid] ?: 0f,
                    explanation = plan.explanationByBvid[video.bvid].orEmpty(),
                    actions = listOf(
                        RecommendationAction(
                            id = "open",
                            label = "播放",
                            targetBvid = video.bvid
                        )
                    )
                )
            },
            groups = listOf(
                RecommendationGroup(
                    id = "preferred_creators",
                    title = "偏好 UP",
                    items = plan.upRanks.map { rank ->
                        RecommendationGroupItem(
                            id = rank.mid.toString(),
                            title = rank.name,
                            subtitle = "${rank.watchCount} 次观看",
                            score = rank.score,
                            watchCount = rank.watchCount
                        )
                    }
                )
            ),
            historySampleCount = plan.historySampleCount,
            sceneSignals = request.sceneSignals,
            generatedAt = plan.generatedAt
        )
    }

    fun clearPersonalizationData() {
        ioScope.launch {
            try {
                val context = personalizationContext()
                TodayWatchProfileStore.clear(context)
                TodayWatchFeedbackStore.clear(context)
                updateConfig { current ->
                    current.copy(refreshTriggerToken = System.currentTimeMillis())
                }
                Logger.d(TAG, "已清空今日推荐画像与反馈缓存")
            } catch (e: Exception) {
                Logger.e(TAG, "清空画像失败", e)
            }
        }
    }

    private suspend fun loadConfigSuspend() {
        try {
            val context = PluginManager.getContext()
            val json = PluginStore.getConfigJson(context, id)
            config = if (json.isNullOrBlank()) {
                TodayWatchPluginConfig()
            } else {
                normalizeConfig(Json.decodeFromString(json))
            }
            _configState.value = config
        } catch (e: Exception) {
            Logger.e(TAG, "加载配置失败", e)
            config = TodayWatchPluginConfig()
            _configState.value = config
        }
    }

    private fun saveConfig() {
        ioScope.launch {
            try {
                val context = PluginManager.getContext()
                PluginStore.setConfigJson(context, id, Json.encodeToString(config))
            } catch (e: Exception) {
                Logger.e(TAG, "保存配置失败", e)
            }
        }
    }

    private fun normalizeConfig(source: TodayWatchPluginConfig): TodayWatchPluginConfig {
        val upRankLimit = source.upRankLimit.coerceIn(1, 12)
        val queueBuildLimit = source.queueBuildLimit.coerceIn(6, 40)
        val queuePreviewLimit = source.queuePreviewLimit.coerceIn(3, 12).coerceAtMost(queueBuildLimit)
        return source.copy(
            upRankLimit = upRankLimit,
            queueBuildLimit = queueBuildLimit,
            queuePreviewLimit = queuePreviewLimit,
            historySampleLimit = source.historySampleLimit.coerceIn(20, 120),
            waterfallExponent = source.waterfallExponent.coerceIn(1.0f, 2.2f)
        )
    }



    companion object {
        const val PLUGIN_ID: String = "today_watch"

        fun getInstance(): TodayWatchPlugin? {
            return PluginManager.plugins
                .find { it.plugin.id == PLUGIN_ID }
                ?.plugin as? TodayWatchPlugin
        }
    }
}

private fun RecommendationMode.toTodayWatchMode(): TodayWatchMode {
    return when (this) {
        RecommendationMode.RELAX -> TodayWatchMode.RELAX
        RecommendationMode.LEARN -> TodayWatchMode.LEARN
    }
}

private fun RecommendationCreatorSignal.toTodayWatchCreatorSignal(): TodayWatchCreatorSignal {
    return TodayWatchCreatorSignal(
        mid = mid,
        name = name,
        score = score,
        watchCount = watchCount
    )
}

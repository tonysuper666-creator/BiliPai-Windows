package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.feature.home.HomeFeedMergePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal data class DesktopRecommendationBatch(val items: List<VideoItem>, val requestedMode: DesktopRecommendationMode,
    val actualSources: Set<DesktopRecommendationMode>, val sourceNotice: String = "")

/** Match the original mobile fallback and parallel merged recommendation flow. */
internal suspend fun resolveDiscoveryRecommendation(mode: DesktopRecommendationMode,
    web: suspend (merged: Boolean) -> List<VideoItem>, app: suspend (merged: Boolean) -> List<VideoItem>): DesktopRecommendationBatch {
    suspend fun capture(request: suspend () -> List<VideoItem>): Result<List<VideoItem>> = try { Result.success(request()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
    return when (mode) {
        DesktopRecommendationMode.WEB -> DesktopRecommendationBatch(web(false), mode, setOf(DesktopRecommendationMode.WEB))
        DesktopRecommendationMode.MOBILE -> {
            val appResult = capture { app(false) }
            val appItems = appResult.getOrNull().orEmpty()
            if (appItems.isNotEmpty()) DesktopRecommendationBatch(appItems, mode, setOf(DesktopRecommendationMode.MOBILE))
            else DesktopRecommendationBatch(web(false), mode, setOf(DesktopRecommendationMode.WEB), "移动端推荐暂不可用，已回退网页端推荐")
        }
        DesktopRecommendationMode.MERGED -> coroutineScope {
            val webTask = async { capture { web(true) } }; val appTask = async { capture { app(true) } }
            val webResult = webTask.await(); val appResult = appTask.await()
            val webItems = webResult.getOrNull().orEmpty(); val appItems = appResult.getOrNull().orEmpty()
            if (webItems.isEmpty() && appItems.isEmpty()) throw webResult.exceptionOrNull() ?: appResult.exceptionOrNull()
                ?: BiliApiException(-1, "获取合并推荐流失败")
            DesktopRecommendationBatch(HomeFeedMergePolicy.mergeFeeds(web = webItems, app = appItems), mode,
                buildSet { if (webItems.isNotEmpty()) add(DesktopRecommendationMode.WEB); if (appItems.isNotEmpty()) add(DesktopRecommendationMode.MOBILE) },
                when { webItems.isEmpty() -> "网页端暂未返回推荐，当前显示合并模式的 App 推荐"
                    appItems.isEmpty() -> "App 端暂未返回推荐，当前显示合并模式的网页端推荐"
                    else -> "" })
        }
    }
}

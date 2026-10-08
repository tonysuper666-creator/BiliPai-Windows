package com.bilipai.desktop.data

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.AppSignUtils
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.resolveRegionRankingRid
import com.android.purebilibili.data.repository.shouldFallbackRegionLatestToRanking
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.core.store.TodayWatchDislikedVideoSnapshot
import com.android.purebilibili.core.store.TodayWatchFeedbackSnapshot
import com.android.purebilibili.feature.home.resolveHomeNotInterestedAction
import com.android.purebilibili.feature.home.resolveWeeklyNumberForRequest
import com.android.purebilibili.feature.partition.resolvePartitionBangumiType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class DesktopDiscoveryRepository(private val repository: DesktopRepository,
    private val preferences: DesktopDiscoveryPreferences = DesktopDiscoveryPreferences()) {
    private val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(repository.httpClient)
        .addConverterFactory(Json { ignoreUnknownKeys = true; coerceInputValues = true }.asConverterFactory("application/json".toMediaType()))
        .build().create(BilibiliApi::class.java)
    private val mutationMutex = Mutex()
    internal val homePreferences: DesktopDiscoveryPreferences get() = preferences
    val feedMode: StateFlow<DesktopRecommendationMode> get() = preferences.feedMode
    val refreshCount: StateFlow<Int> get() = preferences.refreshCount
    suspend fun setFeedMode(value: DesktopRecommendationMode) = preferences.setFeedMode(value)
    suspend fun setRefreshCount(value: Int) = preferences.setRefreshCount(value)
    fun feedback(mid: Long?): StateFlow<TodayWatchFeedbackSnapshot> = preferences.feedback(mid)
    val blockedUps: DesktopBlockedUpStore get() = preferences.blockedUps
    internal fun freezeWritesForRestore() = preferences.freezeWritesForRestore()
    fun blockedCreators(mid: Long?): StateFlow<Set<Long>> = preferences.blockedCreators(mid)
    internal fun recommendationContext(mid: Long? = repository.account.value?.mid) = preferences.recommendationContext(mid)
    suspend fun clearFeedback(expectedAccountMid: Long? = repository.account.value?.mid) {
        val epoch = repository.sessionEpoch
        mutationMutex.withLock { ensureEpoch(epoch); ensureAccount(expectedAccountMid); preferences.clearFeedback(expectedAccountMid) }
    }

    suspend fun page(section: DiscoverySection, page: Int = 1, regionId: Int = 0, weeklyNumber: Int? = null): DiscoveryPage = withContext(Dispatchers.IO) {
        require(page > 0 && regionId >= 0 && (weeklyNumber == null || weeklyNumber > 0))
        val catalogRequest = repository.catalogInvocation()
        catalogRequest.ensureSession()
        val api = catalogRequest.api
        when (section) {
            DiscoverySection.RECOMMEND -> {
                val count = refreshCount.value; val mode = feedMode.value
                val result = resolveDiscoveryRecommendation(mode, web = { merged ->
                    val params = if (merged) buildDesktopMergedWebRecommendParams(page - 1, count) else discoveryRecommendParams(page, count)
                    val response = api.getRecommendParams(catalogRequest.sign(params))
                    verified(response.code, response.message, response.data).item.orEmpty().map { it.toVideoItem() }.filter { it.bvid.isNotBlank() }
                }, app = { merged ->
                    val token = catalogRequest.accessToken()?.takeIf { it.isNotBlank() }
                    val response = if (merged) {
                        val params = buildDesktopMergedMobileRecommendParams(page - 1)
                        token?.let { params["access_key"] = it }
                        val encoded = AppSignUtils.signForAndroidHdLogin(params).mapValues { (_, value) -> AppSignUtils.percentEncode(value) }
                        api.getMobileFeedEncoded(encoded)
                    } else {
                        val accessToken = token ?: throw BiliApiException(-101, "需要移动端登录凭证才能使用 App 推荐")
                        api.getMobileFeed(AppSignUtils.signForTvLogin(buildDesktopMobileRecommendParams(page - 1, count, accessToken)))
                    }
                    verified(response.code, response.message, response.data).items.orEmpty().filter { it.goto == "av" }
                        .map { it.toVideoItem() }.filter { it.bvid.isNotBlank() }
                })
                DiscoveryPage(result.items, (page + 1).takeIf { result.items.isNotEmpty() }, requestedMode = mode,
                    actualSources = result.actualSources, sourceNotice = result.sourceNotice)
            }
            DiscoverySection.POPULAR -> {
                var metadata: PopularData? = null
                catalogRequest.shared.getPopularVideos(page, catalogRequest.ensureSession) { metadata = it }.getOrThrow()
                catalogRequest.assertOwned()
                // Preserve no_more / empty-page semantics from this exact response.
                discoveryPopularPage(metadata ?: throw BiliApiException(-1, "响应数据为空"), page)
            }
            DiscoverySection.RANKING -> ranking(resolveRegionRankingRid(regionId) ?: regionId, catalogRequest)
            DiscoverySection.PRECIOUS -> {
                var metadata: PopularPreciousData? = null
                val items = catalogRequest.shared.getPreciousVideos(catalogRequest.ensureSession) { metadata = it }.getOrThrow()
                catalogRequest.assertOwned()
                val data = metadata ?: throw BiliApiException(-1, "响应数据为空")
                DiscoveryPage(items, null, data.title, data.explain)
            }
            DiscoverySection.WEEKLY -> {
                val number = weeklyNumber ?: resolveWeeklyNumberForRequest(weeklyPeriods().map { it.number })
                val data = catalogRequest.shared.getWeeklyPeriod(number).getOrThrow()
                catalogRequest.assertOwned()
                DiscoveryPage(data.list.orEmpty().map { it.toVideoItem() }.filter { it.bvid.isNotBlank() }, null,
                    data.config?.name.orEmpty(), data.reminder.ifBlank { data.config?.subject.orEmpty() }, data.config)
            }
            DiscoverySection.REGION -> {
                require(regionId > 0) { "请选择分区" }
                require(resolvePartitionBangumiType(regionId) == null) { "此分区使用番剧 / 影视索引" }
                val response = api.getRegionVideos(regionId, page, 30)
                val raw = response.data?.archives.orEmpty()
                val items = raw.map { it.toVideoItem() }.filter { it.bvid.isNotBlank() }
                if (shouldFallbackRegionLatestToRanking(regionId, page, items.size, response.code)) {
                    if (regionId == 202) {
                        val legacy = api.getLegacyRegionVideos(regionId, page, 30)
                        if (legacy.code == 0) {
                            val data = legacy.data ?: throw BiliApiException(-1, "分区响应为空")
                            val incoming = data.archives.orEmpty()
                            val hasMore = incoming.isNotEmpty() && (data.page?.let { page.toLong() * it.size < it.count } ?: true)
                            return@withContext DiscoveryPage(incoming.map { it.toVideoItem() }.filter { it.bvid.isNotBlank() }, (page + 1).takeIf { hasMore }).also { catalogRequest.assertOwned() }
                        }
                    }
                    resolveRegionRankingRid(regionId)?.let { return@withContext ranking(it, catalogRequest) }
                }
                check(response.code, response.message)
                if (response.data == null) throw BiliApiException(-1, "分区响应为空")
                DiscoveryPage(items, (page + 1).takeIf { raw.isNotEmpty() })
            }
        }.also { catalogRequest.assertOwned() }
    }


    // STABLE_WEEKLY_SERIES_MEMBERS: sole original Result protocol / shared API.
    private val weeklySeriesRequestsDelegate by lazy {
        object : com.android.purebilibili.feature.home.DesktopWeeklySeriesRequests {
            // The same shared catalog supplies both retained Weekly UI requests.
            override suspend fun getWeeklyPeriods(): Result<List<PopularSeriesPeriod>> = withContext(Dispatchers.IO) {
                try { val request = repository.catalogInvocation(); request.ensureSession(); request.shared.getWeeklyPeriods().also { request.assertOwned() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Result.failure(failure) }
            }
            override suspend fun getWeeklyPeriod(number: Int): Result<PopularSeriesOneData> = withContext(Dispatchers.IO) {
                try { val request = repository.catalogInvocation(); request.ensureSession(); request.shared.getWeeklyPeriod(number).also { request.assertOwned() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Result.failure(failure) }
            }
        }
    }
    fun weeklySeriesRequests(): com.android.purebilibili.feature.home.DesktopWeeklySeriesRequests = weeklySeriesRequestsDelegate

    suspend fun weeklyPeriods(): List<PopularSeriesPeriod> = withContext(Dispatchers.IO) {
        val request = repository.catalogInvocation()
        request.ensureSession()
        request.shared.getWeeklyPeriods().getOrThrow().also { request.assertOwned() }
    }

    suspend fun videoShots(bvid: String, cid: Long): VideoshotData = withContext(Dispatchers.IO) {
        require(bvid.isNotBlank() && cid > 0); repository.ensureSession()
        val response = api.getVideoshot(bvid, cid)
        verified(response.code, response.message, response.data)
    }

    /** All writes begin with the user's selected reason. Local feedback survives a remote failure. */
    suspend fun notInterested(video: VideoItem, reason: RecommendationFeedbackReason,
        expectedAccountMid: Long? = repository.account.value?.mid): DesktopRecommendationFeedbackResult {
        val epoch = repository.sessionEpoch
        return withContext(Dispatchers.IO) {
        require(video.bvid.isNotBlank() && reason.name.isNotBlank())
        mutationMutex.withLock {
            ensureEpoch(epoch); ensureAccount(expectedAccountMid); val mid = expectedAccountMid
            val action = resolveHomeNotInterestedAction(video, reason)
            preferences.record(mid, TodayWatchDislikedVideoSnapshot(video.bvid, video.title, video.owner.name, video.owner.mid,
                System.currentTimeMillis()), action.keywords, action.shouldBlockCreator)
            val creator = if (action.shouldSyncCreatorToBilibiliBlockedList) syncCreator(action.creatorMid, true, epoch) else null
            val metadata = video.recommendationFeedback
            var serverSuccess: Boolean? = null; var serverError: String? = null
            if (metadata?.supportsServerSync == true && reason.id != null) {
                try {
                    val token = repository.accessTokenCredentials().first?.takeIf { it.isNotBlank() }
                        ?: throw BiliApiException(-101, "缺少移动端登录凭证")
                    val request = buildRecommendationFeedbackRequest(metadata, reason) ?: throw BiliApiException(-1, "当前推荐不支持服务器同步")
                    repository.ensureSession(); ensureEpoch(epoch)
                    val params = buildRecommendationFeedbackParams(request, token, AppSignUtils.getTimestamp())
                    val response = mutationApi(epoch).submitMobileFeedDislike(AppSignUtils.signForTvLogin(params))
                    check(response.code, response.message); serverSuccess = true
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) { serverSuccess = false; serverError = safeFailure(error) }
            }
            val message = when { serverSuccess == false -> "已在本地生效，服务器推荐反馈同步失败"
                else -> reason.toast.ifBlank { "已减少相关内容推荐" } }
            DesktopRecommendationFeedbackResult(message, creator, serverSuccess, serverError)
        }
        }
    }

    suspend fun unblockCreator(mid: Long, expectedAccountMid: Long? = repository.account.value?.mid): BlockedUpWriteResult {
        val epoch = repository.sessionEpoch
        return withContext(Dispatchers.IO) {
        require(mid > 0)
        mutationMutex.withLock {
            ensureEpoch(epoch); ensureAccount(expectedAccountMid)
            preferences.changeBlocked(expectedAccountMid, mid, false)
            syncCreator(mid, false, epoch)
        }
        }
    }

    private fun mutationApi(epoch: Long): BilibiliApi = Retrofit.Builder().baseUrl("https://api.bilibili.com/")
        .client(repository.httpClient.newBuilder().retryOnConnectionFailure(false).addInterceptor { chain ->
            ensureEpoch(epoch); chain.proceed(chain.request())
        }.build())
        .addConverterFactory(Json { ignoreUnknownKeys = true; coerceInputValues = true }.asConverterFactory("application/json".toMediaType()))
        .build().create(BilibiliApi::class.java)

    private suspend fun syncCreator(mid: Long, blocked: Boolean, epoch: Long): BlockedUpWriteResult {
        val csrf = runCatching { repository.requireCsrf() }.getOrNull()
        if (csrf.isNullOrBlank()) return BlockedUpWriteResult(true, BilibiliBlockedListRemoteStatus.SKIPPED_NOT_LOGGED_IN,
            buildBlockedUpWriteMessage(blocked, BilibiliBlockedListRemoteStatus.SKIPPED_NOT_LOGGED_IN))
        var status = BilibiliBlockedListRemoteStatus.FAILED; var error: String? = null
        try {
            repository.ensureSession(); ensureEpoch(epoch)
            val (act, source) = desktopBlockedRelationArguments(blocked, BlockedUpRelationSource.PROFILE)
            val response = mutationApi(epoch).modifyRelation(fid = mid, act = act, reSrc = source, csrf = csrf)
            check(response.code, response.message); status = BilibiliBlockedListRemoteStatus.SUCCESS
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { status = BilibiliBlockedListRemoteStatus.FAILED; error = safeFailure(failure) }
        return BlockedUpWriteResult(true, status, buildBlockedUpWriteMessage(blocked, status, error))
    }

    private fun ensureEpoch(epoch: Long) { if (epoch != repository.sessionEpoch) throw BiliApiException(-101, "账号已变化，请重新操作") }
    private fun ensureAccount(mid: Long?) { if (mid != repository.account.value?.mid) throw BiliApiException(-101, "账号已变化，请重新操作") }
    private fun safeFailure(error: Exception): String = when (error) {
        is BiliApiException -> error.message.orEmpty()
        is retrofit2.HttpException -> "服务器返回 HTTP ${error.code()}"
        else -> "网络连接失败，请稍后重试"
    }

    private suspend fun ranking(rid: Int, request: DesktopCatalogInvocation): DiscoveryPage {
        var metadata: RankingData? = null
        val items = request.shared.getRankingVideos(rid, "all", request.ensureSession, request.wbiKeys) { metadata = it }.getOrThrow()
        request.assertOwned()
        val data = metadata ?: throw BiliApiException(-1, "响应数据为空")
        return DiscoveryPage(items, null, "排行榜", data.note)
    }

    private fun check(code: Int, message: String) { if (code != 0) throw BiliApiException(code, message.ifBlank { "加载失败 ($code)" }) }
    private fun <T : Any> verified(code: Int, message: String, data: T?): T { check(code, message); return data ?: throw BiliApiException(-1, "响应数据为空") }
}

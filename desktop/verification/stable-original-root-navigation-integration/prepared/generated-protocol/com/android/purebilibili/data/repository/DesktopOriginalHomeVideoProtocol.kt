package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.*
import com.android.purebilibili.core.store.DesktopFeedSettings
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.ui.DesktopHomeProtocolEnvironment
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** Selected complete original home request bodies; required services are views of Root's graph. */
internal class DesktopOriginalHomeVideoProtocol(private val environment:DesktopHomeProtocolEnvironment) : AutoCloseable {
    private val api get()=environment.api
    @Volatile private var preloadedHomeVideos: Result<List<VideoItem>>? = null
    @Volatile private var homePreloadDeferred: Deferred<Result<List<VideoItem>>>? = null
    @Volatile private var hasCompletedHomePreload = false
    private val verticalVideoCache = ConcurrentHashMap<String, Boolean>()

    fun isHomeDataReady(): Boolean {
        return shouldReportHomeDataReadyForSplash(
            hasCompletedPreload = hasCompletedHomePreload,
            hasPreloadedData = preloadedHomeVideos != null
        )
    }

    fun preloadHomeData(scope: CoroutineScope = environment.parentScope) {
        val activePreloadTask = homePreloadDeferred?.takeIf { it.isActive } != null
        if (!shouldStartHomePreload(preloadedHomeVideos != null, activePreloadTask)) return
        hasCompletedHomePreload = false

        com.android.purebilibili.core.util.Logger.d("VideoRepo", "🚀 Starting home data preload...")

        homePreloadDeferred = scope.async {
            try {
                val feedApiType = environment.feedApiType()
                if (shouldPrimeBuvidForHomePreload(feedApiType)) {
                    // 移动端推荐流可能依赖 buvid 会话，保留预热。
                    environment.ensureBuvid3FromSpi()
                } else {
                    com.android.purebilibili.core.util.Logger.d(
                        "VideoRepo",
                        "🚀 Skip buvid warmup for WEB home preload"
                    )
                }

                val result = getHomeVideosInternal(idx = 0)
                preloadedHomeVideos = result

                com.android.purebilibili.core.util.Logger.d("VideoRepo", "🚀 Home data preload finished. Success=${result.isSuccess}")
                result
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) {
                com.android.purebilibili.core.util.Logger.e("VideoRepo", "🚀 Home data preload failed", e)
                Result.failure<List<VideoItem>>(e).also { preloadedHomeVideos = it }
            } finally {
                hasCompletedHomePreload = true
            }
        }
    }

    private suspend fun awaitHomePreloadResult(): Result<List<VideoItem>>? {
        val deferred = homePreloadDeferred ?: return null
        return runCatching { deferred.await() }.getOrNull()
    }

    private fun consumePreloadedHomeVideos(): Result<List<VideoItem>>? {
        val cached = preloadedHomeVideos ?: return null
        preloadedHomeVideos = null
        homePreloadDeferred = null
        return cached
    }

    suspend fun getHomeVideos(idx: Int = 0): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        // 如果是首次加载 (idx=0) 且有预加载数据，直接使用
        if (idx == 0) {
            val cached = consumePreloadedHomeVideos()
            if (cached != null) {
                com.android.purebilibili.core.util.Logger.d("VideoRepo", "✅ Using preloaded home data!")
                return@withContext cached
            }

            val hasActivePreloadTask = homePreloadDeferred?.isActive == true
            if (shouldReuseInFlightPreloadForHomeRequest(idx, hasActivePreloadTask, hasPreloadedData = false)) {
                val awaited = awaitHomePreloadResult()
                if (awaited != null) {
                    com.android.purebilibili.core.util.Logger.d(
                        "VideoRepo",
                        "✅ Reused in-flight home preload result"
                    )
                    consumePreloadedHomeVideos()
                    return@withContext awaited
                }
            }
        }

        getHomeVideosInternal(idx)
    }

    private suspend fun getHomeVideosInternal(idx: Int): Result<List<VideoItem>> {
        try {
            //  读取推荐流类型设置
            val feedApiType = environment.feedApiType()
            val refreshCount = environment.refreshCount()

            com.android.purebilibili.core.util.Logger.d(
                "VideoRepo",
                " getHomeVideos: feedApiType=$feedApiType, idx=$idx, refreshCount=$refreshCount"
            )

            when (feedApiType) {
                DesktopFeedSettings.FeedApiType.MOBILE -> {
                    // 尝试使用移动端 API
                    val mobileResult = fetchMobileFeed(idx = idx, refreshCount = refreshCount)
                    if (mobileResult.isSuccess && mobileResult.getOrNull()?.isNotEmpty() == true) {
                        return mobileResult
                    } else {
                        // 移动端 API 失败，回退到 Web API
                        com.android.purebilibili.core.util.Logger.d("VideoRepo", " Mobile API failed, fallback to Web API")
                        return fetchWebFeed(idx = idx, refreshCount = refreshCount)
                    }
                }
                DesktopFeedSettings.FeedApiType.MERGED -> {
                    // 合并模式(参数对齐 PiliNara 原版):
                    // Web 半边用 fresh_type=4/feed_version=V8/brush=idx 等参数, 携带 cookie;
                    // App 半边用 android_hd 身份头取流(剥离 cookie, 已登录时带 access_key),
                    // 并行请求后交错合并、按视频去重
                    return coroutineScope {
                        val webDeferred = async { fetchMergedWebFeed(idx = idx, refreshCount = refreshCount) }
                        val mobileDeferred = async { fetchMergedMobileFeed(idx = idx) }
                        val webResult = webDeferred.await()
                        val mobileResult = mobileDeferred.await()

                        val webList = webResult.getOrNull().orEmpty()
                        val mobileList = mobileResult.getOrNull().orEmpty()

                        if (webList.isEmpty() && mobileList.isEmpty()) {
                            // 两者都失败：优先返回 web 的失败原因，其次移动端
                            val error = webResult.exceptionOrNull() ?: mobileResult.exceptionOrNull()
                                ?: Exception("获取合并推荐流失败")
                            com.android.purebilibili.core.util.Logger.d("VideoRepo", " Merged feed both failed: ${error.message}")
                            Result.failure(error)
                        } else {
                            com.android.purebilibili.core.util.Logger.d(
                                "VideoRepo",
                                " Merged feed: web=${webList.size}, mobile=${mobileList.size}"
                            )
                            Result.success(com.android.purebilibili.feature.home.HomeFeedMergePolicy.mergeFeeds(web = webList, app = mobileList))
                        }
                    }
                }
                else -> return fetchWebFeed(idx = idx, refreshCount = refreshCount)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Windows boundary does not print raw URL/response exceptions.
            return Result.failure(e)
        }
    }

    private suspend fun fetchWebFeed(idx: Int, refreshCount: Int): Result<List<VideoItem>> {
        try {
            val cachedKeys = environment.wbiKeys().getOrNull()
            val navWbiImg = if (cachedKeys == null) api.getNavInfo().data?.wbi_img else null
            val resolvedKeys = resolveHomeFeedWbiKeys(
                cachedKeys = cachedKeys,
                navWbiImg = navWbiImg
            ) ?: throw Exception("无法获取 Key")
            val (imgKey, subKey) = resolvedKeys

            val params = mapOf(
                "ps" to refreshCount.toString(), "fresh_type" to "3", "fresh_idx" to idx.toString(),
                "feed_version" to System.currentTimeMillis().toString(), "y_num" to idx.toString()
            )
            val signedParams = WbiUtils.sign(params, imgKey, subKey)
            val feedResp = api.getRecommendParams(signedParams)

            //  [调试] 检查 API 是否返回 dimension 字段
            feedResp.data?.item?.take(3)?.forEachIndexed { index, item ->
                com.android.purebilibili.core.util.Logger.d("VideoRepo", 
                    " 视频[$index]: ${item.title?.take(15)}... dimension=${item.dimension} isVertical=${item.dimension?.isVertical}")
            }

            val list = feedResp.data?.item?.map { it.toVideoItem() }?.filter { it.bvid.isNotEmpty() } ?: emptyList()

            //  [调试] 检查转换后的 VideoItem
            val verticalCount = list.count { it.isVertical }
            com.android.purebilibili.core.util.Logger.d("VideoRepo", " Web推荐: total=${list.size}, vertical=$verticalCount")

            return Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Windows boundary does not print raw URL/response exceptions.
            return Result.failure(e)
        }
    }

    private suspend fun fetchMobileFeed(idx: Int, refreshCount: Int): Result<List<VideoItem>> {
        try {
            val accessToken = environment.accessToken()
            if (accessToken.isNullOrEmpty()) {
                com.android.purebilibili.core.util.Logger.d("VideoRepo", " No access_token, fallback to Web API")
                return Result.failure(Exception("需要登录才能使用移动端推荐流"))
            }

            val params = mapOf(
                "idx" to idx.toString(),
                "pull" to if (idx == 0) "1" else "0",  // 1=刷新, 0=加载更多
                "column" to "4",  // 4列布局
                "flush" to "5",   // 刷新间隔
                "autoplay_card" to "11",
                "ps" to refreshCount.toString(),
                "access_key" to accessToken,
                "appkey" to AppSignUtils.TV_APP_KEY,
                "ts" to AppSignUtils.getTimestamp().toString(),
                "mobi_app" to "android",
                "device" to "android",
                "build" to "8130300"
            )

            val signedParams = AppSignUtils.signForTvLogin(params)

            com.android.purebilibili.core.util.Logger.d("VideoRepo", " Mobile feed request: idx=$idx")
            val feedResp = api.getMobileFeed(signedParams)

            if (feedResp.code != 0) {
                com.android.purebilibili.core.util.Logger.d("VideoRepo", " Mobile feed error: code=${feedResp.code}, msg=${feedResp.message}")
                return Result.failure(Exception(feedResp.message))
            }

            val list = feedResp.data?.items
                ?.filter { it.goto == "av" }  // 只保留视频类型
                ?.map { it.toVideoItem() }
                ?.filter { it.bvid.isNotEmpty() }
                ?: emptyList()

            com.android.purebilibili.core.util.Logger.d("VideoRepo", " Mobile推荐: total=${list.size}")

            return Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            com.android.purebilibili.core.util.Logger.d("VideoRepo", " Mobile feed exception: ${e.message}")
            return Result.failure(e)
        }
    }

    private suspend fun fetchMergedWebFeed(idx: Int, refreshCount: Int): Result<List<VideoItem>> {
        try {
            val cachedKeys = environment.wbiKeys().getOrNull()
            val navWbiImg = if (cachedKeys == null) api.getNavInfo().data?.wbi_img else null
            val resolvedKeys = resolveHomeFeedWbiKeys(
                cachedKeys = cachedKeys,
                navWbiImg = navWbiImg
            ) ?: throw Exception("无法获取 Key")
            val (imgKey, subKey) = resolvedKeys

            val params = mapOf(
                "version" to "1",
                "feed_version" to "V8",
                "homepage_ver" to "1",
                "ps" to refreshCount.toString(),
                "fresh_idx" to idx.toString(),
                "brush" to idx.toString(),
                "fresh_type" to "4"
            )
            val signedParams = WbiUtils.sign(params, imgKey, subKey)
            val feedResp = api.getRecommendParams(signedParams)

            val list = feedResp.data?.item?.map { it.toVideoItem() }?.filter { it.bvid.isNotEmpty() } ?: emptyList()

            com.android.purebilibili.core.util.Logger.d("VideoRepo", " Merged Web推荐: total=${list.size}")

            return Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Windows boundary does not print raw URL/response exceptions.
            return Result.failure(e)
        }
    }

    private suspend fun fetchMergedMobileFeed(idx: Int): Result<List<VideoItem>> {
        try {
            // app 取流依赖 buvid 会话, 缺失时先通过 SPI 获取
            // 先等会话备份异步恢复完成，避免启动窗口内误判 buvid 缺失而多打一次 SPI。
            environment.awaitSessionRestored()
            if (environment.buvid3().isNullOrEmpty()) {
                environment.ensureBuvid3FromSpi()
            }
            val params = mutableMapOf(
                "build" to "2001100",
                "c_locale" to "zh_CN",
                "channel" to "master",
                "column" to "4",
                "device" to "pad",
                "device_name" to "android",
                "device_type" to "0",
                "disable_rcmd" to "0",
                "flush" to "5",
                "fnval" to "976",
                "fnver" to "0",
                "force_host" to "2",
                "fourk" to "1",
                "guidance" to "0",
                "https_url_req" to "0",
                "idx" to idx.toString(),
                "mobi_app" to "android_hd",
                "network" to "wifi",
                "platform" to "android",
                "player_net" to "1",
                "pull" to if (idx == 0) "true" else "false",  // 首页 true=刷新, 往后 false=加载更多
                "qn" to "32",
                "recsys_mode" to "0",
                "s_locale" to "zh_CN",
                "splash_id" to "",
                "statistics" to "{\"appId\":5,\"platform\":3,\"version\":\"2.0.1\",\"abtest\":\"\"}",
                "ts" to AppSignUtils.getTimestamp().toString(),
                "voice_balance" to "0"
            )
            // 已登录时补 access_key: 对齐 PiliNara 原版(其对 app 端点补 access_key 后再签名)
            environment.accessToken()
                ?.takeIf { it.isNotBlank() }
                ?.let { params["access_key"] = it }

            // key/value percent-encode 后拼接签名,
            // 再用 encoded=true 端点原样发送, 保证签名与线上 query 完全一致
            val signedParams = AppSignUtils.signForAndroidHdLogin(params)
            val encodedParams = signedParams.mapValues { (_, value) -> AppSignUtils.percentEncode(value) }

            com.android.purebilibili.core.util.Logger.d("VideoRepo", " Merged Mobile feed request: idx=$idx")
            val feedResp = api.getMobileFeedEncoded(encodedParams)

            if (feedResp.code != 0) {
                com.android.purebilibili.core.util.Logger.d("VideoRepo", " Merged Mobile feed error: code=${feedResp.code}, msg=${feedResp.message}")
                return Result.failure(Exception(feedResp.message))
            }

            val list = feedResp.data?.items
                ?.filter { it.goto == "av" }  // 只保留视频类型
                ?.map { it.toVideoItem() }
                ?.filter { it.bvid.isNotEmpty() }
                ?: emptyList()

            com.android.purebilibili.core.util.Logger.d("VideoRepo", " Merged Mobile推荐: total=${list.size}")

            return Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            com.android.purebilibili.core.util.Logger.d("VideoRepo", " Merged Mobile feed exception: ${e.message}")
            return Result.failure(e)
        }
    }

    suspend fun getPopularVideos(page: Int = 1): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getPopularVideos(pn = page, ps = 30)
            val list = resp.data?.list?.map { it.toVideoItem() }?.filter { it.bvid.isNotEmpty() } ?: emptyList()
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Windows boundary does not print raw URL/response exceptions.
            Result.failure(e)
        }
    }

    suspend fun getRankingVideos(rid: Int = 0, type: String = "all"): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        try {
            val keys = environment.wbiKeys().getOrElse { throw it }
            val signedParams = WbiUtils.sign(
                params = mapOf("rid" to rid.toString(), "type" to type),
                imgKey = keys.first,
                subKey = keys.second,
            )
            val resp = api.getRankingVideos(signedParams)
            if (resp.code != 0) {
                return@withContext Result.failure(Exception(resp.message.ifBlank { "排行榜加载失败(${resp.code})" }))
            }
            val list = resp.data?.list
                ?.map { it.toVideoItem() }
                ?.filter { it.bvid.isNotEmpty() }
                ?: emptyList()
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Windows boundary does not print raw URL/response exceptions.
            Result.failure(e)
        }
    }

    suspend fun getPreciousVideos(): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getPopularPreciousVideos()
            if (resp.code != 0) {
                return@withContext Result.failure(Exception(resp.message.ifBlank { "入站必刷加载失败(${resp.code})" }))
            }
            val list = resp.data?.list
                ?.map { it.toVideoItem() }
                ?.filter { it.bvid.isNotEmpty() }
                ?: emptyList()
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Windows boundary does not print raw URL/response exceptions.
            Result.failure(e)
        }
    }

    suspend fun getWeeklyMustWatchVideos(number: Int? = null): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        try {
            val targetNumber = number ?: run {
                val listResp = api.getWeeklySeriesList()
                if (listResp.code != 0) {
                    return@withContext Result.failure(Exception(listResp.message.ifBlank { "每周必看列表加载失败(${listResp.code})" }))
                }
                val latest = listResp.data?.list
                    ?.map { it.number }
                    ?.maxOrNull()
                latest ?: 1
            }
            val resp = api.getWeeklySeriesVideos(number = targetNumber)
            if (resp.code != 0) {
                return@withContext Result.failure(Exception(resp.message.ifBlank { "每周必看加载失败(${resp.code})" }))
            }
            val list = resp.data?.list
                ?.map { it.toVideoItem() }
                ?.filter { it.bvid.isNotEmpty() }
                ?: emptyList()
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Windows boundary does not print raw URL/response exceptions.
            Result.failure(e)
        }
    }

    suspend fun getRegionVideos(tid: Int, page: Int = 1): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getRegionVideos(rid = tid, pn = page, ps = 30)
            val list = resp.data?.archives
                ?.map { it.toVideoItem() }
                ?.filter { it.bvid.isNotEmpty() }
                ?: emptyList()
            if (shouldFallbackRegionLatestToRanking(
                    tid = tid,
                    page = page,
                    latestVideoCount = list.size,
                    latestResponseCode = resp.code
                )
            ) {
                if (tid == 202) {
                    val legacy = api.getLegacyRegionVideos(rid = tid, pn = page, ps = 30)
                    if (legacy.code == 0) {
                        return@withContext Result.success(
                            legacy.data?.archives
                                ?.map { it.toVideoItem() }
                                ?.filter { it.bvid.isNotEmpty() }
                                ?: emptyList()
                        )
                    }
                }
                // dynamic/region 只稳定支持子分区；一级分区用排行榜兜底，避免标签页空白。
                val rankingRid = resolveRegionRankingRid(tid)
                if (rankingRid != null) {
                    return@withContext getRankingVideos(rid = rankingRid)
                }
            }
            if (resp.code != 0) {
                return@withContext Result.failure(Exception(resp.message.ifBlank { "分区视频加载失败(${resp.code})" }))
            }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Windows boundary does not print raw URL/response exceptions.
            Result.failure(e)
        }
    }

    suspend fun getNavInfo(): Result<NavData> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getNavInfo()
            if (resp.code == 0 && resp.data != null) {
                Result.success(resp.data)
            } else {
                if (resp.code == -101) {
                    Result.success(NavData(isLogin = false))
                } else {
                    Result.failure(Exception("错误码: ${resp.code}"))
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPreviewVideoUrl(bvid: String, cid: Long): String? {
        // 复用 fetchAsGuestFallback 逻辑获取简单 MP4
        val data = fetchAsGuestFallback(bvid, cid)
        // 返回第一个 durl 的 url
        return data?.durl?.firstOrNull()?.url
    }

    suspend fun isVerticalVideo(bvid: String, aid: Long = 0L): Boolean = withContext(Dispatchers.IO) {
        val normalizedBvid = bvid.trim()
        if (normalizedBvid.isEmpty() && aid <= 0L) return@withContext false
        val cacheKey = normalizedBvid.ifEmpty { "av$aid" }
        verticalVideoCache[cacheKey]?.let { return@withContext it }
        try {
            val lookup = resolveVideoInfoLookupInput(rawBvid = normalizedBvid, aid = aid)
                ?: return@withContext false
            val viewResp = if (lookup.bvid.isNotEmpty()) {
                api.getVideoInfo(lookup.bvid)
            } else {
                api.getVideoInfoByAid(lookup.aid)
            }
            val isVertical = viewResp.data?.dimension?.isVertical == true
            verticalVideoCache[cacheKey] = isVertical
            isVertical
        } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
            false
        }
    }

    private suspend fun fetchAsGuestFallback(bvid: String, cid: Long): PlayUrlData? {
        try {
            com.android.purebilibili.core.util.Logger.d("VideoRepo", " fetchAsGuestFallback: bvid=$bvid, cid=$cid (using guestApi)")

            // ✅ 使用 guestApi - 不携带登录凭证
            val guestApi = environment.guestApi

            for (guestQn in buildGuestFallbackQualities()) {
                val legacyResult = guestApi.getPlayUrlLegacy(
                    bvid = bvid,
                    cid = cid,
                    qn = guestQn,
                    fnval = 1, // MP4 格式
                    platform = "html5", // HTML5 平台
                    highQuality = if (guestQn >= 64) 1 else 0
                )

                if (legacyResult.code == 0 && legacyResult.data != null) {
                    val data = legacyResult.data
                    if (!data.durl.isNullOrEmpty()) {
                        com.android.purebilibili.core.util.Logger.d(
                            "VideoRepo",
                            " Guest fallback (Legacy ${guestQn}p) success: actual=${data.quality}"
                        )
                        return data
                    }
                } else {
                    com.android.purebilibili.core.util.Logger.d(
                        "VideoRepo",
                        " Guest fallback ${guestQn}p failed: code=${legacyResult.code}"
                    )
                }
            }

        } catch (cancelled: CancellationException) { throw cancelled
        } catch (e: Exception) {
            android.util.Log.w("VideoRepo", "Guest fallback failed: ${e.message}")
        }

        return null
    }
    override fun close() { homePreloadDeferred?.cancel(); homePreloadDeferred=null; preloadedHomeVideos=null; verticalVideoCache.clear() }
}

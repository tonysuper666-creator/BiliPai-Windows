package com.android.purebilibili.data.repository
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.core.network.WbiUtils
import com.android.purebilibili.core.network.AppSignUtils
import android.util.Log as Logger
import kotlinx.coroutines.*
import com.android.purebilibili.feature.video.ui.pager.PORTRAIT_PLAYBACK_TARGET_QUALITY
import com.android.purebilibili.feature.video.ui.pager.shouldUsePortraitParallelPlaybackBootstrap
import com.bilipai.desktop.ui.DesktopOriginalVideoLoadRepository
import com.bilipai.desktop.ui.DesktopOriginalVideoLoadProtocolEnvironment

internal class DesktopOriginalVideoLoadProtocol(private val environment:DesktopOriginalVideoLoadProtocolEnvironment) : DesktopOriginalVideoLoadRepository {
    private val api get() = environment.api
    private val APP_API_COOLDOWN_MS = 120_000L
    private var appApiCooldownUntilMs:Long
        get() = environment.state.appApiCooldownUntilMs
        set(value) { environment.state.appApiCooldownUntilMs = value }
    private var wbiKeysCache:Pair<String,String>?
        get() = environment.state.wbiKeys
        set(value) { environment.state.wbiKeys = value }
    private var wbiKeysTimestamp:Long
        get() = environment.state.wbiKeysTimestamp
        set(value) { environment.state.wbiKeysTimestamp = value }
    private var last412Time:Long
        get() = environment.state.last412Time
        set(value) { environment.state.last412Time = value }
    private val WBI_CACHE_DURATION = 1000 * 60 * 30
    private suspend fun ensureBuvid3FromSpi() = environment.ensureBuvid()
    private fun playbackAccount() = environment.playbackAccount()
    private fun hasPlaybackSessionCookie() = environment.hasPlaybackSessionCookie()
    private fun playbackAccessToken() = environment.playbackAccessToken()
    private fun playbackAccessTokenPlatform() = environment.playbackAccessTokenPlatform()
    override fun isUsingDedicatedPlaybackAccount() = playbackAccount() != null
    override fun isPlaybackLoggedIn() = resolveVideoPlaybackAuthState(hasPlaybackSessionCookie(), !playbackAccessToken().isNullOrEmpty())
    override fun isPlaybackVip() = environment.isPlaybackVip()
    override fun isAppApiCoolingDown() = (appApiCooldownUntilMs-System.currentTimeMillis()).coerceAtLeast(0L)>0L
    private fun isDirectedTrafficModeActive() = shouldEnableDirectedTrafficMode(environment.directedTrafficEnabled(),environment.isMobileData())
    private data class PlayUrlFetchResult(
        val data: PlayUrlData,
        val source: PlayUrlSource
    )

    override suspend fun getVideoInfoOnly(
        bvid: String,
        aid: Long,
        requestedCid: Long
    ): Result<ViewInfo> = withContext(Dispatchers.IO) {
        environment.assertOwned()

        try {
            val lookup = resolveVideoInfoLookupInput(rawBvid = bvid, aid = aid)
                ?: throw Exception("无效的视频标识: bvid=$bvid, aid=$aid")
            val viewResp = if (lookup.bvid.isNotEmpty()) {
                api.getVideoInfo(lookup.bvid)
            } else {
                api.getVideoInfoByAid(lookup.aid)
            }
            val rawInfo = viewResp.data ?: throw Exception("视频详情为空: ${viewResp.code}")
            val cid = resolveRequestedVideoCid(
                requestCid = requestedCid,
                infoCid = rawInfo.cid,
                pages = rawInfo.pages
            )
            if (cid == 0L) throw Exception("CID 获取失败")
            val info = if (cid > 0L && cid != rawInfo.cid) {
                rawInfo.copy(cid = cid)
            } else {
                rawInfo
            }
            environment.assertOwned()
            Result.success(info)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            environment.assertOwned()

            Result.failure(e)
        }
    }

    override suspend fun getInitialPlayUrlData(
        bvid: String,
        cid: Long,
        targetQuality: Int,
        audioLang: String?
    ): PlayUrlData? = withContext(Dispatchers.IO) {
        environment.assertOwned()

        if (bvid.isBlank() || cid <= 0L) return@withContext null

        val isAutoHighestQuality = targetQuality >= 127
        val isLogin = resolveVideoPlaybackAuthState(
            hasSessionCookie = hasPlaybackSessionCookie(),
            hasAccessToken = !playbackAccessToken().isNullOrEmpty()
        )
        val isVip = isPlaybackVip()
        val auto1080pEnabled = environment.auto1080pEnabled()
        val startQuality = resolveInitialStartQuality(
            targetQuality = targetQuality,
            isAutoHighestQuality = isAutoHighestQuality,
            isLogin = isLogin,
            isVip = isVip,
            auto1080pEnabled = auto1080pEnabled
        )

        if (!shouldSkipPlayUrlCache(isAutoHighestQuality, isVip, audioLang)) {
            val cachedPlayData = environment.cache.get(
                bvid = bvid,
                cid = cid,
                requestedQuality = startQuality
            )
            val cachedDashIds = cachedPlayData?.dash?.video?.map { it.id }.orEmpty()
            if (
                cachedPlayData != null &&
                shouldAcceptCachedPlayUrlForAutoHighest(
                    isAutoHighestQuality = isAutoHighestQuality,
                    isVip = isVip,
                    cachedDashVideoIds = cachedDashIds
                )
            ) {
                environment.assertOwned()
                return@withContext cachedPlayData
            }
        }

        val fetchResult = fetchPlayUrlRecursive(
            bvid = bvid,
            cid = cid,
            targetQn = startQuality,
            audioLang = audioLang,
            requestKind = PlayUrlRequestKind.INITIAL
        ) ?: return@withContext null

        val dashVideoIds = fetchResult.data.dash?.video?.map { it.id }?.distinct() ?: emptyList()
        if (shouldCachePlayUrlResult(
                source = fetchResult.source,
                audioLang = audioLang,
                requestedQuality = startQuality,
                returnedQuality = fetchResult.data.quality,
                dashVideoIds = dashVideoIds
            )
        ) {
            environment.cache.put(
                bvid = bvid,
                cid = cid,
                data = fetchResult.data,
                quality = startQuality
            )
        }
        fetchResult.data
    }

    override suspend fun getVideoDetails(
        bvid: String,
        aid: Long,
        requestedCid: Long,
        targetQuality: Int?,
        audioLang: String?
    ): Result<Pair<ViewInfo, PlayUrlData>> = withContext(Dispatchers.IO) {
        environment.assertOwned()

        try {
            val lookup = resolveVideoInfoLookupInput(rawBvid = bvid, aid = aid)
                ?: throw Exception("无效的视频标识: bvid=$bvid, aid=$aid")
            val viewResp = if (lookup.bvid.isNotEmpty()) {
                Logger.d("VideoRepo", " getVideoDetails: using bvid=${lookup.bvid}")
                api.getVideoInfo(lookup.bvid)
            } else {
                Logger.d("VideoRepo", " getVideoDetails: using aid=${lookup.aid}")
                api.getVideoInfoByAid(lookup.aid)
            }
            
            val rawInfo = viewResp.data ?: throw Exception("视频详情为空: ${viewResp.code}")
            val cid = resolveRequestedVideoCid(
                requestCid = requestedCid,
                infoCid = rawInfo.cid,
                pages = rawInfo.pages
            )
            val info = if (cid > 0L && cid != rawInfo.cid) {
                rawInfo.copy(cid = cid)
            } else {
                rawInfo
            }
            val cacheBvid = info.bvid.ifBlank { lookup.bvid.ifBlank { bvid } }
            
            //  [调试] 记录视频信息
            Logger.d(
                "VideoRepo",
                " getVideoDetails: bvid=${info.bvid}, aid=${info.aid}, requestCid=$requestedCid, infoCid=${rawInfo.cid}, resolvedCid=$cid, title=${info.title.take(20)}..."
            )
            
            if (cid == 0L) throw Exception("CID 获取失败")

            // 🚀 [修复] 自动最高画质模式：跳过缓存，确保获取最新的高清流
            val isAutoHighestQuality = targetQuality != null && targetQuality >= 127

            //  [优化] 根据登录和大会员状态选择起始画质
            val isLogin = resolveVideoPlaybackAuthState(
                hasSessionCookie = hasPlaybackSessionCookie(),
                hasAccessToken = !playbackAccessToken().isNullOrEmpty()
            )
            val isVip = isPlaybackVip()
            
            //  [实验性功能] 读取 auto1080p 设置
            val auto1080pEnabled = environment.auto1080pEnabled()
            
            val startQuality = resolveInitialStartQuality(
                targetQuality = targetQuality,
                isAutoHighestQuality = isAutoHighestQuality,
                isLogin = isLogin,
                isVip = isVip,
                auto1080pEnabled = auto1080pEnabled
            )
            Logger.d(
                "VideoRepo",
                buildStartQualityDecisionSummary(
                    bvid = cacheBvid.ifBlank { bvid },
                    cid = cid,
                    userSettingQuality = targetQuality,
                    startQuality = startQuality,
                    isAutoHighestQuality = isAutoHighestQuality,
                    isLoggedIn = isLogin,
                    isVip = isVip,
                    auto1080pEnabled = auto1080pEnabled,
                    audioLang = audioLang
                )
            )

            // [优化] 默认语言优先走缓存；VIP 自动最高仅在缓存已含高码率轨时复用，避免每次冷拉。
            if (!shouldSkipPlayUrlCache(isAutoHighestQuality, isVip, audioLang)) {
                val cachedPlayData = environment.cache.get(
                    bvid = cacheBvid,
                    cid = cid,
                    requestedQuality = startQuality
                )
                val cachedDashIds = cachedPlayData?.dash?.video?.map { it.id }.orEmpty()
                if (
                    cachedPlayData != null &&
                    shouldAcceptCachedPlayUrlForAutoHighest(
                        isAutoHighestQuality = isAutoHighestQuality,
                        isVip = isVip,
                        cachedDashVideoIds = cachedDashIds
                    )
                ) {
                    Logger.d(
                        "VideoRepo",
                        " Using cached PlayUrlData for bvid=$cacheBvid, requestedQuality=$startQuality"
                    )
                    return@withContext Result.success(Pair(info, cachedPlayData))
                }
            } else {
                Logger.d(
                    "VideoRepo",
                    "🚀 Skip cache: bvid=$cacheBvid, isAutoHighest=$isAutoHighestQuality, audioLang=${audioLang ?: "default"}"
                )
            }

            val playUrlBvid = cacheBvid.ifBlank { bvid }
            val fetchResult = fetchPlayUrlRecursive(
                bvid = playUrlBvid,
                cid = cid,
                targetQn = startQuality,
                audioLang = audioLang,
                requestKind = PlayUrlRequestKind.INITIAL
            )
                ?: throw Exception("无法获取任何画质的播放地址")
            val playData = fetchResult.data

            //  支持 DASH 和 durl 两种格式
            val hasDash = !playData.dash?.video.isNullOrEmpty()
            val hasDurl = !playData.durl.isNullOrEmpty()
            val dashVideoIds = playData.dash?.video?.map { it.id }?.distinct()?.sortedDescending() ?: emptyList()
            Logger.d(
                "VideoRepo",
                buildPlayUrlFetchSummary(
                    bvid = playUrlBvid,
                    cid = cid,
                    source = fetchResult.source,
                    requestedQuality = startQuality,
                    returnedQuality = playData.quality,
                    acceptQualities = playData.accept_quality,
                    dashVideoIds = dashVideoIds,
                    hasDurl = hasDurl,
                    isLoggedIn = isLogin,
                    isVip = isVip,
                    audioLang = audioLang
                )
            )
            if (!hasDash && !hasDurl) throw Exception("播放地址解析失败 (无 dash/durl)")

            //  [优化] 缓存结果 (仅默认语言缓存)
            if (shouldCachePlayUrlResult(
                    source = fetchResult.source,
                    audioLang = audioLang,
                    requestedQuality = startQuality,
                    returnedQuality = playData.quality,
                    dashVideoIds = dashVideoIds
                )
            ) {
                environment.cache.put(
                    bvid = cacheBvid,
                    cid = cid,
                    data = playData,
                    quality = startQuality
                )
                Logger.d(
                    "VideoRepo",
                    " Cached PlayUrlData for bvid=$cacheBvid, cid=$cid, requestedQuality=$startQuality, actualQuality=${playData.quality}"
                )
            } else {
                Logger.d(
                    "VideoRepo",
                    " Skip cache write: source=${fetchResult.source}, audioLang=${audioLang ?: "default"}"
                )
            }

            environment.assertOwned()
            Result.success(Pair(info, playData))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            environment.assertOwned()

            Logger.e("VideoRepo", "Original video detail failed", e)
            Result.failure(e)
        }
    }

    override suspend fun getPlaybackNavInfo(): Result<NavData> = withContext(Dispatchers.IO) {
        environment.assertOwned()

        try {
            val resp = environment.playbackApi.getNavInfo()
            if (resp.code == 0 && resp.data != null) {
                Result.success(resp.data)
            } else {
                if (resp.code == -101) {
                    Result.success(NavData(isLogin = false))
                } else {
                    Result.failure(Exception("错误码: ${resp.code}"))
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            environment.assertOwned()

            Result.failure(e)
        }
    }

    override suspend fun getPlayUrlData(bvid: String, cid: Long, qn: Int, audioLang: String?): PlayUrlData? = withContext(Dispatchers.IO) {
        environment.assertOwned()

        fetchPlayUrlRecursive(
            bvid = bvid,
            cid = cid,
            targetQn = qn,
            audioLang = audioLang,
            requestKind = PlayUrlRequestKind.EXPLICIT
        )?.data
    }

    suspend fun getPlayUrlDataForPlaybackTransition(
        bvid: String,
        cid: Long,
        qn: Int,
        audioLang: String? = null
    ): PlayUrlData? = withContext(Dispatchers.IO) {
        environment.assertOwned()

        fetchPlayUrlRecursive(
            bvid = bvid,
            cid = cid,
            targetQn = qn,
            audioLang = audioLang,
            requestKind = PlayUrlRequestKind.PLAYBACK_TRANSITION
        )?.data
    }

    suspend fun getExactPremiumPlayUrl(
        bvid: String,
        cid: Long,
        targetQn: Int,
        audioLang: String? = null
    ): PlayUrlData? = withContext(Dispatchers.IO) {
        environment.assertOwned()

        if (targetQn !in 125..127) {
            return@withContext null
        }

        val hasToken = !playbackAccessToken().isNullOrEmpty()
        val cooldownUntil = appApiCooldownUntilMs
        val now = System.currentTimeMillis()
        val canUseApp = shouldCallAccessTokenApi(
            nowMs = now,
            cooldownUntilMs = cooldownUntil,
            hasAccessToken = hasToken
        )
        if (!canUseApp) {
            return@withContext null
        }

        val payload = fetchPlayUrlWithAccessToken(
            bvid = bvid,
            cid = cid,
            qn = targetQn,
            audioLang = audioLang
        ) ?: return@withContext null

        val dashVideos = payload.dash?.video.orEmpty()
        val dashIds = dashVideos.map { it.id }.distinct()
        if (!hasExactPlayableRequestedTrack(targetQn, dashVideos)) {
            Logger.d(
                "VideoRepo",
                " getExactPremiumPlayUrl: APP returned ${payload.quality}, dash=$dashIds — missing exact target $targetQn"
            )
            return@withContext null
        }

        Logger.d(
            "VideoRepo",
            " getExactPremiumPlayUrl: success — exact $targetQn track found via APP"
        )
        payload
    }

    private suspend fun fetchPlayUrlRecursive(
        bvid: String,
        cid: Long,
        targetQn: Int,
        audioLang: String? = null,
        requestKind: PlayUrlRequestKind
    ): PlayUrlFetchResult? {
        //  关键：确保有正确的 buvid3 (来自 Bilibili SPI API)
        ensureBuvid3FromSpi()

        val isLoggedIn = resolveVideoPlaybackAuthState(
            hasSessionCookie = hasPlaybackSessionCookie(),
            hasAccessToken = !playbackAccessToken().isNullOrEmpty()
        )
        Logger.d("VideoRepo", " fetchPlayUrlRecursive: bvid=$bvid, isLoggedIn=$isLoggedIn, targetQn=$targetQn, audioLang=$audioLang")

        if (isStrictPremiumQualityRequest(requestKind, targetQn)) {
            return fetchDashWithFallback(
                bvid = bvid,
                cid = cid,
                targetQn = targetQn,
                audioLang = audioLang,
                requestKind = requestKind
            )
        }

        return if (isLoggedIn) {
            // 已登录：保持 Web/WBI 主路径，失败时再走最小 fallback
            fetchDashWithFallback(
                bvid = bvid,
                cid = cid,
                targetQn = targetQn,
                audioLang = audioLang,
                requestKind = requestKind
            )
        } else {
            // 未登录：保持 Web/WBI 主路径，再回退到最小游客 fallback
            fetchGuestPlaybackWithFallback(
                bvid = bvid,
                cid = cid,
                targetQn = targetQn,
                requestKind = requestKind
            )
        }
    }

    private fun hasPlayableStreams(data: PlayUrlData?): Boolean {
        if (data == null) return false
        return !data.durl.isNullOrEmpty() || !data.dash?.video.isNullOrEmpty()
    }

    private suspend fun fetchDashWithFallback(
        bvid: String,
        cid: Long,
        targetQn: Int,
        audioLang: String? = null,
        requestKind: PlayUrlRequestKind
    ): PlayUrlFetchResult? {
        val fallbackOrder = buildLoggedInPlaybackFallbackOrder()
        val directedTrafficMode = isDirectedTrafficModeActive()
        Logger.d(
            "VideoRepo",
            " [LoggedIn] DASH-first strategy, qn=$targetQn, directedTrafficMode=$directedTrafficMode"
        )
        
        val strictPremiumRequest = isStrictPremiumQualityRequest(requestKind, targetQn)
        // 手动高级画质只能请求精确档位；首次加载和普通档位仍保留快速降级。
        val dashQualities = if (strictPremiumRequest) {
            listOf(targetQn)
        } else {
            buildDashAttemptQualities(targetQn)
        }
        for ((qualityIndex, dashQn) in dashQualities.withIndex()) {
            val retryDelays = resolveDashRetryDelays(
                targetQn = dashQn,
                isPrimaryAttempt = qualityIndex == 0
            )
            val retryOnlyTransientEmptyResponse = shouldRetryOnlyTransientEmptyDashResponse(
                targetQn = dashQn,
                isPrimaryAttempt = qualityIndex == 0,
            )
            dashRetry@ for ((attempt, delayMs) in retryDelays.withIndex()) {
                if (delayMs > 0L) {
                    Logger.d(
                        "VideoRepo",
                        " DASH retry ${attempt + 1} for qn=$dashQn..."
                    )
                    kotlinx.coroutines.delay(delayMs)
                }

                try {
                    val data = fetchPlayUrlWithWbiInternal(bvid, cid, dashQn, audioLang)
                    if (hasPlayableStreams(data)) {
                        val payload = data ?: continue
                        val dashVideoIds = payload.dash?.video.orEmpty()
                            .filter { it.getValidUrl().isNotEmpty() }
                            .map { it.id }
                            .distinct()
                        val shouldRetryTrackRecovery = shouldRetryDashTrackRecovery(
                            targetQn = dashQn,
                            returnedQuality = payload.quality,
                            acceptQualities = payload.accept_quality,
                            dashVideoIds = dashVideoIds
                        )
                        if (shouldRetryTrackRecovery && attempt < retryDelays.lastIndex) {
                            Logger.d(
                                "VideoRepo",
                                " [LoggedIn] DASH track recovery retry: requestedQn=$dashQn, returnedQuality=${payload.quality}, accept=${payload.accept_quality}, dashIds=$dashVideoIds"
                            )
                            continue
                        }
                        if (payload.quality < dashQn || dashQn !in dashVideoIds) {
                            Logger.d(
                                "VideoRepo",
                                " [LoggedIn] DASH returned downgraded playable result: requestedQn=$dashQn, quality=${payload.quality}, dashIds=$dashVideoIds; defer actual selection to playback layer"
                            )
                        }
                        if (!shouldAcceptAppApiResultForTargetQuality(
                                requestKind = requestKind,
                                targetQn = dashQn,
                                returnedQuality = payload.quality,
                                dashVideoIds = dashVideoIds
                            )
                        ) {
                            Logger.d(
                                "VideoRepo",
                                " [LoggedIn] Reject downgraded result for explicit quality request: requestedQn=$dashQn, quality=${payload.quality}, dashIds=$dashVideoIds"
                            )
                            if (retryOnlyTransientEmptyResponse) {
                                break@dashRetry
                            }
                            continue@dashRetry
                        }
                        Logger.d(
                            "VideoRepo",
                            " [LoggedIn] DASH success: quality=${payload.quality}, requestedQn=$dashQn"
                        )
                        return PlayUrlFetchResult(payload, PlayUrlSource.DASH)
                    }
                    android.util.Log.w("VideoRepo", " DASH qn=$dashQn attempt=${attempt + 1}: data is null or empty")
                    if (attempt < retryDelays.lastIndex) {
                        wbiKeysCache = null
                        wbiKeysTimestamp = 0L
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            environment.assertOwned()

                    android.util.Log.w("VideoRepo", "DASH qn=$dashQn attempt ${attempt + 1} failed: ${e.message}")
                    if (e.message?.contains("412") == true) {
                        last412Time = System.currentTimeMillis()
                        if (attempt < retryDelays.lastIndex) {
                            wbiKeysCache = null
                            wbiKeysTimestamp = 0L
                        }
                    }
                    if (retryOnlyTransientEmptyResponse) {
                        break@dashRetry
                    }
                }
            }
        }

        if (PlayUrlSource.APP in fallbackOrder) {
            val canUseAppFallback = shouldCallAccessTokenApi(
                nowMs = System.currentTimeMillis(),
                cooldownUntilMs = appApiCooldownUntilMs,
                hasAccessToken = !playbackAccessToken().isNullOrEmpty()
            )
            if (canUseAppFallback) {
                Logger.d(
                    "VideoRepo",
                    " [LoggedIn] WBI chain exhausted, trying APP access_token fallback..."
                )
                for (appQn in dashQualities) {
                    val appData = fetchPlayUrlWithAccessToken(bvid, cid, appQn, audioLang = audioLang)
                    if (hasPlayableStreams(appData)) {
                        val payload = appData ?: continue
                        val appDashIds = payload.dash?.video.orEmpty()
                            .filter { it.getValidUrl().isNotEmpty() }
                            .map { it.id }
                            .distinct()
                        if (!shouldAcceptAppApiResultForTargetQuality(
                                requestKind = requestKind,
                                targetQn = appQn,
                                returnedQuality = payload.quality,
                                dashVideoIds = appDashIds
                            )
                        ) {
                            Logger.w(
                                "VideoRepo",
                                " [LoggedIn] APP fallback rejected downgraded qn=$appQn result: quality=${payload.quality}, dashIds=$appDashIds"
                            )
                            continue
                        }
                        Logger.d(
                            "VideoRepo",
                            " [LoggedIn] APP fallback success: quality=${payload.quality}, requestedQn=$appQn"
                        )
                        return PlayUrlFetchResult(payload, PlayUrlSource.APP)
                    }
                }
            } else {
                Logger.d(
                    "VideoRepo",
                    " [LoggedIn] Skip APP fallback: no access token or cooldown active"
                )
            }
        }

        if (strictPremiumRequest) {
            return null
        }

        if (PlayUrlSource.LEGACY in fallbackOrder) {
            Logger.d("VideoRepo", " [LoggedIn] DASH failed, trying Legacy API...")
            try {
                val legacyResult = environment.playbackApi.getPlayUrlLegacy(bvid = bvid, cid = cid, qn = 80)
                if (legacyResult.code == 0 && legacyResult.data != null) {
                    val data = legacyResult.data
                    if (hasPlayableStreams(data)) {
                        Logger.d("VideoRepo", " [LoggedIn] Legacy API success: quality=${data.quality}")
                        return PlayUrlFetchResult(data, PlayUrlSource.LEGACY)
                    }
                } else {
                    android.util.Log.w("VideoRepo", "Legacy API returned code=${legacyResult.code}, msg=${legacyResult.message}")
                }
            } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            environment.assertOwned()

                android.util.Log.w("VideoRepo", "[LoggedIn] Legacy API failed: ${e.message}")
            }
        }

        if (PlayUrlSource.GUEST in fallbackOrder) {
            Logger.d("VideoRepo", " [LoggedIn] All auth methods failed! Trying GUEST fallback (no auth)...")
            val guestResult = fetchAsGuestFallback(bvid, cid)
            if (guestResult != null) {
                Logger.d("VideoRepo", " [LoggedIn->Guest] Guest fallback success: quality=${guestResult.quality}")
                return PlayUrlFetchResult(guestResult, PlayUrlSource.GUEST)
            }
        }

        android.util.Log.e("VideoRepo", " [LoggedIn] All attempts failed for bvid=$bvid")
        return null
    }

    private suspend fun fetchAsGuestFallback(bvid: String, cid: Long): PlayUrlData? {
        try {
            Logger.d("VideoRepo", " fetchAsGuestFallback: bvid=$bvid, cid=$cid (using guestApi)")
            
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
                        Logger.d(
                            "VideoRepo",
                            " Guest fallback (Legacy ${guestQn}p) success: actual=${data.quality}"
                        )
                        return data
                    }
                } else {
                    Logger.d(
                        "VideoRepo",
                        " Guest fallback ${guestQn}p failed: code=${legacyResult.code}"
                    )
                }
            }
            
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            environment.assertOwned()

            android.util.Log.w("VideoRepo", "Guest fallback failed: ${e.message}")
        }
        
        return null
    }

    private suspend fun fetchGuestPlaybackWithFallback(
        bvid: String,
        cid: Long,
        targetQn: Int,
        requestKind: PlayUrlRequestKind
    ): PlayUrlFetchResult? {
        val fallbackOrder = buildGuestPlaybackFallbackOrder()
        Logger.d("VideoRepo", " [Guest] WBI-first strategy")

        for (source in fallbackOrder) {
            when (source) {
                PlayUrlSource.DASH -> {
                    try {
                        val dashData = fetchPlayUrlWithWbiInternal(bvid, cid, targetQn, audioLang = null)
                        if (dashData != null && (!dashData.durl.isNullOrEmpty() || !dashData.dash?.video.isNullOrEmpty())) {
                            val dashIds = dashData.dash?.video?.map { it.id }?.distinct() ?: emptyList()
                            if (!shouldAcceptAppApiResultForTargetQuality(
                                    requestKind = requestKind,
                                    targetQn = targetQn,
                                    returnedQuality = dashData.quality,
                                    dashVideoIds = dashIds
                                )
                            ) {
                                Logger.d(
                                    "VideoRepo",
                                    " [Guest] Reject downgraded result for explicit quality request: requestedQn=$targetQn, quality=${dashData.quality}, dashIds=$dashIds"
                                )
                                continue
                            }
                            Logger.d("VideoRepo", " [Guest] DASH success: quality=${dashData.quality}")
                            return PlayUrlFetchResult(dashData, PlayUrlSource.DASH)
                        }
                    } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            environment.assertOwned()

                        android.util.Log.w("VideoRepo", "[Guest] DASH failed: ${e.message}")
                    }
                }
                PlayUrlSource.LEGACY -> {
                    try {
                        Logger.d("VideoRepo", " [Guest] DASH failed, trying legacy playurl API...")
                        val legacyResult = api.getPlayUrlLegacy(bvid = bvid, cid = cid, qn = 80)
                        if (legacyResult.code == 0 && legacyResult.data != null) {
                            val data = legacyResult.data
                            if (!data.durl.isNullOrEmpty() || !data.dash?.video.isNullOrEmpty()) {
                                val dashIds = data.dash?.video?.map { it.id }?.distinct() ?: emptyList()
                                if (!shouldAcceptAppApiResultForTargetQuality(
                                        requestKind = requestKind,
                                        targetQn = targetQn,
                                        returnedQuality = data.quality,
                                        dashVideoIds = dashIds
                                    )
                                ) {
                                    Logger.d(
                                        "VideoRepo",
                                        " [Guest] Reject downgraded legacy result for explicit quality request: requestedQn=$targetQn, quality=${data.quality}, dashIds=$dashIds"
                                    )
                                    continue
                                }
                                Logger.d("VideoRepo", " [Guest] Legacy API success: quality=${data.quality}")
                                return PlayUrlFetchResult(data, PlayUrlSource.LEGACY)
                            }
                        } else {
                            android.util.Log.w("VideoRepo", "Legacy API returned code=${legacyResult.code}, msg=${legacyResult.message}")
                        }
                    } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            environment.assertOwned()

                        android.util.Log.w("VideoRepo", "[Guest] Legacy API failed: ${e.message}")
                    }
                }
                else -> Unit
            }
        }

        android.util.Log.e("VideoRepo", " [Guest] All attempts failed for bvid=$bvid")
        return null
    }

    private suspend fun fetchPlayUrlWithWbiInternal(bvid: String, cid: Long, qn: Int, audioLang: String? = null): PlayUrlData? {
        Logger.d("VideoRepo", "fetchPlayUrlWithWbiInternal: bvid=$bvid, cid=$cid, qn=$qn, audioLang=$audioLang")
        
        //  使用缓存的 Keys
        val (imgKey, subKey) = getWbiKeys()
        val isLoggedIn = resolveVideoPlaybackAuthState(
            hasSessionCookie = hasPlaybackSessionCookie(),
            hasAccessToken = !playbackAccessToken().isNullOrEmpty()
        )
        val auto1080pEnabled = environment.auto1080pEnabled()
        
        val params = buildPlayUrlWbiBaseParams(
            bvid = bvid,
            cid = cid,
            qn = qn,
            audioLang = audioLang,
            tryLook = shouldRequestPlayUrlTryLook(
                isLoggedIn = isLoggedIn,
                auto1080pEnabled = auto1080pEnabled
            )
        )

        val directedOverrides = buildDirectedTrafficWbiOverrides(
            directedTrafficEnabled = environment.directedTrafficEnabled(),
            isOnMobileData = environment.isMobileData()
        )
        if (directedOverrides.isNotEmpty()) {
            params.putAll(directedOverrides)
            Logger.d(
                "VideoRepo",
                " Applied directed traffic WBI overrides: $directedOverrides"
            )
        }
        
        val signedParams = WbiUtils.sign(params, imgKey, subKey)
        val response = environment.playbackApi.getPlayUrl(signedParams)
        
        Logger.d("VideoRepo", " PlayUrl response: code=${response.code}, requestedQn=$qn, returnedQuality=${response.data?.quality}")
        Logger.d("VideoRepo", " accept_quality=${response.data?.accept_quality}, accept_description=${response.data?.accept_description}")
        //  [调试] 输出 DASH 视频流 ID 列表
        val dashIds = response.data?.dash?.video?.map { it.id }?.distinct()?.sortedDescending()
        Logger.d("VideoRepo", " DASH video IDs: $dashIds")
        
        if (response.code == 0) {
            val payload = response.data
            if (hasPlayableStreams(payload)) {
                return payload
            }
            Logger.w(
                "VideoRepo",
                " PlayUrl success but empty payload: requestedQn=$qn, returnedQuality=${payload?.quality}, dashIds=$dashIds"
            )
            return null
        }
        
        //  [优化] API 返回错误码分类处理，提供更明确的错误信息
        val errorMessage = classifyPlayUrlError(response.code, response.message)
        android.util.Log.e("VideoRepo", " PlayUrl API error: code=${response.code}, message=${response.message}, classified=$errorMessage")
        // 对于不可重试的错误，抛出明确异常
        if (response.code in listOf(-404, -403, -10403, -62002)) {
            throw Exception(errorMessage)
        }
        return null
    }

    private suspend fun fetchPlayUrlWithAccessToken(bvid: String, cid: Long, qn: Int, allowRetry: Boolean = true, audioLang: String? = null): PlayUrlData? {
        val accessToken = playbackAccessToken()
        if (accessToken.isNullOrEmpty()) {
            Logger.d("VideoRepo", " No access_token available, fallback to Web API")
            return null
        }
        
        Logger.d("VideoRepo", " fetchPlayUrlWithAccessToken: bvid=$bvid, qn=$qn, retry=$allowRetry")
        
        val tokenPlatform = playbackAccessTokenPlatform()
        val usesAndroidToken = tokenPlatform == environment.androidAccessTokenPlatform
        val params = mapOf(
            "bvid" to bvid,
            "cid" to cid.toString(),
            "qn" to qn.toString(),
            // 4048 (Web DASH formats) + 16384 (APP-only HDR Vivid, qn=129).
            "fnval" to "20432",
            "fnver" to "0",
            "fourk" to "1",
            "access_key" to accessToken,
            "appkey" to if (usesAndroidToken) AppSignUtils.ANDROID_APP_KEY else AppSignUtils.TV_APP_KEY,
            "ts" to AppSignUtils.getTimestamp().toString(),
            "platform" to "android",
            "mobi_app" to if (usesAndroidToken) "android" else "android_tv_yst",
            "device" to "android"
        ).toMutableMap()
        
        if (!audioLang.isNullOrEmpty()) {
           params["cur_language"] = audioLang
           params["lang"] = audioLang
        }
        
        val signedParams = if (usesAndroidToken) {
            AppSignUtils.signForAndroidApi(params)
        } else {
            AppSignUtils.signForTvLogin(params)
        }
        
        try {
            val response = environment.playbackApi.getPlayUrlApp(signedParams)
            
            // Check for -101 (Invalid Access Key)
            if (response.code == -101 && allowRetry && environment.canRefreshPrimaryToken() && playbackAccount() == null) {
                Logger.w("VideoRepo", " Access token invalid (-101), trying to refresh...")
                val success = environment.refreshPrimaryToken()
                if (success) {
                    Logger.i("VideoRepo", " Token refreshed successfully, retrying request...")
                    return fetchPlayUrlWithAccessToken(bvid, cid, qn, false, audioLang)
                } else {
                    Logger.e("VideoRepo", " Token refresh failed, aborting retry.")
                }
            }
            
            val dashIds = response.data?.dash?.video?.map { it.id }?.distinct()?.sortedDescending()
            Logger.d("VideoRepo", " APP PlayUrl response: code=${response.code}, qn=$qn, dashIds=$dashIds")
            
            if (response.code == 0 && response.data != null) {
                val payload = response.data
                if (hasPlayableStreams(payload)) {
                    appApiCooldownUntilMs = 0L
                    Logger.d("VideoRepo", " APP API success: returned quality=${payload.quality}, available: $dashIds")
                    return payload
                }
                Logger.w(
                    "VideoRepo",
                    " APP API success but empty payload: qn=$qn, quality=${payload.quality}"
                )
            } else {
                if (response.code == -351) {
                    appApiCooldownUntilMs = System.currentTimeMillis() + APP_API_COOLDOWN_MS
                    Logger.w(
                        "VideoRepo",
                        " APP API hit anti-risk (-351), cooldown ${APP_API_COOLDOWN_MS}ms"
                    )
                }
                Logger.d("VideoRepo", " APP API error: code=${response.code}, msg=${response.message}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            environment.assertOwned()

            Logger.d("VideoRepo", " APP API exception: ${e.message}")
        }
        
        return null
    }

    override suspend fun getRelatedVideos(bvid: String): List<RelatedVideo> = withContext(Dispatchers.IO) {
        environment.assertOwned()

        try { api.getRelatedVideos(bvid).data ?: emptyList() } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            environment.assertOwned()
 emptyList() }
    }

    private fun classifyPlayUrlError(code: Int, message: String?): String {
        return when (code) {
            -404 -> "视频不存在或已被删除"
            -403 -> "视频暂不可用"
            -10403 -> {
                when {
                    message?.contains("地区") == true -> "该视频在当前地区不可用"
                    message?.contains("会员") == true || message?.contains("vip") == true -> "需要大会员才能观看"
                    else -> "视频需要特殊权限才能观看"
                }
            }
            -62002 -> "视频已设为私密"
            -62004 -> "视频正在审核中"
            -62012 -> "视频已下架"
            87008 -> "当前视频可能是专属视频，可能需包月充电观看"
            -400 -> "请求参数错误"
            -101 -> "未登录，请先登录"
            -352 -> "请求频率过高，请稍后再试"
            else -> "获取播放地址失败 (错误码: $code)"
        }
    }

    private suspend fun getWbiKeys(): Pair<String, String> {
        val currentCheck = System.currentTimeMillis()
        val cached = wbiKeysCache
        if (cached != null && (currentCheck - wbiKeysTimestamp < WBI_CACHE_DURATION)) {
            return cached
        }

        //  [优化] 增加重试逻辑，最多 3 次尝试
        val maxRetries = 3
        var lastError: Exception? = null
        
        for (attempt in 1..maxRetries) {
            try {
                val navResp = api.getNavInfo()
                val wbiImg = navResp.data?.wbi_img
                
                if (wbiImg != null) {
                    val imgKey = wbiImg.img_url.substringAfterLast("/").substringBefore(".")
                    val subKey = wbiImg.sub_url.substringAfterLast("/").substringBefore(".")
                    
                    wbiKeysCache = Pair(imgKey, subKey)
                    wbiKeysTimestamp = System.currentTimeMillis()
                    Logger.d("VideoRepo", " WBI Keys obtained successfully (attempt $attempt)")
                    return wbiKeysCache!!
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            environment.assertOwned()

                lastError = e
                android.util.Log.w("VideoRepo", "getWbiKeys attempt $attempt failed: ${e.message}")
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(200L * attempt) // 递增延迟
                }
            }
        }
        
        throw Exception("Wbi Keys Error after $maxRetries attempts: ${lastError?.message}")
    }
    suspend fun getPortraitPlaybackDetails(
        bvid: String,
        aid: Long = 0,
        requestedCid: Long = 0,
        targetQuality: Int = PORTRAIT_PLAYBACK_TARGET_QUALITY,
        audioLang: String? = null
    ): Result<Pair<ViewInfo, PlayUrlData>> = withContext(Dispatchers.IO) {
        environment.assertOwned()
        try {
            if (shouldUsePortraitParallelPlaybackBootstrap(bvid, requestedCid)) {
                coroutineScope {
                    val infoDeferred = async {
                        getVideoInfoOnly(
                            bvid = bvid,
                            aid = aid,
                            requestedCid = requestedCid
                        )
                    }
                    val playUrlDeferred = async {
                        getInitialPlayUrlData(
                            bvid = bvid,
                            cid = requestedCid,
                            targetQuality = targetQuality,
                            audioLang = audioLang
                        ) ?: throw Exception("无法获取播放地址")
                    }
                    infoDeferred.await().fold(
                        onSuccess = { info ->
                            Result.success(info to playUrlDeferred.await())
                        },
                        onFailure = { error ->
                            Result.failure(error)
                        }
                    )
                }
            } else {
                getVideoDetails(
                    bvid = bvid,
                    aid = aid,
                    requestedCid = requestedCid,
                    targetQuality = targetQuality,
                    audioLang = audioLang
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }.also { environment.assertOwned() }


    suspend fun preloadPortraitPlayUrl(
        bvid: String,
        cid: Long,
        aid: Long = 0L,
        targetQuality: Int = PORTRAIT_PLAYBACK_TARGET_QUALITY
    ): PlayUrlData? {
        if (bvid.isBlank()) return null
        environment.assertOwned()
        return withContext(Dispatchers.IO) {
            if (cid <= 0L) {
                val resolved = getPortraitPlaybackDetails(
                    bvid = bvid,
                    aid = aid,
                    requestedCid = 0L,
                    targetQuality = targetQuality
                ).getOrNull()?.second
                if (resolved != null) {
                    Logger.d(
                        "VideoRepo",
                        "🚀 Portrait preload resolved cid and playurl: bvid=$bvid"
                    )
                }
                return@withContext resolved
            }
            val cachedPlayData = environment.cache.get(
                bvid = bvid,
                cid = cid,
                requestedQuality = targetQuality
            )
            if (cachedPlayData != null) {
                Logger.d(
                    "VideoRepo",
                    "🚀 Portrait preload skip (cached): bvid=$bvid"
                )
                return@withContext cachedPlayData
            }
            val playData = getInitialPlayUrlData(
                bvid = bvid,
                cid = cid,
                targetQuality = targetQuality
            )
            if (playData != null) {
                Logger.d(
                    "VideoRepo",
                    "🚀 Portrait preloaded playurl: bvid=$bvid"
                )
            }
            playData
        }.also { environment.assertOwned() }
    }

}

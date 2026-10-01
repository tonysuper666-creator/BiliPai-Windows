package com.android.purebilibili.data.repository
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.core.network.WbiUtils
import com.android.purebilibili.feature.video.progress.PbpProgressData
import com.android.purebilibili.feature.video.progress.parsePbpProgressData
import android.util.Log as Logger
import kotlinx.coroutines.*
import com.bilipai.desktop.ui.DesktopOriginalVideoMetadataEnvironment

internal class DesktopOriginalVideoOwnerMetadataProtocol(private val environment:DesktopOriginalVideoMetadataEnvironment) {
    private val api get() = environment.load.api
    private suspend fun ensureBuvid3FromSpi() = environment.load.ensureBuvid()
    private suspend fun getWbiKeys() = environment.protocol.getWbiKeys()
    private suspend fun getPlaybackNavInfo() = environment.protocol.getPlaybackNavInfo()
    private fun isUsingDedicatedPlaybackAccount() = environment.protocol.isUsingDedicatedPlaybackAccount()
    suspend fun refreshVipStatusForPreferredQualityIfNeeded(
        isLoggedIn: Boolean,
        cachedIsVip: Boolean,
        storedQuality: Int,
        autoHighestEnabled: Boolean
    ): Boolean {
        if (
            !com.android.purebilibili.core.util.shouldRefreshVipStatusBeforeResolvingDefaultQuality(
                storedQuality = storedQuality,
                autoHighestEnabled = autoHighestEnabled,
                isLoggedIn = isLoggedIn,
                cachedIsVip = cachedIsVip
            )
        ) {
            return cachedIsVip
        }

        // 按播放会话刷新：有独立播放账号时查播放账号的 nav，且不污染主账号 VIP 缓存。
        return getPlaybackNavInfo()
            .getOrNull()
            ?.takeIf { it.isLogin }
            ?.let { navData ->
                val isVip = navData.vip.status == 1
                if (!isUsingDedicatedPlaybackAccount()) {
                    environment.updatePrimaryVip(isVip)
                }
                Logger.d(
                    "VideoRepo",
                    " Refreshed VIP status before quality resolution: cached=$cachedIsVip, refreshed=$isVip, storedQuality=$storedQuality, autoHighest=$autoHighestEnabled, dedicatedPlayback=${isUsingDedicatedPlaybackAccount()}"
                )
                isVip
            }
            ?: cachedIsVip
    }

    suspend fun getAiSummary(bvid: String, cid: Long, upMid: Long): Result<AiSummaryResponse> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive(); environment.assertOwned()
        ensureBuvid3FromSpi()
        logAiSummaryPreflight(
            bvid = bvid,
            cid = cid,
            upMid = upMid
        )

        try {
            val (imgKey, subKey) = getWbiKeys()
            val params = buildAiSummaryParams(
                bvid = bvid,
                cid = cid,
                upMid = upMid
            )
            val signedParams = WbiUtils.sign(params, imgKey, subKey)

            Logger.d(
                "VideoRepo",
                "🤖 AI Summary request: bvid=$bvid cid=$cid upMidPresent=${upMid > 0L}"
            )
            val response = api.getAiConclusion(signedParams)
            currentCoroutineContext().ensureActive(); environment.assertOwned()
            val diagnosis = diagnoseAiSummaryResponse(response)
            logAiSummaryResponse(
                bvid = bvid,
                cid = cid,
                diagnosis = diagnosis,
                hasModelResult = response.data?.modelResult != null,
                summaryLength = response.data?.modelResult?.summary?.length ?: 0,
                outlineCount = response.data?.modelResult?.outline?.size ?: 0
            )

            Result.success(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            currentCoroutineContext().ensureActive(); environment.assertOwned()
            val diagnosis = diagnoseAiSummaryFailure(e)
            if ((e as? retrofit2.HttpException)?.code() == 412 || e.message.orEmpty().contains("412")) {
                environment.load.state.wbiKeys = null
                environment.load.state.wbiKeysTimestamp = 0L
            }
            Logger.w(
                "VideoRepo",
                "🤖 AI Summary request failed: bvid=$bvid cid=$cid status=${diagnosis.status} reason=${diagnosis.reason} retryable=${diagnosis.shouldRetryRequest}"
            )
            Result.failure(e)
        }
    }

    private fun buildAiSummaryParams(
        bvid: String,
        cid: Long,
        upMid: Long
    ): Map<String, String> {
        val params = linkedMapOf(
            "bvid" to bvid,
            "cid" to cid.toString()
        )
        if (upMid > 0L) {
            params["up_mid"] = upMid.toString()
        }
        return params
    }

    private fun logAiSummaryPreflight(
        bvid: String,
        cid: Long,
        upMid: Long
    ) {
        val hasSess = environment.hasPrimarySession()
        val hasCsrf = environment.hasPrimaryCsrf()
        val hasBuvid = environment.hasPrimaryBuvid()
        val hasAccessToken = environment.hasPrimaryAccessToken()
        Logger.i(
            "VideoRepo",
            "🤖 AI Summary preflight: bvid=$bvid cid=$cid upMidPresent=${upMid > 0L} hasSess=$hasSess hasCsrf=$hasCsrf hasBuvid=$hasBuvid hasAccessToken=$hasAccessToken buvidInitialized=${environment.isBuvidInitialized()}"
        )
    }

    private fun logAiSummaryResponse(
        bvid: String,
        cid: Long,
        diagnosis: AiSummaryFetchDiagnosis,
        hasModelResult: Boolean,
        summaryLength: Int,
        outlineCount: Int
    ) {
        Logger.i(
            "VideoRepo",
            "🤖 AI Summary response: bvid=$bvid cid=$cid status=${diagnosis.status} reason=${diagnosis.reason} rootCode=${diagnosis.rootCode} dataCode=${diagnosis.dataCode} stid=${diagnosis.stid ?: ""} hasModelResult=$hasModelResult summaryLength=$summaryLength outlineCount=$outlineCount retryLater=${diagnosis.shouldRetryLater}"
        )
    }

    suspend fun getVideoshot(bvid: String, cid: Long): VideoshotData? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive(); environment.assertOwned()
        try {
            Logger.d("VideoRepo", "🖼️ getVideoshot: bvid=$bvid, cid=$cid")
            val response = api.getVideoshot(bvid = bvid, cid = cid)
            currentCoroutineContext().ensureActive(); environment.assertOwned()
            if (response.code == 0 && response.data != null && response.data.isValid) {
                Logger.d("VideoRepo", "🖼️ Videoshot success: ${response.data.image.size} images, ${response.data.index.size} frames")
                response.data
            } else {
                Logger.d("VideoRepo", "🖼️ Videoshot failed: code=${response.code}")
                null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            currentCoroutineContext().ensureActive(); environment.assertOwned()
            Logger.w("VideoRepo", "🖼️ Videoshot exception: ${e.message}")
            null
        }
    }

    suspend fun getPlayerInfo(bvid: String, cid: Long): Result<PlayerInfoData> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive(); environment.assertOwned()
        try {
            val (imgKey, subKey) = getWbiKeys()
            val params = mapOf(
                "bvid" to bvid,
                "cid" to cid.toString()
            )
            val signedParams = WbiUtils.sign(params, imgKey, subKey)
            val response = api.getPlayerInfo(signedParams)
            currentCoroutineContext().ensureActive(); environment.assertOwned()
            if (response.code == 0 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception("PlayerInfo error: ${response.code}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            currentCoroutineContext().ensureActive(); environment.assertOwned()
            Result.failure(e)
        }
    }

    suspend fun getPbpProgressData(
        bvid: String,
        cid: Long,
        aid: Long = 0L
    ): Result<PbpProgressData> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive(); environment.assertOwned()
        try {
            if (cid <= 0L) {
                return@withContext Result.failure(
                    IllegalArgumentException("PBP cid invalid: $cid")
                )
            }
            val body = api.getPbpData(
                cid = cid,
                bvid = bvid.takeIf { it.isNotBlank() },
                aid = aid.takeIf { it > 0L }
            )
            parsePbpProgressData(body.string()).let { parsed ->
                currentCoroutineContext().ensureActive(); environment.assertOwned()
                Result.success(parsed)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            currentCoroutineContext().ensureActive(); environment.assertOwned()
            Result.failure(e)
        }
    }

    suspend fun getInteractEdgeInfo(
        bvid: String,
        graphVersion: Long,
        edgeId: Long? = null
    ): Result<InteractEdgeInfoData> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive(); environment.assertOwned()
        try {
            val response = api.getInteractEdgeInfo(bvid = bvid, graphVersion = graphVersion, edgeId = edgeId)
            currentCoroutineContext().ensureActive(); environment.assertOwned()
            if (response.code == 0 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message.ifBlank { "互动分支信息加载失败(${response.code})" }))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            currentCoroutineContext().ensureActive(); environment.assertOwned()
            Result.failure(e)
        }
    }
}

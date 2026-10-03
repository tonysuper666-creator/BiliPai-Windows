// GENERATED complete original getBangumiPlayUrl; upstream 3d5d19a2f994daccd0e2f8b5f522b6d82f43d589; LF 358d9c8f0b9a787de638c69db7b4bda67974b649b8db4fab4fb34f244e5ad612
package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BangumiApi
import com.android.purebilibili.core.network.WbiUtils
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.ui.ownedBangumiRequest
import kotlinx.coroutines.Dispatchers

internal fun signBangumiPlayUrlParams(
    params: Map<String, String>,
    wbiKeys: Pair<String, String>?,
    includeRiskFingerprint: Boolean = false
): Map<String, String> {
    val (imgKey, subKey) = wbiKeys ?: return params
    if (imgKey.isBlank() || subKey.isBlank()) return params
    return WbiUtils.sign(params, imgKey, subKey, includeRiskFingerprint = includeRiskFingerprint)
}

/** Complete original PGC/PUGV playurl protocol inside one captured actual
 * playback request. This object has no client/cache/Store/player authority. */
internal class DesktopOriginalBangumiPlayRequests(
    private val capturedPlaybackApi: BangumiApi,
    private val fetchWbiKeys: suspend () -> Result<Pair<String, String>>,
    private val refreshWbiKeys: suspend () -> Result<Pair<String, String>>,
    private val owned: () -> Boolean,
) {
suspend fun getBangumiPlayUrl(
    epId: Long,
    qn: Int = 80,
    cid: Long = 0L,
    bvid: String? = null,
    seasonId: Long? = null,
    aid: Long = 0L,
    isCourse: Boolean = false
): Result<BangumiVideoInfo> = ownedBangumiRequest(Dispatchers.IO, owned) {
    try {
        android.util.Log.d("BangumiRepo", "📡 getBangumiPlayUrl: epId=$epId, cid=$cid, seasonId=$seasonId, aid=$aid, isCourse=$isCourse, qn=$qn")
        val baseParams = buildBangumiPlayUrlParams(
            epId = epId,
            cid = cid,
            qn = qn,
            bvid = bvid,
            seasonId = seasonId,
            aid = aid,
            isCourse = isCourse
        )
        val wbiKeys = fetchWbiKeys().getOrNull()
            ?: refreshWbiKeys().getOrNull()
        val signedParams = signBangumiPlayUrlParams(
            params = baseParams,
            wbiKeys = wbiKeys,
            includeRiskFingerprint = isCourse
        )
        android.util.Log.d(
            "BangumiRepo",
            "📡 getBangumiPlayUrl request params: wbiSigned=${signedParams.containsKey("w_rid")}, keys=${signedParams.keys.sorted()}"
        )
        val playbackApi = capturedPlaybackApi

        val finalResponse = if (isCourse) {
            // 课程优先使用 PUGV playurl
            val pugvResponse = runCatching {
                val pugvRawJson = playbackApi.getPugvPlayUrl(signedParams).string()
                decodeBangumiPlayUrlPayload(pugvRawJson)
            }.getOrNull()
            if (pugvResponse != null && pugvResponse.code == 0 && pugvResponse.videoInfo != null) {
                pugvResponse
            } else {
                val primary = runCatching {
                    decodeBangumiPlayUrlPayload(playbackApi.getBangumiPlayUrl(signedParams).string())
                }.getOrNull()
                primary?.takeIf { it.code == 0 && it.videoInfo != null }
                    ?: pugvResponse
                    ?: BangumiPlayUrlPayload(code = -1, message = "获取播放地址失败", videoInfo = null)
            }
        } else {
            val primaryResponse = runCatching {
                decodeBangumiPlayUrlPayload(
                    rawJson = playbackApi.getBangumiPlayUrl(
                        signedParams
                    ).string()
                )
            }.getOrNull()
            val response = if (primaryResponse != null && !shouldFallbackToLegacyBangumiPlayUrl(primaryResponse)) {
                primaryResponse
            } else {
                runCatching {
                    decodeBangumiPlayUrlPayload(
                        rawJson = playbackApi.getBangumiPlayUrlLegacy(
                            signedParams
                        ).string()
                    )
                }.getOrNull() ?: primaryResponse
            }
            // 如果常规 PGC playurl 失败（例如 -404 啥都木有，或异常），尝试 PUGV 课堂/课程 playurl
            if (response != null && response.code == 0 && response.videoInfo != null) {
                response
            } else {
                runCatching {
                    val pugvRawJson = playbackApi.getPugvPlayUrl(signedParams).string()
                    decodeBangumiPlayUrlPayload(pugvRawJson)
                }.getOrNull()?.takeIf { it.code == 0 && it.videoInfo != null }
                    ?: response
                    ?: BangumiPlayUrlPayload(code = -1, message = "获取播放地址失败", videoInfo = null)
            }
        }
        android.util.Log.d(
            "BangumiRepo",
            "📡 getBangumiPlayUrl response: code=${finalResponse.code}, msg=${finalResponse.message}, hasResult=${finalResponse.videoInfo != null}"
        )

        if (finalResponse.code == 0 && finalResponse.videoInfo != null) {
            val result = finalResponse.videoInfo
            android.util.Log.d(
                "BangumiRepo",
                "📹 PlayUrl: quality=${result.quality}, hasDash=${result.dash != null}, " +
                    "hasDurl=${!result.durl.isNullOrEmpty()}, preview=${result.isPreview}, " +
                    "paid=${result.hasPaid}, drm=${result.isDrm}, status=${result.status}"
            )
            validateBangumiPlayableVideoInfo(result)
        } else {
            val errorMsg = when (finalResponse.code) {
                -10403 -> "需要大会员才能观看"
                -404 -> "视频或课程不存在"
                -101 -> "请先登录后观看"
                -400 -> "请求参数错误"
                -403 -> if (isCourse) "访问权限不足：该课程需购买后观看" else "访问权限不足"
                else -> "获取播放地址失败: ${finalResponse.message} (code=${finalResponse.code})"
            }
            Result.failure(Exception(errorMsg))
        }
    } catch (e: Exception) {
        android.util.Log.e("BangumiRepo", "getBangumiPlayUrl error: ${e.message}", e)
        Result.failure(e)
    }
}
}

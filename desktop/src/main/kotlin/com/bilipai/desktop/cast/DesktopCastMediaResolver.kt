package com.bilipai.desktop.cast

import com.android.purebilibili.core.network.AppSignUtils
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.plugin.CastPluginMediaRequest
import com.android.purebilibili.data.model.response.Dash
import com.android.purebilibili.data.model.response.PlayUrlData
import com.android.purebilibili.data.repository.buildTvCastPlayUrlParams
import com.android.purebilibili.data.repository.extractTvCastPlayableUrl
import com.android.purebilibili.data.repository.selectCastDashAudio
import com.android.purebilibili.data.repository.selectCastDashVideo
import com.android.purebilibili.feature.cast.LocalProxyServer
import com.android.purebilibili.feature.video.playback.dash.buildLocalDashManifest
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.PlaybackSource
import com.bilipai.desktop.data.VideoDetails
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Original TV playurl parameters, codec selection, and two-track local MPD builder. */
class DesktopCastMediaResolver(private val repository: DesktopRepository, private val context: DesktopPluginContext) {
    private val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(repository.httpClient)
        .addConverterFactory(Json { ignoreUnknownKeys = true; coerceInputValues = true }.asConverterFactory("application/json".toMediaType()))
        .build().create(BilibiliApi::class.java)

    suspend fun video(details: VideoDetails, pageIndex: Int, source: PlaybackSource, positionMs: Long,
        autoplay: Boolean = true): CastPluginMediaRequest = withContext(Dispatchers.IO) {
        val page = details.pages.getOrNull(pageIndex) ?: error("视频分P不存在")
        val epoch = repository.sessionEpoch
        val tvData = castCatching {
            repository.ensureSession()
            val params = buildTvCastPlayUrlParams(details.aid, page.cid, source.quality, repository.accessTokenCredentials().first)
            val response = api.getTvPlayUrl(AppSignUtils.signForTvLogin(params))
            if (response.code == 0) response.data else null
        }.getOrNull()
        check(epoch == repository.sessionEpoch) { "账号已切换，请重新开始投屏" }
        resolveDesktopCastMedia(context, details.title, details.author, source, tvData, page.duration * 1000,
            positionMs, autoplay)
    }

    /** Live/single-file media can use the same registered streaming proxy without account cookies. */
    fun singleFile(url: String, title: String, creator: String = "", contentType: String = "video/mp4",
        positionMs: Long = 0, autoplay: Boolean = true): CastPluginMediaRequest {
        requirePlayableCastUrl(url)
        LocalProxyServer.ensureStarted()
        return CastPluginMediaRequest(LocalProxyServer.getProxyUrl(context, url), title, creator, contentType,
            positionMs.coerceAtLeast(0), autoplay)
    }
}

internal fun requirePlayableCastUrl(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("无效的投屏媒体地址")
    require(parsed.username.isEmpty() && parsed.password.isEmpty())
    return parsed.toString()
}

internal fun resolveDesktopCastMedia(context: DesktopPluginContext, title: String, creator: String, source: PlaybackSource,
    tvData: PlayUrlData?, durationMs: Long, positionMs: Long, autoplay: Boolean): CastPluginMediaRequest {
    // A multi-segment progressive response is not a single file. Prefer its complete DASH response.
    val tvProgressive = tvData?.takeIf { it.durl.orEmpty().size == 1 }?.let(::extractTvCastPlayableUrl)
    val dash = if (tvProgressive == null) tvData?.dash ?: source.cachedDashData else null
    LocalProxyServer.ensureStarted()
    val (url, contentType) = when {
        tvProgressive != null -> LocalProxyServer.getProxyUrl(context, requirePlayableCastUrl(tvProgressive)) to "video/mp4"
        dash != null -> buildDesktopCastDash(context, dash, source.quality, tvData?.timelength ?: durationMs) to LocalProxyServer.DASH_CONTENT_TYPE
        source.audioUrl != null -> error("分离音视频流缺少完整 DASH 清单，无法完整投屏")
        source.progressiveSegments.size > 1 || tvData?.durl.orEmpty().size > 1 -> error("设备投屏无法将多个媒体分段当作单文件播放")
        else -> LocalProxyServer.getProxyUrl(context, requirePlayableCastUrl(source.videoUrl)) to "video/mp4"
    }
    return CastPluginMediaRequest(url, title, creator, contentType, positionMs.coerceAtLeast(0), autoplay)
}

internal fun buildDesktopCastDash(context: DesktopPluginContext, dash: Dash, quality: Int, durationMs: Long): String {
    val video = selectCastDashVideo(dash.video, quality.takeIf { it > 0 } ?: 80) ?: error("DASH 缺少视频轨道")
    val audio = selectCastDashAudio(dash.audio.orEmpty(), dash.dolby, dash.flac) ?: error("DASH 缺少设备可用的 AAC 音轨")
    val proxyVideo = video.copy(baseUrl = LocalProxyServer.getProxyUrl(context, requirePlayableCastUrl(video.getValidUrl())), backupUrl = null)
    val proxyAudio = audio.copy(baseUrl = LocalProxyServer.getProxyUrl(context, requirePlayableCastUrl(audio.getValidUrl())), backupUrl = null)
    val manifest = buildLocalDashManifest(durationMs.takeIf { it > 0 } ?: dash.duration * 1000L,
        (dash.minBufferTime * 1000f).toLong().coerceAtLeast(0), listOf(proxyVideo), listOf(proxyAudio))
    return LocalProxyServer.registerDashManifest(context, manifest)
}

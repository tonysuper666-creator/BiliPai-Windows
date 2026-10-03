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
import com.android.purebilibili.core.player.dash.buildLocalDashManifest
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
        autoplay: Boolean = true, nativeSource: com.bilipai.desktop.player.PlaybackSource? = null): CastPluginMediaRequest = withContext(Dispatchers.IO) {
        val page = details.pages.getOrNull(pageIndex) ?: error("视频分P不存在")
        val epoch = repository.sessionEpoch
        val tvData = castCatching {
            repository.ensureSession()
            val params = buildTvCastPlayUrlParams(details.aid, page.cid, source.quality, repository.accessTokenCredentials().first)
            val receipt = source.authorizationReceipt ?: throw kotlinx.coroutines.CancellationException("Missing cast authorization")
            val authorization = repository.capturePlaybackAuthorization(receipt.accountEpoch) { repository.isPlaybackSourceCurrent(source) }
            if (authorization.receipt != receipt) throw kotlinx.coroutines.CancellationException("Cast authorization retired")
            val ownedApi = repository.ownedPlaybackService(BilibiliApi::class.java, authorization) { repository.isPlaybackSourceCurrent(source) }
            val response = ownedApi.getTvPlayUrl(AppSignUtils.signForTvLogin(params))
            if (response.code == 0) response.data else null
        }.getOrNull()
        check(epoch == repository.sessionEpoch) { "账号已切换，请重新开始投屏" }
        resolveDesktopCastMedia(context, details.title, details.author, source, tvData, page.duration * 1000,
            positionMs, autoplay, nativeSource)
    }

    /** Live/single-file media carries only caller-provided stream headers, without an account jar. */
    fun singleFile(url: String, title: String, creator: String = "", contentType: String = "video/mp4",
        positionMs: Long = 0, autoplay: Boolean = true, streamHeaders: Map<String, String>? = null): CastPluginMediaRequest {
        requirePlayableCastUrl(url)
        LocalProxyServer.ensureStarted()
        return CastPluginMediaRequest(LocalProxyServer.getProxyUrl(context, url, streamHeaders), title, creator, contentType,
            positionMs.coerceAtLeast(0), autoplay)
    }

    /** Root passes currentSourceSnapshot().source after its ownership/session recheck. */
    fun nativeSource(source: com.bilipai.desktop.player.PlaybackSource, creator: String = "",
        contentType: String = "video/mp4", positionMs: Long = 0, autoplay: Boolean = true): CastPluginMediaRequest =
        resolveDesktopNativeCastMedia(context, source, creator, contentType, positionMs, autoplay)

    fun existingSource(source: PlaybackSource, nativeSource: com.bilipai.desktop.player.PlaybackSource,
        creator: String = "", durationMs: Long = 0, positionMs: Long = 0,
        autoplay: Boolean = true): CastPluginMediaRequest = resolveDesktopCastMedia(context,
        nativeSource.title, creator, source, null, durationMs, positionMs, autoplay, nativeSource)
}

internal fun resolveDesktopNativeCastMedia(context: DesktopPluginContext, source: com.bilipai.desktop.player.PlaybackSource,
    creator: String, contentType: String, positionMs: Long, autoplay: Boolean): CastPluginMediaRequest {
    check(source.audioUrl == null) { "分离音视频流缺少完整 DASH 清单，无法完整投屏" }
    check(source.progressiveSegments.size <= 1) { "设备投屏无法将多个媒体分段当作单文件播放" }
    val url = requirePlayableCastUrl(source.progressiveSegments.singleOrNull()?.url ?: source.videoUrl)
    LocalProxyServer.ensureStarted()
    return CastPluginMediaRequest(LocalProxyServer.getProxyUrl(context, url, desktopCastStreamHeaders(source)),
        source.title, creator, contentType, positionMs.coerceAtLeast(0), autoplay)
}

internal fun requirePlayableCastUrl(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("无效的投屏媒体地址")
    require(parsed.username.isEmpty() && parsed.password.isEmpty())
    return parsed.toString()
}

internal fun resolveDesktopCastMedia(context: DesktopPluginContext, title: String, creator: String, source: PlaybackSource,
    tvData: PlayUrlData?, durationMs: Long, positionMs: Long, autoplay: Boolean,
    nativeSource: com.bilipai.desktop.player.PlaybackSource? = null): CastPluginMediaRequest {
    // A multi-segment progressive response is not a single file. Prefer its complete DASH response.
    val tvProgressive = tvData?.takeIf { it.durl.orEmpty().size == 1 }?.let(::extractTvCastPlayableUrl)
    val dash = if (tvProgressive == null) tvData?.dash ?: source.cachedDashData else null
    val headers = nativeSource?.let(::desktopCastStreamHeaders) ?: desktopCastStreamHeaders(com.bilipai.desktop.player.PlaybackSource(source.videoUrl,
        referer = source.referer, cookieHeader = source.cookieHeader))
    LocalProxyServer.ensureStarted()
    val (url, contentType) = when {
        tvProgressive != null -> LocalProxyServer.getProxyUrl(context, requirePlayableCastUrl(tvProgressive), headers) to "video/mp4"
        dash != null -> buildDesktopCastDash(context, dash, source.quality, tvData?.timelength ?: durationMs, headers) to LocalProxyServer.DASH_CONTENT_TYPE
        source.audioUrl != null -> error("分离音视频流缺少完整 DASH 清单，无法完整投屏")
        source.progressiveSegments.size > 1 || tvData?.durl.orEmpty().size > 1 -> error("设备投屏无法将多个媒体分段当作单文件播放")
        else -> LocalProxyServer.getProxyUrl(context, requirePlayableCastUrl(source.videoUrl), headers) to "video/mp4"
    }
    return CastPluginMediaRequest(url, title, creator, contentType, positionMs.coerceAtLeast(0), autoplay)
}

internal fun buildDesktopCastDash(context: DesktopPluginContext, dash: Dash, quality: Int, durationMs: Long,
    streamHeaders: Map<String, String>? = null): String {
    val video = selectCastDashVideo(dash.video, quality.takeIf { it > 0 } ?: 80) ?: error("DASH 缺少视频轨道")
    val audio = selectCastDashAudio(dash.audio.orEmpty(), dash.dolby, dash.flac) ?: error("DASH 缺少设备可用的 AAC 音轨")
    val proxyVideo = video.copy(baseUrl = LocalProxyServer.getProxyUrl(context, requirePlayableCastUrl(video.getValidUrl()), streamHeaders), backupUrl = null)
    val proxyAudio = audio.copy(baseUrl = LocalProxyServer.getProxyUrl(context, requirePlayableCastUrl(audio.getValidUrl()), streamHeaders), backupUrl = null)
    val manifest = buildLocalDashManifest(durationMs.takeIf { it > 0 } ?: dash.duration * 1000L,
        (dash.minBufferTime * 1000f).toLong().coerceAtLeast(0), listOf(proxyVideo), listOf(proxyAudio))
    return LocalProxyServer.registerDashManifest(context, manifest)
}

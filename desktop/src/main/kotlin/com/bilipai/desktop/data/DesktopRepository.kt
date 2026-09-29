package com.bilipai.desktop.data

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.BuvidApi
import com.android.purebilibili.core.network.FORCE_COOKIE_HEADER
import com.android.purebilibili.core.network.PassportApi
import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.core.network.WbiUtils
import com.android.purebilibili.data.model.response.NavData
import com.android.purebilibili.data.model.response.PlayUrlData
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.data.model.response.getBestAudio
import com.android.purebilibili.data.model.response.getBestVideo
import com.android.purebilibili.data.repository.buildDashAttemptQualities
import com.android.purebilibili.data.repository.buildPlayUrlWbiBaseParams
import com.android.purebilibili.data.repository.resolveDashRetryDelays
import com.android.purebilibili.data.repository.resolveVideoInfoLookupInput
import com.android.purebilibili.feature.login.parseLoginCookieHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Retrofit
import retrofit2.HttpException
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Windows platform adapter around upstream API declarations, models, signing and playback policies. */
class DesktopRepository internal constructor(private val sessions: DesktopSessionStore) {
    constructor() : this(DesktopSessionStore())

    val account: StateFlow<AccountSummary?> = sessions.account
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val client = OkHttpClient.Builder()
        .cookieJar(sessions)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder().header("User-Agent", USER_AGENT)
            val referer = resolveReferer(original.url, original.header("Referer"))
            if (referer == null) builder.removeHeader("Referer") else builder.header("Referer", referer)
            if (original.header("Origin").isNullOrBlank()) builder.header("Origin", "https://www.bilibili.com")
            if (original.header("Accept").isNullOrBlank()) builder.header("Accept", "application/json, text/plain, */*")
            builder.header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            val response = chain.proceed(builder.build())
            if (response.code == 412 || response.code == 429) {
                val code = response.code
                response.close()
                throw BiliApiException(code, if (code == 412)
                    "Bilibili 暂时限制了此请求 (HTTP 412)。请在浏览器打开 B 站完成正常验证，或登录后重试；也可稍后再试。"
                    else "Bilibili 请求过于频繁 (HTTP 429)，请稍后再试。")
            }
            response
        }
        // OkHttp's CookieJar runs after application interceptors. Imported cookies must be applied here.
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val forcedCookie = request.header(FORCE_COOKIE_HEADER)
            val replacement = if (forcedCookie != null) {
                request.newBuilder().header("Cookie", forcedCookie).removeHeader(FORCE_COOKIE_HEADER).build()
            } else request
            chain.proceed(replacement)
        }
        .build()
    private fun retrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl).client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    private val api = retrofit("https://api.bilibili.com/").create(BilibiliApi::class.java)
    private val searchApi = retrofit("https://api.bilibili.com/").create(SearchApi::class.java)
    private val passportApi = retrofit("https://passport.bilibili.com/").create(PassportApi::class.java)
    private val buvidApi = retrofit("https://api.bilibili.com/").create(BuvidApi::class.java)
    private val visitorMutex = Mutex()
    @Volatile private var visitorInitialized = false
    private val wbiMutex = Mutex()
    private var wbiKeys: Pair<String, String>? = null
    private var wbiExpiresAt = 0L

    suspend fun popular(page: Int = 1): List<VideoCard> = withContext(Dispatchers.IO) {
        require(page > 0)
        ensureVisitorSession()
        val response = api.getPopularVideos(pn = page)
        checkCode(response.code, response.message)
        response.data?.list.orEmpty().map { it.toVideoItem().toCard() }.filter { it.bvid.isNotBlank() }
    }

    suspend fun recommendations(page: Int = 1): List<VideoCard> = withContext(Dispatchers.IO) {
        require(page > 0)
        ensureVisitorSession()
        try {
            val response = api.getRecommendParams(sign(mapOf(
                "fresh_type" to "3", "ps" to "24", "fresh_idx" to page.toString(),
                "fresh_idx_1h" to page.toString(), "web_location" to "1430650",
            )))
            checkCode(response.code, response.message)
            response.data?.item.orEmpty().map { it.toVideoItem().toCard() }.filter { it.bvid.isNotBlank() }
                .ifEmpty { popular(page) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (isRequestLimited(error)) throw error
            popular(page)
        }
    }

    suspend fun search(query: String, page: Int = 1): List<VideoCard> = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "请输入搜索词" }
        require(page > 0)
        ensureVisitorSession()
        val response = searchApi.search(sign(mapOf(
            "keyword" to query.trim(), "search_type" to "video", "page" to page.toString(),
            "page_size" to "24", "order" to "totalrank", "web_location" to "1430654",
        )))
        checkCode(response.code, response.message)
        response.data?.result.orEmpty().map { it.toVideoItem().toCard() }.filter { it.bvid.isNotBlank() }
    }

    suspend fun videoDetails(bvid: String): VideoDetails = withContext(Dispatchers.IO) {
        ensureVisitorSession()
        val lookup = resolveVideoInfoLookupInput(bvid, 0)
            ?: throw IllegalArgumentException("请输入有效的 BV 或 av 视频编号")
        val response = if (lookup.aid > 0) api.getVideoInfoByAid(lookup.aid) else api.getVideoInfo(lookup.bvid)
        checkCode(response.code, response.message)
        val info = response.data ?: throw BiliApiException(-1, "视频详情为空")
        val pages = info.pages.map { VideoPart(it.cid, it.part, it.duration) }
            .ifEmpty { if (info.cid > 0) listOf(VideoPart(info.cid, info.title, 0)) else emptyList() }
        VideoDetails(info.bvid, info.aid, info.title, info.desc, normalizeUrl(info.pic), info.owner.name,
            info.stat.view.toLong(), info.stat.like.toLong(), pages)
    }

    suspend fun playback(details: VideoDetails, pageIndex: Int = 0, quality: Int = 80): PlaybackSource = withContext(Dispatchers.IO) {
        ensureVisitorSession()
        val part = details.pages.getOrNull(pageIndex) ?: throw IllegalArgumentException("视频分 P 不存在")
        var lastError: Exception? = null
        for ((index, targetQuality) in buildDashAttemptQualities(quality).withIndex()) {
            for (retryDelay in resolveDashRetryDelays(targetQuality, isPrimaryAttempt = index == 0)) {
                if (retryDelay > 0) delay(retryDelay)
                try {
                    val response = api.getPlayUrl(sign(buildPlayUrlWbiBaseParams(details.bvid, part.cid, targetQuality)))
                    checkCode(response.code, response.message)
                    response.data?.toPlaybackSource(details, targetQuality)?.let { return@withContext it }
                    lastError = BiliApiException(-1, "播放接口未返回可用媒体流")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    lastError = error
                    // A rejected session/rate limit must not trigger a rapid quality retry chain.
                    if (error is BiliApiException && error.apiCode in setOf(-101, -352, -412, 412, 429)) throw error
                    if (error is HttpException && error.code() in setOf(429, 412)) throw error
                }
            }
        }
        try {
            val response = api.getPlayUrlLegacy(details.bvid, part.cid, qn = quality)
            checkCode(response.code, response.message)
            response.data?.toPlaybackSource(details, quality)?.let { return@withContext it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            lastError = error
        }
        throw lastError ?: BiliApiException(-1, "此视频暂无可用播放地址")
    }

    suspend fun related(bvid: String): List<VideoCard> = withContext(Dispatchers.IO) {
        ensureVisitorSession()
        api.getRelatedVideos(bvid).data.orEmpty().map {
            VideoCard(it.bvid, it.title, normalizeUrl(it.pic), it.owner.name, it.stat.view.toLong(), it.duration)
        }.filter { it.bvid.isNotBlank() }
    }

    suspend fun comments(aid: Long, page: Int = 1): List<Comment> = withContext(Dispatchers.IO) {
        require(aid > 0 && page > 0)
        ensureVisitorSession()
        val response = api.getReplyListLegacy(oid = aid, pn = page, ps = 20, sort = 1)
        checkCode(response.code, response.message)
        val data = response.data ?: return@withContext emptyList()
        val replies = if (page == 1) data.collectTopReplies() + data.replies.orEmpty() else data.replies.orEmpty()
        replies.distinctBy { it.rpid }.map {
            Comment(it.rpid, it.member.uname, normalizeUrl(it.member.avatar), it.content.message, it.like, it.ctime)
        }
    }

    suspend fun beginQrLogin(): QrLogin = withContext(Dispatchers.IO) {
        ensureVisitorSession()
        val response = passportApi.generateQrCode()
        checkCode(response.code, response.message)
        val data = response.data ?: throw BiliApiException(-1, "二维码响应为空")
        QrLogin(data.qrcode_key?.takeIf { it.isNotBlank() } ?: throw BiliApiException(-1, "二维码缺少标识"),
            data.url?.takeIf { it.isNotBlank() } ?: throw BiliApiException(-1, "二维码链接为空"))
    }

    suspend fun pollQrLogin(key: String): QrLoginState = withContext(Dispatchers.IO) {
        val response = passportApi.pollQrCode(key)
        if (!response.isSuccessful) throw BiliApiException(response.code(), "二维码请求失败 (${response.code()})")
        val body = response.body() ?: throw BiliApiException(-1, "二维码状态为空")
        checkCode(body.code, body.message)
        val data = body.data ?: throw BiliApiException(-1, "二维码状态为空")
        when (data.code) {
            86101 -> QrLoginState.Waiting
            86090 -> QrLoginState.Scanned
            86038 -> QrLoginState.Expired
            0 -> {
                val cookies = sessions.currentCookies().toMutableMap()
                // Some poll responses expose the credentials in their success redirect query.
                data.url?.toHttpUrlOrNull()?.let { url ->
                    DesktopSessionStore.PERSISTED_COOKIE_NAMES.forEach { name ->
                        url.queryParameter(name)?.let { cookies[name] = it }
                    }
                }
                val summary = validateCookieHeader(cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                sessions.saveAccount(cookies, summary)
                QrLoginState.Complete(summary)
            }
            else -> throw BiliApiException(data.code, data.message)
        }
    }

    suspend fun importCookies(raw: String): AccountSummary = withContext(Dispatchers.IO) {
        val parsed = parseLoginCookieHeader(raw) ?: throw IllegalArgumentException("Cookie 中缺少 SESSDATA")
        val summary = validateCookieHeader(parsed.toCookieHeader())
        sessions.saveAccount(parsed.values, summary, imported = true)
        summary
    }

    suspend fun refreshAccount(): AccountSummary? = withContext(Dispatchers.IO) {
        if (sessions.currentCookies()["SESSDATA"].isNullOrBlank()) return@withContext null
        val response = api.getNavInfo()
        if (response.code == -101 || response.data?.isLogin == false) {
            sessions.logout()
            return@withContext null
        }
        checkCode(response.code, "无法验证登录状态")
        val summary = response.data?.toAccount() ?: throw BiliApiException(-1, "登录资料为空")
        sessions.saveAccount(sessions.currentCookies(), summary)
        summary
    }

    fun logout() {
        sessions.logout()
        visitorInitialized = false
    }

    private suspend fun ensureVisitorSession() = visitorMutex.withLock {
        if (visitorInitialized) return@withLock
        // Standard visitor bootstrap, once per process. It does not attempt to bypass a security challenge.
        client.newCall(Request.Builder().url("https://www.bilibili.com/")
            .header("Accept", "text/html,application/xhtml+xml").build()).execute().use { response ->
            if (!response.isSuccessful) throw BiliApiException(response.code, "无法初始化 B 站访客会话 (${response.code})")
        }
        val response = buvidApi.getSpi()
        checkCode(response.code, "无法获取 B 站访客标识")
        val visitors = mutableMapOf<String, String>()
        response.data?.let { data ->
            data.b_3.takeIf { it.isNotBlank() }?.let { visitors["buvid3"] = it }
            data.b_4.takeIf { it.isNotBlank() }?.let { visitors["buvid4"] = it }
        }
        sessions.saveSpiCookies(visitors)
        visitorInitialized = true
    }

    private suspend fun validateCookieHeader(header: String): AccountSummary {
        require(!header.contains('\n') && !header.contains('\r')) { "Cookie 格式不正确" }
        val response = passportApi.validateCookieSession(header)
        checkCode(response.code, "Cookie 已失效，请重新登录")
        val nav = response.data?.takeIf { it.isLogin } ?: throw BiliApiException(-101, "Cookie 已失效，请重新登录")
        return nav.toAccount()
    }

    private suspend fun sign(params: Map<String, String>): Map<String, String> {
        val keys = wbiMutex.withLock {
            val now = System.currentTimeMillis()
            if (wbiKeys == null || now >= wbiExpiresAt) {
                // The anonymous nav response can carry WBI keys with code -101.
                val img = api.getNavInfo().data?.wbi_img ?: throw BiliApiException(-1, "无法获取接口签名信息")
                val imageKey = img.img_url.substringAfterLast('/').substringBefore('.')
                val subKey = img.sub_url.substringAfterLast('/').substringBefore('.')
                require(imageKey.length == 32 && subKey.length == 32) { "接口签名信息格式异常" }
                wbiKeys = imageKey to subKey
                wbiExpiresAt = now + TimeUnit.MINUTES.toMillis(5)
            }
            requireNotNull(wbiKeys)
        }
        return WbiUtils.sign(params, keys.first, keys.second)
    }

    private fun PlayUrlData.toPlaybackSource(details: VideoDetails, requestedQuality: Int): PlaybackSource? {
        val referer = "https://www.bilibili.com/video/${details.bvid}"
        dash?.let { streams ->
            val video = streams.getBestVideo(requestedQuality, preferCodec = "avc1", secondPreferCodec = "hev1",
                isHevcSupported = true, isAv1Supported = true)
            val audio = streams.getBestAudio()
            val videoUrl = video?.getValidUrl()?.takeIf { it.isNotBlank() }
            if (videoUrl != null) return PlaybackSource(normalizeUrl(videoUrl),
                audio?.getValidUrl()?.takeIf { it.isNotBlank() }?.let(::normalizeUrl), details.title, referer,
                quality = video?.id ?: quality)
        }
        val progressive = durl.orEmpty()
        // The player facade supports one muxed URL; concatenated FLV segments require another adapter.
        if (progressive.size == 1 && progressive.first().url.isNotBlank()) {
            return PlaybackSource(normalizeUrl(progressive.first().url), null, details.title, referer, quality = quality)
        }
        return null
    }

    private fun VideoItem.toCard() = VideoCard(bvid, title, normalizeUrl(pic), owner.name, stat.view.toLong(), duration)
    private fun NavData.toAccount() = AccountSummary(mid, uname, normalizeUrl(face), vip.status == 1)

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        // Mirror upstream ApiClient: explicit headers win, WBI omits Referer, video APIs use the video page.
        internal fun resolveReferer(url: HttpUrl, explicit: String?): String? {
            explicit?.takeIf { it.isNotBlank() }?.let { return it }
            if (url.encodedPath.contains("/wbi/") || url.host == "app.bilibili.com") return null
            return url.queryParameter("bvid")?.takeIf { it.isNotBlank() }?.let { "https://www.bilibili.com/video/$it" }
                ?: "https://www.bilibili.com"
        }
        private fun normalizeUrl(url: String): String = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> "https://" + url.removePrefix("http://")
            else -> url
        }
        private fun checkCode(code: Int, message: String) {
            if (code != 0) throw BiliApiException(code, message)
        }
        private fun isRequestLimited(error: Exception): Boolean =
            error is BiliApiException && error.apiCode in setOf(-101, -352, -412, 412, 429) ||
                error is HttpException && error.code() in setOf(412, 429)
    }
}

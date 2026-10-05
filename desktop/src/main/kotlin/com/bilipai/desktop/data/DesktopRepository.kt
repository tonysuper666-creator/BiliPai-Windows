package com.bilipai.desktop.data

import com.bilipai.desktop.player.PlayerPreferences
import com.bilipai.desktop.player.resolveDesktopDashSelection
import com.android.purebilibili.feature.video.playback.audio.resolveRequestedAudioQuality
import com.android.purebilibili.feature.video.playback.policy.resolveSpeedCompatibleAudioQualityPreference
import com.android.purebilibili.feature.video.viewmodel.resolveEffectiveVideoCodecPreference
import com.android.purebilibili.feature.video.viewmodel.resolveEffectiveAv1Support

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.BuvidApi
import com.android.purebilibili.core.network.DynamicApi
import com.android.purebilibili.core.network.FORCE_COOKIE_HEADER
import com.android.purebilibili.core.network.PassportApi
import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.core.network.WbiUtils
import com.android.purebilibili.core.network.resolveAndroidHdLoginAppKeyHeader
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
import com.android.purebilibili.feature.bangumi.collectPlayableDurlUrls
import com.android.purebilibili.feature.video.viewmodel.normalizeCodecFamilyKey
import com.android.purebilibili.feature.video.viewmodel.resolveEffectiveVideoSecondCodecPreference
import com.android.purebilibili.feature.video.viewmodel.resolvePlaybackVideoCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Retrofit
import retrofit2.HttpException
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import com.bilipai.desktop.player.PlaybackSegment

/** Windows platform adapter around upstream API declarations, models, signing and playback policies. */
private data class DesktopSessionEpoch(val value: Long, val stillOwned: () -> Boolean = { true }, val guestBuvid3: String? = null, val playbackAuthorization: DesktopPlaybackAuthorization? = null)
class DesktopRepository internal constructor(private val sessions: DesktopSessionStore) {
    constructor() : this(DesktopSessionStore())

    val account: StateFlow<AccountSummary?> = sessions.account
    val savedAccounts: StateFlow<List<DesktopStoredAccountInfo>> = sessions.accounts
    internal val sessionEpoch: Long get() = sessions.generation
    internal val sessionEpochFlow: StateFlow<Long> = sessions.generationState
    internal val dynamicCacheSessionGuard: DesktopDynamicCacheSessionGuard get() = sessions
    internal val followStateEvents = DesktopFollowStateEvents(sessions)
    private val authMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val client = OkHttpClient.Builder()
        .proxySelector(com.bilipai.desktop.network.DesktopNetworkProxyPlatform.buildAppProxySelector())
        .cookieJar(sessions)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            // An owner-bound Call.Factory stamps this before newCall/enqueue. Preserve it here;
            // choosing the current epoch at execution would authorize an old queued request.
            val requestOwner = original.tag(DesktopSessionEpoch::class.java) ?: DesktopSessionEpoch(sessions.generation)
            val expectedEpoch = requestOwner.value
            if (expectedEpoch != sessions.generation || !requestOwner.stillOwned() || requestOwner.playbackAuthorization?.let { !sessions.isPlaybackAuthorizationCurrent(it.receipt) } == true)
                throw java.io.IOException("Request owner retired")
            val builder = original.newBuilder().tag(DesktopSessionEpoch::class.java, requestOwner)
                .header("User-Agent", resolvePlatformUserAgent(original.url, original.header("User-Agent")))
            val requestCookies = if (requestOwner.guestBuvid3 != null) emptyMap()
                else requestOwner.playbackAuthorization?.let { sessions.playbackRequestCookies(it, original.url, requestOwner.stillOwned) } ?: sessions.homeRequestCookies(expectedEpoch, requestOwner.stillOwned)
            applyDesktopMergedRecommendationHeaders(builder, original.url, requestOwner.guestBuvid3 ?: requestCookies["buvid3"].orEmpty())
            if (original.url.host == "app.bilibili.com" && original.url.encodedPath in setOf("/x/v2/space", "/x/v2/space/likearc")) {
                val buvid = original.header("X-BiliPai-Login-Buvid") ?: requestOwner.guestBuvid3 ?: requestCookies["buvid3"].orEmpty()
                if (buvid.isNotBlank()) builder.header("buvid", buvid)
                builder.removeHeader("X-BiliPai-Login-Buvid").header("bili-http-engine", "cronet").header("env", "prod")
                    .header("app-key", "android64").header("x-bili-aurora-zone", "sh001")
            }
            val referer = resolveReferer(original.url, original.header("Referer"))
            if (referer == null) builder.removeHeader("Referer") else builder.header("Referer", referer)
            if (original.header("Origin").isNullOrBlank() && original.url.host != "app.bilibili.com" &&
                resolveAndroidHdLoginAppKeyHeader(original.url.encodedPath) == null) builder.header("Origin", "https://www.bilibili.com")
            if (original.header("Accept").isNullOrBlank()) builder.header("Accept", "application/json, text/plain, */*")
            builder.header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            requestOwner.playbackAuthorization?.let { sessions.requestPlaybackAuthorization.set(it) }
            sessions.requestGeneration.set(expectedEpoch)
            sessions.requestAdmission.set(requestOwner.stillOwned)
            sessions.requestGuestBuvid3.set(requestOwner.guestBuvid3)
            val response = try { chain.proceed(builder.build()) } finally {
                sessions.requestPlaybackAuthorization.remove()
                sessions.requestGuestBuvid3.remove()
                sessions.requestAdmission.remove()
                sessions.requestGeneration.remove()
            }
            if (expectedEpoch != sessions.generation || !requestOwner.stillOwned() || requestOwner.playbackAuthorization?.let { !sessions.isPlaybackAuthorizationCurrent(it.receipt) } == true) {
                response.close(); throw BiliApiException(-101, "账号已切换，请重新加载")
            }
            if ((response.code == 412 || response.code == 429) && original.tag(com.bilipai.desktop.player.cache.DesktopCdnRangeRequestPolicy::class.java) == null) {
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
            val requestOwner = request.tag(DesktopSessionEpoch::class.java) ?: DesktopSessionEpoch(sessions.generation)
            val epoch = requestOwner.value
            if (epoch != sessions.generation || !requestOwner.stillOwned() || requestOwner.playbackAuthorization?.let { !sessions.isPlaybackAuthorizationCurrent(it.receipt) } == true) throw BiliApiException(-101, "账号已切换，请重新操作")
            val forcedCookie = request.header(FORCE_COOKIE_HEADER)
            val replacement = if (requestOwner.guestBuvid3 != null) {
                // The original guest route has fresh visitor identity only; explicit account
                // headers cannot override its guest CookieJar boundary.
                request.newBuilder().removeHeader(FORCE_COOKIE_HEADER)
                    .header("Cookie", "buvid3=${requestOwner.guestBuvid3}").build()
            } else if (forcedCookie != null) {
                request.newBuilder().header("Cookie", forcedCookie).removeHeader(FORCE_COOKIE_HEADER).build()
            } else request
            val stripped = stripDesktopAnonymousHomeFeedCookie(stripDesktopMergedFeedCookie(replacement))
            val mediaOrigin = stripped.tag(com.bilipai.desktop.player.cache.DesktopMediaOriginHeaders::class.java)
            val response = chain.proceed(mediaOrigin?.apply(stripped) ?: stripped)
            // The same client must reject captured parallel-range redirects before
            // RetryAndFollowUpInterceptor can send a follow-up to another signed address.
            if (request.tag(com.bilipai.desktop.player.cache.DesktopCdnRangeRequestPolicy::class.java) != null)
                com.bilipai.desktop.player.cache.DesktopCdnRangeRequestPolicy.rejectRedirect(response)
            // BridgeInterceptor saves response cookies after this interceptor returns. An old
            // account's in-flight response must never populate the newly activated account jar.
            if (epoch != sessions.generation || !requestOwner.stillOwned() || requestOwner.playbackAuthorization?.let { !sessions.isPlaybackAuthorizationCurrent(it.receipt) } == true) response.newBuilder().headers(response.headers.newBuilder().removeAll("Set-Cookie").build()).build()
            else response
        }
        .build()
    private fun retrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl).client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    private val api = retrofit("https://api.bilibili.com/").create(BilibiliApi::class.java)
    private val searchApi = retrofit("https://api.bilibili.com/").create(SearchApi::class.java)
    private val passportApi = retrofit("https://passport.bilibili.com/").create(PassportApi::class.java)
    private val validationPassportRetrofit = Retrofit.Builder().baseUrl("https://passport.bilibili.com/")
        .client(client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    private val validationPassportApi = validationPassportRetrofit.create(PassportApi::class.java)
    private val buvidApi = retrofit("https://api.bilibili.com/").create(BuvidApi::class.java)
    private val dynamicApi = retrofit("https://api.bilibili.com/").create(DynamicApi::class.java)
    private val visitorMutex = Mutex()
    @Volatile private var visitorInitialized = false
    @Volatile private var visitorGeneration = -1L
    private val wbiMutex = Mutex()
    // Store -> entry admission -> this short monitor. Never suspend or perform IO here.
    private val playbackProtocolMonitor = Any()
    private var wbiKeys: Pair<String, String>? = null
    private var wbiExpiresAt = 0L
    private var wbiGeneration = -1L
    private var appApiCooldownUntilMs = 0L
    private var lastPlayback412Time = 0L
    private val playbackCache = DesktopPlaybackCache()
    private val originalVideoWbiCacheDurationMs = TimeUnit.MINUTES.toMillis(30)

    private fun resetVideoProtocolGenerationLocked(generation: Long) {
        if (wbiGeneration != generation) {
            wbiKeys = null
            wbiExpiresAt = 0L
            appApiCooldownUntilMs = 0L
            lastPlayback412Time = 0L
            wbiGeneration = generation
        }
    }

    /** Views only: the existing playbackCache and WBI/diagnostics fields remain sole owners.
     * commitIfCurrent must take the same entry gate AFTER this Store admission.
     * Only short state/cache actions run in this gate; no Call/ACK/native wait or join. */
    internal fun originalVideoProtocolViews(
        authorization: DesktopPlaybackAuthorization,
        stillOwned: () -> Boolean,
        commitIfCurrent: ((() -> Unit) -> Boolean),
        cacheKey: (String, Long, Int) -> DesktopPlaybackCache.Key,
    ): Pair<com.bilipai.desktop.ui.DesktopOriginalVideoRawCache, com.bilipai.desktop.ui.DesktopOriginalVideoProtocolState> {
        fun <T> admitted(block: () -> T): T = sessions.withPlaybackAuthorizationAdmission(authorization.receipt, stillOwned) {
            var result: Result<T>? = null
            if (!commitIfCurrent {
                if (!stillOwned()) throw CancellationException("Original video request retired")
                result = runCatching(block)
            }) throw CancellationException("Original video entry retired")
            requireNotNull(result).getOrThrow()
        }
        fun <T> state(block: () -> T): T = admitted { synchronized(playbackProtocolMonitor) {
            resetVideoProtocolGenerationLocked(authorization.receipt.accountEpoch)
            block()
        } }
        fun key(bvid: String, cid: Long, quality: Int): DesktopPlaybackCache.Key = cacheKey(bvid, cid, quality).also {
            require(it.accountEpoch == authorization.receipt.accountEpoch && it.authorizationRevision == authorization.receipt.revision)
        }
        val cache = object : com.bilipai.desktop.ui.DesktopOriginalVideoRawCache {
            override fun get(bvid: String, cid: Long, requestedQuality: Int): PlayUrlData? = admitted {
                playbackCache.get(key(bvid, cid, requestedQuality))?.data
            }
            override fun put(bvid: String, cid: Long, data: PlayUrlData, quality: Int) { admitted {
                playbackCache.put(key(bvid, cid, quality), data, quality)
            } }
        }
        val protocolState = object : com.bilipai.desktop.ui.DesktopOriginalVideoProtocolState {
            override var appApiCooldownUntilMs: Long
                get() = state { this@DesktopRepository.appApiCooldownUntilMs }
                set(value) { state { this@DesktopRepository.appApiCooldownUntilMs = value } }
            override var wbiKeys: Pair<String, String>?
                get() = state { this@DesktopRepository.wbiKeys }
                set(value) { state { this@DesktopRepository.wbiKeys = value } }
            override var wbiKeysTimestamp: Long
                get() = state { if (wbiExpiresAt == 0L) 0L else wbiExpiresAt - originalVideoWbiCacheDurationMs }
                set(value) { state { wbiExpiresAt = if (value == 0L) 0L else value + originalVideoWbiCacheDurationMs } }
            override var last412Time: Long
                get() = state { lastPlayback412Time }
                set(value) { state { lastPlayback412Time = value } }
        }
        return cache to protocolState
    }

    /** Entry view of the EXISTING raw playback cache. Every synchronous read
     * captures the current valid authorization under the SAME Store; completed
     * request Jobs are not retained for a page-wide cache view. */
    internal fun originalVideoOwnerCacheView(expectedEpoch: Long, stillOwned: () -> Boolean,
        commitIfEntryCurrent: ((() -> Unit) -> Boolean)): com.bilipai.desktop.ui.DesktopOriginalVideoOwnerCache {
        fun <T> admitted(block: (DesktopPlaybackAuthorizationReceipt) -> T): T {
            val authorization = capturePlaybackAuthorization(expectedEpoch, stillOwned)
            return sessions.withPlaybackAuthorizationAdmission(authorization.receipt, stillOwned) {
                var result: Result<T>? = null
                if (!commitIfEntryCurrent {
                    if (!stillOwned()) throw CancellationException("Original video cache entry retired")
                    result = runCatching { block(authorization.receipt) }
                }) throw CancellationException("Original video cache entry retired")
                checkNotNull(result).getOrThrow()
            }
        }
        return object : com.bilipai.desktop.ui.DesktopOriginalVideoOwnerCache {
            override fun get(bvid: String, cid: Long): PlayUrlData? = admitted { receipt ->
                playbackCache.getForVideo(receipt.accountEpoch, receipt.revision, bvid, cid)?.data
            }
            override fun invalidate(bvid: String, cid: Long) { admitted { receipt ->
                playbackCache.invalidateVideo(receipt.accountEpoch, bvid, cid)
            } }
        }
    }

    internal fun originalVideoCacheKey(authorization: DesktopPlaybackAuthorization, bvid: String, cid: Long,
        quality: Int, preferences: PlayerPreferences, codecOverride: String?, blockedCodecs: Set<String>,
        av1Supported: Boolean): DesktopPlaybackCache.Key {
        val codec = normalizeCodecFamilyKey(codecOverride)
        require(codec == null || codec in setOf("avc1", "hev1", "av01")) { "不支持的视频编码" }
        return DesktopPlaybackCache.Key(authorization.receipt.accountEpoch, bvid, cid, quality, codec,
            firstCodec = resolveEffectiveVideoCodecPreference(codec, preferences.videoCodecPreference, blockedCodecs),
            secondCodec = resolveEffectiveVideoSecondCodecPreference(codec, preferences.videoSecondCodecPreference),
            audioQuality = resolveSpeedCompatibleAudioQualityPreference(resolveRequestedAudioQuality(
                preferences.defaultAudioQuality, preferences.lastSelectedAudioQuality), preferences.speed.toFloat()),
            av1Supported = resolveEffectiveAv1Support(av1Supported, blockedCodecs), authorizationRevision = authorization.receipt.revision)
    }

    /** Read-only UI cooldown projection of the existing protocol monitor. Never
     * retain a completed request Binding or manufacture a fallback cooldown. */
    internal fun originalVideoCooldownForEntry(expectedEpoch:Long,stillOwned:()->Boolean,
        commitIfEntryCurrent:((()->Unit)->Boolean),nowMs:Long):Long =
        withPrimaryPlaybackAdmission(expectedEpoch,stillOwned) {
            var result:Long? = null
            if (!commitIfEntryCurrent {
                if (!stillOwned()) throw CancellationException("Original entry cooldown retired")
                result = synchronized(playbackProtocolMonitor) {
                    resetVideoProtocolGenerationLocked(expectedEpoch)
                    (appApiCooldownUntilMs-nowMs).coerceAtLeast(0L)
                }
            }) throw CancellationException("Original entry cooldown admission retired")
            checkNotNull(result)
        }

    internal val playbackAuthorizationRevision: StateFlow<Long> get() = sessions.playbackAuthorizationRevision
    internal fun storedAccountSessions() = sessions.storedAccountSessions()
    internal fun activeAccountMid() = sessions.activeAccountMid()
    internal fun getPlaybackAccountMid() = sessions.getPlaybackAccountMid()
    internal fun getPlaybackAccount() = sessions.getPlaybackAccount()
    internal fun setPlaybackAccountMid(mid: Long?, expectedEpoch: Long, stillOwned: () -> Boolean): Boolean =
        sessions.setPlaybackAccountMid(mid, expectedEpoch, stillOwned)
    internal fun capturePlaybackCachePartition(receipt: DesktopPlaybackAuthorizationReceipt, stillOwned: () -> Boolean): String =
        sessions.capturePlaybackCachePartition(receipt, stillOwned)
    internal fun capturePlaybackAuthorization(expectedEpoch: Long, stillOwned: () -> Boolean) =
        sessions.capturePlaybackAuthorization(expectedEpoch, stillOwned)
    /** Called from captured Binding's Store -> entry read. No request or IO is created. */
    internal fun capturePlaybackMediaCookieHeader(authorization: DesktopPlaybackAuthorization,
        url: String, stillOwned: () -> Boolean): String {
        assertPlaybackAuthorization(authorization, stillOwned)
        val parsed = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid captured media URL")
        return (authorization.cookieJar?.loadForRequest(parsed) ?: sessions.loadForRequest(parsed))
            .joinToString("; ") { "${it.name}=${it.value}" }.also {
                assertPlaybackAuthorization(authorization, stillOwned)
            }
    }

    internal fun assertPlaybackAuthorization(authorization: DesktopPlaybackAuthorization, stillOwned: () -> Boolean) =
        sessions.withPlaybackAuthorizationAdmission(authorization.receipt, stillOwned) { Unit }
    internal fun isPlaybackSourceCurrent(source: PlaybackSource): Boolean =
        source.authorizationReceipt?.let(sessions::isPlaybackAuthorizationCurrent) == true
    internal suspend fun signPlaybackWebParams(params: Map<String, String>, authorization: DesktopPlaybackAuthorization,
        stillOwned: () -> Boolean, includeRiskFingerprint: Boolean = false): Map<String, String> {
        assertPlaybackAuthorization(authorization, stillOwned)
        val navApi = ownedHomeService(BilibiliApi::class.java, "https://api.bilibili.com/", authorization.receipt.accountEpoch, stillOwned)
        val signed = sign(params, includeRiskFingerprint = includeRiskFingerprint, requestApi = navApi,
            expectedEpoch = authorization.receipt.accountEpoch, stillOwned = stillOwned)
        assertPlaybackAuthorization(authorization, stillOwned)
        return signed
    }
    internal fun isPlaybackReceiptCurrent(receipt: DesktopPlaybackAuthorizationReceipt): Boolean =
        sessions.isPlaybackAuthorizationCurrent(receipt)
    internal fun <T> withPlaybackReceiptAdmission(receipt: DesktopPlaybackAuthorizationReceipt,
        stillOwned: () -> Boolean, block: () -> T): T = sessions.withPlaybackAuthorizationAdmission(receipt, stillOwned, block)
    internal fun <T> withPrimaryPlaybackAdmission(epoch: Long, stillOwned: () -> Boolean, block: () -> T): T =
        sessions.withHomeRequestAdmission(epoch, stillOwned, block)
    internal fun primaryPlaybackCalls(epoch: Long, stillOwned: () -> Boolean, delegate: okhttp3.Call.Factory): okhttp3.Call.Factory =
        okhttp3.Call.Factory { request -> sessions.withHomeRequestAdmission(epoch, stillOwned) {
            delegate.newCall(request.newBuilder().tag(DesktopSessionEpoch::class.java, DesktopSessionEpoch(epoch, stillOwned)).build())
        } }
    internal fun playbackReceiptCalls(receipt: DesktopPlaybackAuthorizationReceipt, stillOwned: () -> Boolean, delegate: okhttp3.Call.Factory): okhttp3.Call.Factory {
        val authorization = sessions.withPlaybackAuthorizationAdmission(receipt, stillOwned) {
            sessions.capturePlaybackAuthorization(receipt.accountEpoch, stillOwned).also {
                if (it.receipt != receipt) throw kotlinx.coroutines.CancellationException("Playback authorization retired")
            }
        }
        return okhttp3.Call.Factory { request -> delegate.newCall(request.newBuilder().tag(DesktopSessionEpoch::class.java,
            DesktopSessionEpoch(receipt.accountEpoch, stillOwned, playbackAuthorization = authorization)).build()) }
    }

    internal fun <T> withPlaybackSourceAdmission(source: PlaybackSource, stillOwned: () -> Boolean, block: () -> T): T {
        val receipt = source.authorizationReceipt ?: throw kotlinx.coroutines.CancellationException("Missing playback authorization receipt")
        return sessions.withPlaybackAuthorizationAdmission(receipt, stillOwned, block)
    }
    /** Uses this client's existing dispatcher/pool/transport and the same Store CookieJar. */
    internal fun ownedPlaybackCallFactory(authorization: DesktopPlaybackAuthorization, stillOwned: () -> Boolean): okhttp3.Call.Factory =
        okhttp3.Call.Factory { request ->
            if (!sessions.isPlaybackAuthorizationCurrent(authorization.receipt) || !stillOwned()) throw java.io.IOException("Playback authorization retired")
            client.newCall(request.newBuilder().tag(DesktopSessionEpoch::class.java,
                DesktopSessionEpoch(authorization.receipt.accountEpoch, stillOwned, playbackAuthorization = authorization)).build())
        }
    internal fun <T> ownedPlaybackService(type: Class<T>, authorization: DesktopPlaybackAuthorization, stillOwned: () -> Boolean): T =
        Retrofit.Builder().baseUrl("https://api.bilibili.com/").callFactory(ownedPlaybackCallFactory(authorization, stillOwned))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(type)

    // Original guest CookieJar identity generation expression, now a scalar on the SAME
    // existing transport owner. No second CookieJar/cache/client or account credential copy.
    private val homeGuestBuvid3: String by lazy { java.util.UUID.randomUUID().toString().replace("-", "") + "infoc" }

    /** Admission facade over the SAME client/dispatcher/pool/CookieJar. No client or cookie
     * state is constructed. The immutable epoch and lifetime are attached before Call creation. */
    internal fun ownedHomeCallFactory(expectedEpoch: Long, stillOwned: () -> Boolean, guest: Boolean = false, transport: OkHttpClient = client): okhttp3.Call.Factory =
        okhttp3.Call.Factory { request ->
            if (expectedEpoch != sessions.generation || !stillOwned())
                throw java.io.IOException("Home request owner retired")
            transport.newCall(request.newBuilder().tag(DesktopSessionEpoch::class.java,
                DesktopSessionEpoch(expectedEpoch, stillOwned, if (guest) homeGuestBuvid3 else null)).build())
        }

    /** Services over the SAME Call.Factory/client/cookie state, used once by retained Home. */
    internal fun <T> ownedHomeService(type: Class<T>, baseUrl: String, expectedEpoch: Long,
        stillOwned: () -> Boolean, guest: Boolean = false): T = Retrofit.Builder().baseUrl(baseUrl)
        .callFactory(ownedHomeCallFactory(expectedEpoch, stillOwned, guest))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(type)

    /** Original official QR authorization endpoints over the EXISTING validation transport.
     * Default system TLS is already used by this owner; no debug trust override, new pool,
     * CookieJar, dispatcher or credential persistence is introduced. */
    internal fun ownedProfileQrAuthorizationService(expectedEpoch: Long, stillOwned: () -> Boolean): PassportApi {
        val transport = validationPassportRetrofit.callFactory() as OkHttpClient
        val bound = ownedHomeCallFactory(expectedEpoch, stillOwned, transport = transport)
        val restricted = okhttp3.Call.Factory { request ->
            val url = request.url
            val allowed = url.isHttps && url.port == 443 && when (url.host) {
                "api.bilibili.com" -> url.encodedPath == "/x/web-interface/nav" && request.method == "GET"
                "passport.bilibili.com" -> when (url.encodedPath) {
                    "/x/passport-login/web/qrcode/check", "/x/passport-login/web/qrcode/scene" -> request.method == "GET"
                    "/x/passport-login/web/qrcode/confirm", "/x/passport-tv-login/h5/qrcode/confirm" -> request.method == "POST"
                    else -> false
                }
                else -> false
            }
            if (!allowed) throw java.io.IOException("扫码授权请求地址不受支持")
            bound.newCall(request)
        }
        return validationPassportRetrofit.newBuilder().callFactory(restricted).build().create(PassportApi::class.java)
    }

    /** Reads the existing WBI cache, with its existing expiry and owner-bound nav API. */
    internal suspend fun homeWbiKeys(expectedEpoch: Long, stillOwned: () -> Boolean,
        ownedApi: BilibiliApi, forceRefresh: Boolean = false): Result<Pair<String, String>> {
        return try {
            if (expectedEpoch != sessions.generation || !stillOwned()) throw kotlinx.coroutines.CancellationException("Home epoch retired")
            sign(emptyMap(), forceRefresh = forceRefresh, requestApi = ownedApi, expectedEpoch = expectedEpoch, stillOwned = stillOwned)
            val keys = wbiMutex.withLock {
                sessions.withHomeRequestAdmission(expectedEpoch, stillOwned) { synchronized(playbackProtocolMonitor) {
                    resetVideoProtocolGenerationLocked(expectedEpoch)
                    requireNotNull(wbiKeys)
                } }
            }
            Result.success(keys)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
    }

    internal suspend fun ensureOwnedHomeSession(expectedEpoch: Long, stillOwned: () -> Boolean,
        ownedBuvidApi: BuvidApi) = ensureVisitorSession(expectedEpoch, stillOwned,
            ownedHomeCallFactory(expectedEpoch, stillOwned), ownedBuvidApi)

    /** Existing visitor SPI/bootstrap completion only, not Android activation proof. */
    internal fun ownedHomeVisitorInitialized(expectedEpoch: Long, stillOwned: () -> Boolean): Boolean =
        sessions.withHomeRequestAdmission(expectedEpoch, stillOwned) {
            visitorInitialized && visitorGeneration == expectedEpoch
        }

    internal fun ownedHomeAccessToken(expectedEpoch: Long, stillOwned: () -> Boolean): String? =
        sessions.withHomeRequestAdmission(expectedEpoch, stillOwned) { sessions.accessTokenCredentials().first }
    /** One atomic credential/platform snapshot under the existing same-Store epoch gate. */
    internal fun ownedHomeAccessTokenIdentity(expectedEpoch: Long, stillOwned: () -> Boolean): Pair<String?,String> =
        sessions.withHomeRequestAdmission(expectedEpoch, stillOwned) { sessions.accessTokenCredentials() }
    internal fun ownedHomeCookie(name: String, expectedEpoch: Long, stillOwned: () -> Boolean): String? =
        sessions.homeRequestCookies(expectedEpoch, stillOwned)[name]
    // SavedSession is synchronously read/decrypted by the existing Store constructor. This is
    // the actual same-owner restoration boundary, not a second deferred restoration cache.
    internal fun assertOwnedHomeSessionRestored(expectedEpoch: Long, stillOwned: () -> Boolean) =
        sessions.withHomeRequestAdmission(expectedEpoch, stillOwned) { Unit }

    internal fun updateHomeNavIdentity(expectedEpoch: Long, expectedMid: Long?, navMid: Long?, isVip: Boolean,
        onAuthenticationInvalidated: (Long, Long) -> Unit): Boolean =
        sessions.updateHomeNavIdentity(expectedEpoch, expectedMid, navMid, isVip, onAuthenticationInvalidated)


    /** All Profile reads/writes are admitted by THIS same Store, then Root's entry gate.
     * The callback must only commit short synchronous state/file actions, never HTTP/native join. */
    internal fun <T> withProfileAccountAdmission(expectedEpoch: Long, expectedMid: Long?,
        stillOwned: () -> Boolean, commitIfCurrent: ((() -> Unit) -> Boolean),
        action: DesktopSessionStore.() -> T): T {
        try {
            return sessions.withHomeRequestAdmission(expectedEpoch, stillOwned) {
                if (sessions.activeAccountMid() != expectedMid) throw CancellationException("Profile primary MID changed")
                var applied = false
                var value: Any? = null
                val admitted = commitIfCurrent {
                    if (sessions.generation != expectedEpoch || sessions.activeAccountMid() != expectedMid || !stillOwned())
                        throw CancellationException("Profile account entry retired")
                    value = action(sessions); applied = true
                }
                if (!admitted || !applied) throw CancellationException("Profile account entry rejected")
                @Suppress("UNCHECKED_CAST")
                value as T
            }
        } catch (error: BiliApiException) {
            throw CancellationException("Profile account epoch retired").also { it.initCause(error) }
        }
    }

    /** Root's existing authentication/cache reset, outside Store/entry monitors. */
    internal fun profileAuthenticationChanged() = resetAuthentication()

    /** Completion of this exact logout only: no mutation or admission under the new epoch. */
    internal fun verifyProfileLogoutTerminal(expectedEpoch: Long, acceptedEpoch: Long): Boolean = try {
        sessions.withHomeRequestAdmission(acceptedEpoch, { true }) {
            acceptedEpoch == expectedEpoch + 1L && sessions.activeAccountMid() == null && sessions.currentCookies()["SESSDATA"].isNullOrBlank()
        }
    } catch (_: BiliApiException) { false }

    internal val httpClient: OkHttpClient get() = client
    private val playbackClient by lazy { com.bilipai.desktop.network.DesktopNetworkProxyPlatform.buildPlaybackOkHttpClient(client) }
    internal val playbackHttpClient: OkHttpClient get() = playbackClient
    internal suspend fun ensureSession() = ensureVisitorSession()
    internal suspend fun signWebParams(params: Map<String, String>, includeRiskFingerprint: Boolean = false,
        forceRefresh: Boolean = false) = sign(params, includeRiskFingerprint, forceRefresh)
    internal suspend fun signPrimaryLiveWebParams(params: Map<String, String>, expectedEpoch: Long,
        stillOwned: () -> Boolean): Map<String, String> = sign(params,
        requestApi = ownedHomeService(BilibiliApi::class.java, "https://api.bilibili.com/", expectedEpoch, stillOwned),
        expectedEpoch = expectedEpoch, stillOwned = stillOwned)
    internal fun accessTokenCredentials(): Pair<String?, String> = sessions.accessTokenCredentials()
    internal fun appCredentials(): DesktopAppCredentials? = sessions.appCredentials()
    internal fun loginIdentityBuvid(): String = sessions.loginIdentityBuvid()
    internal fun saveLoginIdentityBuvid(value: String) = sessions.saveLoginIdentityBuvid(value)
    internal fun authCookies(): Map<String, String> = sessions.currentCookies().filterKeys { it in DesktopSessionStore.PERSISTED_COOKIE_NAMES }

    internal suspend fun installLogin(cookies: Map<String, String>, credentials: DesktopAppCredentials? = null,
        snapshot: DesktopSessionStore.CookieSnapshot? = null, expectedMid: Long? = null,
        expectedActiveMid: Long? = null, requestReceipt: DesktopPlaybackAuthorizationReceipt? = null,
        stillOwned: () -> Boolean = { true }, commitIfCurrent: ((() -> Unit) -> Boolean)? = null): AccountSummary = withContext(Dispatchers.IO) {
        authMutex.withLock {
            val epoch = requestReceipt?.accountEpoch ?: sessions.generation
            fun assertRequest() {
                if (requestReceipt != null) sessions.withPlaybackAuthorizationAdmission(requestReceipt, stillOwned) { Unit }
                else if (!stillOwned()) throw CancellationException("Login request retired")
            }
            currentCoroutineContext().ensureActive(); assertRequest()
            if (expectedActiveMid != null && account.value?.mid != expectedActiveMid) throw BiliApiException(-101, "账号已切换，请重新操作")
            val validationApi = if (requestReceipt == null) validationPassportApi else {
                // Same existing NO_COOKIES validation transport, tagged before newCall.
                val current = { stillOwned() && sessions.isPlaybackAuthorizationCurrent(requestReceipt) }
                validationPassportRetrofit.newBuilder().callFactory(playbackReceiptCalls(requestReceipt, current,
                    validationPassportRetrofit.callFactory())).build().create(PassportApi::class.java)
            }
            val summary = validateCookieHeader(cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }, validationApi)
            currentCoroutineContext().ensureActive(); assertRequest()
            if (expectedMid != null) require(summary.mid == expectedMid) { "登录返回的账号与验证结果不一致" }
            if (expectedActiveMid != null && account.value?.mid != expectedActiveMid) throw BiliApiException(-101, "账号已切换，请重新操作")
            if (epoch != sessions.generation) throw BiliApiException(-101, "账号已变化，请重新登录")
            if (requestReceipt == null) {
                resetAuthentication()
                sessions.saveAccount(cookies, summary, imported = true, credentials = credentials, preserveAccessToken = false, snapshot = snapshot)
            } else {
                val callerContext = currentCoroutineContext()
                sessions.withPlaybackAuthorizationAdmission(requestReceipt, stillOwned) {
                    val commit = requireNotNull(commitIfCurrent) { "Owned token install requires the Root entry gate" }
                    if (!commit {
                        callerContext.ensureActive()
                        if (!stillOwned()) throw CancellationException("Token install entry retired")
                        // Short synchronous cache/Store commit only. Do not cancel the
                        // shared dispatcher or stop any already-running native source.
                        resetAuthenticationCaches()
                        sessions.saveAccount(cookies, summary, imported = true, credentials = credentials,
                            preserveAccessToken = false, snapshot = snapshot)
                    }) throw CancellationException("Token install entry retired")
                }
            }
            summary
        }
    }

    suspend fun switchAccount(mid: Long): AccountSummary = withContext(Dispatchers.IO) {
        authMutex.withLock {
            val epoch = sessions.generation
            val header = sessions.accountCookieHeader(mid) ?: throw IllegalArgumentException("此账号已被移除，请重新登录")
            val summary = validateCookieHeader(header)
            require(summary.mid == mid) { "保存的账号会话与资料不一致，请重新登录" }
            if (epoch != sessions.generation) throw BiliApiException(-101, "账号已变化，请重新切换")
            currentCoroutineContext().ensureActive()
            resetAuthentication()
            check(sessions.activateAccount(mid, summary)) { "无法切换账号" }
            summary
        }
    }

    suspend fun removeSavedAccount(mid: Long) = withContext(Dispatchers.IO) { authMutex.withLock {
        if (account.value?.mid == mid) resetAuthentication()
        sessions.removeAccount(mid)
    } }

    private fun resetAuthentication() {
        client.dispatcher.cancelAll()
        resetAuthenticationCaches()
    }
    internal fun storagePlaybackMemoryBytes(): Long = playbackCache.estimatedBytes()
    internal fun clearStoragePlaybackCache(checkRequest: () -> Unit) {
        checkRequest();synchronized(playbackProtocolMonitor) { appApiCooldownUntilMs=0L;lastPlayback412Time=0L }
        playbackCache.clear();com.android.purebilibili.core.cooldown.PlaybackCooldownManager.clearAll();checkRequest()
    }
    /** Cache only. Visitor/session/cookies and selected playback authorization stay untouched. */
    internal fun invalidateStorageWbi(checkRequest: () -> Unit) {
        checkRequest();synchronized(playbackProtocolMonitor) { wbiKeys=null;wbiExpiresAt=0L;wbiGeneration=-1L };checkRequest()
    }

    private fun resetAuthenticationCaches() {
        visitorInitialized = false
        synchronized(playbackProtocolMonitor) {
            wbiKeys = null; wbiExpiresAt = 0L; wbiGeneration = -1L
            appApiCooldownUntilMs = 0L; lastPlayback412Time = 0L
        }
        playbackCache.clear()
    }

    internal fun requireAccount(): AccountSummary = account.value?.takeIf { it.mid > 0 }
        ?: throw BiliApiException(-101, "请先登录后查看账号内容")

    internal fun requireCsrf(): String {
        requireAccount()
        return sessions.currentCookies()["bili_jct"]?.takeIf { it.isNotBlank() }
            ?: throw BiliApiException(-101, "登录凭证缺少 CSRF，请重新登录后重试")
    }

    suspend fun cloudFavoriteFolders(): List<CloudFavoriteFolder> = withContext(Dispatchers.IO) {
        val loggedIn = requireAccount()
        ensureVisitorSession()
        val response = api.getFavFolders(loggedIn.mid)
        checkPersonalCode(response.code, response.message)
        val data = response.data ?: throw BiliApiException(-1, "云端收藏夹响应为空")
        data.list.orEmpty().filter { it.id > 0 }.distinctBy { it.id }.map {
            CloudFavoriteFolder(it.id, it.title, normalizeUrl(it.cover), it.media_count.coerceAtLeast(0))
        }
    }

    suspend fun cloudFavoriteVideos(folderId: Long, page: Int = 1): VideoPage = withContext(Dispatchers.IO) {
        require(folderId > 0 && page > 0)
        requireAccount()
        ensureVisitorSession()
        val response = api.getFavoriteList(mediaId = folderId, pn = page, ps = 20)
        checkPersonalCode(response.code, response.message)
        val data = response.data ?: throw BiliApiException(-1, "云端收藏内容响应为空")
        personalFavoritePage(data)
    }

    suspend fun cloudHistory(cursor: CloudHistoryCursor? = null): CloudHistoryPage = withContext(Dispatchers.IO) {
        require(cursor == null || cursor.max >= 0 && cursor.viewAt >= 0)
        requireAccount()
        ensureVisitorSession()
        val response = api.getHistoryList(ps = 30, max = cursor?.max?.takeIf { it > 0 },
            viewAt = cursor?.viewAt?.takeIf { it > 0 }, business = cursor?.business?.trim()?.takeIf { it.isNotBlank() }, type = "archive")
        checkPersonalCode(response.code, response.message)
        val data = response.data ?: throw BiliApiException(-1, "云端历史响应为空")
        personalHistoryPage(data, cursor)
    }

    suspend fun watchLater(page: Int = 1): VideoPage = withContext(Dispatchers.IO) {
        require(page > 0)
        requireAccount()
        ensureVisitorSession()
        val response = api.getWatchLaterPage(sign(personalWatchLaterParams(page)))
        checkPersonalCode(response.code, response.message)
        val data = response.data ?: throw BiliApiException(-1, "稍后再看响应为空")
        personalWatchLaterPage(data, page)
    }

    suspend fun followingVideos(offset: String = ""): FollowingVideoPage = withContext(Dispatchers.IO) {
        require(offset.length <= 256)
        requireAccount()
        ensureVisitorSession()
        val response = dynamicApi.getDynamicFeed(type = "video", offset = offset.trim())
        checkPersonalCode(response.code, response.message)
        val data = response.data ?: throw BiliApiException(-1, "关注视频动态响应为空")
        personalFollowingPage(data, offset.trim())
    }

    private fun checkPersonalCode(code: Int, message: String) {
        if (code == -101) throw BiliApiException(code, "登录已失效，请重新登录后重试")
        checkCode(code, message)
    }

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
            info.stat.view.toLong(), info.stat.like.toLong(), pages, authorMid = info.owner.mid, raw = info)
    }

    suspend fun playback(details: VideoDetails, pageIndex: Int = 0, quality: Int = 80,
        codecOverride: String? = null, forceRefresh: Boolean = false,
        playbackPreferences: PlayerPreferences = PlayerPreferences(), blockedVideoCodecs: Set<String> = emptySet()): PlaybackSource = withContext(Dispatchers.IO) {
        val epoch = sessions.generation
        val requestJob = currentCoroutineContext()[kotlinx.coroutines.Job]
        val owned = { requestJob?.isActive != false }
        ensureVisitorSession(epoch, owned)
        currentCoroutineContext().ensureActive()
        val authorization = capturePlaybackAuthorization(epoch, owned)
        val playbackApi = ownedPlaybackService(BilibiliApi::class.java, authorization, owned)
        val primaryNavApi = ownedHomeService(BilibiliApi::class.java, "https://api.bilibili.com/", epoch, owned)
        fun assertCurrent() = assertPlaybackAuthorization(authorization, owned)
        val part = details.pages.getOrNull(pageIndex) ?: throw IllegalArgumentException("视频分 P 不存在")
        val preferenceSnapshot = playbackPreferences.normalized()
        val blockedSnapshot = blockedVideoCodecs.toSet()
        val codec = normalizeCodecFamilyKey(codecOverride)
        require(codec == null || codec in setOf("avc1", "hev1", "av01")) { "不支持的视频编码" }
        val cacheKey = originalVideoCacheKey(authorization, details.bvid, part.cid, quality,
            preferenceSnapshot, codec, blockedSnapshot, av1Supported = true)
        val cached = sessions.withPlaybackAuthorizationAdmission(authorization.receipt, owned) {
            if (forceRefresh) { playbackCache.invalidateVideo(epoch, details.bvid, part.cid); null } else playbackCache.get(cacheKey)
        }
        cached?.let { cached ->
            cached.data.toPlaybackSource(details, cached.selectionQuality, codec, preferenceSnapshot, blockedSnapshot)?.let {
                assertCurrent()
                return@withContext it.copy(authorizationReceipt = authorization.receipt)
            }
        }
        fun selected(data: PlayUrlData?, targetQuality: Int): PlaybackSource? {
            val source = data?.toPlaybackSource(details, targetQuality, codec, preferenceSnapshot, blockedSnapshot) ?: return null
            sessions.withPlaybackAuthorizationAdmission(authorization.receipt, owned) { playbackCache.put(cacheKey, requireNotNull(data), targetQuality) }
            return source.copy(authorizationReceipt = authorization.receipt)
        }
        var lastError: Exception? = null
        var refreshSignature = forceRefresh
        for ((index, targetQuality) in buildDashAttemptQualities(quality).withIndex()) {
            for (retryDelay in resolveDashRetryDelays(targetQuality, isPrimaryAttempt = index == 0)) {
                if (retryDelay > 0) delay(retryDelay)
                assertCurrent()
                try {
                    val params = sign(buildPlayUrlWbiBaseParams(details.bvid, part.cid, targetQuality), forceRefresh = refreshSignature, requestApi = primaryNavApi, expectedEpoch = epoch, stillOwned = owned)
                    assertCurrent()
                    refreshSignature = false
                    val response = playbackApi.getPlayUrl(params)
                    assertCurrent()
                    checkCode(response.code, response.message)
                    selected(response.data, targetQuality)?.let { return@withContext it }
                    lastError = BiliApiException(-1, "播放接口未返回可用媒体流")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    assertCurrent()
                    lastError = error
                    // A rejected session/rate limit must not trigger a rapid quality retry chain.
                    if (error is BiliApiException && error.apiCode in setOf(-101, -352, -412, 412, 429)) throw error
                    if (error is HttpException && error.code() in setOf(429, 412)) throw error
                }
            }
        }
        try {
            assertCurrent()
            val response = playbackApi.getPlayUrlLegacy(details.bvid, part.cid, qn = quality)
            assertCurrent()
            checkCode(response.code, response.message)
            selected(response.data, quality)?.let { return@withContext it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            assertCurrent()
            lastError = error
        }
        throw lastError ?: BiliApiException(-1, "此视频暂无可用播放地址")
    }

    suspend fun related(bvid: String): List<VideoCard> = withContext(Dispatchers.IO) {
        ensureVisitorSession()
        api.getRelatedVideos(bvid).data.orEmpty().map {
            VideoCard(it.bvid, it.title, normalizeUrl(it.pic), it.owner.name, it.stat.view.toLong(), it.duration, authorMid = it.owner.mid)
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

    private val webLogin by lazy { DesktopLoginRepository(this) }
    /** Borrow the same existing login/refresh authority for the original owner. */
    internal val originalVideoLogin: DesktopLoginRepository get() = webLogin
    suspend fun beginQrLogin(): QrLogin = webLogin.beginWebQr()
    suspend fun pollQrLogin(key: String): QrLoginState = webLogin.pollWebQr(key)

    suspend fun importCookies(raw: String): AccountSummary = withContext(Dispatchers.IO) {
        val parsed = parseLoginCookieHeader(raw) ?: throw IllegalArgumentException("Cookie 中缺少 SESSDATA")
        installLogin(parsed.values)
    }

    suspend fun refreshAccount(): AccountSummary? = withContext(Dispatchers.IO) {
        authMutex.withLock {
            val cookies = sessions.currentCookies()
            if (cookies["SESSDATA"].isNullOrBlank()) return@withLock null
            val epoch = sessions.generation
            try {
                val summary = validateCookieHeader(cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                if (epoch != sessions.generation) throw BiliApiException(-101, "账号已切换，请重新加载")
                sessions.saveAccount(cookies, summary)
                summary
            } catch (error: BiliApiException) {
                if (error.apiCode != -101 || epoch != sessions.generation) throw error
                resetAuthentication(); sessions.logout(); null
            }
        }
    }

    fun logout() {
        resetAuthentication()
        sessions.logout()
    }

    /** AUTH Home nav invalidation: account mutation under the SAME Store admission only.
     * The caller drains its event outside the Store callback; HTTP cancellation occurs after
     * admission releases the Store monitor. Retired/foreign events never log out an account. */
    internal fun logoutHomeAuthenticationInvalidated(expectedEpoch: Long, expectedMid: Long,
        stillOwned: () -> Boolean): Boolean {
        val applied = try {
            sessions.withHomeRequestAdmission(expectedEpoch, stillOwned) {
                if (expectedMid <= 0L || sessions.account.value?.mid != expectedMid) false
                else { sessions.logout(); true }
            }
        } catch (failure: BiliApiException) {
            if (failure.apiCode == -101) return false
            throw failure
        }
        if (applied) resetAuthentication()
        return applied
    }

    private suspend fun ensureVisitorSession(expectedEpoch: Long? = null,
        stillOwned: () -> Boolean = { true }, callFactory: okhttp3.Call.Factory = client,
        visitorApi: BuvidApi = buvidApi) = visitorMutex.withLock {
        val generation = expectedEpoch ?: sessions.generation
        if (generation != sessions.generation || !stillOwned()) throw CancellationException("Visitor owner retired")
        if (visitorInitialized && visitorGeneration == generation) return@withLock
        // Standard visitor bootstrap, once per process. It does not attempt to bypass a security challenge.
        callFactory.newCall(Request.Builder().url("https://www.bilibili.com/")
            .header("Accept", "text/html,application/xhtml+xml").build()).execute().use { response ->
            if (!response.isSuccessful) throw BiliApiException(response.code, "无法初始化 B 站访客会话 (${response.code})")
        }
        val response = visitorApi.getSpi()
        checkCode(response.code, "无法获取 B 站访客标识")
        val visitors = mutableMapOf<String, String>()
        response.data?.let { data ->
            data.b_3.takeIf { it.isNotBlank() }?.let { visitors["buvid3"] = it }
            data.b_4.takeIf { it.isNotBlank() }?.let { visitors["buvid4"] = it }
        }
        if (generation != sessions.generation || !stillOwned()) throw CancellationException("Visitor owner retired")
        sessions.saveSpiCookies(visitors, generation)
        visitorGeneration = generation
        visitorInitialized = true
    }

    private suspend fun validateCookieHeader(header: String, validationApi: PassportApi = validationPassportApi): AccountSummary {
        require(!header.contains('\n') && !header.contains('\r')) { "Cookie 格式不正确" }
        val response = validationApi.validateCookieSession(header)
        checkCode(response.code, "Cookie 已失效，请重新登录")
        val nav = response.data?.takeIf { it.isLogin } ?: throw BiliApiException(-101, "Cookie 已失效，请重新登录")
        return nav.toAccount()
    }

    private suspend fun sign(params: Map<String, String>, includeRiskFingerprint: Boolean = false,
        forceRefresh: Boolean = false, requestApi: BilibiliApi = api, expectedEpoch: Long? = null,
        stillOwned: () -> Boolean = { true }): Map<String, String> {
        val keys = wbiMutex.withLock {
            val generation = expectedEpoch ?: sessions.generation
            val now = System.currentTimeMillis()
            val cached = sessions.withHomeRequestAdmission(generation, stillOwned) { synchronized(playbackProtocolMonitor) {
                resetVideoProtocolGenerationLocked(generation)
                wbiKeys?.takeIf { !forceRefresh && now < wbiExpiresAt }
            } }
            cached ?: run {
                // Nav fetch remains outside Store/entry/protocol monitor; anonymous -101 can carry keys.
                val img = requestApi.getNavInfo().data?.wbi_img ?: throw BiliApiException(-1, "无法获取接口签名信息")
                val imageKey = img.img_url.substringAfterLast('/').substringBefore('.')
                val subKey = img.sub_url.substringAfterLast('/').substringBefore('.')
                require(imageKey.length == 32 && subKey.length == 32) { "接口签名信息格式异常" }
                sessions.withHomeRequestAdmission(generation, stillOwned) { synchronized(playbackProtocolMonitor) {
                    resetVideoProtocolGenerationLocked(generation)
                    wbiKeys = imageKey to subKey
                    wbiExpiresAt = System.currentTimeMillis() + originalVideoWbiCacheDurationMs
                    requireNotNull(wbiKeys)
                } }
            }
        }
        return WbiUtils.sign(params, keys.first, keys.second, includeRiskFingerprint = includeRiskFingerprint)
    }

    internal fun PlayUrlData.toPlaybackSource(details: VideoDetails, requestedQuality: Int, codecOverride: String? = null,
        playbackPreferences: PlayerPreferences = PlayerPreferences(), blockedVideoCodecs: Set<String> = emptySet()): PlaybackSource? {
        val referer = "https://www.bilibili.com/video/${details.bvid}"
        val qualities = acceptQuality.mapIndexed { index, id -> PlaybackQuality(id, acceptDescription.getOrNull(index) ?: id.toString()) }
        dash?.let { streams ->
            val selection = resolveDesktopDashSelection(streams, requestedQuality, playbackPreferences, codecOverride, blockedVideoCodecs)
            val video = selection.video
            val audio = selection.audio.selected?.track
            val videoUrl = video?.getValidUrl()?.takeIf { it.isNotBlank() }
            if (videoUrl != null) return PlaybackSource(normalizeUrl(videoUrl),
                audio?.getValidUrl()?.takeIf { it.isNotBlank() }?.let(::normalizeUrl), details.title, referer,
                quality = video?.id ?: quality, availableQualities = qualities,
                videoAlternatives = video?.backupUrl.orEmpty().filter(String::isNotBlank).map(::normalizeUrl),
                audioAlternatives = audio?.backupUrl.orEmpty().filter(String::isNotBlank).map(::normalizeUrl),
                videoCodecFamily = resolvePlaybackVideoCodec(videoUrl, streams.video), cachedDashData = streams, audioSelection = selection.audio)
        }
        val progressive = durl.orEmpty()
        val progressiveUrls = collectPlayableDurlUrls(progressive)
        // Preserve the upstream sequence, including each duration. Missing segments must not
        // silently turn a complete video into a truncated first segment.
        if (progressive.isNotEmpty() && progressiveUrls.size == progressive.size) {
            val urls = progressiveUrls.map { normalizeUrl(it).toHttpUrlOrNull()?.toString() }
            if (urls.any { it == null }) return null
            val parts = progressive.zip(urls).map { (segment, url) ->
                PlaybackSegment(requireNotNull(url), segment.length.takeIf { it > 0 }?.div(1000.0))
            }
            return PlaybackSource(parts.first().url, null, details.title, referer, quality = quality,
                availableQualities = qualities,
                videoAlternatives = if (parts.size == 1) progressive.first().backupUrl.orEmpty()
                    .mapNotNull { normalizeUrl(it).toHttpUrlOrNull()?.toString() } else emptyList(),
                progressiveSegments = if (parts.size > 1) parts else emptyList())
        }
        return null
    }

    private fun VideoItem.toCard() = VideoCard(bvid, title, normalizeUrl(pic), owner.name, stat.view.toLong(), duration, authorMid = owner.mid)
    private fun NavData.toAccount() = AccountSummary(mid, uname, normalizeUrl(face), vip.status == 1)

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val QR_ACCOUNT_COOKIE_NAMES = setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5")

        internal fun resolvePlatformUserAgent(url: HttpUrl, explicit: String?): String = when {
            !explicit.isNullOrBlank() -> explicit
            resolveAndroidHdLoginAppKeyHeader(url.encodedPath) != null || isDesktopMergedFeedRequest(url) -> "Mozilla/5.0 BiliDroid/2.0.1 (bbcallen@gmail.com) os/android model/android_hd mobi_app/android_hd build/2001100 channel/master innerVer/2001100 osVer/15 network/2"
            url.host == "app.bilibili.com" -> "Mozilla/5.0 BiliDroid/8.43.0 (bbcallen@gmail.com) os/android model/android mobi_app/android build/8430300 channel/master innerVer/8430300 osVer/15 network/2"
            else -> USER_AGENT
        }

        /** Authorize only the current QR response; stored credentials can belong to a different account. */
        internal fun resolveQrLoginCookies(
            responseUrl: HttpUrl,
            setCookieHeaders: List<String>,
            successUrl: String?,
            previousJar: Map<String, String>,
        ): Map<String, String> {
            require(responseUrl.scheme == "https" && DesktopSessionStore.isBilibiliHost(responseUrl.host)) { "二维码登录响应来源无效" }
            val allowedNames = DesktopSessionStore.PERSISTED_COOKIE_NAMES
            val resolved = linkedMapOf<String, String>()
            val now = System.currentTimeMillis()
            setCookieHeaders.forEach { header ->
                Cookie.parse(responseUrl, header)?.takeIf { cookie ->
                    cookie.name in allowedNames && cookie.value.isNotBlank() && cookie.expiresAt > now &&
                        DesktopSessionStore.isBilibiliHost(cookie.domain)
                }?.let { cookie -> resolved[cookie.name] = cookie.value }
            }
            // HttpUrl decodes the URL query once. It must never overwrite literal Set-Cookie values.
            successUrl?.toHttpUrlOrNull()?.takeIf {
                it.scheme == "https" && DesktopSessionStore.isBilibiliHost(it.host)
            }?.let { url ->
                allowedNames.forEach { name ->
                    url.queryParameter(name)?.takeIf { it.isNotBlank() }?.let { resolved.putIfAbsent(name, it) }
                }
            }
            previousJar.filterKeys { it in allowedNames && it !in QR_ACCOUNT_COOKIE_NAMES }.forEach { (name, value) ->
                if (value.isNotBlank()) resolved.putIfAbsent(name, value)
            }
            return resolved
        }

        // Mirror upstream ApiClient: explicit headers win, WBI omits Referer, video APIs use the video page.
        internal fun resolveReferer(url: HttpUrl, explicit: String?): String? {
            explicit?.takeIf { it.isNotBlank() }?.let { return it }
            if (url.encodedPath.contains("/wbi/") || url.host == "app.bilibili.com" ||
                resolveAndroidHdLoginAppKeyHeader(url.encodedPath) != null) return null
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

package com.bilipai.desktop.data

import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.login.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Candidate credentials live in a separate jar until explicit nav validation succeeds. */
class DesktopLoginRepository(private val repository: DesktopRepository,
    private val expectedLoginEpoch: Long? = null,
    private val loginStillOwned: () -> Boolean = { true },
    private val onLoginInstalled: ((DesktopLoginInstallationReceipt) -> Unit)? = null,
) {
    private val candidate = DesktopSessionStore.temporary()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val lock = Mutex()
    private val identity = processLoginIdentity.let { generated ->
        val stored = repository.loginIdentityBuvid().takeIf { it.matches(Regex("XY[0-9a-f]{35}")) }
        generated.copy(buvid = stored ?: generated.buvid.also(repository::saveLoginIdentityBuvid))
    }
    internal val captchaClient = repository.httpClient.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES).build()
    private val client = repository.httpClient.newBuilder().cookieJar(candidate).retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            val original = chain.request()
            val appKey = resolveAndroidHdLoginAppKeyHeader(original.url.encodedPath)
            if (appKey == null) chain.proceed(original)
            else {
                // These transport fields are the original ApiClient Android-HD Passport profile.
                val request = original.newBuilder().removeHeader("Origin")
                    .header("User-Agent", "Mozilla/5.0 BiliDroid/2.0.1 (bbcallen@gmail.com) os/android model/android_hd mobi_app/android_hd build/2001100 channel/master innerVer/2001100 osVer/15 network/2")
                    .header("app-key", appKey).header("buvid", identity.buvid).removeHeader("X-BiliPai-Login-Buvid")
                    .header("bili-http-engine", "cronet").header("env", "prod")
                    .header("x-bili-trace-id", "11111111111111111111111111111111:1111111111111111:0:0")
                    .header("x-bili-aurora-eid", "").header("x-bili-aurora-zone", "")
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8").build()
                chain.proceed(request)
            }
        }.build()
    private val api = Retrofit.Builder().baseUrl("https://passport.bilibili.com/").client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(PassportApi::class.java)

    suspend fun phoneRegions(): List<PhoneRegion> = action {
        val response = api.getCountryList(); check(response.code, response.message)
        response.data?.let(::mapPassportCountryListToPhoneRegions)?.takeIf { it.isNotEmpty() }
            ?: throw BiliApiException(-1, "国家地区列表为空")
    }

    suspend fun captcha(): CaptchaData = action { val response = api.getCaptcha(); check(response.code, response.message)
        response.data ?: throw BiliApiException(-1, "验证参数为空") }

    suspend fun beginTvQr(): QrLogin = action {
        candidate.logout()
        val response = api.generateTvQrCode(tvParams())
        check(response.code, response.message)
        val data = response.data ?: throw BiliApiException(-1, "TV 二维码响应为空")
        QrLogin(data.authCode?.takeIf(String::isNotBlank) ?: throw BiliApiException(-1, "TV 二维码缺少标识"),
            data.url?.takeIf(String::isNotBlank) ?: throw BiliApiException(-1, "TV 二维码链接为空"))
    }

    suspend fun pollTvQr(key: String): QrLoginState = action {
        require(key.isNotBlank())
        val response = api.pollTvQrCode(tvParams(key))
        when (response.code) {
            86039 -> QrLoginState.Waiting; 86090 -> QrLoginState.Scanned; 86038 -> QrLoginState.Expired
            0 -> {
                val data = response.data ?: throw BiliApiException(-1, "TV 登录结果为空")
                val cookies = data.cookieInfo?.cookies.orEmpty().associate { it.name to it.value }.filterKeys { it in DesktopSessionStore.PERSISTED_COOKIE_NAMES }
                requireLoginCookies(cookies)
                val credentials = data.accessToken.takeIf { it.isNotBlank() }?.let { DesktopAppCredentials(it, data.refreshToken, "tv",
                    expiry(data.expiresIn)) }
                QrLoginState.Complete(installFormLogin(cookies, credentials, expectedMid = data.mid.takeIf { it > 0 }))
            }
            else -> throw BiliApiException(response.code, response.message.ifBlank { "TV 扫码请求失败" })
        }
    }

    suspend fun beginWebQr(): QrLogin = action {
        candidate.logout(); val response = api.generateQrCode(); check(response.code, response.message)
        val data = response.data ?: throw BiliApiException(-1, "二维码响应为空")
        QrLogin(data.qrcode_key?.takeIf(String::isNotBlank) ?: throw BiliApiException(-1, "二维码缺少标识"),
            data.url?.takeIf(String::isNotBlank) ?: throw BiliApiException(-1, "二维码链接为空"))
    }

    suspend fun pollWebQr(key: String): QrLoginState = action {
        val response = api.pollQrCode(key); checkHttp(response)
        val body = response.body() ?: throw BiliApiException(-1, "二维码状态为空")
        check(body.code, body.message); val data = body.data ?: throw BiliApiException(-1, "二维码状态为空")
        when (data.code) {
            86101 -> QrLoginState.Waiting; 86090 -> QrLoginState.Scanned; 86038 -> QrLoginState.Expired
            0 -> { val cookies = DesktopRepository.resolveQrLoginCookies(response.raw().request.url,
                response.headers().values("Set-Cookie"), data.url, candidate.currentCookies())
                QrLoginState.Complete(installFormLogin(cookies)) }
            else -> throw BiliApiException(data.code, data.message)
        }
    }

    suspend fun sendSms(phone: String, region: PhoneRegion, captcha: CaptchaData, result: DesktopCaptchaResult): DesktopSmsResult {
        require(isPhoneDigitsValidForRegion(phone, region)) { "手机号格式不正确" }
        requireCaptchaResult(captcha, result)
        return action {
            val timestamp = System.currentTimeMillis()
            val cid = resolveSmsApiCid(region)
            val params = buildAndroidSmsSendParams(phone, cid, captcha.token, result.challenge, result.validate, result.seccode,
                identity.buvid, AppSignUtils.createLoginSessionId(identity.buvid, timestamp), timestamp / 1000)
            val response = api.sendSmsCodeByApp(identity.buvid, AppSignUtils.signForAndroidHdLogin(params))
            val replacement = response.data?.recaptchaUrl?.takeIf(String::isNotBlank)?.let(::parseLoginRecaptchaUrl)
            if (replacement != null) DesktopSmsResult.CaptchaRequired(replacement)
            else if (response.code == -105 || response.code == 0 && response.data?.captchaKey.isNullOrBlank()) {
                val fallback = api.getCaptcha(source = "main_web"); check(fallback.code, fallback.message)
                DesktopSmsResult.CaptchaRequired(fallback.data ?: throw BiliApiException(-1, "验证参数为空"))
            } else {
                check(response.code, response.message)
                val key = response.data?.captchaKey?.takeIf(String::isNotBlank) ?: throw BiliApiException(-1, "短信响应缺少验证码标识")
                DesktopSmsResult.Sent(DesktopSmsSession(phone, cid, key))
            }
        }
    }

    suspend fun loginSms(session: DesktopSmsSession, code: String): DesktopLoginResult {
        require(code.matches(Regex("[0-9]{4,8}"))) { "请输入短信验证码" }
        return action {
            val key = webKey()
            val deviceToken = RsaEncryption.encrypt(createPiliPlusRandomString(16), key.key) ?: throw BiliApiException(-1, "无法加密登录设备凭据")
            val params = buildAndroidSmsLoginParams(session.phone, session.countryCode, code.toInt(), session.captchaKey,
                identity.buvid, identity.deviceId, deviceToken, AppSignUtils.getTimestamp())
            acceptAppResponse(api.loginBySmsApp(identity.buvid, AppSignUtils.signForAndroidHdLogin(params)))
        }
    }

    suspend fun loginPassword(username: String, password: String, captcha: CaptchaData? = null,
        result: DesktopCaptchaResult? = null): DesktopLoginResult {
        require(username.isNotBlank() && password.isNotBlank()) { "请输入账号和密码" }
        if (captcha != null) requireCaptchaResult(captcha, requireNotNull(result) { "请先完成安全验证" })
        return action {
            val key = webKey()
            val encrypted = RsaEncryption.encryptPassword(password, key.key, key.hash) ?: throw BiliApiException(-1, "密码加密失败")
            val deviceToken = RsaEncryption.encrypt(createPiliPlusRandomString(16), key.key) ?: throw BiliApiException(-1, "无法加密登录设备凭据")
            val params = buildAndroidPasswordLoginParams(username.trim(), encrypted, captcha?.token, result?.challenge, result?.validate, result?.seccode,
                identity.buvid, identity.deviceId, deviceToken, AppSignUtils.getTimestamp())
            acceptAppResponse(api.loginByPasswordApp(identity.buvid, AppSignUtils.signForAndroidHdLogin(params)))
        }
    }

    suspend fun riskCaptcha(risk: DesktopLoginResult.RiskRequired): CaptchaData = action {
        requireBiliRiskUrl(risk.params.refererUrl)
        val response = api.safeCenterPreCapture(); check(response.code, response.message)
        val data = response.data ?: throw BiliApiException(-1, "安全中心验证参数为空")
        CaptchaData(data.recaptchaToken, GeetestData(data.geeGt, data.geeChallenge), type = "geetest")
    }

    suspend fun sendRiskSms(risk: DesktopLoginResult.RiskRequired, captcha: CaptchaData, result: DesktopCaptchaResult): String {
        requireBiliRiskUrl(risk.params.refererUrl); requireCaptchaResult(captcha, result)
        return action {
            val params = buildSafeCenterSmsSendParams(risk.params.tmpToken, captcha.token, result.challenge, result.validate, result.seccode)
            val response = api.safeCenterSendSms(risk.params.refererUrl, AppSignUtils.signForAndroidHdLogin(params))
            check(response.code, response.message)
            response.data?.captchaKey?.takeIf(String::isNotBlank) ?: throw BiliApiException(-1, "安全中心短信标识为空")
        }
    }

    suspend fun verifyRiskSms(risk: DesktopLoginResult.RiskRequired, captchaKey: String, code: String): DesktopLoginResult {
        requireBiliRiskUrl(risk.params.refererUrl); require(captchaKey.isNotBlank() && code.matches(Regex("[0-9]{4,8}")))
        return action {
            val params = buildSafeCenterSmsVerifyParams(code, risk.params.tmpToken, risk.params.requestId, risk.params.source, captchaKey)
            val verified = api.safeCenterVerifySms(risk.params.refererUrl, AppSignUtils.signForAndroidHdLogin(params)); check(verified.code, verified.message)
            val exchange = verified.data?.code?.takeIf(String::isNotBlank) ?: throw BiliApiException(-1, "安全中心未返回授权结果")
            acceptAppResponse(api.oauth2AccessToken(AppSignUtils.signForAndroidHdLogin(buildOauth2AccessTokenParams(exchange, identity.buvid, AppSignUtils.getTimestamp()))))
        }
    }

    suspend fun refreshTvToken(): AccountSummary = action { refreshTvTokenBody(api) }

    /** Original TokenRefreshHelper availability, projected from the SAME primary Store.
     * Caller invokes this inside its existing receipt admission. */
    internal fun originalPlaybackTokenRefreshAvailable(): Boolean = repository.appCredentials()?.let {
        it.platform == "tv" && it.refreshToken.isNotBlank()
    } == true

    /** The sole login actor, with caller Job + Root entry + captured authorization.
     * Success is terminal: the new credentials invalidate the supplied old receipt. */
    internal suspend fun refreshTvTokenForOriginalPlayback(receipt: DesktopPlaybackAuthorizationReceipt,
        stillOwned: () -> Boolean, commitIfCurrent: ((() -> Unit) -> Boolean)): Boolean {
        val requestJob = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
        val alive = { requestJob.isActive && stillOwned() }
        return try {
            action {
                repository.withPlaybackReceiptAdmission(receipt, alive) {
                    val authorization = repository.capturePlaybackAuthorization(receipt.accountEpoch, alive)
                    if (authorization.playbackAccount != null) throw kotlinx.coroutines.CancellationException("Selected playback account does not refresh primary token")
                }
                if (!repository.withPlaybackReceiptAdmission(receipt, alive) { originalPlaybackTokenRefreshAvailable() }) return@action false
                val current = { alive() && repository.isPlaybackReceiptCurrent(receipt) }
                val ownedApi = repository.ownedHomeService(PassportApi::class.java, "https://passport.bilibili.com/", receipt.accountEpoch, current)
                refreshTvTokenBody(ownedApi, receipt, alive, commitIfCurrent)
                // Do not assert the OLD receipt after successful replacement. The raw
                // binding will cancel its old load and Root will capture a new one.
                true
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            // Transport may wrap a retired owner in IOException. Cancellation must
            // not become ordinary false and continue an obsolete fallback request.
            repository.withPlaybackReceiptAdmission(receipt, alive) { Unit }
            false // Original TokenRefreshHelper ordinary failure on a CURRENT owner.
        }
    }

    private suspend fun refreshTvTokenBody(requestApi: PassportApi, receipt: DesktopPlaybackAuthorizationReceipt? = null,
        stillOwned: () -> Boolean = { true }, commitIfCurrent: ((() -> Unit) -> Boolean)? = null): AccountSummary {
        val uiCaller = kotlinx.coroutines.currentCoroutineContext()
        fun <T> uiAdmitted(block: () -> T): T = if (expectedLoginEpoch == null) block()
            else repository.withPrimaryPlaybackAdmission(expectedLoginEpoch, { uiCaller.ensureActive(); loginStillOwned() }, block)
        val account = if (receipt == null) uiAdmitted { repository.requireAccount() } else repository.withPlaybackReceiptAdmission(receipt, stillOwned) { repository.requireAccount() }
        val cookies = if (receipt == null) uiAdmitted { repository.authCookies() } else repository.withPlaybackReceiptAdmission(receipt, stillOwned) { repository.authCookies() }
        val credentials = (if (receipt == null) uiAdmitted { repository.appCredentials() } else repository.withPlaybackReceiptAdmission(receipt, stillOwned) { repository.appCredentials() })
            ?: throw BiliApiException(-101, "此账号没有 App 授权，请重新扫码")
        require(credentials.platform == "tv" && credentials.refreshToken.isNotBlank()) { "此授权不支持 TV 刷新，请重新登录" }
        val params = mapOf("access_key" to credentials.accessToken, "refresh_token" to credentials.refreshToken,
            "appkey" to AppSignUtils.TV_APP_KEY, "ts" to AppSignUtils.getTimestamp().toString())
        val response = requestApi.refreshToken(AppSignUtils.signForTvLogin(params)); check(response.code, response.message)
        if (receipt != null) repository.withPlaybackReceiptAdmission(receipt, stillOwned) { Unit }
        else uiAdmitted { Unit }
        val data = response.data ?: throw BiliApiException(-1, "授权刷新结果为空")
        require(data.accessToken.isNotBlank()) { "授权刷新没有返回 token" }
        val updated = cookies + data.cookieInfo?.cookies.orEmpty().associate { it.name to it.value }.filterKeys { it in DesktopSessionStore.PERSISTED_COOKIE_NAMES }
        if (receipt == null && (expectedLoginEpoch != null || onLoginInstalled != null))
            return repository.installLogin(updated, DesktopAppCredentials(data.accessToken, data.refreshToken, "tv", expiry(data.expiresIn)),
                expectedMid = account.mid, expectedActiveMid = account.mid, stillOwned = loginStillOwned,
                expectedLoginEpoch = expectedLoginEpoch, onLoginInstalled = onLoginInstalled)
        return repository.installLogin(updated, DesktopAppCredentials(data.accessToken, data.refreshToken, "tv", expiry(data.expiresIn)),
            expectedMid = account.mid, expectedActiveMid = account.mid, requestReceipt = receipt, stillOwned = stillOwned, commitIfCurrent = commitIfCurrent)
    }

    private suspend fun installFormLogin(cookies: Map<String, String>, credentials: DesktopAppCredentials? = null,
        expectedMid: Long? = null): AccountSummary = repository.installLogin(cookies, credentials,
        candidate.cookieSnapshot(), expectedMid = expectedMid, stillOwned = loginStillOwned,
        expectedLoginEpoch = expectedLoginEpoch, onLoginInstalled = onLoginInstalled)

    private suspend fun webKey(): WebKeyData {
        val response = api.getWebKey(); check(response.code, response.message)
        return response.data?.takeIf { it.key.isNotBlank() } ?: throw BiliApiException(-1, "登录密钥为空")
    }

    private suspend fun acceptAppResponse(response: Response<LoginResponse>): DesktopLoginResult {
        checkHttp(response)
        val body = response.body() ?: throw BiliApiException(-1, "登录响应为空")
        if (body.code == -105) {
            val captcha = parseLoginRecaptchaUrl(body.data?.url.orEmpty()) ?: throw BiliApiException(-105, "安全验证参数不完整，请重新登录")
            return DesktopLoginResult.CaptchaRequired(captcha)
        }
        check(body.code, body.message)
        if (isPasswordLoginRiskChallenge(body.data)) {
            val params = parseRiskVerifyUrl(body.data!!.url) ?: throw BiliApiException(-1, "登录需要手机验证，但参数不完整")
            requireBiliRiskUrl(params.refererUrl)
            val info = api.safeCenterGetInfo(params.tmpToken); check(info.code, info.message)
            val account = info.data?.accountInfo?.takeIf { it.telVerify } ?: throw BiliApiException(-1, "此账号不能通过手机验证，请使用扫码登录")
            return DesktopLoginResult.RiskRequired(params, account.hideTel, body.data.message)
        }
        val bodyCookies = body.data?.cookieInfo?.cookies.orEmpty().associate { it.name to it.value }
        val cookies = resolveAppLoginCookies(response.raw().request.url, response.headers().values("Set-Cookie"), bodyCookies)
        requireLoginCookies(cookies)
        val token = body.data?.tokenInfo
        val credentials = token?.accessToken?.takeIf(String::isNotBlank)?.let { DesktopAppCredentials(it,
            requireNotNull(token).refreshToken, "android") }
        return DesktopLoginResult.Complete(installFormLogin(cookies, credentials))
    }

    private fun tvParams(key: String? = null) = AppSignUtils.signForTvLogin(buildMap {
        put("appkey", AppSignUtils.TV_APP_KEY); put("local_id", "0"); put("ts", AppSignUtils.getTimestamp().toString()); key?.let { put("auth_code", it) }
    })
    private fun expiry(seconds: Long) = if (seconds > 0 && seconds < Long.MAX_VALUE / 1000 - System.currentTimeMillis() / 1000)
        System.currentTimeMillis() + seconds * 1000 else 0L
    private fun requireLoginCookies(cookies: Map<String, String>) { if (cookies["SESSDATA"].isNullOrBlank()) throw BiliApiException(-1, "登录未返回 Cookie，请使用扫码登录") }
    private fun check(code: Int, message: String) { if (code != 0) throw BiliApiException(code, message.ifBlank { "登录请求失败 ($code)" }) }
    private fun checkHttp(response: Response<*>) { if (!response.isSuccessful) throw BiliApiException(response.code(), "登录请求失败 (HTTP ${response.code()})") }
    private suspend fun <T> action(block: suspend () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }
}

// Upstream creates deviceId once per process and persists only the independent App buvid.
private val processLoginIdentity by lazy { createPiliPlusLoginIdentity() }

internal fun resolveAppLoginCookies(url: HttpUrl, headers: List<String>, bodyCookies: Map<String, String>): Map<String, String> {
    require(url.scheme == "https" && DesktopSessionStore.isBilibiliHost(url.host))
    val result = linkedMapOf<String, String>()
    headers.mapNotNull { Cookie.parse(url, it) }.filter { it.name in DesktopSessionStore.PERSISTED_COOKIE_NAMES && it.expiresAt > System.currentTimeMillis() }
        .forEach { result[it.name] = it.value }
    // App login's authorized cookie_info is the upstream primary source, with literal encoding preserved.
    bodyCookies.filterKeys { it in DesktopSessionStore.PERSISTED_COOKIE_NAMES }.filterValues(String::isNotBlank).forEach { (name, value) -> result[name] = value }
    return result
}

internal fun requireBiliRiskUrl(raw: String) {
    val url = raw.toHttpUrlOrNull()
    require(url != null && url.scheme == "https" && DesktopSessionStore.isBilibiliHost(url.host)) { "安全验证链接来源无效" }
}

internal fun requireCaptchaResult(captcha: CaptchaData, result: DesktopCaptchaResult) {
    require(captcha.token.isNotBlank() && captcha.geetest?.gt?.isNotBlank() == true && captcha.geetest?.challenge?.isNotBlank() == true) { "安全验证参数不完整" }
    require(result.challenge.isNotBlank() && result.validate.isNotBlank() && result.seccode.isNotBlank()) { "请完成当前安全验证" }
}

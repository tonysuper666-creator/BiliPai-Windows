package com.android.purebilibili.data.repository

import android.content.Context
import com.android.purebilibili.core.network.AppSignUtils
import com.android.purebilibili.core.network.NetworkModule
import com.android.purebilibili.core.store.AccountSessionStore
import com.android.purebilibili.core.store.TokenManager
import com.android.purebilibili.data.model.response.NavData
import com.android.purebilibili.data.model.response.TvPollResponse
import com.android.purebilibili.data.model.response.TvQrCodeResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SessionRepository {
    suspend fun account(): Result<NavData?> = dataRequest {
        val response = NetworkModule.api.getNavInfo()
        if (response.code == -101) return@dataRequest null
        if (response.code != 0) throw ContentRequestException(response.code, response.message)
        response.data?.takeIf { it.isLogin }
    }

    suspend fun save(
        context: Context, sessData: String, csrf: String = "", buvid3: String = "",
        mid: Long = 0, accessToken: String = "", refreshToken: String = "",
        accessTokenPlatform: String = TokenManager.ACCESS_TOKEN_PLATFORM_TV,
    ) = withContext(Dispatchers.IO) {
        require(sessData.isNotBlank()) { "登录数据缺少 SESSDATA" }
        TokenManager.saveCookies(context, sessData)
        if (csrf.isNotBlank()) TokenManager.saveCsrf(context, csrf)
        if (buvid3.isNotBlank()) TokenManager.saveBuvid3(context, buvid3)
        if (mid > 0L) TokenManager.saveMid(context, mid)
        if (accessToken.isNotBlank()) {
            TokenManager.saveAccessToken(context, accessToken, refreshToken, accessTokenPlatform)
        } else {
            TokenManager.clearAccessToken(context)
        }
    }

    suspend fun signOut(context: Context) = withContext(Dispatchers.IO) {
        TokenManager.clear(context)
        AccountSessionStore.clearActiveAccount(context)
        AccountSessionStore.setPlaybackAccountMid(context, null)
        NetworkModule.clearRuntimeCookies()
        NetworkModule.clearPlaybackAccountClient()
    }

    suspend fun completeQrLogin(context: Context, response: TvPollResponse) = withContext(Dispatchers.IO) {
        check(response.code == 0) { "扫码尚未确认" }
        val data = response.data ?: error("登录数据为空")
        val cookies = data.cookieInfo?.cookies.orEmpty().associate { it.name to it.value }
        NetworkModule.clearRuntimeCookies()
        save(context, cookies["SESSDATA"].orEmpty(), cookies["bili_jct"].orEmpty(),
            cookies["buvid3"].orEmpty(), data.mid, data.accessToken, data.refreshToken)
        AccountSessionStore.upsertCurrentAccount(context)
    }
}

/** The polling lifecycle belongs to the caller so refresh/back cancels it immediately. */
object QrLoginRepository {
    suspend fun generate(): TvQrCodeResponse = NetworkModule.passportApi.generateTvQrCode(
        AppSignUtils.signForTvLogin(mapOf(
            "appkey" to AppSignUtils.TV_APP_KEY, "local_id" to "0",
            "ts" to AppSignUtils.getTimestamp().toString(),
        )),
    )

    suspend fun poll(authCode: String): TvPollResponse = NetworkModule.passportApi.pollTvQrCode(
        AppSignUtils.signForTvLogin(mapOf(
            "appkey" to AppSignUtils.TV_APP_KEY, "auth_code" to authCode, "local_id" to "0",
            "ts" to AppSignUtils.getTimestamp().toString(),
        )),
    )
}

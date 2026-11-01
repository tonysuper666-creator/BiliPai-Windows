package com.bilipai.desktop.data

import com.android.purebilibili.core.network.BilibiliApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Public favorite-folder contents for the collection page use the active jar; account switching invalidates in-flight results. */
class DesktopSpaceRepository internal constructor(client: OkHttpClient,
    private val ensureSession: suspend () -> Unit,
    private val currentEpoch: () -> Long) {
    constructor(repository: DesktopRepository) : this(repository.httpClient, { repository.ensureSession() }, { repository.sessionEpoch })

    private val web = Retrofit.Builder().baseUrl("https://api.bilibili.com/")
        .client(client.newBuilder().retryOnConnectionFailure(false).build())
        .addConverterFactory(Json { ignoreUnknownKeys = true; coerceInputValues = true }
            .asConverterFactory("application/json".toMediaType())).build()
    private val api = web.create(BilibiliApi::class.java)

    /** Unlike the personal page, public folder reads do not require an account. The server enforces privacy. */
    suspend fun favoriteResources(folderId: Long, page: Int = 1): PersonalResourcePage<Int> = read({ require(folderId > 0 && page > 0) }) {
        val response = api.getFavoriteList(mediaId = folderId, pn = page, ps = 20)
        val data = verified(response.code, response.message, response.data, "收藏夹内容")
        val raw = data.medias.orEmpty()
        PersonalResourcePage(raw.map { PersonalResource.Favorite(it) }, nextSpacePage(page, data.has_more && raw.isNotEmpty()))
    }

    private suspend fun <T> read(validate: () -> Unit, action: suspend () -> T): T = withContext(Dispatchers.IO) {
        validate()
        val expected = currentEpoch()
        fun checkEpoch() { if (currentEpoch() != expected) throw BiliApiException(-101, "账号已切换，请重新加载") }
        ensureSession()
        checkEpoch()
        action().also { checkEpoch() }
    }

    private fun check(code: Int, message: String) {
        if (code != 0) throw BiliApiException(code, if (code in setOf(-101, -111)) "登录凭证已失效，请重新登录后重试"
            else message.ifBlank { "请求失败 ($code)" })
    }
    private fun <T : Any> verified(code: Int, message: String, data: T?, label: String): T {
        check(code, message)
        return data ?: throw BiliApiException(-1, "$label 数据为空")
    }
}

internal fun nextSpacePage(page: Int, more: Boolean): Int? = (page + 1).takeIf { page < Int.MAX_VALUE && more }

/** Original navigation debug messages contain full URLs; the desktop binding keeps them private. */
object DesktopSpaceRouteLog {
    fun d(tag: String, message: String) = Unit
    fun e(tag: String, message: String) = Unit
}

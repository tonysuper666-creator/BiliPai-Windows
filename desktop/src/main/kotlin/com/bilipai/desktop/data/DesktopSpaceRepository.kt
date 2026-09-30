package com.bilipai.desktop.data

import com.android.purebilibili.core.network.AppSignUtils
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.SpaceApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.space.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Public UP-space reads use the active jar; account switching invalidates in-flight results. */
class DesktopSpaceRepository internal constructor(client: OkHttpClient,
    private val ensureSession: suspend () -> Unit,
    private val sign: suspend (Map<String, String>) -> Map<String, String>,
    private val currentEpoch: () -> Long) {
    constructor(repository: DesktopRepository) : this(repository.httpClient, { repository.ensureSession() },
        { repository.signWebParams(it) }, { repository.sessionEpoch })

    private val web = Retrofit.Builder().baseUrl("https://api.bilibili.com/")
        .client(client.newBuilder().retryOnConnectionFailure(false).build())
        .addConverterFactory(Json { ignoreUnknownKeys = true; coerceInputValues = true }
            .asConverterFactory("application/json".toMediaType())).build()
    private val api = web.create(BilibiliApi::class.java)
    private val space = web.create(SpaceApi::class.java)

    suspend fun createdFavorites(mid: Long): List<FavFolder> = read({ require(mid > 0) }) {
        val response = api.getFavFolders(mid)
        check(response.code, response.message)
        resolveSpaceFavoriteFoldersForDisplay(response.data?.list.orEmpty().map { it.copy(source = FavFolderSource.OWNED) })
    }

    suspend fun collectedFavorites(mid: Long, page: Int = 1): DesktopSpaceFolderPage = read({ require(mid > 0 && page > 0) }) {
        val response = api.getCollectedFavFolders(mid, pn = page, ps = 20, platform = "web")
        check(response.code, response.message)
        val data = response.data
        val raw = data?.list.orEmpty().map { it.copy(source = FavFolderSource.SUBSCRIBED) }
        DesktopSpaceFolderPage(resolveSpaceFavoriteFoldersForDisplay(raw), data?.count ?: 0,
            nextSpacePage(page, data?.count ?: 0, raw.size, 20))
    }

    /** Unlike the personal page, public folder reads do not require an account. The server enforces privacy. */
    suspend fun favoriteResources(folderId: Long, page: Int = 1): PersonalResourcePage<Int> = read({ require(folderId > 0 && page > 0) }) {
        val response = api.getFavoriteList(mediaId = folderId, pn = page, ps = 20)
        val data = verified(response.code, response.message, response.data, "收藏夹内容")
        val raw = data.medias.orEmpty()
        PersonalResourcePage(raw.map { PersonalResource.Favorite(it) }, nextSpacePage(page, data.has_more && raw.isNotEmpty()))
    }

    suspend fun articles(mid: Long, page: Int = 1): DesktopSpaceArticlePage = read({ require(mid > 0 && page > 0) }) {
        val response = space.getSpaceArticleList(sign(desktopSpaceArticleParams(mid, page)))
        check(response.code, response.message)
        val data = response.data
        DesktopSpaceArticlePage(data, nextSpacePage(page, data?.has_more == true && data.lists.isNotEmpty()))
    }

    suspend fun supporters(mid: Long): DesktopSpaceSupporters = read({ require(mid > 0) }) {
        val response = space.getAppSpaceSupporters(AppSignUtils.signForAndroidApi(desktopSpaceSupporterParams(mid)))
        val data = verified(response.code, response.message, response.data, "UP 主支持者")
        val groups = resolveSpaceSupporterGroups(data)
        DesktopSpaceSupporters(data, groups.first, groups.second)
    }

    /** The original rank page fetches pn=1/ps=100 and only falls back when both web lists are empty. */
    suspend fun chargeRank(mid: Long, privilegeType: Int? = null): DesktopSpaceChargeRank = read({
        require(mid > 0 && (privilegeType == null || privilegeType >= 0))
    }) {
        val response = space.getUpowerRank(desktopSpaceRankParams(mid, privilegeType))
        val data = verified(response.code, response.message, response.data, "充电排行")
        if (data.rankInfo.isEmpty() && data.levelInfo.isEmpty()) {
            val fallback = desktopSpaceElecFallback {
                space.getAppSpaceSupporters(AppSignUtils.signForAndroidApi(desktopSpaceSupporterParams(mid)))
            }
            if (fallback != null) return@read DesktopSpaceChargeRank(data, desktopSpaceElecItems(fallback), fallback.total, true)
        }
        DesktopSpaceChargeRank(data, data.rankInfo, data.levelInfo.sumOf { it.memberTotal.toLong() }, false)
    }

    suspend fun guards(mid: Long, page: Int = 1): DesktopSpaceGuardPage = read({ require(mid > 0 && page > 0) }) {
        val response = space.getMemberGuard(desktopSpaceGuardParams(mid, page))
        val data = verified(response.code, response.message, response.data, "舰队")
        DesktopSpaceGuardPage(data, if (page == 1) data.guardTopList.take(3) else emptyList(),
            if (page == 1) data.guardTopList.drop(3) else data.guardTopList,
            nextSpacePage(page, data.hasMore == 1 && data.guardTopList.isNotEmpty()))
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

data class DesktopSpaceFolderPage(val items: List<FavFolder>, val totalCount: Int, val nextPage: Int?)
data class DesktopSpaceArticlePage(val data: SpaceArticleData?, val nextPage: Int?) {
    val items get() = data?.lists.orEmpty()
}
data class DesktopSpaceSupporters(val data: SpaceSupportersData, val charge: SpaceSupporterGroup?, val guard: SpaceSupporterGroup?)
data class DesktopSpaceChargeRank(val data: SpaceUpowerRankData, val items: List<SpaceUpowerRankItem>,
    val totalCount: Long, val usedElecFallback: Boolean)
data class DesktopSpaceGuardPage(val data: SpaceMemberGuardData, val tops: List<SpaceGuardMemberItem>,
    val items: List<SpaceGuardMemberItem>, val nextPage: Int?)

internal fun nextSpacePage(page: Int, more: Boolean): Int? = (page + 1).takeIf { page < Int.MAX_VALUE && more }
internal fun nextSpacePage(page: Int, total: Int, rawCount: Int, pageSize: Int): Int? =
    nextSpacePage(page, rawCount > 0 && page.toLong() * pageSize < total)

internal suspend fun desktopSpaceElecFallback(fetch: suspend () -> SpaceSupportersResponse): SpaceElecBlock? = try {
    val result = fetch()
    result.data?.elec?.takeIf { result.code == 0 && it.total > 0 && it.list.isNotEmpty() }
} catch (failure: Exception) {
    if (failure is CancellationException) throw failure
    null // Optional original elec fallback never hides a web failure or blocks an empty rank.
}

/** Original navigation debug messages contain full URLs; the desktop binding keeps them private. */
object DesktopSpaceRouteLog {
    fun d(tag: String, message: String) = Unit
    fun e(tag: String, message: String) = Unit
}

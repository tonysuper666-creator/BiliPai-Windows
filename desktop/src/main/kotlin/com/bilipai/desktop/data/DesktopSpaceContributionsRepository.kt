package com.bilipai.desktop.data

import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopOriginalSpaceInteraction
import com.android.purebilibili.feature.space.*
import com.android.purebilibili.feature.list.desktopSpaceInteractionHasMore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Original public UP contributions, separate from the private account-only personal navigation. */
class DesktopSpaceContributionsRepository internal constructor(client: OkHttpClient,
    private val ensureSession: suspend () -> Unit, private val sign: suspend (Map<String,String>) -> Map<String,String>,
    private val credentials: () -> Pair<String?,String>, private val currentEpoch: () -> Long) {
    constructor(repository: DesktopRepository) : this(repository.httpClient, { repository.ensureSession() },
        { repository.signWebParams(it) }, { repository.accessTokenCredentials() }, { repository.sessionEpoch })
    private val web = Retrofit.Builder().baseUrl("https://api.bilibili.com/")
        .client(client.newBuilder().retryOnConnectionFailure(false).build())
        .addConverterFactory(Json { ignoreUnknownKeys = true; coerceInputValues = true }.asConverterFactory("application/json".toMediaType())).build()
    private val space = web.create(SpaceApi::class.java)
    private val api = web.create(BilibiliApi::class.java)
    private val bangumi = web.create(BangumiApi::class.java)

    suspend fun metadata(mid: Long): DesktopSpaceMetadata = read({ require(mid > 0) }) {
        val (token, platform) = credentials()
        val response = space.getSpaceAggregate(buildSpaceAggregateParams(mid, token, platform))
        val data = verified(response.code, response.message, response.data, "UP 主空间聚合资料")
        DesktopSpaceMetadata(data, resolveSpaceMainTabs(data.tab2), resolveSpaceContributionTabs(data.tab2))
    }

    suspend fun home(mid: Long): DesktopSpaceHome = read({ require(mid > 0) }) {
        coroutineScope {
            val top = async { optionalSpaceHome { space.getTopArc(mid).let { if (it.code == 0) it.data else null } } }
            val notice = async { optionalSpaceHome { space.getNotice(mid).let { if (it.code == 0) it.data.takeIf(String::isNotEmpty) else null } } }
            DesktopSpaceHome(top.await(), notice.await().orEmpty())
        }
    }

    /** A null cursor performs the original first-page metadata lookup before locating the oldest page. */
    suspend fun videos(mid: Long, page: Int? = null, order: VideoSortOrder = VideoSortOrder.PUBDATE,
        categoryId: Int = 0, keyword: String = ""): DesktopSpaceOrderedVideos = read({ require(mid > 0 && (page == null || page > 0) && categoryId >= 0) }) {
        suspend fun fetch(number: Int): SpaceVideoData {
            val response = space.getSpaceVideos(sign(communitySpaceVideoParams(mid, number, order.apiValue, categoryId, keyword)))
            return verified(response.code, response.message, response.data, "UP 主投稿")
        }
        var loaded = page ?: 1
        var data = fetch(loaded)
        if (page == null) {
            val initial = resolveInitialSpaceVideoPage(order, data.page.count, 30)
            if (initial != loaded) { loaded = initial; data = fetch(initial) }
        }
        val items = normalizeSpaceVideoPage(order, data.list.vlist)
        DesktopSpaceOrderedVideos(data, items, loaded, resolveNextSpaceVideoPage(order, loaded, data.page.count, 30).takeIf { items.isNotEmpty() })
    }

    suspend fun interactions(mid: Long, coins: Boolean = false, page: Int = 1,
        previous: List<VideoItem> = emptyList()): DesktopSpaceInteractionPage = read({ require(mid > 0 && page > 0) }) {
        val (token, platform) = credentials()
        val params = buildSpaceLikedArchiveParams(mid, page, 20, token, platform)
        val app = try { if (coins) space.getSpaceCoinArchive(params) else space.getSpaceLikedArchive(params) }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; null }
        val response = when {
            app != null && app.code == 0 && (app.data?.item?.isNotEmpty() == true || (app.data?.count ?: 0) > 0 || page > 1) -> app
            coins -> app ?: throw BiliApiException(-1, "获取投币视频失败")
            else -> api.getLikedVideos(mid, page, 20)
        }
        check(response.code, response.message)
        val mapped = DesktopOriginalSpaceInteraction.fromData(response.data)
        val oldKeys = previous.mapTo(hashSetOf()) { it.bvid.ifBlank { it.id.toString() } }
        val advanced = mapped.items.any { it.bvid.ifBlank { it.id.toString() } !in oldKeys }
        DesktopSpaceInteractionPage(response.data, mapped.items, mapped.total,
            nextSpacePage(page, advanced && desktopSpaceInteractionHasMore(mapped, 20)))
    }

    suspend fun followedBangumi(mid: Long, page: Int = 1, previous: List<FollowBangumiItem> = emptyList()): DesktopSpaceFollowPage = read({ require(mid > 0 && page > 0) }) {
        // Original SpaceViewModel selects MY_FOLLOW_TYPE_BANGUMI (1), not the private current-account wrapper.
        val response = bangumi.getMyFollowBangumi(vmid = mid, type = 1, pn = page, ps = 30)
        val data = verified(response.code, response.message, response.data, "UP 主追番")
        val incoming = data.list.orEmpty()
        val merged = mergeSpaceBangumiItems(previous, incoming)
        DesktopSpaceFollowPage(data, mergeSpaceBangumiItems(emptyList(), incoming), nextSpacePage(maxOf(page, data.pn),
            shouldContinueSpaceBangumiPagination(previous.size, merged.size, incoming.size, data.pn, data.ps, data.total)))
    }

    suspend fun courses(mid: Long, page: Int = 1): DesktopSpaceCoursePage = read({ require(mid > 0 && page > 0) }) {
        val response = space.getSpaceCheese(mid, page, 30)
        val data = verified(response.code, response.message, response.data, "UP 主课堂")
        DesktopSpaceCoursePage(data, mergeSpaceCheeseItems(emptyList(), data.items), nextSpacePage(page, data.page?.next == true && data.items.isNotEmpty()))
    }

    suspend fun audios(mid: Long, page: Int = 1, previous: List<SpaceAudioItem> = emptyList()): DesktopSpaceAudioPage = read({ require(mid > 0 && page > 0) }) {
        val response = space.getSpaceAudioList(uid = mid, pn = page)
        val data = verified(response.code, response.msg, response.data, "UP 主音频")
        // Refresh starts from an empty owner, avoiding old row IDs suppressing replacement data.
        val base = if (page == 1) emptyList() else previous
        val (merged, total, more) = desktopSpaceAudioMerge(base, data, page == 1)
        val old = base.mapTo(hashSetOf()) { it.id }
        DesktopSpaceAudioPage(data, merged.filter { it.id !in old }, total, nextSpacePage(page, more))
    }

    private suspend fun <T> read(validate: () -> Unit, action: suspend () -> T): T = withContext(Dispatchers.IO) {
        validate(); val expected = currentEpoch()
        fun checkEpoch() { if (currentEpoch() != expected) throw BiliApiException(-101, "账号已切换，请重新加载") }
        ensureSession(); checkEpoch(); action().also { checkEpoch() }
    }
    private fun check(code: Int, message: String) { if (code != 0) throw BiliApiException(code, message) }
    private fun <T : Any> verified(code: Int, message: String, data: T?, label: String): T {
        check(code, message); return data ?: throw BiliApiException(-1, "$label 数据为空")
    }
}

data class DesktopSpaceHome(val topVideo: SpaceTopArcData?, val notice: String)
data class DesktopSpaceMetadata(val data: SpaceAggregateData, val mainTabs: List<SpaceMainTabItem>,
    val contributionTabs: List<SpaceContributionTab>)
data class DesktopSpaceOrderedVideos(val data: SpaceVideoData, val items: List<SpaceVideoItem>, val page: Int, val nextPage: Int?)
data class DesktopSpaceInteractionPage(val data: LikedVideosData?, val items: List<VideoItem>, val total: Int, val nextPage: Int?)
data class DesktopSpaceFollowPage(val data: MyFollowBangumiData, val items: List<FollowBangumiItem>, val nextPage: Int?)
data class DesktopSpaceCoursePage(val data: SpaceCheeseData, val items: List<SpaceCheeseItem>, val nextPage: Int?)
data class DesktopSpaceAudioPage(val data: SpaceAudioData, val items: List<SpaceAudioItem>, val total: Int, val nextPage: Int?)

internal suspend fun <T> optionalSpaceHome(action: suspend () -> T?): T? = try { action() }
    catch (failure: Exception) { if (failure is CancellationException) throw failure; null }

package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.AppSignUtils
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.ui.DesktopHomeProtocolEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DesktopOriginalLiveListProtocol(private val environment: DesktopHomeProtocolEnvironment) {
    private val api get() = environment.api
    private val existingLive = DesktopOriginalHomeLiveProtocol(environment.api)
    private suspend fun getLiveRooms(page: Int) = existingLive.getLiveRooms(page)
    private suspend fun getFollowedLive(page: Int) = existingLive.getFollowedLive(page)
    suspend fun getRecommendedLiveRooms(): Result<List<LiveRoom>> = withContext(Dispatchers.IO) {
        try {
            val recommendResp = api.getLiveRecommendList()
            val recommendRooms = if (recommendResp.code == 0) {
                recommendResp.data?.recommendRoomList
                    ?.filterNot { it.isAd }
                    ?.map { it.toLiveRoom() }
                    .orEmpty()
            } else {
                emptyList()
            }
            if (recommendRooms.isNotEmpty()) {
                Result.success(recommendRooms)
            } else {
                getLiveRooms(page = 1)
            }
        } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) {
            getLiveRooms(page = 1)
        }
    }

    suspend fun getLiveFeedHome(
        page: Int = 1,
        moduleSelect: Boolean = false,
    ): Result<LiveFeedHomeSnapshot> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getLiveFeedIndex(buildLiveAppFeedParams(page = page, moduleSelect = moduleSelect))
            if (resp.code != 0 || resp.data == null) {
                return@withContext if (page == 1) {
                    fallbackLiveFeedHome()
                } else {
                    Result.failure(Exception(resp.message.ifBlank { "直播 feed 加载失败" }))
                }
            }
            Result.success(parseLiveFeedHomeSnapshot(resp.data))
        } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) {
            if (page == 1) fallbackLiveFeedHome() else Result.failure(e)
        }
    }

    suspend fun getLiveSecondHome(
        parentAreaId: Int,
        areaId: Int = 0,
        page: Int = 1,
        sortType: String? = null,
    ): Result<LiveFeedHomeSnapshot> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getLiveAppSecondList(
                buildLiveAppSecondListParams(
                    page = page,
                    parentAreaId = parentAreaId,
                    areaId = areaId,
                    sortType = sortType,
                )
            )
            if (resp.code == 0 && resp.data != null) {
                val rooms = resp.data.list
                    ?.filter { isUsableLiveFeedRoom(it) }
                    ?.map { it.toLiveRoom() }
                    ?.filter(::shouldKeepLiveSecondListRoom)
                    ?.distinctBy { it.roomid }
                    .orEmpty()
                val hasMore = when {
                    resp.data.hasMore != 0 -> resp.data.hasMore == 1
                    resp.data.count > 0 -> page * 20 < resp.data.count
                    else -> rooms.size >= 20
                }
                return@withContext Result.success(
                    LiveFeedHomeSnapshot(
                        rooms = rooms,
                        sortTags = resp.data.newTags.orEmpty().filter { it.name.isNotBlank() || it.sortType.isNotBlank() },
                        hasMore = hasMore,
                        totalCount = resp.data.count,
                    )
                )
            }
        } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
            // fall through
        }

        getAreaRoomsPage(
            parentAreaId = parentAreaId,
            areaId = areaId,
            page = page,
            sortType = sortType?.takeIf { it.isNotBlank() } ?: "online",
        ).map { pageResult ->
            LiveFeedHomeSnapshot(
                rooms = pageResult.rooms,
                hasMore = pageResult.hasMore,
                totalCount = pageResult.totalCount,
            )
        }
    }

    private fun buildLiveAppFeedParams(page: Int, moduleSelect: Boolean): Map<String, String> {
        val params = linkedMapOf(
            "appkey" to AppSignUtils.ANDROID_APP_KEY,
            "actionKey" to "appkey",
            "build" to "8430300",
            "version" to "8.43.0",
            "c_locale" to "zh_CN",
            "s_locale" to "zh_CN",
            "channel" to "master",
            "device" to "android",
            "device_name" to "android",
            "device_type" to "0",
            "fnval" to "912",
            "disable_rcmd" to "0",
            "https_url_req" to "1",
            "mobi_app" to "android",
            "network" to "wifi",
            "page" to page.toString(),
            "platform" to "android",
            "scale" to "2",
            "ts" to AppSignUtils.getTimestamp().toString(),
        )
        environment.accessToken()?.takeIf { it.isNotBlank() }?.let {
            params["access_key"] = it
            params["relation_page"] = "1"
        }
        if (moduleSelect) params["module_select"] = "1"
        return AppSignUtils.signForAndroidApi(params)
    }

    private fun buildLiveAppSecondListParams(
        page: Int,
        parentAreaId: Int,
        areaId: Int,
        sortType: String?,
    ): Map<String, String> {
        val params = linkedMapOf(
            "appkey" to AppSignUtils.ANDROID_APP_KEY,
            "actionKey" to "appkey",
            "build" to "8430300",
            "version" to "8.43.0",
            "c_locale" to "zh_CN",
            "s_locale" to "zh_CN",
            "channel" to "master",
            "device" to "android",
            "device_name" to "android",
            "device_type" to "0",
            "fnval" to "912",
            "disable_rcmd" to "0",
            "https_url_req" to "1",
            "mobi_app" to "android",
            "module_select" to "0",
            "network" to "wifi",
            "page" to page.toString(),
            "page_size" to "20",
            "platform" to "android",
            "qn" to "0",
            "tag_version" to "1",
            "scale" to "2",
            "parent_area_id" to parentAreaId.toString(),
            "area_id" to areaId.toString(),
            "ts" to AppSignUtils.getTimestamp().toString(),
        )
        environment.accessToken()?.takeIf { it.isNotBlank() }?.let {
            params["access_key"] = it
        }
        if (!sortType.isNullOrBlank()) {
            params["sort_type"] = sortType
        }
        return AppSignUtils.signForAndroidApi(params)
    }

    private suspend fun fallbackLiveFeedHome(): Result<LiveFeedHomeSnapshot> {
        val recommend = getRecommendedLiveRooms().getOrElse { emptyList() }
        val follow = getFollowedLive(page = 1).getOrElse { emptyList() }
        val areas = getLiveAreaIndex().getOrElse { emptyList() }
            .map {
                LiveFeedAreaEntry(
                    title = it.name,
                    areaId = 0,
                    parentAreaId = it.id,
                )
            }
        return Result.success(
            LiveFeedHomeSnapshot(
                rooms = recommend,
                followRooms = follow,
                areaEntries = areas,
                hasMore = recommend.size >= 20,
            )
        )
    }

    suspend fun getLiveAreaIndex(): Result<List<LiveAreaParent>> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getLiveAreaList()
            if (resp.code == 0) {
                Result.success(resp.data ?: emptyList())
            } else {
                Result.failure(Exception(resp.message.ifBlank { resp.msg.ifBlank { "获取直播标签失败" } }))
            }
        } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAreaRoomsPage(
        parentAreaId: Int,
        areaId: Int = 0,
        page: Int = 1,
        pageSize: Int = 30,
        sortType: String = "online",
        areaTitle: String = ""
    ): Result<LiveAreaRoomsPage> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getLiveList(
                parentAreaId = parentAreaId,
                areaId = areaId,
                page = page,
                pageSize = pageSize,
                sortType = sortType
            )
            if (resp.code == 0) {
                val data = resp.data
                val rooms = data?.getAllRooms() ?: emptyList()
                Result.success(
                    LiveAreaRoomsPage(
                        rooms = rooms,
                        hasMore = hasMoreLiveAreaRooms(
                            loadedCount = rooms.size,
                            page = page,
                            pageSize = pageSize,
                            hasMoreFlag = data?.hasMore ?: 0,
                            totalCount = data?.count ?: 0
                        ),
                        totalCount = data?.count ?: 0
                    )
                )
            } else if (shouldFallbackLiveAreaRooms(code = resp.code, message = resp.message)) {
                val fallbackResp = api.getLiveSecondAreaList(
                    parentAreaId = parentAreaId,
                    areaId = areaId,
                    page = page,
                    sortType = sortType
                )
                if (fallbackResp.code == 0) {
                    val data = fallbackResp.data
                    Result.success(
                        LiveAreaRoomsPage(
                            rooms = data?.list ?: emptyList(),
                            hasMore = data?.hasMore == 1,
                            totalCount = data?.count ?: 0
                        )
                    )
                } else {
                    Result.failure(
                        Exception(
                            resolveLiveAreaRoomsErrorMessage(
                                code = fallbackResp.code,
                                message = fallbackResp.message
                            )
                        )
                    )
                }
            } else {
                Result.failure(
                    Exception(
                        resolveLiveAreaRoomsErrorMessage(
                            code = resp.code,
                            message = resp.message
                        )
                    )
                )
            }
        } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

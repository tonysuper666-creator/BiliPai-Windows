package com.bilipai.desktop.data

import com.android.purebilibili.core.util.HtmlEntityUtils
import com.android.purebilibili.data.model.response.BangumiDetail
import com.android.purebilibili.data.model.response.BangumiVideoInfo
import com.android.purebilibili.data.model.response.BangumiDetailResponse
import com.android.purebilibili.data.model.response.BangumiIndexConditionData
import com.android.purebilibili.data.model.response.TimelineDay
import com.android.purebilibili.data.model.response.LivePlayUrlData
import com.android.purebilibili.data.model.response.getBestAudio
import com.android.purebilibili.data.model.response.getBestVideo
import com.android.purebilibili.data.model.response.BangumiFilter
import com.android.purebilibili.data.model.response.buildBangumiIndexRequestFilter
import com.android.purebilibili.data.model.response.resolveBangumiSearchTypeForSeasonType
import com.android.purebilibili.data.model.response.SearchType
import com.android.purebilibili.data.model.response.PugvSeasonResponse
import com.android.purebilibili.data.model.response.UserStatus
import com.android.purebilibili.data.model.response.toBangumiDetail
import com.android.purebilibili.data.repository.BangumiPlayUrlPayload
import com.android.purebilibili.feature.bangumi.collectPlayableDurlUrls
import com.android.purebilibili.core.network.socket.LiveDanmakuClient
import com.android.purebilibili.data.repository.LiveDanmakuPermission
import com.android.purebilibili.data.repository.LiveDanmakuSendRequest
import com.android.purebilibili.data.repository.LivePrefetchDanmaku
import com.android.purebilibili.data.repository.parseLiveDanmakuPermission
import com.android.purebilibili.data.repository.parseLiveDanmakuHistoryItems
import com.android.purebilibili.data.repository.resolveDesktopLiveDanmakuHosts
import com.bilipai.desktop.player.PlaybackSegment
import com.android.purebilibili.data.repository.decodeBangumiPlayUrlPayload
import com.android.purebilibili.data.repository.shouldFallbackToLegacyBangumiPlayUrl
import com.android.purebilibili.data.repository.validateBangumiPlayableVideoInfo
import com.android.purebilibili.data.repository.shouldLoadBangumiSections
import com.android.purebilibili.data.repository.mergeBangumiDetailSections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Live and licensed PGC playback share the existing Windows login jar and WBI signer. */
class DesktopMediaRepository(private val repository: DesktopRepository) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val api = DesktopMediaApi(repository, json)

    suspend fun liveAreas(): List<LiveArea> = withContext(Dispatchers.IO) {
        repository.ensureSession()
        val response = api.live.getLiveAreaList()
        checkCode(response.code, response.message.ifBlank { response.msg })
        response.data.orEmpty().map { parent ->
            LiveArea(parent.id, parent.name, children = parent.list.orEmpty().mapNotNull { child ->
                val id = child.id.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                LiveArea(id, child.name, parent.id, parent.name, mediaUrl(child.pic))
            })
        }
    }

    suspend fun liveRooms(page: Int = 1, parentAreaId: Int = 0, areaId: Int = 0): MediaPage<LiveCard> = withContext(Dispatchers.IO) {
        require(page > 0 && parentAreaId >= 0 && areaId >= 0)
        repository.ensureSession()
        val response = api.live.getLiveList(parentAreaId, areaId, page)
        checkCode(response.code, response.message)
        val data = response.data
        val cards = data?.getAllRooms().orEmpty().filter { it.roomid > 0 }.map {
            LiveCard(it.roomid, cleanTitle(it.title), mediaUrl(it.displayCover()), it.uname,
                mediaUrl(it.face), it.viewerCount().toLong(), it.areaName)
        }
        MediaPage(cards, page, data?.hasMore == 1, data?.count ?: 0)
    }

    suspend fun searchLive(query: String, page: Int = 1): MediaPage<LiveCard> = withContext(Dispatchers.IO) {
        val response = api.search.searchLive(searchParams(query, page, "live_room"))
        checkCode(response.code, response.message)
        val data = response.data
        val cards = data?.result.orEmpty().filter { it.roomid > 0 }.map {
            val item = it.cleanupFields()
            LiveCard(item.roomid, item.title, item.cover.ifBlank { item.user_cover },
                item.uname, item.uface, item.online.toLong(), item.area_v2_name.ifBlank { item.cate_name })
        }
        MediaPage(cards, page, page < (data?.numPages ?: page), data?.numResults ?: 0)
    }

    suspend fun liveRecommendations(): List<LiveCard> = withContext(Dispatchers.IO) {
        repository.ensureSession()
        val response = api.live.getLiveRecommendList()
        checkCode(response.code, response.message)
        response.data?.recommendRoomList.orEmpty().filter { !it.isAd && it.roomId > 0 }.map {
            val room = it.toLiveRoom()
            LiveCard(room.roomid, cleanTitle(room.title), mediaUrl(room.displayCover()), room.uname,
                mediaUrl(room.face), room.viewerCount().toLong(), room.areaName)
        }
    }

    suspend fun followedLive(page: Int = 1): MediaPage<LiveCard> = withContext(Dispatchers.IO) {
        require(page > 0)
        if (repository.account.value == null) throw BiliApiException(-101, "请先登录后查看关注直播")
        repository.ensureSession()
        val response = api.live.getFollowedLive(page)
        checkCode(response.code, response.message)
        val data = response.data
        MediaPage(data?.list.orEmpty().map {
            val room = it.toLiveRoom()
            LiveCard(room.roomid, cleanTitle(room.title), mediaUrl(room.displayCover()), room.uname,
                mediaUrl(room.face), room.viewerCount().toLong(), room.areaName)
        }, page, page < (data?.pageinfo?.total_page ?: page), (data?.livingNum ?: 0) + (data?.notLivingNum ?: 0))
    }

    suspend fun liveRoom(roomId: Long): LiveRoomDetails = withContext(Dispatchers.IO) {
        require(roomId > 0)
        repository.ensureSession()
        val initialized = api.live.getLiveRoomInit(roomId)
        checkCode(initialized.code, initialized.message)
        val init = initialized.data ?: throw BiliApiException(-1, "直播间信息为空")
        val realId = init.roomId.takeIf { it > 0 } ?: throw BiliApiException(-1, "直播间不存在")
        val response = api.live.getLiveRoomDetail(realId)
        checkCode(response.code, response.message)
        val data = response.data ?: throw BiliApiException(-1, "直播间详情为空")
        val room = data.roomInfo ?: throw BiliApiException(-1, "直播间详情为空")
        val anchor = data.anchorInfo?.baseInfo
        LiveRoomDetails(realId, cleanTitle(room.title), mediaUrl(room.cover.ifBlank { room.keyframe }),
            anchor?.uname.orEmpty(), mediaUrl(anchor?.face.orEmpty()),
            (data.watchedShow?.viewerCount()?.toLong() ?: 0).takeIf { it > 0 } ?: room.online.toLong(),
            room.areaName, room.liveStatus, cleanTitle(room.description),
            init.isLocked || init.isHidden || (init.encrypted && !init.pwdVerified) || room.lockStatus != 0 || room.hiddenStatus != 0)
    }

    suspend fun livePlayback(room: LiveRoomDetails, quality: Int = 10000, onlyAudio: Boolean = false): PlaybackSource =
        livePlaybackInfo(room, quality, onlyAudio).source

    suspend fun livePlaybackInfo(room: LiveRoomDetails, quality: Int = 10000, onlyAudio: Boolean = false): LivePlaybackInfo = withContext(Dispatchers.IO) {
        require(quality > 0)
        if (room.locked) throw BiliApiException(-403, "该直播间暂不可观看")
        if (!room.isLive) throw BiliApiException(-1, "主播尚未开播")
        repository.ensureSession()
        val primary = api.live.getLivePlayUrl(roomId = room.roomId, quality = quality, onlyAudio = if (onlyAudio) 1 else null,
            signedParams = repository.signWebParams(emptyMap()))
        if (primary.code in setOf(-101, -352, -412, -403)) checkCode(primary.code, primary.message)
        val selected = if (primary.code == 0) primary.data?.let { selectLive(it, room, quality) } else null
        if (selected != null) {
            if (selected.qualities.isNotEmpty()) return@withContext selected
            val legacyQuality = try {
                api.live.getLivePlayUrlLegacy(room.roomId, quality).takeIf { it.code == 0 }?.data?.quality_description
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { null }
            return@withContext selected.copy(qualities = legacyQuality.orEmpty().map { MediaQuality(it.qn, it.desc) })
        }
        val legacy = api.live.getLivePlayUrlLegacy(room.roomId, quality)
        checkCode(legacy.code, legacy.message)
        legacy.data?.let { selectLive(it, room, quality) } ?: throw BiliApiException(-1, "直播接口没有返回可播放流")
    }

    /** Every connection obtains a fresh token using this shared session's signed API. */
    internal suspend fun liveDanmakuClient(scope: CoroutineScope, roomId: Long): LiveDanmakuClient = withContext(Dispatchers.IO) {
        repository.ensureSession()
        val room = liveRoom(roomId)
        val identity = repository.account.value
        val params = mapOf("id" to room.roomId.toString(), "type" to "0", "web_location" to "444.8")
        var response = api.live.getDanmuInfoWbi(repository.signWebParams(params))
        if (response.code != 0)
            response = api.live.getDanmuInfoWbi(repository.signWebParams(params, forceRefresh = true))
        checkCode(response.code, response.message)
        val data = response.data ?: throw BiliApiException(-1, "直播弹幕服务信息为空")
        val urls = resolveDesktopLiveDanmakuHosts(data.host_list)
        require(urls.isNotEmpty() && data.token.isNotBlank()) { "没有可用的直播弹幕服务器" }
        if (repository.account.value != identity) throw CancellationException("账号已切换，请重新连接直播间")
        LiveDanmakuClient(scope, repository.httpClient).also { it.connect(urls, data.token, room.roomId, identity?.mid ?: 0L) }
    }

    suspend fun liveDanmakuPermission(roomId: Long): LiveDanmakuPermission = withContext(Dispatchers.IO) {
        require(roomId > 0)
        repository.ensureSession()
        api.live.getLiveDanmakuConfig(roomId).use { parseLiveDanmakuPermission(it.string()) }
    }

    suspend fun liveDanmakuHistory(roomId: Long): List<LivePrefetchDanmaku> = withContext(Dispatchers.IO) {
        require(roomId > 0)
        repository.ensureSession()
        api.live.getLiveDanmakuHistory(roomId).use { parseLiveDanmakuHistoryItems(it.string()).getOrThrow() }
    }

    /** Called only by an explicit Send button; credentials are resolved at that moment. */
    suspend fun sendLiveDanmaku(request: LiveDanmakuSendRequest) = withContext(Dispatchers.IO) {
        require(request.roomId > 0 && request.message.isNotBlank())
        val identity = repository.requireAccount()
        val permission = liveDanmakuPermission(request.roomId)
        require(permission.canSend) { permission.statusText }
        require(permission.maxLength <= 0 || request.message.length <= permission.maxLength) { "弹幕不能超过 ${permission.maxLength} 个字" }
        require(permission.availableColors.any { it.color == request.color } && permission.availableModes.any { it.mode == request.mode }) {
            "当前账号没有所选弹幕样式权限"
        }
        val signed = repository.signWebParams(mapOf("web_location" to "444.8"))
        if (repository.account.value != identity) throw CancellationException("账号已切换，已取消直播弹幕发送")
        val csrf = repository.requireCsrf()
        val response = try {
            api.live.sendLiveDanmaku(signedParams = signed, roomId = request.roomId, msg = request.message,
                color = request.color, fontsize = request.fontSize, mode = request.mode, bubble = request.bubble,
                roomType = request.roomType, jumpFrom = request.jumpFrom, replyMid = request.replyMid,
                replyAttr = request.replyAttr, replyUname = request.replyUname, replayDmid = request.replayDmid,
                statistics = request.statistics, dmType = request.dmType, emoticonOptions = request.emoticonOptions,
                csrf = csrf, csrfToken = csrf)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            if (signed.isEmpty()) throw error
            if (repository.account.value != identity) throw CancellationException("账号已切换，已取消直播弹幕发送")
            api.live.sendLiveDanmaku(roomId = request.roomId, msg = request.message, color = request.color,
                fontsize = request.fontSize, mode = request.mode, csrf = csrf, csrfToken = csrf)
        }
        checkCode(response.code, response.message)
    }

    suspend fun bangumiIndex(page: Int = 1, seasonType: Int = 1, filter: BangumiFilter = BangumiFilter()): MediaPage<BangumiCard> = withContext(Dispatchers.IO) {
        require(page > 0 && seasonType in setOf(1, 2, 3, 4, 5, 7))
        repository.ensureSession()
        val request = buildBangumiIndexRequestFilter(filter, seasonType)
        val response = api.bangumi.getBangumiIndex(seasonType, seasonType, page = page,
            order = request.order, sort = request.sortDirection, area = request.area, isFinish = request.isFinish,
            year = request.year, releaseDate = request.releaseDate, styleId = request.styleId, producerId = request.producerId,
            seasonStatus = request.seasonStatus, seasonVersion = request.seasonVersion, spokenLanguageType = request.spokenLanguageType,
            copyright = request.copyright, seasonMonth = request.seasonMonth)
        checkCode(response.code, response.message)
        val data = response.data
        MediaPage(data?.list.orEmpty().filter { it.seasonId > 0 }.map {
            BangumiCard(it.seasonId, cleanTitle(it.title), mediaUrl(it.cover), it.indexShow.ifBlank { it.newEp?.indexShow.orEmpty() },
                it.badge, it.score, it.mediaId)
        }, page, data?.hasNext == 1, data?.total ?: 0)
    }

    suspend fun bangumiFilters(seasonType: Int = 1): BangumiIndexConditionData = withContext(Dispatchers.IO) {
        repository.ensureSession()
        val response = api.bangumi.getBangumiIndexCondition(seasonType = seasonType)
        checkCode(response.code, response.message)
        response.data ?: throw BiliApiException(-1, "番剧筛选条件为空")
    }

    suspend fun bangumiTimeline(type: Int = 1): List<TimelineDay> = withContext(Dispatchers.IO) {
        repository.ensureSession()
        val response = api.bangumi.getTimeline(types = type)
        checkCode(response.code, response.message)
        response.result.orEmpty()
    }

    suspend fun searchBangumi(query: String, page: Int = 1, seasonType: Int = 1): MediaPage<BangumiCard> = withContext(Dispatchers.IO) {
        val anime = resolveBangumiSearchTypeForSeasonType(seasonType) == SearchType.BANGUMI
        val params = searchParams(query, page, if (anime) "media_bangumi" else "media_ft")
        val response = if (anime) api.search.searchBangumi(params) else api.search.searchMediaFt(params)
        checkCode(response.code, response.message)
        val data = response.data
        val cards = data?.result.orEmpty().mapNotNull {
            val id = it.seasonId.takeIf { id -> id > 0 } ?: it.pgcSeasonId
            if (id <= 0) return@mapNotNull null
            BangumiCard(id, cleanTitle(it.title), mediaUrl(it.cover), it.indexShow,
                it.badges.orEmpty().joinToString(" ") { badge -> badge.text },
                it.mediaScore?.score?.toString().orEmpty(), it.mediaId)
        }
        MediaPage(cards, page, page < (data?.numPages ?: page), data?.numResults ?: 0)
    }

    suspend fun bangumiSeason(seasonId: Long = 0, episodeId: Long = 0, isCourse: Boolean = false): BangumiSeason = withContext(Dispatchers.IO) {
        require(seasonId > 0 || episodeId > 0)
        repository.ensureSession()
        suspend fun courseDetail(): BangumiDetail {
            val response = api.bangumi.getPugvSeasonDetail(
                seasonId = seasonId.takeIf { it > 0 && episodeId <= 0 }, epId = episodeId.takeIf { it > 0 })
                .use { json.decodeFromString<PugvSeasonResponse>(it.string()) }
            checkCode(response.code, response.message)
            return response.data?.toBangumiDetail() ?: throw BiliApiException(-1, "课程详情为空")
        }
        var detail = if (isCourse) courseDetail() else try {
            val response = api.bangumi.getSeasonDetail(seasonId.takeIf { it > 0 }, episodeId.takeIf { it > 0 })
                .use { json.decodeFromString<BangumiDetailResponse>(it.string()) }
            checkCode(response.code, response.message)
            response.result ?: throw BiliApiException(-1, "番剧详情为空")
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (original: Exception) {
            try { courseDetail() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw original }
        }
        if (detail.seasonId <= 0) throw BiliApiException(-1, "番剧不存在")
        if (detail.seasonType != 10 && shouldLoadBangumiSections(detail)) {
            try {
                val sections = api.bangumi.getSeasonSections(detail.seasonId)
                checkCode(sections.code, sections.message)
                sections.result?.let { detail = mergeBangumiDetailSections(detail, it) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (episodeCards(detail).isEmpty() || error is BiliApiException && error.apiCode in setOf(412, 429, -101, -352, -412)) throw error
            }
        }
        toSeason(detail)
    }

    /** The original APIs distinguish PGC follow from PUGV course favorites. */
    suspend fun setBangumiFollowing(season: BangumiSeason, following: Boolean): BangumiSeason = withContext(Dispatchers.IO) {
        require(season.seasonId > 0)
        val csrf = repository.requireCsrf()
        suspend fun course() = if (following) api.bangumi.addFavPugv(season.seasonId, csrf)
            else api.bangumi.delFavPugv(season.seasonId, csrf)
        val result = if (season.isCourse) course() else {
            val pgc = if (following) api.bangumi.followBangumi(season.seasonId, csrf)
                else api.bangumi.unfollowBangumi(season.seasonId, csrf)
            if (pgc.code == 0) pgc else try {
                course().takeIf { it.code == 0 } ?: pgc
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { pgc }
        }
        checkCode(result.code, result.message)
        val detail = season.upstreamDetail ?: return@withContext season
        val previous = detail.userStatus ?: UserStatus()
        toSeason(detail.copy(userStatus = previous.copy(follow = if (following) 1 else 0,
            followStatus = if (following && !season.isCourse) previous.followStatus.coerceAtLeast(1) else 0)))
    }

    suspend fun updateBangumiFollowStatus(season: BangumiSeason, status: Int): BangumiSeason = withContext(Dispatchers.IO) {
        require(!season.isCourse && season.seasonId > 0 && status in 1..3)
        val result = api.bangumi.updateBangumiFollowStatus(season.seasonId, status, repository.requireCsrf())
        checkCode(result.code, result.message)
        val detail = season.upstreamDetail ?: return@withContext season
        toSeason(detail.copy(userStatus = (detail.userStatus ?: UserStatus()).copy(follow = 1, followStatus = status)))
    }

    suspend fun followedBangumi(page: Int = 1, type: Int = 1, status: Int? = null): MediaPage<BangumiCard> = withContext(Dispatchers.IO) {
        require(page > 0 && type in 1..2 && (status == null || status in 1..3))
        val account = repository.requireAccount()
        repository.ensureSession()
        val response = api.bangumi.getMyFollowBangumi(account.mid, type, status, page)
        checkCode(response.code, response.message)
        val data = response.data
        MediaPage(data?.list.orEmpty().filter { it.seasonId > 0 }.map {
            BangumiCard(it.seasonId, cleanTitle(it.title), mediaUrl(it.cover), it.newEp?.indexShow.orEmpty(), it.badge,
                "", it.mediaId)
        }, page, page * (data?.ps?.takeIf { it > 0 } ?: 30) < (data?.total ?: 0), data?.total ?: 0)
    }

    suspend fun bangumiSeasonByMediaId(mediaId: Long): BangumiSeason = withContext(Dispatchers.IO) {
        require(mediaId > 0)
        repository.ensureSession()
        val response = api.bangumi.getBangumiMediaInfo(mediaId)
        checkCode(response.code, response.message)
        val seasonId = response.result?.media?.seasonId?.takeIf { it > 0 } ?: throw BiliApiException(-1, "番剧媒体没有关联季度")
        bangumiSeason(seasonId = seasonId)
    }

    suspend fun bangumiPlayback(season: BangumiSeason, episode: BangumiEpisode, quality: Int = 80): PlaybackSource =
        bangumiPlaybackInfo(season, episode, quality).source

    suspend fun bangumiPlaybackInfo(season: BangumiSeason, episode: BangumiEpisode, quality: Int = 80): BangumiPlaybackInfo = withContext(Dispatchers.IO) {
        require(episode.id > 0 && quality > 0 && season.episodes.any { it.id == episode.id })
        val epoch = repository.sessionEpoch
        val requestJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val owned = { requestJob?.isActive != false }
        val visitorApi = repository.ownedHomeService(com.android.purebilibili.core.network.BuvidApi::class.java,
            "https://api.bilibili.com/", epoch, owned)
        repository.ensureOwnedHomeSession(epoch, owned, visitorApi)
        val authorization = repository.capturePlaybackAuthorization(epoch, owned)
        fun assertCurrent() = repository.assertPlaybackAuthorization(authorization, owned)
        assertCurrent()
        val playbackApi = repository.ownedPlaybackService(com.android.purebilibili.core.network.BangumiApi::class.java, authorization, owned)
        val params = buildBangumiParams(season.seasonId, episode, quality, season.isCourse)
        val signed = repository.signPlaybackWebParams(params, authorization, owned, includeRiskFingerprint = season.isCourse)
        suspend fun request(block: suspend () -> okhttp3.ResponseBody): BangumiPlayUrlPayload? = try {
            assertCurrent()
            val value = block().use { decodeBangumiPlayUrlPayload(it.string(), json) }
            assertCurrent()
            value
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { assertCurrent(); null }
        val payload = if (season.isCourse) {
            val pugv = request { playbackApi.getPugvPlayUrl(signed) }
            pugv?.takeIf { it.code == 0 && it.videoInfo != null }
                ?: request { playbackApi.getBangumiPlayUrl(signed) }?.takeIf { it.code == 0 && it.videoInfo != null }
                ?: pugv
        } else {
            val primary = request { playbackApi.getBangumiPlayUrl(signed) }
            val pgc = if (primary != null && !shouldFallbackToLegacyBangumiPlayUrl(primary)) primary
                else request { playbackApi.getBangumiPlayUrlLegacy(signed) } ?: primary
            pgc?.takeIf { it.code == 0 && it.videoInfo != null }
                ?: request { playbackApi.getPugvPlayUrl(signed) }?.takeIf { it.code == 0 && it.videoInfo != null }
                ?: pgc
        } ?: throw BiliApiException(-1, "获取番剧/课程播放地址失败")
        checkCode(payload.code, payload.message)
        val video = payload.videoInfo ?: throw BiliApiException(-1, "番剧接口没有返回媒体信息")
        assertCurrent()
        val source = selectBangumi(video, season, episode, quality).copy(authorizationReceipt = authorization.receipt)
        BangumiPlaybackInfo(source, video.acceptQuality.orEmpty().mapIndexed { index, id ->
            MediaQuality(id, video.acceptDescription?.getOrNull(index) ?: "${id}P")
        }, video.isPreview, (video.timelength.takeIf { it > 0 } ?: video.timeLengthAlt) / 1000,
            season.downloadAllowed && !video.isPreview)
    }

    private suspend fun searchParams(query: String, page: Int, type: String): Map<String, String> {
        require(query.isNotBlank() && page > 0)
        repository.ensureSession()
        return repository.signWebParams(mapOf("keyword" to query.trim(), "search_type" to type,
            "page" to page.toString(), "pagesize" to "20", "web_location" to "1430654"))
    }

    companion object {
        internal fun selectLive(data: LivePlayUrlData, room: LiveRoomDetails, quality: Int): LivePlaybackInfo? {
            val qualities = (data.playurl_info?.playurl?.gQnDesc.orEmpty() + data.quality_description.orEmpty())
                .distinctBy { it.qn }.map { MediaQuality(it.qn, it.desc) }
            val codecs = data.playurl_info?.playurl?.stream.orEmpty().flatMap { stream ->
                stream.format.orEmpty().flatMap { format -> format.codec.orEmpty().map { codec ->
                    Triple(if (codec.codecName == "avc") 0 else if (codec.codecName == "hevc") 1 else 2,
                        if (stream.protocolName == "http_hls") 0 else 1, codec)
                } }
            }.sortedWith(compareBy({ it.first }, { it.second }))
            for ((_, _, codec) in codecs) {
                if (codec.baseUrl.isBlank()) continue
                val urls = codec.url_info.orEmpty().mapNotNull { info -> playableUrl(info.host + codec.baseUrl + info.extra) }.distinct()
                if (urls.isNotEmpty()) return LivePlaybackInfo(PlaybackSource(urls.first(), null, room.title,
                    "https://live.bilibili.com/${room.roomId}", quality = codec.currentQn.takeIf { it > 0 } ?: quality), qualities, urls.drop(1))
            }
            val urls = data.durl.orEmpty().mapNotNull { playableUrl(it.url) }
            return urls.firstOrNull()?.let { LivePlaybackInfo(PlaybackSource(it, null, room.title,
                "https://live.bilibili.com/${room.roomId}", quality = data.current_quality.takeIf { q -> q > 0 } ?: quality), qualities, urls.drop(1)) }
        }

        internal fun selectBangumi(info: BangumiVideoInfo, season: BangumiSeason, episode: BangumiEpisode, quality: Int): PlaybackSource {
            validateBangumiPlayableVideoInfo(info).getOrElse { throw BiliApiException(-403, it.message.orEmpty()) }
            val referer = if (season.isCourse) "https://www.bilibili.com/cheese/play/ep${episode.id}"
                else "https://www.bilibili.com/bangumi/play/ep${episode.id}"
            val title = "${season.title} · ${episode.title} ${episode.subtitle}".trim()
            info.dash?.let { dash ->
                val video = dash.getBestVideo(quality, preferCodec = "avc1", secondPreferCodec = "hev1", isHevcSupported = true, isAv1Supported = true)
                val url = video?.getValidUrl()?.let(::playableUrl)
                if (url != null) return PlaybackSource(url, dash.getBestAudio()?.getValidUrl()?.let(::playableUrl), title, referer, quality = video?.id ?: quality)
            }
            val segments = info.durl.orEmpty().ifEmpty { info.durls.orEmpty() }
            val urls = collectPlayableDurlUrls(segments)
            if (segments.isNotEmpty() && urls.size == segments.size && urls.all { playableUrl(it) != null }) {
                val parts = segments.zip(urls).map { (segment, url) -> PlaybackSegment(requireNotNull(playableUrl(url)),
                    segment.length.takeIf { it > 0 }?.div(1000.0)) }
                return PlaybackSource(parts.first().url, null, title, referer, quality = info.quality,
                    progressiveSegments = if (parts.size > 1) parts else emptyList())
            }
            if (info.isDrm) throw BiliApiException(-403, "该番剧使用 DRM 版权保护，当前不支持此媒体流")
            throw BiliApiException(-1, "此集没有可用媒体流，请检查账号权限或地区限制")
        }

        internal fun buildBangumiParams(seasonId: Long, episode: BangumiEpisode, quality: Int, isCourse: Boolean = false) =
            com.android.purebilibili.data.repository.buildBangumiPlayUrlParams(
                epId = episode.id, cid = episode.cid, qn = quality, bvid = episode.bvid,
                seasonId = seasonId, aid = episode.aid, isCourse = isCourse,
            )

        internal fun toSeason(detail: BangumiDetail) = BangumiSeason(detail.seasonId, detail.title, mediaUrl(detail.cover), detail.evaluate,
            episodeCards(detail).filter { it.id > 0 }.distinctBy { it.id }, detail.seasons.orEmpty().filter { it.seasonId > 0 }.map {
                BangumiCard(it.seasonId, it.seasonTitle.ifBlank { it.title }, mediaUrl(it.cover), badge = it.badge)
            }, detail.rating?.score?.toString().orEmpty(), detail.rights?.allowDownload == 1, detail.rights?.areaLimit == 1, detail)

        private fun episodeCards(detail: BangumiDetail): List<BangumiEpisode> = detail.episodes.orEmpty().map { it.toEpisode("") } +
            detail.section.orEmpty().flatMap { section -> section.episodes.orEmpty().map { it.toEpisode(section.title) } }
        private fun com.android.purebilibili.data.model.response.BangumiEpisode.toEpisode(section: String) =
            BangumiEpisode(id, aid, bvid, cid, title, longTitle, mediaUrl(cover), duration / 1000, badge, section, status,
                playable, episodeCanView, from)
        internal fun playableUrl(value: String): String? = mediaUrl(value).toHttpUrlOrNull()?.takeIf { it.scheme in setOf("http", "https") }?.toString()
        private fun mediaUrl(value: String) = if (value.startsWith("//")) "https:$value" else value
        private fun cleanTitle(value: String) = HtmlEntityUtils.unescape(value.replace(Regex("<[^>]+>"), ""))
        private fun checkCode(code: Int, message: String) {
            if (code == 0) return
            val detail = when (code) { -101 -> "请先登录后观看"; -10403 -> "当前账号需要大会员权限"; -403 -> "当前账号没有观看权限"; else -> message }
            throw BiliApiException(code, detail)
        }
    }
}

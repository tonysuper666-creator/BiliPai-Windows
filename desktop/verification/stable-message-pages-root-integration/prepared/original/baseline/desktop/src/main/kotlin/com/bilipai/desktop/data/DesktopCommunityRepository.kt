package com.bilipai.desktop.data

import kotlinx.coroutines.ensureActive

import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.MessageSendPayloadFactory
import com.android.purebilibili.data.repository.resolveHistoryCursorQuery
import com.android.purebilibili.core.util.IdUtils
import kotlinx.coroutines.CancellationException
import com.android.purebilibili.feature.video.note.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import kotlin.random.Random
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Upstream web API contracts, sharing the desktop's authorized cookie jar and WBI keys. */
class DesktopCommunityRepository(private val repository: DesktopRepository,
    val blockedUps: DesktopBlockedUpStore = DesktopBlockedUpStore(com.bilipai.desktop.plugins.DesktopPluginContext(
        com.bilipai.desktop.plugins.DesktopPluginStore(com.bilipai.desktop.DesktopLibrary.directoryForAccount(null))))) {
    val blockedUpRepository by lazy { DesktopBlockedUpRepository(repository, blockedUps) }
    internal val accountEpoch get() = repository.sessionEpochFlow
    val account get() = repository.account
    val search by lazy { DesktopSearchRepository(repository) }
    val searchPreferences by lazy { DesktopSearchPreferences() }
    private val heartbeatReporter by lazy { DesktopPlaybackHeartbeatReporter(repository, searchPreferences) }
    private val articleHistoryReporter by lazy { DesktopArticleHistoryReporter(repository, searchPreferences) }

    internal suspend fun reportDynamicArticleView(articleId: Long, expectedEpoch: Long, expectedMid: Long?): Boolean =
        articleHistoryReporter.report(articleId, expectedEpoch, expectedMid)

    suspend fun reportPlayHeartbeat(bvid: String, cid: Long, playedTimeSec: Long = 0,
        realPlayedTimeSec: Long = playedTimeSec, startTsSec: Long = System.currentTimeMillis() / 1000,
        aid: Long = 0, epid: Long = 0, sid: Long = 0, videoType: Int = 3, subType: Int? = null,
        expectedSessionEpoch: Long = repository.sessionEpoch): Boolean = heartbeatReporter.report(bvid, cid,
        playedTimeSec, realPlayedTimeSec, startTsSec, aid, epid, sid, videoType, subType, expectedSessionEpoch)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val client = repository.httpClient.newBuilder().retryOnConnectionFailure(false).build()
    private fun retrofit(base: String) = Retrofit.Builder().baseUrl(base).client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    private val web = retrofit("https://api.bilibili.com/")
    private val api = web.create(BilibiliApi::class.java)
    private val dynamic = web.create(DynamicApi::class.java)
    private val space = web.create(SpaceApi::class.java)
    internal val favoriteApi get() = api
    internal val favoriteSpaceApi get() = space
    internal val favoriteDynamicApi get() = dynamic
    internal val favoriteBangumiApi by lazy { web.create(BangumiApi::class.java) }
    private val searchApi = web.create(SearchApi::class.java)
    private val article = web.create(ArticleApi::class.java)
    private val messages = retrofit("https://api.vc.bilibili.com/").create(MessageApi::class.java)
    private val mutationMutex = Mutex()
    private val messageDeviceId = UUID.randomUUID().toString()

    suspend fun sendTextMessage(talkerId: Long, text: String, sessionType: Int = 1): SendMessageData {
        require(text.isNotBlank()) { "请输入消息内容" }
        return sendMessage(talkerId, sessionType, 1, communityMessageTextContent(text))
    }

    suspend fun uploadPrivateImage(fileName: String, mimeType: String, bytes: ByteArray): UploadCommentImageData {
        validateCommunityImage(fileName, mimeType, bytes)
        return mutate { csrf ->
            val response = api.uploadPrivateMessageImage(imagePart(fileName, mimeType, bytes),
                "im".toRequestBody(TEXT_MEDIA), csrf.toRequestBody(TEXT_MEDIA))
            verified(response.code, response.message, response.data, "私信图片上传")
        }
    }

    suspend fun sendImageMessage(talkerId: Long, image: UploadCommentImageData, sessionType: Int = 1,
        mimeType: String = "image/jpeg"): SendMessageData {
        require(image.imageUrl.isNotBlank() && image.imageWidth > 0 && image.imageHeight > 0)
        val imageType = mimeType.substringAfter('/', "jpg")
        return sendMessage(talkerId, sessionType, 2, MessageSendPayloadFactory.buildImageContent(
            image.imageUrl, image.imageWidth, image.imageHeight, imageType, image.imgSize))
    }

    suspend fun withdrawMessage(talkerId: Long, message: PrivateMessageItem, sessionType: Int = 1): SendMessageData {
        requireCommunityWithdraw(message, repository.requireAccount().mid)
        require(message.receiver_id == talkerId && message.receiver_type == sessionType) { "消息不属于当前会话" }
        return sendMessage(talkerId, sessionType, 5, MessageSendPayloadFactory.buildWithdrawContent(message.msg_key), message.sender_uid)
    }

    suspend fun markSessionRead(talkerId: Long, sessionType: Int, ackSeqno: Long) {
        require(talkerId > 0 && sessionType in 1..2 && ackSeqno > 0)
        mutate { csrf ->
            val response = messages.updateAck(talkerId, sessionType, ackSeqno, csrf, csrf)
            check(response.code, response.message)
        }
    }

    private suspend fun sendMessage(talkerId: Long, sessionType: Int, type: Int, content: String, expectedSender: Long? = null): SendMessageData {
        require(talkerId > 0 && sessionType in 1..2)
        return mutate { csrf ->
            val sender = repository.requireAccount().mid
            if (expectedSender != null && sender != expectedSender) throw BiliApiException(-101, "账号已切换，请重新操作")
            require(talkerId != sender || sessionType != 1) { "不能给自己发送私信" }
            val response = messages.sendMsg(senderUid = sender, receiverId = talkerId, receiverType = sessionType,
                msgType = type, content = content, timestamp = System.currentTimeMillis() / 1000,
                devId = messageDeviceId, csrf = csrf, csrfToken = csrf)
            verified(response.code, response.message.ifBlank { response.msg }, response.data, "私信发送结果")
        }
    }

    suspend fun savePrivateNote(aid: Long, document: VideoNoteEditorDocument, noteId: String? = null,
        publish: Boolean = false): String {
        val encoded = VideoNoteContentCodec.encode(document)
        require(aid > 0 && encoded.contentLength > 0) { "请输入笔记内容" }
        require(noteId == null || noteId.toLongOrNull()?.let { it > 0 } == true) { "笔记编号无效" }
        return mutate { csrf ->
            val response = api.saveVideoNote(communityNoteFields(aid, document, encoded, noteId, publish, csrf))
            check(response.code, response.message)
            response.data?.noteId?.takeIf { it > 0 }?.toString() ?: noteId
                ?: throw BiliApiException(-1, "保存成功但缺少笔记 ID，请刷新笔记列表")
        }
    }

    suspend fun deletePrivateNote(aid: Long, noteId: String) {
        require(aid > 0 && noteId.toLongOrNull()?.let { it > 0 } == true)
        mutate { csrf -> val response = api.deleteVideoNote(aid, noteId, csrf); check(response.code, response.message) }
    }

    suspend fun uploadDynamicImage(fileName: String, mimeType: String, bytes: ByteArray): DynamicCreatePic {
        validateCommunityImage(fileName, mimeType, bytes)
        return mutate { csrf ->
            val response = api.uploadCommentImage(imagePart(fileName, mimeType, bytes), "daily".toRequestBody(TEXT_MEDIA),
                "new_dyn".toRequestBody(TEXT_MEDIA), csrf.toRequestBody(TEXT_MEDIA))
            val image = verified(response.code, response.message, response.data, "动态图片上传")
            DynamicCreatePic(image.imageUrl, image.imageWidth, image.imageHeight, image.imgSize)
        }
    }

    suspend fun publishDynamic(draft: DynamicPublishDraft, images: List<DynamicCreatePic> = draft.existingImages): DynamicCreateFeedData? {
        require(draft.imageUris.isEmpty()) { "请先上传选中的本地图片" }
        require(draft.text.isNotBlank() || images.isNotEmpty()) { "请输入文字或选择图片" }
        require(images.size <= 9) { "最多选择 9 张图片" }
        return mutate { csrf ->
            val contents = buildDynamicCreateContents(draft.text, draft.voteId, draft.voteTitle, draft.mentions, draft.emotes)
            val reserve = draft.reserveId.takeIf { it > 0 }?.let { id -> kotlinx.serialization.json.buildJsonObject {
                put("common_card", kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive(14)); put("biz_id", kotlinx.serialization.json.JsonPrimitive(id))
                    put("reserve_source", kotlinx.serialization.json.JsonPrimitive(0)); put("reserve_lottery", kotlinx.serialization.json.JsonPrimitive(0))
                })
            } }
            val request = DynamicCreateFeedRequest(DynamicCreateFeedReq(
                content = DynamicCreateFeedContent(contents.ifEmpty { listOf(DynamicRepostContentItem(" ", 1, "")) }, draft.title.trim().takeIf { it.isNotEmpty() }),
                scene = resolveDynamicCreateScene(images.isNotEmpty()), pics = images.takeIf { it.isNotEmpty() }, attach_card = reserve,
                option = if (draft.private) DynamicCreateOption(private_pub = 1) else null,
                topic = draft.topic?.takeIf { it.id > 0 }?.let { DynamicCreateTopic(it.id, it.name) },
                upload_id = "${repository.requireAccount().mid}_${System.currentTimeMillis() / 1000}_${Random.nextInt(1000, 10000)}"))
            val response = dynamic.createFeedDynamic(csrf = csrf, body = request)
            check(response.code, response.message)
            return@mutate response.data
        }
    }

    suspend fun setCommentLike(target: CommunityCommentTarget, rpid: Long, liked: Boolean) {
        require(rpid > 0)
        mutate { csrf -> val response = api.likeReply(target.oid, target.type, rpid, if (liked) 1 else 0, csrf); check(response.code, response.message) }
    }

    suspend fun deleteComment(target: CommunityCommentTarget, rpid: Long) {
        require(rpid > 0)
        mutate { csrf -> val response = api.deleteReply(target.oid, target.type, rpid, csrf); check(response.code, response.message) }
    }

    suspend fun personalHistory(cursor: CloudHistoryCursor? = null): PersonalResourcePage<CloudHistoryCursor> = read(accountOnly = true,
        validate = { require(cursor == null || cursor.max >= 0 && cursor.viewAt >= 0) }) {
        // HistoryRepository omits type for all-business history and omits the initial empty cursor.
        val params = resolveHistoryCursorQuery(cursor?.max ?: 0, cursor?.viewAt ?: 0, cursor?.business)
        val response = api.getHistoryList(ps = 30, max = params.max, viewAt = params.viewAt, business = params.business, type = null)
        val data = verified(response.code, response.message, response.data, "云端历史")
        PersonalResourcePage(data.list.orEmpty().map { PersonalResource.History(it.toHistoryItem()) }, personalHistoryPage(data, cursor).nextCursor)
    }

    suspend fun deleteHistoryResource(item: HistoryItem) {
        val kid = com.android.purebilibili.feature.list.resolveHistoryDeleteKid(item)
        require(!kid.isNullOrBlank()) { "这条历史记录缺少可删除的资源编号" }
        mutate { csrf -> val response = api.deleteHistoryItem(kid, csrf); check(response.code, response.message) }
    }

    suspend fun spaceCollections(mid: Long): SeasonsSeriesData = read(validate = { require(mid > 0) }) {
        val response = space.getSeasonsSeriesList(mid)
        verified(response.code, response.message, response.data, "UP 主合集和系列")
    }

    suspend fun personalFavorites(folderId: Long, page: Int = 1): PersonalResourcePage<Int> = read(accountOnly = true,
        validate = { require(folderId > 0 && page > 0) }) {
        val response = api.getFavoriteList(mediaId = folderId, pn = page, ps = 20)
        val data = verified(response.code, response.message, response.data, "收藏夹内容")
        val items = data.medias.orEmpty()
        PersonalResourcePage(items.map { PersonalResource.Favorite(it) }, (page + 1).takeIf { page < Int.MAX_VALUE && data.has_more && items.isNotEmpty() })
    }

    suspend fun personalWatchLater(page: Int = 1): PersonalResourcePage<Int> = read(accountOnly = true, validate = { require(page > 0) }) {
        val response = api.getWatchLaterPage(repository.signWebParams(personalWatchLaterParams(page)))
        val data = verified(response.code, response.message, response.data, "稍后再看")
        val items = data.list.orEmpty()
        PersonalResourcePage(items.map { PersonalResource.WatchLater(it) }, (page + 1).takeIf { page < Int.MAX_VALUE && items.isNotEmpty() && page.toLong() * 20 < data.count })
    }

    suspend fun likedVideos(page: Int = 1): VideoPage = read(accountOnly = true, validate = { require(page > 0) }) {
        val mid = repository.requireAccount().mid
        val credentials = repository.accessTokenCredentials()
        val app = try { space.getSpaceLikedArchive(buildSpaceLikedArchiveParams(mid, page, 20, credentials.first, credentials.second)) }
        catch (error: Exception) { if (error is CancellationException) throw error; null }
        val response = if (app != null && app.code == 0 && (app.data?.item?.isNotEmpty() == true || (app.data?.count ?: 0) > 0 || page > 1)) app
            else api.getLikedVideos(mid = mid, page = page, pageSize = 20)
        val data = verified(response.code, response.message, response.data, "赞过的视频")
        communityLikedPage(data, page)
    }

    suspend fun collectionVideos(mid: Long, collectionId: Long, collectionType: String, page: Int = 1): CommunityCollectionPage = read(validate = {
        require(collectionId > 0 && page > 0 && collectionType in setOf("favorite_season", "season", "series"))
        require(mid > 0 || collectionType == "favorite_season")
    }) {
        when (collectionType) {
            "favorite_season" -> {
                val response = api.getFavoriteSeasonList(seasonId = collectionId, pn = page, ps = 20)
                val data = verified(response.code, response.message, response.data, "收藏合集")
                val videos = personalFavoritePage(data)
                CommunityCollectionPage(videos.items, (page + 1).takeIf { page < Int.MAX_VALUE && videos.hasMore }, data.info?.title.orEmpty(), favorite = data)
            }
            "season" -> {
                val response = space.getSeasonArchives(mid, collectionId, pageNum = page, pageSize = 30)
                val data = verified(response.code, response.message, response.data, "UP 主合集")
                CommunityCollectionPage(data.archives.map { item -> VideoCard(item.bvid, item.title, personalImageUrl(item.pic), item.author,
                    item.stat.view, item.duration, publishedAt = item.pubdate, authorMid = mid) },
                    communityNumberedNext(page, data.page.page_size.takeIf { it > 0 } ?: 30, data.page.total, data.archives.size), data.meta.name, season = data)
            }
            else -> {
                val response = space.getSeriesArchives(mid, collectionId, pn = page, ps = 30)
                val data = verified(response.code, response.message, response.data, "UP 主系列")
                CommunityCollectionPage(data.archives.map { item -> VideoCard(item.bvid, item.title, personalImageUrl(item.pic), item.author,
                    item.stat.view, item.duration, publishedAt = item.pubdate, authorMid = mid) },
                    communityNumberedNext(page, data.page.size.takeIf { it > 0 } ?: 30, data.page.total, data.archives.size), "系列 $collectionId", series = data)
            }
        }
    }

    suspend fun dynamicFeed(type: String = "all", offset: String = "", updateBaseline: String = ""): DynamicPage =
        read(accountOnly = true, validate = { require(type in setOf("all", "video", "pgc", "article")) }) {
            val response = dynamic.getDynamicFeed(type = type, offset = offset, updateBaseline = updateBaseline)
            communityDynamicPage(verified(response.code, response.message, response.data, "动态列表"), offset)
        }

    suspend fun spaceDynamics(mid: Long, offset: String = ""): DynamicPage =
        read(accountOnly = true, validate = { require(mid > 0) }) {
            val response = dynamic.getUserDynamicFeed(communitySpaceDynamicParams(mid, offset))
            communityDynamicPage(verified(response.code, response.message, response.data, "UP 主动态"), offset)
        }

    suspend fun dynamicDetail(id: String): DynamicDetailData = read(validate = { require(id.trim().toLongOrNull()?.let { it > 0 } == true) }) {
        val response = dynamic.getDynamicDetail(id = id.trim())
        verified(response.code, response.message, response.data, "动态详情")
    }

    suspend fun opusDetail(id: String): DynamicDetailData = read(validate = { require(id.trim().toLongOrNull()?.let { it > 0 } == true) }) {
        fetchOpus(id.trim())
    }

    suspend fun dynamicComments(target: CommunityCommentTarget, page: Int = 1, sort: Int = 0): CommentPage =
        read(validate = { require(page > 0 && sort in 0..2) }) {
            val response = dynamic.getDynamicReplies(oid = target.oid, type = target.type, pn = page, sort = sort)
            socialCommentPage(verified(response.code, response.message, response.data, "动态评论"), page, nested = false)
        }

    suspend fun dynamicCommentReplies(target: CommunityCommentTarget, rootId: Long, page: Int = 1): CommentPage =
        read(validate = { require(rootId > 0 && page > 0) }) {
            val response = api.getReplyReply(oid = target.oid, type = target.type, root = rootId, pn = page)
            socialCommentPage(verified(response.code, response.message, response.data, "评论回复"), page, nested = true)
        }

    suspend fun publishDynamicComment(target: CommunityCommentTarget, message: String, rootId: Long? = null,
        parentId: Long? = null): PublishedComment {
        require(message.isNotBlank()) { "请输入评论内容" }
        val destination = commentTarget(rootId, parentId)
        return mutate { csrf ->
            val response = api.addReply(oid = target.oid, type = target.type, message = message,
                root = destination.root, parent = destination.parent, csrf = csrf)
            check(response.code, response.message)
            val data = response.data
            PublishedComment(data?.rpid?.takeIf { it > 0 } ?: data?.rpidStr?.toLongOrNull()?.takeIf { it > 0 },
                data?.reply?.let { socialComment(it) })
        }
    }

    suspend fun setDynamicLike(id: String, liked: Boolean) {
        require(id.trim().toLongOrNull()?.let { it > 0 } == true)
        mutate { csrf ->
            val response = dynamic.likeDynamic(csrf = csrf, body = DynamicThumbRequest(id.trim(), if (liked) 1 else 2))
            check(response.code, response.message)
        }
    }

    suspend fun repostDynamic(id: String, message: String = "") {
        require(id.trim().toLongOrNull()?.let { it > 0 } == true)
        mutate { csrf ->
            val response = dynamic.repostDynamic(csrf = csrf, body = buildDynamicRepostRequest(id.trim(), message))
            check(response.code, response.message)
        }
    }

    suspend fun spaceVideos(mid: Long, page: Int = 1, order: String = "pubdate", categoryId: Int = 0,
        keyword: String = ""): SpaceVideoPage = read(validate = {
        require(mid > 0 && page > 0 && categoryId >= 0 && order in setOf("pubdate", "click", "stow"))
    }) {
        val response = space.getSpaceVideos(repository.signWebParams(communitySpaceVideoParams(mid, page, order, categoryId, keyword)))
        val data = verified(response.code, response.message, response.data, "UP 主投稿")
        SpaceVideoPage(data, communityNumberedNext(maxOf(page, data.page.pn), data.page.ps.takeIf { it > 0 } ?: 30,
            data.page.count, data.list.vlist.size))
    }

    /** Original UP space params are supplied by its original UID-pagination repository. */
    internal suspend fun dynamicSelectedUserPage(params:Map<String,String>):DynamicFeedResponse {
        val expectedEpoch=repository.sessionEpoch;val expectedMid=repository.account.value?.mid
        fun owns()=repository.sessionEpoch==expectedEpoch&&repository.account.value?.mid==expectedMid
        val result=read(accountOnly=true,validate={require(params["host_mid"]?.toLongOrNull()?.let{it>0}==true)}) {
            if(!owns())throw kotlinx.coroutines.CancellationException("Dynamic source retired")
            dynamic.getUserDynamicFeed(params)
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if(!owns())throw kotlinx.coroutines.CancellationException("Dynamic source retired")
        return result
    }
    internal suspend fun dynamicFollowedLiveUsers():List<LiveRoom> {
        val expectedEpoch=repository.sessionEpoch;val expectedMid=repository.account.value?.mid
        val result=read(accountOnly=true) {
            val response=api.getFollowedLive(page=1,pageSize=50)
            check(response.code,response.message)
            com.android.purebilibili.data.repository.desktopOriginalDynamicFollowedLiveUsers(response)
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if(repository.sessionEpoch!=expectedEpoch||repository.account.value?.mid!=expectedMid)throw kotlinx.coroutines.CancellationException("Dynamic source retired")
        return result
    }
    internal suspend fun dynamicUnreadUsers():UplistData? {
        val expectedEpoch=repository.sessionEpoch;val expectedMid=repository.account.value?.mid
        val result=read(accountOnly=true) {
            repository.requireCsrf()
            val response=dynamic.getDynamicUplist()
            check(response.code,response.message)
            response.data
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if(repository.sessionEpoch!=expectedEpoch||repository.account.value?.mid!=expectedMid)throw kotlinx.coroutines.CancellationException("Dynamic source retired")
        return result
    }

    suspend fun followings(mid: Long, page: Int = 1): RelationPage = read(validate = { require(mid > 0 && page > 0) }) {
        val response = api.getFollowings(vmid = mid, pn = page, ps = 50)
        val data = verified(response.code, response.message, response.data, "关注列表")
        RelationPage(data, communityNumberedNext(page, 50, data.total, data.list.orEmpty().size))
    }

    suspend fun relationStats(mid: Long): RelationStatData = read(validate = { require(mid > 0) }) {
        val response = space.getRelationStat(mid)
        verified(response.code, response.message, response.data, "关注和粉丝数")
    }

    suspend fun typedSearch(keyword: String, type: SearchType = SearchType.VIDEO, page: Int = 1,
        filters: Map<String, String> = emptyMap()): TypedSearchPage = read(validate = {
        require(keyword.isNotBlank() && page > 0)
        require(filters.keys.all { it in COMMUNITY_SEARCH_FILTERS }) { "不支持的搜索筛选参数" }
    }) {
        val params = repository.signWebParams(communitySearchParams(keyword.trim(), type, page, filters))
        val result: CommunitySearchResult
        val next: Int?
        when (type) {
            SearchType.VIDEO -> {
                val r = searchApi.search(params); val d = verified(r.code, r.message, r.data, "视频搜索")
                result = CommunitySearchResult.Videos(d)
                next = communitySearchNext(page, d.page, d.numPages, d.result.orEmpty().size, video = true)
            }
            SearchType.UP -> {
                val r = searchApi.searchUp(params); val d = verified(r.code, r.message, r.data, "UP 主搜索")
                result = CommunitySearchResult.Users(d)
                next = communitySearchNext(page, d.page, d.numPages, d.result.orEmpty().size, video = false)
            }
            SearchType.BANGUMI, SearchType.MEDIA_FT -> {
                val r = if (type == SearchType.BANGUMI) searchApi.searchBangumi(params) else searchApi.searchMediaFt(params)
                val d = verified(r.code, r.message, r.data, "番剧影视搜索")
                result = CommunitySearchResult.Media(d)
                next = communitySearchNext(page, d.page, d.numPages, d.result.orEmpty().size, video = false)
            }
            SearchType.LIVE -> {
                val r = searchApi.searchLive(params); val d = verified(r.code, r.message, r.data, "直播间搜索")
                result = CommunitySearchResult.LiveRooms(d)
                next = communitySearchNext(page, d.page, d.numPages, d.result.orEmpty().size, video = false)
            }
            SearchType.LIVE_USER -> {
                val r = searchApi.searchLiveUser(params); val d = verified(r.code, r.message, r.data, "主播搜索")
                result = CommunitySearchResult.LiveUsers(d)
                next = communitySearchNext(page, d.page, d.numPages, d.result.orEmpty().size, video = false)
            }
            SearchType.ARTICLE -> {
                val r = searchApi.searchArticle(params); val d = verified(r.code, r.message, r.data, "专栏搜索")
                result = CommunitySearchResult.Articles(d)
                next = communitySearchNext(page, d.page, d.numPages, d.result.orEmpty().size, video = false)
            }
            SearchType.TOPIC -> {
                val r = searchApi.searchTopic(params); val d = verified(r.code, r.message, r.data, "话题搜索")
                result = CommunitySearchResult.Topics(d)
                next = communitySearchNext(page, d.page, d.numPages, d.result.orEmpty().size, video = false)
            }
            SearchType.PHOTO -> {
                val r = searchApi.searchPhoto(params); val d = verified(r.code, r.message, r.data, "相簿搜索")
                result = CommunitySearchResult.Photos(d)
                next = communitySearchNext(page, d.page, d.numPages, d.result.orEmpty().size, video = false)
            }
        }
        TypedSearchPage(type, result, next)
    }

    suspend fun searchSuggestions(term: String): List<SearchSuggestTag> {
        if (term.isBlank()) return emptyList()
        return read {
            val response = searchApi.getSearchSuggest(term.trim())
            check(response.code, "获取搜索建议失败")
            response.result?.tag.orEmpty()
        }
    }

    suspend fun searchHotWords(limit: Int = 30): HotSearchData = read(validate = { require(limit in 1..50) }) {
        val response = searchApi.getHotSearch(repository.signWebParams(mapOf("limit" to limit.toString())))
        verified(response.code, response.message, response.data, "热搜")
    }

    suspend fun searchDefault(): SearchDefaultData = read {
        val response = searchApi.getDefaultSearch(repository.signWebParams(emptyMap()))
        verified(response.code, response.message, response.data, "默认搜索词")
    }

    suspend fun unreadMessages(): CommunityUnread = read(accountOnly = true) {
        val privateResponse = messages.getUnreadCount()
        val privateData = verified(privateResponse.code, privateResponse.message.ifBlank { privateResponse.msg }, privateResponse.data, "私信未读数")
        val activity = messages.getFeedUnread()
        CommunityUnread(privateData, verified(activity.code, activity.message.ifBlank { activity.msg }, activity.data, "消息未读数"))
    }

    suspend fun replyMessages(cursor: CommunityMessageCursor? = null): ReplyFeedPage = read(accountOnly = true) {
        val response = messages.getReplyFeed(cursor = cursor?.id, cursorTime = cursor?.time)
        val data = verified(response.code, response.message.ifBlank { response.msg }, response.data, "回复我的")
        ReplyFeedPage(data, communityMessageNext(data.cursor, cursor, data.items.orEmpty().size))
    }

    suspend fun mentionMessages(cursor: CommunityMessageCursor? = null): MentionFeedPage = read(accountOnly = true) {
        val response = messages.getAtFeed(cursor = cursor?.id, cursorTime = cursor?.time)
        val data = verified(response.code, response.message.ifBlank { response.msg }, response.data, "@ 我的")
        MentionFeedPage(data, communityMessageNext(data.cursor, cursor, data.items.orEmpty().size))
    }

    suspend fun likeMessages(cursor: CommunityMessageCursor? = null): LikeFeedPage = read(accountOnly = true) {
        val response = messages.getLikeFeed(cursor = cursor?.id, cursorTime = cursor?.time)
        val data = verified(response.code, response.message.ifBlank { response.msg }, response.data, "收到的赞")
        LikeFeedPage(data, communityMessageNext(data.total?.cursor, cursor, data.total?.items.orEmpty().size))
    }

    suspend fun systemNotices(cursor: Long? = null): SystemNoticePage = read(accountOnly = true,
        validate = { require(cursor == null || cursor > 0) }) {
        val response = messages.getSystemNotices(cursor = cursor)
        check(response.code, response.message.ifBlank { response.msg })
        val items = response.data.orEmpty()
        SystemNoticePage(items, items.lastOrNull()?.cursor?.takeIf { items.size >= 20 && it > 0 && it != cursor })
    }

    suspend fun messageSessions(cursor: CommunitySessionCursor = CommunitySessionCursor(1, 0)): CommunitySessionPage =
        read(accountOnly = true) {
            val response = messages.getSessions(pn = cursor.page, endTs = cursor.endTs, size = 50)
            communitySessionPage(verified(response.code, response.message.ifBlank { response.msg }, response.data, "私信会话"), cursor)
        }

    suspend fun messageHistory(talkerId: Long, sessionType: Int = 1, endSeqno: Long = 0): CommunityMessageHistory =
        read(accountOnly = true, validate = { require(talkerId > 0 && sessionType in 1..2 && endSeqno >= 0) }) {
            val response = messages.fetchSessionMsgs(talkerId = talkerId, sessionType = sessionType, size = 30, endSeqno = endSeqno)
            communityMessageHistory(verified(response.code, response.message.ifBlank { response.msg }, response.data, "私信记录"), endSeqno)
        }

    suspend fun articleDetail(articleId: Long, includeOpus: Boolean = true): ArticleDocument = read(validate = { require(articleId > 0) }) {
        val expectedEpoch = repository.sessionEpoch
        val expectedAccountMid = repository.account.value?.mid
        val response = article.getArticleView(repository.signWebParams(mapOf("id" to articleId.toString(),
            "gaia_source" to "main_web", "web_location" to "333.976")))
        val data = verified(response.code, response.message, response.data, "专栏详情")
        val opus = if (includeOpus && data.dynamicId.isNotBlank()) fetchOpus(data.dynamicId) else null
        articleContentWithBestEffortHistory(communityArticleDocument(data, opus)) {
            articleHistoryReporter.report(data.id, expectedEpoch, expectedAccountMid)
        }
    }

    suspend fun playerMetadata(bvid: String, cid: Long): PlayerInfoData = read(validate = { require(bvid.isNotBlank() && cid > 0) }) {
        val response = api.getPlayerInfo(repository.signWebParams(mapOf("bvid" to bvid, "cid" to cid.toString())))
        verified(response.code, response.message, response.data, "字幕和章节信息")
    }

    suspend fun aiSummary(bvid: String, cid: Long, upMid: Long = 0): AiSummaryData = read(validate = {
        require(bvid.isNotBlank() && cid > 0 && upMid >= 0)
    }) {
        val response = api.getAiConclusion(repository.signWebParams(buildMap {
            put("bvid", bvid); put("cid", cid.toString()); if (upMid > 0) put("up_mid", upMid.toString())
        }))
        // data.code/status/modelResult describe absence or unsupported content; preserve them for the UI.
        verified(response.code, response.message, response.data, "AI 总结")
    }

    suspend fun privateNoteIds(aid: Long): VideoNoteArchiveListData = read(accountOnly = true, validate = { require(aid > 0) }) {
        // The upstream GET declares csrf optional; the shared authorized session is sufficient for this read.
        val response = api.getPrivateVideoNoteIds(oid = aid)
        verified(response.code, response.message, response.data, "私人笔记列表")
    }

    suspend fun privateNote(aid: Long, noteId: String): VideoNoteInfoData = read(accountOnly = true,
        validate = { require(aid > 0 && noteId.isNotBlank()) }) {
        val response = api.getPrivateVideoNoteInfo(oid = aid, noteId = noteId)
        verified(response.code, response.message, response.data, "私人笔记")
    }

    suspend fun noteAvailability(aid: Long): VideoNoteForbidData = read(validate = { require(aid > 0) }) {
        val response = api.getVideoNoteForbidState(aid)
        verified(response.code, response.message, response.data, "笔记入口")
    }

    suspend fun publicNotes(aid: Long, page: Int = 1): PublicNotePage = read(validate = { require(aid > 0 && page > 0) }) {
        val response = api.getPublicVideoNoteList(oid = aid, pageNumber = page)
        val data = verified(response.code, response.message, response.data, "公开笔记")
        PublicNotePage(data, communityNumberedNext(maxOf(page, data.page.num), data.page.size.takeIf { it > 0 } ?: 10,
            data.page.total, data.list.size))
    }

    suspend fun publicNote(cvid: Long): PublicVideoNoteInfoData = read(validate = { require(cvid > 0) }) {
        val response = api.getPublicVideoNoteInfo(cvid)
        verified(response.code, response.message, response.data, "公开笔记详情")
    }

    private suspend fun fetchOpus(id: String): DynamicDetailData {
        val response = dynamic.getOpusDetail(repository.signWebParams(mapOf("id" to id, "timezone_offset" to "-480",
            "features" to OPUS_DETAIL_FEATURES)))
        return verified(response.code, response.message, response.data, "图文详情")
    }

    private suspend fun <T> read(accountOnly: Boolean = false, validate: () -> Unit = {}, action: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            validate()
            val account = if (accountOnly) repository.requireAccount() else null
            repository.ensureSession()
            if (account != null && repository.requireAccount().mid != account.mid) throw BiliApiException(-101, "账号已切换，请重新加载")
            action()
        }

    private suspend fun <T> mutate(action: suspend (String) -> T): T = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val account = repository.requireAccount()
            repository.requireCsrf()
            repository.ensureSession()
            if (repository.requireAccount().mid != account.mid) throw BiliApiException(-101, "账号已切换，请重新操作")
            action(repository.requireCsrf())
        }
    }

    private fun <T : Any> verified(code: Int, message: String, data: T?, label: String): T {
        check(code, message)
        return data ?: throw BiliApiException(-1, "$label 数据为空")
    }

    private fun check(code: Int, message: String) {
        if (code == -101 || code == -111) throw BiliApiException(code, "登录凭证已失效，请重新登录后重试")
        if (code != 0) throw BiliApiException(code, message.ifBlank { "请求失败 ($code)" })
    }
}

internal val COMMUNITY_SEARCH_FILTERS = setOf("order", "duration", "tids", "pubtime_begin_s", "pubtime_end_s",
    "order_sort", "user_type", "category_id")

private val TEXT_MEDIA = "text/plain".toMediaType()
private fun imagePart(fileName: String, mimeType: String, bytes: ByteArray) =
    MultipartBody.Part.createFormData("file_up", fileName, bytes.toRequestBody(mimeType.toMediaType()))

internal fun validateCommunityImage(fileName: String, mimeType: String, bytes: ByteArray) {
    require(fileName.isNotBlank() && !fileName.contains('\r') && !fileName.contains('\n')) { "图片文件名无效" }
    require(mimeType in setOf("image/jpeg", "image/png", "image/gif", "image/webp")) { "请选择 JPG、PNG、GIF 或 WebP 图片" }
    require(bytes.isNotEmpty() && bytes.size <= 15 * 1024 * 1024) { "图片为空或超过 15MB" }
}

internal fun requireCommunityWithdraw(message: PrivateMessageItem, mid: Long) {
    require(mid > 0 && message.sender_uid == mid) { "只能撤回自己发送的消息" }
    require(message.msg_key > 0 && message.msg_status != 1 && !message.sys_cancel) { "此消息无法撤回" }
}

internal fun communityMessageTextContent(text: String): String {
    // Preserve the upstream payload for its supported input; JSON-encode remaining control characters.
    if (text.none { it.code < 0x20 && it != '\n' }) return MessageSendPayloadFactory.buildTextContent(text)
    return kotlinx.serialization.json.buildJsonObject {
        put("content", kotlinx.serialization.json.JsonPrimitive(text))
    }.toString()
}

internal fun communityLikedPage(data: LikedVideosData, page: Int): VideoPage {
    // Same detailed/aggregate selection as LikedVideosRepository.
    val details = data.list.map { it.toVideoItem() }
    val aggregate = data.item.map { item ->
        val aid = item.aid.takeIf { it > 0 } ?: item.param.toLongOrNull() ?: 0
        val bvid = item.bvid.ifBlank { item.param.takeIf { it.startsWith("BV", ignoreCase = true) }
            ?: aid.takeIf { it > 0 }?.let(IdUtils::av2bv).orEmpty() }
        VideoItem(id = aid, aid = aid, bvid = bvid, cid = item.firstCid, title = item.title, pic = item.cover,
            owner = Owner(name = item.author), stat = Stat(view = item.play, danmaku = item.danmaku, reply = item.reply),
            duration = item.duration, pubdate = item.ctime, tname = item.tname)
    }
    val items = details.ifEmpty { aggregate }
    val cards = items.map { item -> VideoCard(item.bvid, item.title, personalImageUrl(item.pic), item.owner.name,
        item.stat.view.toLong(), item.duration, preferredCid = item.cid, publishedAt = item.pubdate, authorMid = item.owner.mid) }
    val total = data.count.takeIf { it > 0 } ?: items.size
    return VideoPage(cards, items.isNotEmpty() && page.toLong() * 20 < total, total)
}

internal fun communityNoteFields(aid: Long, document: VideoNoteEditorDocument, encoded: EncodedVideoNoteContent,
    noteId: String?, publish: Boolean, csrf: String): Map<String, String> = buildMap {
    put("oid", aid.toString()); put("oid_type", "0"); put("title", document.title.trim())
    put("summary", encoded.summary); put("content", encoded.content); put("tags", encoded.tags)
    put("cls", "1"); put("from", "save"); put("cont_len", encoded.contentLength.toString())
    put("platform", "web"); put("publish", if (publish) "1" else "0"); put("csrf", csrf)
    if (!noteId.isNullOrBlank()) put("note_id", noteId)
}

internal fun communitySearchParams(keyword: String, type: SearchType, page: Int, filters: Map<String, String>): Map<String, String> =
    com.android.purebilibili.data.repository.desktopSearchTypeParams(keyword, type.value, page, filters)

internal fun communitySpaceDynamicParams(mid: Long, offset: String): Map<String, String> =
    mapOf("host_mid" to mid.toString(), "offset" to offset, "features" to SPACE_DYNAMIC_FEATURES,
        "timezone_offset" to "-480", "platform" to "web", "web_location" to "333.1387")

internal fun communitySpaceVideoParams(mid: Long, page: Int, order: String, categoryId: Int, keyword: String): Map<String, String> =
    buildMap {
        put("mid", mid.toString()); put("pn", page.toString()); put("ps", "30"); put("order", order)
        if (categoryId > 0) put("tid", categoryId.toString())
        if (keyword.isNotBlank()) put("keyword", keyword)
    }

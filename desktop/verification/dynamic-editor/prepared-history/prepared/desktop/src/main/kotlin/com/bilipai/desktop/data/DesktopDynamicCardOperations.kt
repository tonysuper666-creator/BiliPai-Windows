package com.bilipai.desktop.data

import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopDynamicVoteRepository
import com.android.purebilibili.feature.dynamic.DynamicDeleteAction
import com.android.purebilibili.feature.dynamic.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.random.Random
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.coroutineContext

/** Windows lifetime binding for the original DynamicApi and request models.
 * The selected endpoint, fields and response handling are from DynamicViewModel,
 * DynamicVoteRepository and DynamicRepository, not a parallel protocol.
 */
internal class DesktopDynamicCardOperations(
    private val repository: DesktopRepository,
    val expectedEpoch: Long = repository.sessionEpoch,
    private val stillOwned: () -> Boolean = { true },
    sharedEmotes: com.bilipai.desktop.ui.DesktopDynamicEmotes? = null,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val ownerJar = repository.httpClient.cookieJar
    private fun assertOwned() {
        if (!isOwned()) throw CancellationException("Dynamic card owner retired")
    }
    fun isOwned(): Boolean = repository.sessionEpoch == expectedEpoch && stillOwned()
    private val jar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            assertOwned(); return ownerJar.loadForRequest(url).also { assertOwned() }
        }
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (isOwned()) ownerJar.saveFromResponse(url, cookies)
        }
    }
    private fun guardedClient(anonymous: Boolean = false) = repository.httpClient.newBuilder().apply {
        cookieJar(if (anonymous) CookieJar.NO_COOKIES else jar)
        interceptors().add(0, okhttp3.Interceptor { chain ->
            if (!isOwned()) throw IOException("Dynamic card owner retired")
            val response = chain.proceed(chain.request())
            if (!isOwned()) { response.close(); throw IOException("Dynamic card owner retired") }
            response
        })
        addNetworkInterceptor { chain ->
            if (!isOwned()) throw IOException("Dynamic card owner retired")
            val response = chain.proceed(chain.request())
            if (!isOwned()) {
                response.close(); throw IOException("Dynamic card owner retired")
            }
            response
        }
    }.build()
    private fun service(anonymous: Boolean = false) = Retrofit.Builder()
        .baseUrl("https://api.bilibili.com/").client(guardedClient(anonymous))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    private val web = service()
    private val dynamic = web.create(DynamicApi::class.java)
    private val api = web.create(BilibiliApi::class.java)
    private val upSearch = com.android.purebilibili.data.repository.DesktopDynamicUpSearch(web.create(SearchApi::class.java),
        { params -> assertOwned(); repository.signWebParams(params).also { assertOwned() } })
    private val space = web.create(SpaceApi::class.java)
    private val messages = Retrofit.Builder().baseUrl("https://api.vc.bilibili.com/")
        .client(guardedClient()).addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build().create(MessageApi::class.java)
    private val messageSessions = com.android.purebilibili.data.repository.DesktopDynamicMessageSessions(messages)
    private val grpc = com.android.purebilibili.core.network.grpc.DesktopDynamicBiliGrpcClient(guardedClient(),
        { assertOwned(); repository.authCookies() }, { assertOwned(); repository.account.value?.mid },
        { assertOwned(); repository.accessTokenCredentials().first }, ::isOwned)
    private val messageShare = com.android.purebilibili.data.repository.DesktopDynamicMessageShareRepository(grpc::request,
        { assertOwned(); repository.account.value?.mid })
    private val userInfo = com.android.purebilibili.feature.message.DesktopDynamicMessageUserInfoLoader(api, space,
        { params -> assertOwned(); repository.signWebParams(params).also { assertOwned() } })
    private val guestWeb = service(anonymous = true)
    private val guestDynamic = guestWeb.create(DynamicApi::class.java)
    private val vote = DesktopDynamicVoteRepository(dynamic, { assertOwned(); repository.requireCsrf() },
        { assertOwned(); repository.requireAccount().mid })
    val emotes: com.bilipai.desktop.ui.DesktopDynamicEmotes = sharedEmotes ?: DesktopDynamicEmoteCatalog(api,
        { repository.account.value?.mid ?: 0L }, { repository.account.value != null }, ::isOwned)

    private suspend fun <T> read(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive(); assertOwned(); repository.ensureSession(); assertOwned()
        block().also { coroutineContext.ensureActive(); assertOwned() }
    }
    private suspend fun <T> mutate(block: suspend (String) -> T): T = mutex.withLock {
        read { repository.requireAccount(); block(repository.requireCsrf()) }
    }
    private fun checked(code: Int, message: String) {
        if (code != 0) throw BiliApiException(code, message.ifBlank { "动态操作失败" })
    }
    private suspend fun <T> result(block: suspend () -> T): Result<T> = try { Result.success(block()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { assertOwned(); Result.failure(failure) }

    suspend fun getVoteInfo(voteId: Long): Result<DynamicVoteInfo> = result {
        read { vote.getVoteInfo(voteId).getOrThrow() }
    }
    suspend fun submitVote(voteId: Long, options: List<Int>, dynamicId: String, anonymous: Boolean = false): Result<DynamicVoteInfo> = result {
        mutate { vote.submitVote(voteId, options, dynamicId, anonymous).getOrThrow() }
    }
    suspend fun getShareTargets(size:Int=5) = result { read { messageShare.getShareTargets(size).getOrThrow() } }
    suspend fun getMessageSessions(size:Int=30) = result { read { messageSessions.getSessions(size=size).getOrThrow() } }
    suspend fun fetchMessageUserInfo(mid:Long) = read { userInfo.fetch(mid) }
    suspend fun sendDynamicShare(receiverId:Long,content:String) = result {
        mutate { messageShare.sendDynamicShare(receiverId,content).getOrThrow() }
    }
    suspend fun searchUp(name:String) = result { read { upSearch.searchUp(name).getOrThrow() } }
    suspend fun addWatchLater(aid:Long) = mutate { csrf ->
        require(aid>0);val response=api.addToWatchLater(aid,csrf);checked(response.code,response.message)
    }
    suspend fun postSourceComment(item:DynamicItem,message:String):Boolean {
        val target=com.android.purebilibili.feature.dynamic.resolveDynamicCommentTargets(item).firstOrNull()?:return false
        return try{mutate{csrf->val response=api.addReply(oid=target.oid,type=target.type,message=message,root=0,parent=0,csrf=csrf);checked(response.code,response.message)};true}
        catch(cancelled:CancellationException){throw cancelled}catch(failed:Exception){assertOwned();false}
    }
    suspend fun setLike(id: String, liked: Boolean) = mutate { csrf ->
        require(id.isNotBlank())
        val response = dynamic.likeDynamic(csrf, body = DynamicThumbRequest(id, if (liked) 1 else 2))
        checked(response.code, response.message)
    }
    suspend fun repost(id: String, text: String = "") = mutate { csrf ->
        require(id.isNotBlank())
        val response = dynamic.repostDynamic(csrf, body = buildDynamicRepostRequest(id, text))
        checked(response.code, response.message)
    }
    suspend fun reserve(action: DynamicReserveAction): Result<DynamicReserveResult> = result {
        require(action.dynamicId.isNotBlank() && action.reserveId > 0L)
        mutate { csrf ->
            val response = dynamic.clickDynamicReserve(csrf, action.reserveId, action.currentButtonStatus,
                action.dynamicId, action.reserveTotal)
            checked(response.code, response.message)
            val data = response.data ?: error("预约操作失败")
            DynamicReserveResult(data.desc_update, data.reserve_update, data.final_btn_status)
        }
    }
    suspend fun delete(action: DynamicDeleteAction) = mutate { csrf ->
        require(action.dynamicId.isNotBlank())
        val response = dynamic.deleteDynamic(csrf, body = DynamicDeleteRequest(action.dynamicId, action.dynType, action.rid))
        checked(response.code, response.message)
    }
    suspend fun setTop(action: DynamicManageAction.ToggleTop) = mutate { csrf ->
        require(action.dynamicId.isNotBlank())
        val request = DynamicTopRequest(action.dynamicId)
        val response = if (action.isCurrentlyTop) dynamic.removeDynamicTop(csrf, request) else dynamic.setDynamicTop(csrf, request)
        checked(response.code, response.message)
    }
    suspend fun setVisibility(action: DynamicManageAction.SetVisibility) = mutate { csrf ->
        val response = dynamic.setDynamicVisibility(csrf, body = DynamicVisibilityRequest(
            buildDynamicVisibilityObjectId(action.dynamicId, action.dynType), resolveDynamicVisibilityAction(action.isPrivate)))
        checked(response.code, response.message)
    }
    suspend fun loadReplyInteraction(oid: Long, type: Int): ReplyInteractionData? = read {
        val response = dynamic.getReplyInteractionStatus(oid, type)
        if (response.code == 0) response.data else null
    }
    suspend fun modifyReplySubject(action: DynamicManageAction.SetReplySubject) = mutate { csrf ->
        val response = dynamic.modifyReplySubject(action.oid, action.replyType, action.action, csrf)
        checked(response.code, response.message)
    }
    suspend fun report(action: DynamicManageAction.Report, reasonType: Int, reasonDesc: String?) = mutate { csrf ->
        require(action.dynamicId.isNotBlank() && action.authorMid > 0)
        val response = dynamic.reportDynamic(csrf, action.authorMid, action.dynamicId, reasonType,
            if (reasonType == 0) reasonDesc else null)
        checked(response.code, response.message)
    }
    /** The original checkCreatedDyn intentionally uses a cookie-free service. */
    suspend fun isPubliclyVisible(id: String): Boolean = read {
        require(id.isNotBlank())
        val response = guestDynamic.getDynamicDetail(id)
        response.code == 0 && response.data?.item != null
    }
// GENERATED original editor members; do not hand-maintain a second request algorithm.
// ORIGINAL app/src/main/java/com/android/purebilibili/data/repository/DynamicCreateRepository.kt
// LF-normalized SHA-256: f204c21e0e5a21e83b5b2b20ce59d822f8ac74cc8f109663da408e250506a373
// ORIGINAL app/src/main/java/com/android/purebilibili/data/repository/CommentRepository.kt
// LF-normalized SHA-256: 80fe4b7dc2d388641b3b5a5cf0cb287567274d91379816131fd1c354707cd998

suspend fun publishDynamic(draft: DynamicPublishDraft, imageProvider: suspend (String) -> Triple<String?, String?, ByteArray>): Result<String> = result {
    mutate { csrf ->
            val mid = repository.requireAccount().mid
            val pics = draft.imageUris.map { uriString ->
                uploadEditorImage(csrf, imageProvider(uriString))
            }
            val contents = buildDynamicCreateContents(
                text = draft.text,
                voteId = draft.voteId,
                voteTitle = draft.voteTitle,
                mentions = draft.mentions,
                emotes = draft.emotes,
            )
            if (contents.isEmpty() && pics.isEmpty()) {
                error("内容不能为空")
            }
            val request = DynamicCreateFeedRequest(
                dyn_req = DynamicCreateFeedReq(
                    content = DynamicCreateFeedContent(
                        contents = contents.ifEmpty {
                            listOf(DynamicRepostContentItem(raw_text = " ", type = 1, biz_id = ""))
                        },
                        title = draft.title.trim().takeIf { it.isNotEmpty() }
                    ),
                    scene = resolveDynamicCreateScene(pics.isNotEmpty()),
                    pics = pics.takeIf { it.isNotEmpty() },
                    attach_card = resolveEditorReserveAttachCard(draft.reserveId),
                    option = if (draft.private) DynamicCreateOption(private_pub = 1) else null,
                    topic = draft.topic?.takeIf { it.id > 0L }?.let {
                        DynamicCreateTopic(id = it.id, name = it.name)
                    },
                    upload_id = "${mid}_${System.currentTimeMillis() / 1000}_${Random.nextInt(1000, 10000)}"
                )
            )
            coroutineContext.ensureActive(); assertOwned()
            val response = dynamic.createFeedDynamic(csrf = csrf, body = request)
            if (response.code != 0) {
                error(response.message.ifBlank { "发布失败" })
            }
            resolveCreatedDynamicId(response.data).ifBlank { "ok" }
        
    }
}

suspend fun editDynamic(dynamicId: String, draft: DynamicPublishDraft, imageProvider: suspend (String) -> Triple<String?, String?, ByteArray>): Result<Unit> = result {
    mutate { csrf ->
            if (dynamicId.isBlank()) error("无法识别该动态")
            val pics = draft.imageUris.map { uriString ->
                draft.existingImages.firstOrNull { it.img_src == uriString }
                    ?: uploadEditorImage(csrf, imageProvider(uriString))
            }
            val contents = buildDynamicCreateContents(
                text = draft.text,
                voteId = draft.voteId,
                voteTitle = draft.voteTitle,
                mentions = draft.mentions,
                emotes = draft.emotes,
            )
            if (contents.isEmpty() && pics.isEmpty()) error("内容不能为空")
            val mid = repository.requireAccount().mid
            val uploadId = "${mid}_${System.currentTimeMillis() / 1000}_${Random.nextInt(1000, 10000)}"
            val request = DynamicEditFeedRequest(
                dyn_req = DynamicCreateFeedReq(
                    content = DynamicCreateFeedContent(
                        contents = contents.ifEmpty {
                            listOf(DynamicRepostContentItem(raw_text = " ", type = 1, biz_id = ""))
                        },
                        title = draft.title.trim().takeIf(String::isNotEmpty),
                    ),
                    scene = resolveDynamicCreateScene(pics.isNotEmpty()),
                    pics = pics.takeIf { it.isNotEmpty() },
                    attach_card = resolveEditorReserveAttachCard(draft.reserveId),
                    option = if (draft.private) DynamicCreateOption(private_pub = 1) else null,
                    topic = draft.topic?.takeIf { it.id > 0L }?.let {
                        DynamicCreateTopic(id = it.id, name = it.name)
                    },
                    upload_id = uploadId,
                ),
                dyn_id_str = dynamicId,
            )
            coroutineContext.ensureActive(); assertOwned()
            val query = repository.signWebParams(mapOf(
                    "platform" to "web",
                    "csrf" to csrf,
                    "x-bili-device-req-json" to
                        "{\"platform\":\"web\",\"device\":\"pc\",\"spmid\":\"333.1368\"}",
                    "w_dyn_req.upload_id" to uploadId,
                    "w_dyn_req.meta" to
                        "{\"app_meta\":{\"from\":\"create.dynamic.web\",\"mobi_app\":\"web\"}}",
                ))
            coroutineContext.ensureActive(); assertOwned()
            val response = dynamic.editFeedDynamic(query = query, body = request)
            if (response.code != 0) error(response.message.ifBlank { "编辑失败" })
        
    }
}

suspend fun createVote(title: String, options: List<String>, description: String, choiceCount: Int, durationDays: Int): Result<DynamicCreatedVote> = result {
    mutate { csrf ->
            val cleanedOptions = options.map { it.trim() }.filter { it.isNotEmpty() }
            if (title.isBlank() || cleanedOptions.size < 2) {
                error("至少填写标题和两个选项")
            }
            coroutineContext.ensureActive(); assertOwned()
            val response = dynamic.createVote(
                csrf = csrf,
                body = DynamicCreateVoteRequest(
                    vote_info = DynamicCreateVoteInfo(
                        title = title.trim(),
                        desc = description.trim(),
                        choice_cnt = choiceCount.coerceIn(1, cleanedOptions.size),
                        duration = (durationDays * 24 * 60 * 60).coerceAtLeast(60),
                        options = cleanedOptions.map { DynamicCreateVoteOption(opt_desc = it) },
                        vote_publisher = repository.requireAccount().mid
                    )
                )
            )
            if (response.code != 0) {
                error(response.message.ifBlank { "创建投票失败" })
            }
            val voteId = response.data?.vote_id ?: 0L
            if (voteId <= 0L) error("投票创建失败")
            DynamicCreatedVote(voteId = voteId, title = title.trim())
        
    }
}

suspend fun createReserve(title: String, livePlanStartTimeSeconds: Long, subType: Int): Result<DynamicCreatedReserve> = result {
    mutate { csrf ->
            if (title.isBlank()) error("请填写预约标题")
            coroutineContext.ensureActive(); assertOwned()
            val response = dynamic.createReserve(
                subType = subType,
                title = title.trim(),
                livePlanStartTime = livePlanStartTimeSeconds,
                csrf = csrf
            )
            if (response.code != 0) {
                error(response.message.ifBlank { "创建预约失败" })
            }
            val reserveId = response.data?.sid ?: 0L
            if (reserveId <= 0L) error("预约创建失败")
            DynamicCreatedReserve(reserveId = reserveId, title = title.trim())
        
    }
}

suspend fun searchPublishTopics(keyword: String): Result<List<DynamicTopicSearchItem>> = result {
    read {
                val response = api.searchDynamicPublishTopics(
                    keywords = keyword.trim().takeIf(String::isNotBlank),
                )
                if (response.code != 0) error(response.message.ifBlank { "搜索话题失败" })
                response.data?.topic_items.orEmpty()
                    .filter { it.id > 0L && it.name.isNotBlank() }
                    .distinctBy { it.id }
            
    }
}

suspend fun searchMentionUsers(keyword: String): Result<List<MentionSearchUser>> = result {
    read {
            val response = api.searchMentionUsers(keyword.trim().takeIf { it.isNotEmpty() })
            if (response.code == 0) {
                val users = response.data
                    ?.groups
                    .orEmpty()
                    .flatMap { it.items }
                    .filter { it.uid > 0L && it.name.isNotBlank() }
                    .distinctBy { it.uid }
                users
            } else {
                val errorMsg = when (response.code) {
                    -101 -> "请先登录后使用@好友"
                    else -> response.message.ifEmpty { "搜索@好友失败 (${response.code})" }
                }
                throw Exception(errorMsg)
            }
        
    }
}

private suspend fun uploadEditorImage(csrf: String, selected: Triple<String?, String?, ByteArray>): DynamicCreatePic {
        coroutineContext.ensureActive(); assertOwned()
        val bytes = selected.third
        if (bytes.isEmpty()) error("图片内容为空")
        if (bytes.size > 15 * 1024 * 1024) error("图片过大（单张最大 15MB）")
        val mimeType = selected.second ?: "image/jpeg"
        val fileName = selected.first ?: "dyn_${System.currentTimeMillis()}.jpg"
        val uploaded = uploadEditorCommentImage(csrf,
            fileName = fileName,
            mimeType = mimeType,
            bytes = bytes
        )
        return DynamicCreatePic(
            img_src = uploaded.imgSrc,
            img_width = uploaded.imgWidth,
            img_height = uploaded.imgHeight,
            img_size = uploaded.imgSize
        )
}

private suspend fun uploadEditorCommentImage(csrf: String, fileName: String, mimeType: String, bytes: ByteArray): ReplyPicture {
        coroutineContext.ensureActive(); assertOwned()
            val mediaType = mimeType.toMediaType()
            val fileBody = bytes.toRequestBody(mediaType)
            val part = okhttp3.MultipartBody.Part.createFormData(
                "file_up",
                fileName.ifBlank { "comment_image.jpg" },
                fileBody
            )
            val textMedia = "text/plain".toMediaType()
            val categoryBody = "daily".toRequestBody(textMedia)
            val bizBody = "new_dyn".toRequestBody(textMedia)
            val csrfBody = csrf.toRequestBody(textMedia)
    
            val response = api.uploadCommentImage(
                fileUp = part,
                category = categoryBody,
                biz = bizBody,
                csrf = csrfBody
            )
    
            coroutineContext.ensureActive(); assertOwned()
            val data = response.data
            return if (response.code == 0 && data != null) {
                ReplyPicture(
                        imgSrc = data.imageUrl,
                        imgWidth = data.imageWidth,
                        imgHeight = data.imageHeight,
                        imgSize = data.imgSize
                    )
            } else {
                throw Exception(response.message.ifEmpty { "图片上传失败 (${response.code})" })
            }
        
}

    private fun resolveEditorReserveAttachCard(reserveId: Long): JsonObject? {
        if (reserveId <= 0L) return null
        return buildJsonObject {
            put("common_card", buildJsonObject {
                put("type", 14)
                put("biz_id", reserveId)
                put("reserve_source", 0)
                put("reserve_lottery", 0)
            })
        }
    }
}

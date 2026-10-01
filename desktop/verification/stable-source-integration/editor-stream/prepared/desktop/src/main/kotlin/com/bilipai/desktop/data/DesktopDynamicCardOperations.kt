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
    private val editorImageOwner = repository.dynamicCacheSessionGuard.dynamicCacheOwner()
    internal fun withOwnedEditorImageAdmission(block: () -> Unit): Boolean {
        val owner = editorImageOwner ?: return false
        return repository.dynamicCacheSessionGuard.withCurrentDynamicCacheOwner(owner) {
            assertOwned(); block(); assertOwned()
        }
    }
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val ownerJar = repository.httpClient.cookieJar
    private fun assertOwned() {
        if (!isOwned()) throw CancellationException("Dynamic card owner retired")
    }
    fun isOwned(): Boolean = repository.sessionEpoch == expectedEpoch && stillOwned()
    fun forEditor(editorOwned: () -> Boolean) = DesktopDynamicCardOperations(repository, expectedEpoch,
        stillOwned = { isOwned() && editorOwned() }, sharedEmotes = emotes)
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
    /** Original publish verification uses AUTH; checkCreatedDyn remains GUEST above. */
    suspend fun getPublishedDynamicDetail(id: String): DynamicDetailResponse = read { dynamic.getDynamicDetail(id) }
// GENERATED original editor members; do not hand-maintain a second request algorithm.
// ORIGINAL app/src/main/java/com/android/purebilibili/data/repository/DynamicCreateRepository.kt
// LF-normalized SHA-256: 2f85544e1e973dd0e7178077ef5c1f439b798a1d86b46701803adeb2a7c0364f
// ORIGINAL app/src/main/java/com/android/purebilibili/data/repository/CommentRepository.kt
// LF-normalized SHA-256: 4950b91a708e4d22ddbc6b6f39ef6880879e175dad543ff1fd5b833518b2b820

suspend fun publishDynamic(draft: DynamicPublishDraft, imageProvider: suspend (String) -> Triple<String?, String?, okhttp3.RequestBody>): Result<String> = result {
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

suspend fun editDynamic(dynamicId: String, draft: DynamicPublishDraft, imageProvider: suspend (String) -> Triple<String?, String?, okhttp3.RequestBody>): Result<Unit> = result {
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

private suspend fun uploadEditorImage(csrf: String, selected: Triple<String?, String?, okhttp3.RequestBody>): DynamicCreatePic {
        coroutineContext.ensureActive(); assertOwned()
        val mimeType = selected.second ?: "image/jpeg"
        val fileName = selected.first ?: "dyn_${System.currentTimeMillis()}.jpg"
        // 流式上传:空/15MB 校验在 CommentRepository 内基于文件尺寸完成,不再整文件读入内存。
        val uploaded = uploadEditorCommentImageBody(csrf,
            fileName = fileName,
            mimeType = mimeType,
            fileBody = selected.third
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
        return uploadEditorCommentImageBody(csrf,
            fileName = fileName.ifBlank { "comment_image.jpg" },
            mimeType = mimeType,
            fileBody = bytes.toRequestBody(mediaType)
        )
    
}

private suspend fun uploadEditorCommentImageBody(csrf: String, fileName: String, mimeType: String, fileBody: okhttp3.RequestBody): ReplyPicture {
        coroutineContext.ensureActive(); assertOwned()
            val part = okhttp3.MultipartBody.Part.createFormData(
                "file_up",
                fileName,
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
            val uploadContext = coroutineContext
            if (!withOwnedEditorImageAdmission { uploadContext.ensureActive() }) throw CancellationException("Dynamic upload account owner retired")
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

// Paste inside existing DesktopDynamicCardOperations; no package/class/API/model producer.
// Requires the frozen editor's existing private uploadEditorCommentImage (one upload body).
private val guestCommentApi = guestWeb.create(BilibiliApi::class.java)
private val commentGrpc = com.android.purebilibili.data.repository.DesktopDynamicCommentGrpc(grpc::request, ::assertOwned)
private val commentProtocol = com.android.purebilibili.data.repository.DesktopDynamicCommentProtocol(
    api, guestCommentApi, commentGrpc,
    { assertOwned(); !repository.authCookies()["SESSDATA"].isNullOrEmpty() },
    { params -> assertOwned(); repository.signWebParams(params).also { assertOwned() } },
    ::assertOwned,
)

suspend fun getCommentsForSubject(oid:Long,type:Int,page:Int,ps:Int=20,mode:Int=3,paginationOffset:String?=null,fallbackOnMissingLocation:Boolean=false):Result<ReplyData> = result { read { commentProtocol.getCommentsForSubject(oid,type,page,ps,mode,paginationOffset,fallbackOnMissingLocation).getOrThrow() } }

suspend fun getCommentCountForSubject(oid:Long,type:Int):Result<Int> = result { read { commentProtocol.getCommentCountForSubject(oid,type).getOrThrow() } }

suspend fun getSortedSubCommentsForSubject(oid:Long,type:Int,rootId:Long,mode:Int,paginationOffset:String?=null,targetReplyId:Long=0L):Result<ReplyData> = result { read { commentProtocol.getSortedSubCommentsForSubject(oid,type,rootId,mode,paginationOffset,targetReplyId).getOrThrow() } }

suspend fun getSubCommentsForSubject(oid:Long,type:Int,rootId:Long,page:Int,ps:Int=20,paginationOffset:String?=null,preferRestPaging:Boolean=true):Result<ReplyData> = result { read { commentProtocol.getSubCommentsForSubject(oid,type,rootId,page,ps,paginationOffset,preferRestPaging).getOrThrow() } }

suspend fun getDialogCommentsForSubject(oid:Long,type:Int,rootId:Long,dialogId:Long,page:Int,paginationOffset:String?=null):Result<ReplyData> = result { read { commentProtocol.getDialogCommentsForSubject(oid,type,rootId,dialogId,page,paginationOffset).getOrThrow() } }

suspend fun translateReply(type:Long,oid:Long,rpid:Long):Result<String?> = result { read { commentGrpc.translateReply(type,oid,rpid).getOrThrow() } }

suspend fun uploadCommentImage(fileName:String,mimeType:String,bytes:ByteArray):Result<ReplyPicture> = result { mutate { csrf -> uploadEditorCommentImage(csrf,fileName,mimeType,bytes) } }

    suspend fun addCommentForSubject(
        oid: Long,
        type: Int,
        message: String,
        root: Long = 0,
        parent: Long = 0,
        pictures: List<ReplyPicture> = emptyList(),
        syncToDynamic: Boolean = false,
        onPublishedRecord: ((ReplyItem, Long, Int, Long, Long, String, Long) -> Unit)? = null
    ): Result<ReplyItem?> = result { mutate { csrf ->
val picturePayload = buildCommentPicturesPayload(pictures)
            
            val response = api.addReply(
                oid = oid,
                type = type,
                message = message,
                root = root.takeIf { it > 0L },
                parent = parent.takeIf { it > 0L },
                pictures = picturePayload,
                syncToDynamic = resolveCommentSyncToDynamicField(syncToDynamic),
                csrf = csrf
            )
            coroutineContext.ensureActive(); assertOwned()

            
            if (response.code == 0) {
                val reply = response.data?.reply
                if (reply != null && reply.rpid > 0L) {
                    val serverPostTime = if (reply.ctime > 0L) reply.ctime * 1000L else System.currentTimeMillis()
                    
                    // Owned optional original-record seam; null is explicitly unbound.
                    coroutineContext.ensureActive(); assertOwned()
                    onPublishedRecord?.invoke(reply, oid, type, root, parent, message, serverPostTime)
                    coroutineContext.ensureActive(); assertOwned()
                }
                // 立刻返回给 UI 渲染
                reply
            } else {

                val errorMsg = when (response.code) {
                    -101 -> "请先登录"
                    -102 -> "账号被封禁"
                    -509 -> "请求过于频繁"
                    12002 -> "评论区已关闭"
                    12015 -> "需要评论验证码"
                    12016 -> "评论内容包含敏感信息"
                    12025 -> "评论字数过多"
                    12035 -> "您已被UP主拉黑"
                    12051 -> "重复评论，请勿刷屏"
                    else -> response.message.ifEmpty { "发送失败 (${response.code})" }
                }
                throw Exception(errorMsg)
            }
        
} }

    suspend fun likeCommentForSubject(
        oid: Long,
        type: Int,
        rpid: Long,
        like: Boolean
    ): Result<Unit> = result { mutate { csrf ->
val response = api.likeReply(
                oid = oid,
                type = type,
                rpid = rpid,
                action = if (like) 1 else 0,
                csrf = csrf
            )
            coroutineContext.ensureActive(); assertOwned()

            
            if (response.code == 0) {
                Unit
            } else {
                throw Exception(response.message.ifEmpty { "操作失败" })
            }
        
} }

    suspend fun hateCommentForSubject(
        oid: Long,
        type: Int,
        rpid: Long,
        hate: Boolean
    ): Result<Unit> = result { mutate { csrf ->
val response = api.hateReply(
                oid = oid,
                type = type,
                rpid = rpid,
                action = if (hate) 1 else 0,
                csrf = csrf
            )
            coroutineContext.ensureActive(); assertOwned()

            
            if (response.code == 0) {
                Unit
            } else {
                throw Exception(response.message.ifEmpty { "操作失败" })
            }
        
} }

    suspend fun deleteCommentForSubject(
        oid: Long,
        type: Int,
        rpid: Long,
    ): Result<Unit> = result { mutate { csrf ->
val response = api.deleteReply(
                oid = oid,
                type = type,
                rpid = rpid,
                csrf = csrf
            )
            coroutineContext.ensureActive(); assertOwned()

            
            if (response.code == 0) {
                Unit
            } else {
                val errorMsg = when (response.code) {
                    -403 -> "无权删除此评论"
                    12022 -> "评论已被删除"
                    else -> response.message.ifEmpty { "删除失败" }
                }
                throw Exception(errorMsg)
            }
        
} }

    suspend fun setCommentTopForSubject(
        oid: Long,
        type: Int,
        rpid: Long,
        isCurrentlyTop: Boolean,
    ): Result<Unit> = result { mutate { csrf ->
val response = api.setReplyTop(
                oid = oid,
                type = type,
                rpid = rpid,
                action = resolveCommentTopActionField(isCurrentlyTop),
                csrf = csrf
            )
            coroutineContext.ensureActive(); assertOwned()


            if (response.code == 0) {
                Unit
            } else {
                throw Exception(response.message.ifEmpty { "置顶操作失败" })
            }
        
} }

    suspend fun reportCommentForSubject(
        oid: Long,
        type: Int,
        rpid: Long,
        reason: Int,
        content: String = "",
    ): Result<Unit> = result { mutate { csrf ->
val response = api.reportReply(
                oid = oid,
                type = type,
                rpid = rpid,
                reason = reason,
                content = content,
                csrf = csrf
            )
            coroutineContext.ensureActive(); assertOwned()

            
            if (response.code == 0) {
                Unit
            } else {
                val errorMsg = when (response.code) {
                    12008 -> "已经举报过了"
                    12019 -> "举报过于频繁"
                    else -> response.message.ifEmpty { "举报失败" }
                }
                throw Exception(errorMsg)
            }
        
} }

private fun buildCommentPicturesPayload(pictures:List<ReplyPicture>):String? {
    if (pictures.isEmpty()) return null
    // Original ReplyPicture already has exactly the four original CommentPicturePayload fields.
    // Emit defaults to match the original private payload's four required fields; no new DTO.
    return kotlinx.serialization.json.Json(json) { encodeDefaults = true }.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(ReplyPicture.serializer()), pictures)
}

    private fun resolveCommentSyncToDynamicField(syncToDynamic: Boolean): Int? {
        return if (syncToDynamic) 1 else null
    }

    private fun resolveCommentTopActionField(isCurrentlyTop: Boolean): Int {
        return if (isCurrentlyTop) 0 else 1
    }

// Additional members inside the existing DesktopDynamicCardOperations; no replacement Ops file.
// Seed is the original DynamicItem captured by the owner's caller. No new seed cache.
// Null history callback explicitly leaves the original best-effort article history side effect unbound.
suspend fun getDynamicDetail(
    dynamicId:String,
    seedItem:DynamicItem?,
    onArticleViewed:(suspend (Long)->Unit)?=null,
):Result<DynamicItem> = result { read {
    val articleProtocol=com.android.purebilibili.data.repository.DesktopDynamicDetailArticleProtocol(
        web.create(ArticleApi::class.java), dynamic, ::signOriginalDetailParams, ::assertOwned, onArticleViewed)
    val protocol=com.android.purebilibili.data.repository.DesktopDynamicDetailProtocol(
        dynamic, ::signOriginalDetailParams,
        { id -> articleProtocol.getArticleDetail(id).getOrNull() }, ::assertOwned)
    protocol.getDynamicDetail(dynamicId,seedItem).getOrThrow()
} }
private suspend fun signOriginalDetailParams(params:Map<String,String>):Map<String,String> {
    coroutineContext.ensureActive();assertOwned()
    return try { repository.signWebParams(params).also { coroutineContext.ensureActive();assertOwned() }
    } catch(cancelled:CancellationException) { throw cancelled
    } catch(failure:Exception) { coroutineContext.ensureActive();assertOwned();params }
}

}

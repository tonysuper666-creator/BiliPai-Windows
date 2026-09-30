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
}

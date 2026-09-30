package com.bilipai.desktop.data

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.SpaceApi
import com.android.purebilibili.data.model.response.SimpleApiResponse
import com.android.purebilibili.data.model.response.FavoriteData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.android.purebilibili.data.repository.FollowStateChange
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Every account mutation is an explicit caller action and uses the shared authorized session. No mutation retries. */
class DesktopSocialRepository(private val repository: DesktopRepository) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    // Keep the same cookie jar/interceptors while disabling automatic transport retries for account writes.
    private val retrofit = Retrofit.Builder().baseUrl("https://api.bilibili.com/")
        .client(repository.httpClient.newBuilder().retryOnConnectionFailure(false).build())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    private val api = retrofit.create(BilibiliApi::class.java)
    private val space = retrofit.create(SpaceApi::class.java)
    private val mutationMutex = Mutex()

    suspend fun createFavoriteFolder(title: String, isPrivate: Boolean = false) {
        val trimmed = title.trim()
        require(trimmed.isNotEmpty()) { "请输入收藏夹名称" }
        mutate { csrf -> check(api.createFavFolder(title = trimmed, privacy = if (isPrivate) 1 else 0, csrf = csrf)) }
    }

    suspend fun deleteFavoriteFolder(folderId: Long) {
        require(folderId > 0)
        mutate { csrf -> check(api.deleteFavFolders(mediaIds = folderId.toString(), csrf = csrf)) }
    }

    suspend fun setFavorite(aid: Long, folderId: Long, favorite: Boolean) {
        require(aid > 0 && folderId > 0)
        mutate { csrf ->
            check(api.dealFavorite(rid = aid, addIds = if (favorite) folderId.toString() else "",
                delIds = if (favorite) "" else folderId.toString(), csrf = csrf))
        }
    }

    suspend fun setWatchLater(aid: Long, add: Boolean) {
        require(aid > 0)
        mutate { csrf ->
            check(if (add) api.addToWatchLater(aid, csrf) else api.deleteFromWatchLater(aid = aid, csrf = csrf))
        }
    }

    suspend fun removeFavoriteResource(folderId: Long, resource: FavoriteData) {
        require(folderId > 0 && resource.id > 0 && resource.type > 0)
        mutate { csrf -> check(api.batchDelFavResource(mediaId = folderId, resources = "${resource.id}:${resource.type}", csrf = csrf)) }
    }

    suspend fun videoRelation(aid: Long): VideoRelation = withContext(Dispatchers.IO) {
        require(aid > 0)
        repository.requireAccount()
        repository.ensureSession()
        val response = api.getVideoRelation(aid = aid)
        check(response.code, response.message)
        val data = response.data ?: throw BiliApiException(-1, "视频互动状态为空")
        VideoRelation(data.like, data.favorite, data.coin.coerceAtLeast(0))
    }

    suspend fun setLike(aid: Long, liked: Boolean) {
        require(aid > 0)
        mutate { csrf -> check(api.likeVideo(aid, if (liked) 1 else 2, csrf)) }
    }

    suspend fun giveCoins(aid: Long, quantity: Int, alsoLike: Boolean = false) {
        require(aid > 0)
        validateCoinQuantity(quantity)
        mutate { csrf -> check(api.coinVideo(aid, quantity, if (alsoLike) 1 else 0, csrf)) }
    }

    suspend fun userProfile(mid: Long): UserProfile = withContext(Dispatchers.IO) {
        require(mid > 0)
        repository.ensureSession()
        val response = space.getSpaceInfo(repository.signWebParams(mapOf("mid" to mid.toString())))
        check(response.code, response.message)
        val user = response.data ?: throw BiliApiException(-1, "UP 主资料为空")
        val statsResponse = space.getRelationStat(mid)
        check(statsResponse.code, statsResponse.message)
        val stats = statsResponse.data ?: throw BiliApiException(-1, "UP 主关注资料为空")
        UserProfile(user.mid, user.name, personalImageUrl(user.face), user.sign, user.level, user.vip.status == 1,
            user.isFollowed, user.official.title, stats.follower.coerceAtLeast(0), stats.following.coerceAtLeast(0))
    }

    suspend fun setFollowing(mid: Long, follow: Boolean) {
        require(mid > 0)
        // Capture before queueing: another login of the same MID is a new authority.
        val owner = repository.followStateEvents.requireOwner()
        require(owner.mid != mid) { "不能关注自己" }
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                repository.followStateEvents.requireCurrent(owner)
                repository.requireCsrf()
                repository.ensureSession()
                currentCoroutineContext().ensureActive()
                repository.followStateEvents.requireCurrent(owner)
                check(api.modifyRelation(mid, if (follow) 1 else 2, repository.requireCsrf()))
                currentCoroutineContext().ensureActive()
                repository.followStateEvents.confirm(owner, FollowStateChange(mid = mid, isFollowing = follow))
            }
        }
    }

    suspend fun removeBlacklist(mid: Long) {
        require(mid > 0)
        require(repository.requireAccount().mid != mid) { "不能修改自己的关注关系" }
        mutate { csrf -> check(api.modifyRelation(mid, 6, csrf)) }
    }

    suspend fun commentPage(aid: Long, page: Int = 1, sort: Int = 1): CommentPage = withContext(Dispatchers.IO) {
        require(aid > 0 && page > 0 && sort in 0..2)
        repository.ensureSession()
        val response = api.getReplyListLegacy(oid = aid, pn = page, ps = 20, sort = sort)
        check(response.code, response.message)
        socialCommentPage(response.data ?: throw BiliApiException(-1, "评论列表为空"), page, nested = false)
    }

    suspend fun commentReplies(aid: Long, rootId: Long, page: Int = 1): CommentPage = withContext(Dispatchers.IO) {
        require(aid > 0 && rootId > 0 && page > 0)
        repository.ensureSession()
        val response = api.getReplyReply(oid = aid, root = rootId, pn = page, ps = 20)
        check(response.code, response.message)
        socialCommentPage(response.data ?: throw BiliApiException(-1, "楼中楼列表为空"), page, nested = true)
    }

    suspend fun publishComment(aid: Long, message: String, rootId: Long? = null, parentId: Long? = null): PublishedComment {
        require(aid > 0)
        require(message.isNotBlank()) { "请输入评论内容" }
        val target = commentTarget(rootId, parentId)
        return mutate { csrf ->
            val response = api.addReply(oid = aid, message = message, root = target.root, parent = target.parent, csrf = csrf)
            check(response.code, response.message)
            val data = response.data
            PublishedComment(data?.rpid?.takeIf { it > 0 } ?: data?.rpidStr?.toLongOrNull()?.takeIf { it > 0 },
                data?.reply?.let { socialComment(it) })
        }
    }

    private suspend fun <T> mutate(action: suspend (String) -> T): T = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val account = repository.requireAccount()
            // Check credentials before performing visitor bootstrap; anonymous/partial sessions never post.
            repository.requireCsrf()
            repository.ensureSession()
            if (repository.requireAccount().mid != account.mid) throw BiliApiException(-101, "账号已切换，请重新操作")
            action(repository.requireCsrf())
        }
    }

    private fun check(response: SimpleApiResponse) = check(response.code, response.message)
    private fun check(code: Int, message: String) {
        if (code == -101 || code == -111) throw BiliApiException(code, "登录凭证已失效，请重新登录后重试")
        if (code != 0) throw BiliApiException(code, message)
    }
}

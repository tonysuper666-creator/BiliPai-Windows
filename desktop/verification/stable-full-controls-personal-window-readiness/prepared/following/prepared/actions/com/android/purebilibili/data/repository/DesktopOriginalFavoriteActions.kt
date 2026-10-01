package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.AppSignUtils
import com.android.purebilibili.data.model.response.FollowingUser
import com.android.purebilibili.data.model.response.RecommendationFeedbackMetadata
import com.android.purebilibili.data.model.response.RecommendationFeedbackReason
import com.android.purebilibili.data.model.response.RecommendationFeedbackType
import com.android.purebilibili.data.model.response.WatchLaterItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
class DesktopOriginalFavoriteActions(private val environment: com.android.purebilibili.feature.list.DesktopFavoriteEnvironment) {
private companion object {
    private const val SPECIAL_FOLLOW_TAG_ID = -10L
    private const val ALL_FOLLOW_TAG_ID = -20L
    private const val FOLLOW_GROUP_BATCH_SIZE = 20
    private const val FOLLOW_GROUP_MAX_RETRIES = 3
    private const val FOLLOW_GROUP_REQUEST_INTERVAL_MS = 220L
    private const val FOLLOW_GROUP_RETRY_BASE_DELAY_MS = 900L
    private const val FOLLOW_GROUP_QUERY_MAX_RETRIES = 3
    private const val FOLLOW_GROUP_QUERY_RETRY_BASE_DELAY_MS = 600L
    private const val FOLLOW_GROUP_TAG_MEMBERS_PAGE_SIZE = 100
    private const val FOLLOW_GROUP_TAG_MEMBERS_MAX_PAGES = 120
}
    suspend fun createFavFolder(title: String, intro: String = "", isPrivate: Boolean = false): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                if (title.isBlank()) return@withContext Result.failure(Exception("标题不能为空"))
                val privacy = if (isPrivate) 1 else 0
                val csrf = environment.csrf() ?: return@withContext Result.failure(Exception("未登录"))
                
                val response = environment.api.createFavFolder(
                    title = title,
                    intro = intro,
                    privacy = privacy,
                    csrf = csrf
                )
                
                if (response.code == 0) {
                    Result.success(true)
                } else {
                    Result.failure(Exception(response.message))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    suspend fun favoriteVideo(aid: Long, favorite: Boolean, folderId: Long? = null): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val csrf = environment.csrf() ?: ""
                if (csrf.isEmpty()) {
                    return@withContext Result.failure(Exception("请先登录"))
                }
                
                // 如果没有指定收藏夹，需要先获取默认收藏夹
                val targetFolderId = folderId ?: getDefaultFolderId()
                if (targetFolderId == null) {
                    return@withContext Result.failure(Exception("无法获取收藏夹"))
                }

                val response = if (favorite) {
                    environment.api.dealFavorite(rid = aid, addIds = targetFolderId.toString(), delIds = "", csrf = csrf)
                } else {
                    environment.api.dealFavorite(rid = aid, addIds = "", delIds = targetFolderId.toString(), csrf = csrf)
                }
                
                if (response.code == 0) {
                    Result.success(favorite)
                } else {
                    Result.failure(Exception(response.message.ifEmpty { "操作失败: ${response.code}" }))
                }
            } catch (e: Exception) {

                Result.failure(e)
            }
        }
    }
    private suspend fun getDefaultFolderId(): Long? {
        return try {
            val mid = environment.currentMid() ?: return null
            val response = environment.api.getFavFolders(mid)
            response.data?.list?.firstOrNull()?.id
        } catch (e: Exception) {

            null
        }
    }
    suspend fun toggleWatchLater(aid: Long, add: Boolean): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val csrf = environment.csrf() ?: ""
                if (csrf.isEmpty()) {
                    return@withContext Result.failure(Exception("请先登录"))
                }
                
                val response = if (add) {
                    environment.api.addToWatchLater(aid = aid, csrf = csrf)
                } else {
                    environment.api.deleteFromWatchLater(aid = aid, csrf = csrf)
                }
                

                
                when (response.code) {
                    0 -> {
                        environment.watchLaterChanged()
                        Result.success(add)
                    }
                    90001 -> Result.failure(Exception("稍后再看列表已满"))
                    90003 -> Result.failure(Exception("视频已被删除"))
                    else -> Result.failure(Exception(response.message.ifEmpty { "操作失败: ${response.code}" }))
                }
            } catch (e: Exception) {

                Result.failure(e)
            }
        }
    }
    private fun normalizeRelationTagIds(raw: Set<Long>): Set<Long> {
        return raw.asSequence().filter { it != 0L }.toSet()
    }
    private fun normalizeRelationTags(
        raw: List<com.android.purebilibili.data.model.response.RelationTagItem>
    ): List<com.android.purebilibili.data.model.response.RelationTagItem> {
        val merged = raw.distinctBy { it.tagid }.toMutableList()
        if (merged.none { it.tagid == SPECIAL_FOLLOW_TAG_ID }) {
            merged += com.android.purebilibili.data.model.response.RelationTagItem(
                tagid = SPECIAL_FOLLOW_TAG_ID,
                name = "特别关注",
                count = 0,
                tip = "第一时间收到该分组下用户更新稿件的通知"
            )
        }
        return merged.sortedBy { it.tagid != SPECIAL_FOLLOW_TAG_ID }
    }
    internal fun chunkFollowGroupTargetMids(
        targetMids: Set<Long>,
        chunkSize: Int = FOLLOW_GROUP_BATCH_SIZE
    ): List<List<Long>> {
        if (targetMids.isEmpty()) return emptyList()
        val effectiveChunkSize = chunkSize.coerceAtLeast(1)
        return targetMids
            .asSequence()
            .filter { it > 0L }
            .distinct()
            .toList()
            .chunked(effectiveChunkSize)
    }
    internal fun isFollowGroupRetryableError(code: Int, message: String): Boolean {
        if (code in setOf(-412, -352, -509, 22015)) return true
        if (message.isBlank()) return false
        return message.contains("频繁") ||
            message.contains("过快") ||
            message.contains("风控") ||
            message.contains("稍后") ||
            message.contains("too many", ignoreCase = true) ||
            message.contains("rate", ignoreCase = true)
    }
    private suspend fun addUsersToRelationTagsWithRetry(
        fids: String,
        tagIds: String,
        csrf: String
    ): Result<Unit> {
        var lastCode = Int.MIN_VALUE
        var lastMessage = ""

        repeat(FOLLOW_GROUP_MAX_RETRIES) { attempt ->
            val response = environment.api.addUsersToRelationTags(
                fids = fids,
                tagIds = tagIds,
                csrf = csrf
            )
            if (response.code == 0) {
                return Result.success(Unit)
            }

            lastCode = response.code
            lastMessage = response.message
            val retryable = isFollowGroupRetryableError(response.code, response.message)
            if (!retryable || attempt >= FOLLOW_GROUP_MAX_RETRIES - 1) {
                return Result.failure(
                    Exception(response.message.ifEmpty { "分组设置失败: ${response.code}" })
                )
            }

            val backoffMs = FOLLOW_GROUP_RETRY_BASE_DELAY_MS * (attempt + 1)
            delay(backoffMs)
        }

        return Result.failure(
            Exception(if (lastMessage.isNotEmpty()) lastMessage else "分组设置失败: $lastCode")
        )
    }
    suspend fun followUser(mid: Long, follow: Boolean): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val csrf = environment.csrf() ?: ""

                if (csrf.isEmpty()) {

                    return@withContext Result.failure(Exception("请先登录"))
                }
                
                val act = if (follow) 1 else 2

                val response = environment.api.modifyRelation(fid = mid, act = act, csrf = csrf)

                
                if (response.code == 0) {
                    environment.confirmFollow(FollowStateChange(mid = mid, isFollowing = follow))
                    Result.success(follow)
                } else {
                    Result.failure(Exception(response.message.ifEmpty { "操作失败: ${response.code}" }))
                }
            } catch (e: Exception) {

                Result.failure(e)
            }
        }
    }
    suspend fun getFollowGroupTags(): Result<List<com.android.purebilibili.data.model.response.RelationTagItem>> {
        return withContext(Dispatchers.IO) {
            try {
                val response = environment.api.getRelationTags()
                if (response.code == 0) {
                    Result.success(normalizeRelationTags(response.data))
                } else {
                    Result.failure(Exception(response.message.ifEmpty { "获取关注分组失败: ${response.code}" }))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    suspend fun getUserFollowGroupIds(mid: Long): Result<Set<Long>> {
        return withContext(Dispatchers.IO) {
            var lastCode = Int.MIN_VALUE
            var lastMessage = ""
            var lastError: Exception? = null

            repeat(FOLLOW_GROUP_QUERY_MAX_RETRIES) { attempt ->
                try {
                    val response = environment.api.getRelationTagUser(mid)
                    if (response.code == 0) {
                        val ids = response.data.keys.mapNotNull { it.toLongOrNull() }.toSet()
                        return@withContext Result.success(normalizeRelationTagIds(ids))
                    }

                    lastCode = response.code
                    lastMessage = response.message
                    val retryable = isFollowGroupRetryableError(response.code, response.message)
                    if (!retryable || attempt >= FOLLOW_GROUP_QUERY_MAX_RETRIES - 1) {
                        return@withContext Result.failure(
                            Exception(response.message.ifEmpty { "获取分组信息失败: ${response.code}" })
                        )
                    }
                } catch (e: Exception) {
                    lastError = e
                    if (attempt >= FOLLOW_GROUP_QUERY_MAX_RETRIES - 1) {
                        return@withContext Result.failure(e)
                    }
                }

                val backoffMs = FOLLOW_GROUP_QUERY_RETRY_BASE_DELAY_MS * (attempt + 1)
                delay(backoffMs)
            }

            val fallbackMessage = if (lastMessage.isNotBlank()) {
                lastMessage
            } else {
                "获取分组信息失败: $lastCode"
            }
            Result.failure(lastError ?: Exception(fallbackMessage))
        }
    }
    suspend fun getFollowGroupMemberMids(
        tagId: Long,
        targetMids: Set<Long> = emptySet()
    ): Result<Set<Long>> {
        return withContext(Dispatchers.IO) {
            try {
                val targetSet = if (targetMids.isEmpty()) null else targetMids
                val result = linkedSetOf<Long>()
                var page = 1

                while (page <= FOLLOW_GROUP_TAG_MEMBERS_MAX_PAGES) {
                    val response = environment.api.getRelationTagMembers(
                        tagId = tagId,
                        pageSize = FOLLOW_GROUP_TAG_MEMBERS_PAGE_SIZE,
                        page = page
                    )
                    if (response.code != 0) {
                        return@withContext Result.failure(
                            Exception(response.message.ifEmpty { "获取分组成员失败: ${response.code}" })
                        )
                    }

                    val mids = response.data
                        .asSequence()
                        .map { it.mid }
                        .filter { it > 0L }
                        .toList()

                    if (targetSet == null) {
                        result.addAll(mids)
                    } else {
                        mids.filterTo(result) { targetSet.contains(it) }
                    }

                    if (mids.size < FOLLOW_GROUP_TAG_MEMBERS_PAGE_SIZE) break
                    page += 1
                    delay(FOLLOW_GROUP_REQUEST_INTERVAL_MS)
                }

                Result.success(result)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    suspend fun getAllFollowGroupUsers(): Result<List<FollowingUser>> {
        return getFollowGroupUsers(tagId = ALL_FOLLOW_TAG_ID)
    }
    private suspend fun getFollowGroupUsers(tagId: Long): Result<List<FollowingUser>> {
        return withContext(Dispatchers.IO) {
            try {
                val result = linkedMapOf<Long, FollowingUser>()
                var page = 1

                while (page <= FOLLOW_GROUP_TAG_MEMBERS_MAX_PAGES) {
                    val response = environment.api.getRelationTagFollowingUsers(
                        tagId = tagId,
                        pageSize = FOLLOW_GROUP_TAG_MEMBERS_PAGE_SIZE,
                        page = page
                    )
                    if (response.code != 0) {
                        return@withContext Result.failure(
                            Exception(response.message.ifEmpty { "获取分组成员失败: ${response.code}" })
                        )
                    }

                    val users = response.data.filter { it.mid > 0L }
                    users.forEach { user -> result[user.mid] = user }

                    if (users.size < FOLLOW_GROUP_TAG_MEMBERS_PAGE_SIZE) break
                    page += 1
                    delay(FOLLOW_GROUP_REQUEST_INTERVAL_MS)
                }

                Result.success(result.values.toList())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    suspend fun overwriteFollowGroupIds(
        targetMids: Set<Long>,
        selectedTagIds: Set<Long>
    ): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                if (targetMids.isEmpty()) return@withContext Result.success(true)
                val csrf = environment.csrf() ?: ""
                if (csrf.isEmpty()) {
                    return@withContext Result.failure(Exception("请先登录"))
                }
                val normalizedSelection = normalizeRelationTagIds(selectedTagIds)
                val midChunks = chunkFollowGroupTargetMids(targetMids)
                if (midChunks.isEmpty()) return@withContext Result.success(true)
                val selectedTagIdsJoined = normalizedSelection.joinToString(",")

                midChunks.forEachIndexed { index, mids ->
                    val fids = mids.joinToString(",")

                    // 先移动到默认分组，确保“完全覆盖”生效。
                    val resetResult = addUsersToRelationTagsWithRetry(
                        fids = fids,
                        tagIds = "0",
                        csrf = csrf
                    )
                    if (resetResult.isFailure) {
                        return@withContext Result.failure(
                            resetResult.exceptionOrNull() ?: Exception("分组设置失败")
                        )
                    }

                    if (normalizedSelection.isNotEmpty()) {
                        val applyResult = addUsersToRelationTagsWithRetry(
                            fids = fids,
                            tagIds = selectedTagIdsJoined,
                            csrf = csrf
                        )
                        if (applyResult.isFailure) {
                            return@withContext Result.failure(
                                applyResult.exceptionOrNull() ?: Exception("分组设置失败")
                            )
                        }
                    }

                    if (index < midChunks.lastIndex) {
                        delay(FOLLOW_GROUP_REQUEST_INTERVAL_MS)
                    }
                }

                Result.success(true)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}

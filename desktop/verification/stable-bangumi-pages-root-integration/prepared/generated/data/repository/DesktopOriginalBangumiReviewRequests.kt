// GENERATED from app/src/main/java/com/android/purebilibili/data/repository/BangumiReviewRepository.kt; pinned LF SHA-256 7dd7ac7da8498648fae7242f921bc6654814ccd47dc28d678730449f78070a12
package com.android.purebilibili.data.repository

import com.android.purebilibili.core.network.BangumiApi
// Root supplies its existing csrf reader.
import com.android.purebilibili.data.model.response.BangumiReviewItem
import com.android.purebilibili.data.model.response.BangumiReviewType
import kotlinx.coroutines.Dispatchers
import com.bilipai.desktop.ui.ownedBangumiRequest

data class BangumiReviewPage(
    val items: List<BangumiReviewItem>,
    val next: String,
    val count: Int,
    val hasMore: Boolean
)

internal class DesktopOriginalBangumiReviewRequests(private val api: BangumiApi, private val csrf: () -> String?, private val owned: () -> Boolean, private val actionOwned: () -> Boolean) {
    suspend fun getReviews(
        mediaId: Long,
        type: BangumiReviewType,
        cursor: String = "",
        sort: Int = 0
    ): Result<BangumiReviewPage> = ownedBangumiRequest(Dispatchers.IO, owned) {
        runCatching {
            val response = if (type == BangumiReviewType.SHORT) {
                api.getBangumiShortReviews(
                    mediaId = mediaId,
                    sort = sort,
                    cursor = cursor
                )
            } else {
                api.getBangumiLongReviews(
                    mediaId = mediaId,
                    sort = sort,
                    cursor = cursor
                )
            }
            if (response.code != 0) {
                error(response.message.ifBlank { "点评加载失败" })
            }
            val data = response.data ?: error("点评为空")
            val count = if (data.count > 0) data.count else data.total
            BangumiReviewPage(
                items = data.list,
                next = data.next,
                count = count,
                hasMore = data.next.isNotBlank()
            )
        }
    }

    suspend fun likeReview(mediaId: Long, reviewId: Long): Result<Unit> = ownedBangumiRequest(Dispatchers.IO, actionOwned) {
        runCatching {
            val csrf = csrf().orEmpty()
            if (csrf.isBlank()) error("请先登录")
            val response = api.likeBangumiReview(
                mediaId = mediaId,
                reviewId = reviewId,
                csrf = csrf
            )
            if (response.code != 0) {
                error(response.message.ifBlank { "点赞失败" })
            }
        }
    }

    suspend fun postShortReview(
        mediaId: Long,
        score: Int,
        content: String
    ): Result<Unit> = ownedBangumiRequest(Dispatchers.IO, actionOwned) {
        runCatching {
            val csrf = csrf().orEmpty()
            if (csrf.isBlank()) error("请先登录")
            val response = api.postBangumiShortReview(
                mediaId = mediaId,
                score = score.coerceIn(2, 10),
                content = content.trim(),
                csrf = csrf
            )
            if (response.code != 0) {
                error(response.message.ifBlank { "发布失败" })
            }
        }
    }
}
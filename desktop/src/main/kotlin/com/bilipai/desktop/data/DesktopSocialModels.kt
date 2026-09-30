package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.ReplyData
import com.android.purebilibili.data.model.response.ReplyItem

data class VideoRelation(val liked: Boolean, val favorited: Boolean, val coins: Int)
data class UserProfile(val mid: Long, val name: String, val avatar: String, val biography: String,
    val level: Int, val isVip: Boolean, val isFollowed: Boolean, val officialTitle: String,
    val followers: Int, val followingCount: Int)
data class CommentPage(val items: List<Comment>, val hasMore: Boolean, val totalCount: Int,
    val root: Comment? = null, val inputEnabled: Boolean = true)
data class PublishedComment(val id: Long?, val comment: Comment?)

internal data class CommentTarget(val root: Long?, val parent: Long?)
internal fun commentTarget(root: Long?, parent: Long?): CommentTarget {
    require(root == null || root > 0) { "根评论编号无效" }
    require(parent == null || parent > 0) { "回复评论编号无效" }
    require(parent == null || root != null) { "回复评论必须提供根评论编号" }
    return CommentTarget(root, if (root != null) parent ?: root else null)
}

internal fun validateCoinQuantity(quantity: Int): Int {
    require(quantity in 1..2) { "请选择投 1 枚或 2 枚硬币" }
    return quantity
}

internal fun socialComment(item: ReplyItem, preview: Boolean = true): Comment =
    Comment(item.rpid, item.member.uname, personalImageUrl(item.member.avatar), item.content.message, item.like, item.ctime,
        replyCount = maxOf(item.rcount, item.count), memberId = item.member.mid.toLongOrNull() ?: item.mid,
        rootId = item.root, liked = item.action == 1, previewReplies = if (preview) item.replies.orEmpty().filter { !it.invisible }
            .map { socialComment(it, preview = false) } else emptyList())

internal fun socialCommentPage(data: ReplyData, page: Int, nested: Boolean): CommentPage {
    require(page > 0)
    val regular = data.replies.orEmpty()
    val raw = if (!nested && page == 1) data.collectTopReplies() + regular else regular
    val items = raw.filter { !it.invisible }.distinctBy { it.rpid }.map { socialComment(it) }
    val total = if (nested) maxOf(data.page.count, data.root?.rcount ?: 0) else data.page.count
    val size = data.page.size.takeIf { it > 0 } ?: 20
    val hasMore = regular.isNotEmpty() && if (total > 0) page.toLong() * size < total else regular.size >= size
    return CommentPage(items, hasMore, total.coerceAtLeast(0),
        root = data.root?.takeIf { !it.invisible }?.let { socialComment(it) },
        inputEnabled = data.control?.inputDisable != true)
}

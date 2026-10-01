// GENERATED from app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoCommentViewModel.kt; do not edit.
// LF-normalized SHA-256: b573d51d5ffd08d5f3bac2c09ab1b0f055a9494e8f92e0d9098feafa5258a517
package com.android.purebilibili.feature.video.viewmodel
import com.android.purebilibili.data.model.response.*
internal fun resolveSubReplyRemoteTotalCount(
    data: ReplyData,
    rootReply: ReplyItem? = null
): Int {
    // 不同接口会把分页窗口大小也写进 page.count；不能把单页数量当总数。
    // 取所有可用声明中的最大值，避免“显示还有 N 条，详情却在首屏结束”。
    return listOf(
        data.page.count,
        data.root?.rcount ?: 0,
        data.root?.count ?: 0,
        data.cursor.allCount,
        rootReply?.rcount ?: 0,
        rootReply?.count ?: 0,
        data.page.acount
    ).filter { it > 0 }.maxOrNull() ?: 0
}

internal fun resolveSubReplyLoadedTotalCount(
    rootReply: ReplyItem?,
    loadedReplyCount: Int,
    remoteReplyCount: Int,
    previousTotalCount: Int = 0
): Int {
    val rootDeclaredCount = maxOf(
        rootReply?.count ?: 0,
        rootReply?.rcount ?: 0,
        rootReply?.replies.orEmpty().size
    )
    return maxOf(
        previousTotalCount,
        rootDeclaredCount,
        remoteReplyCount,
        loadedReplyCount
    ).coerceAtLeast(0)
}

internal fun resolveRoutedCommentRootReply(
    loadedReplies: List<ReplyItem>,
    remoteData: ReplyData?,
    rootReplyId: Long
): ReplyItem? {
    if (rootReplyId <= 0L) return null
    return loadedReplies.firstOrNull { it.rpid == rootReplyId }
        ?: remoteData?.root?.takeIf { it.rpid == rootReplyId }
}

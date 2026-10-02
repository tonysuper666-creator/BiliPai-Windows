package com.android.purebilibili.data.repository
import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.model.response.ReplyItem
/** Original addCommentForSubject asynchronous original-record body. Root owns its lifetime. */
internal suspend fun DesktopOriginalCommentFraudRepository.savePublishedCommentRecord(
    reply: ReplyItem, oid: Long, type: Int, root: Long, parent: Long, message: String, serverPostTime: Long,
) {
    val userUid = reply.mid
    saveRecord(
        rpid = reply.rpid,
        oid = oid,
        type = type,
        root = root,
        parent = parent,
        uid = userUid,
        message = message,
        status = CommentFraudStatus.UNKNOWN, // 当前状态未知（检测中）
        initialStatus = null, // 初始状态先置为 null (等待 5 秒后初检回填)
        postTime = serverPostTime
    )
}

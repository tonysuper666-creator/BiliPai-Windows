private fun findTargetRpid(data: ReplyData?, targetRpid: Long): CommentTargetMatch {
    if (targetRpid <= 0L || data == null) return CommentTargetMatch(false, false)

    fun match(reply: ReplyItem): CommentTargetMatch? {
        if (reply.rpid == targetRpid) return CommentTargetMatch(true, reply.invisible)
        reply.replies.orEmpty().forEach { sub ->
            if (sub.rpid == targetRpid) return CommentTargetMatch(true, sub.invisible)
        }
        return null
    }

    data.replies.orEmpty().forEach { reply -> match(reply)?.let { return it } }
    data.hots.orEmpty().forEach { reply -> match(reply)?.let { return it } }
    data.collectTopReplies().forEach { reply -> match(reply)?.let { return it } }
    return CommentTargetMatch(false, false)
}

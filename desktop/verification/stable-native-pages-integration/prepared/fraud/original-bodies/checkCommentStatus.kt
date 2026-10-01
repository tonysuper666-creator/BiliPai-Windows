suspend fun checkCommentStatus(
    aid: Long,
    rpid: Long,
    rootId: Long = 0,
    hasPictures: Boolean = false,
    sentAtSeconds: Long = 0,
    waitMs: Long = -1
): Result<CommentFraudStatus> = withContext(Dispatchers.IO) {
    try {
        // 确保本地设备访客指纹库 (buvid3) 已准备就绪
        VideoRepository.ensureBuvid3()

        // 等待分布式系统主从同步缓冲期
        val actualWait = when {
            waitMs >= 0 -> waitMs
            hasPictures -> DEFAULT_WAIT_MS + IMAGE_EXTRA_WAIT_MS
            else -> DEFAULT_WAIT_MS
        }
        if (actualWait > 0) {
            Logger.d("CommentFraud", "等待 ${actualWait}ms 后开始检测...")
            delay(actualWait)
        }

        val isReply = rootId > 0
        Logger.d("CommentFraud", "开始检测: aid=$aid, rpid=$rpid, root=$rootId, isReply=$isReply")

        if (isReply) {
            Result.success(checkReplyComment(aid, rpid, rootId, sentAtSeconds))
        } else {
            Result.success(checkRootComment(aid, rpid))
        }
    } catch (e: Exception) {
        Logger.e("CommentFraud", "检测异常: ${e.message}", e)
        Result.success(CommentFraudStatus.UNKNOWN)
    }
}

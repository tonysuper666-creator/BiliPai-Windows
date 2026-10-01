private suspend fun probeCommentPresenceBySeekRpid(
    apiClient: BilibiliApi,
    aid: Long,
    targetRpid: Long
): CommentPresenceProbe {
    return try {
        val (imgKey, subKey) = getWbiKeys()
        val params = TreeMap<String, String>().apply {
            put("oid", aid.toString())
            put("type", "1")
            put("mode", "2") // 时间排序
            put("next", "0") // 必须是 0（第 1 页起始）
            put("ps", "20")
            put("seek_rpid", targetRpid.toString())
        }
        val signedParams = WbiUtils.sign(params, imgKey, subKey)
        val response = apiClient.getReplyList(signedParams)
        when (response.code) {
            0 -> {
                val match = findTargetRpid(response.data, targetRpid)
                CommentPresenceProbe(
                    requestSucceeded = true,
                    found = match.found,
                    deletedHint = false,
                    invisible = match.invisible
                )
            }
            12022, 12009 -> {
                CommentPresenceProbe(
                    requestSucceeded = true,
                    found = false,
                    deletedHint = true
                )
            }
            else -> {
                Logger.w("CommentFraud", "seek_rpid probe failed: code=${response.code}, message=${response.message}")
                CommentPresenceProbe(
                    requestSucceeded = false,
                    found = false,
                    deletedHint = false
                )
            }
        }
    } catch (e: Exception) {
        Logger.e("CommentFraud", "seek_rpid probe exception: ${e.message}")
        CommentPresenceProbe(
            requestSucceeded = false,
            found = false,
            deletedHint = false
        )
    }
}

private suspend fun checkRootComment(aid: Long, rpid: Long): CommentFraudStatus {
    Logger.d("CommentFraud", "[根评论] Step1: guest rawCurl 探测 rpid=$rpid")

    val rpidPattern = Regex(""""rpid":\s*${rpid}""")

    // 1. 路人 rawCurl 请求第 1 页时间倒序 (next=0 为官方第 1 页起始)
    val guestRootUrl = "https://api.bilibili.com/x/v2/reply/main?oid=$aid&type=1&mode=2&next=0&ps=20"
    val guestRootJson = rawCurlGuest(guestRootUrl)

    val guestSeekProbe = CommentPresenceProbe(
        requestSucceeded = guestRootJson != null,
        found = guestRootJson != null && rpidPattern.containsMatchIn(guestRootJson),
        invisible = guestRootJson?.contains(""""rpid":\s*${rpid}[^}]*?"invisible":\s*true""") == true
    )

    if (guestSeekProbe.requestSucceeded && guestSeekProbe.found) {
        Logger.d("CommentFraud", "[根评论] ✅ 根评论路人 rawCurl 命中！")
        return resolveRootFraudStatus(
            guestSeekProbe = guestSeekProbe,
            authSeekProbe = CommentPresenceProbe(requestSucceeded = true, found = true),
            guestReplyPageVisible = null,
            confirmedNotFoundAfterRetry = false
        )
    }

    // 2. 路人未找到，原生 Retrofit API 账号视角复验
    Logger.d("CommentFraud", "[根评论] Step2: 原生 auth 账号视角复查 rpid=$rpid")
    val authSeekProbe = probeCommentPresenceBySeekRpid(
        apiClient = api,
        aid = aid,
        targetRpid = rpid
    )

    // 3. 区分 ShadowBan 与疑似审核中（通过单条回复页探测）
    var guestReplyPageVisible: Boolean? = null
    if (authSeekProbe.requestSucceeded && authSeekProbe.found) {
        Logger.d("CommentFraud", "[根评论] Step3: guest 回复页检测 root=$rpid")
        val guestReplyUrl = "https://api.bilibili.com/x/v2/reply/reply?oid=$aid&root=$rpid&pn=1&ps=1"
        val guestReplyJson = rawCurlGuest(guestReplyUrl)
        if (guestReplyJson != null) {
            guestReplyPageVisible = when {
                guestReplyJson.contains("\"code\":12022") || guestReplyJson.contains("\"code\": 12022") -> false
                guestReplyJson.contains("\"code\":0") || guestReplyJson.contains("\"code\": 0") -> true
                else -> null
            }
        }
    }

    // 4. 二次确认防止假秒删
    val confirmedNotFoundAfterRetry = if (guestSeekProbe.requestSucceeded &&
        !guestSeekProbe.found &&
        authSeekProbe.requestSucceeded &&
        !authSeekProbe.found &&
        !authSeekProbe.deletedHint
    ) {
        Logger.d("CommentFraud", "[根评论] Step4: 二次确认未命中，避免瞬时误判")
        confirmDeletedBySecondProbe(aid = aid, rpid = rpid)
    } else {
        false
    }

    val status = resolveRootFraudStatus(
        guestSeekProbe = guestSeekProbe,
        authSeekProbe = authSeekProbe,
        guestReplyPageVisible = guestReplyPageVisible,
        confirmedNotFoundAfterRetry = confirmedNotFoundAfterRetry
    )
    Logger.d(
        "CommentFraud",
        "[根评论] 判定结果=$status guestSeek=$guestSeekProbe authSeek=$authSeekProbe guestReply=$guestReplyPageVisible"
    )
    return status
}

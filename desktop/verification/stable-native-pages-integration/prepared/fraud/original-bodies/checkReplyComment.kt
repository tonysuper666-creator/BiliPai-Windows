private suspend fun checkReplyComment(
    aid: Long,
    rpid: Long,
    rootId: Long,
    sentAtSeconds: Long = 0L
): CommentFraudStatus {
    Logger.d("CommentFraud", "[楼中楼] Step1: rawCurl 获取路人视角总量 aid=$aid root=$rootId rpid=$rpid")

    // 1. 路人 rawCurl 请求第 1 页
    val firstPageUrl = "https://api.bilibili.com/x/v2/reply/reply?oid=$aid&type=1&root=$rootId&pn=1&ps=20"
    val firstPageJson = rawCurlGuest(firstPageUrl) ?: return CommentFraudStatus.UNKNOWN

    // 根评论不存在或已被主站物理删除
    if (firstPageJson.contains("\"code\":12022") || firstPageJson.contains("\"code\": 12022")) {
        Logger.d("CommentFraud", "[楼中楼] 根评论已失效(12022)，判定秒删")
        return CommentFraudStatus.DELETED
    }

    // 2. 提取真实总数（防 page.count=20 分页窗口假象陷阱）并计算最后一页
    val rcountMatch = Regex(""""rcount":\s*(\d+)""").find(firstPageJson)
    val countMatch = Regex(""""count":\s*(\d+)""").find(firstPageJson)
    val totalCount = maxOf(
        rcountMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0,
        countMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0,
        20
    )
    val lastPage = maxOf(1, (totalCount + 19) / 20)
    Logger.d("CommentFraud", "[楼中楼] Step2: 动态计算总量=$totalCount, 末页=第${lastPage}页")

    val rpidPattern = Regex(""""rpid":\s*${rpid}""")
    var guestFound = false
    var guestInvisible = false
    var targetPage = lastPage

    // 3. 路人 rawCurl 优先探测最后一页
    val lastPageUrl = "https://api.bilibili.com/x/v2/reply/reply?oid=$aid&type=1&root=$rootId&pn=$lastPage&ps=20"
    val lastPageJson = rawCurlGuest(lastPageUrl)

    if (lastPageJson != null && rpidPattern.containsMatchIn(lastPageJson)) {
        guestFound = true
        guestInvisible = lastPageJson.contains(""""rpid":\s*${rpid}[^}]*?"invisible":\s*true""")
    } else if (lastPage > 1) {
        // 倒数第 2 页容差探测（应对高频并发发评导致的页码临界偏移）
        val prevPageUrl = "https://api.bilibili.com/x/v2/reply/reply?oid=$aid&type=1&root=$rootId&pn=${lastPage - 1}&ps=20"
        val prevPageJson = rawCurlGuest(prevPageUrl)
        if (prevPageJson != null && rpidPattern.containsMatchIn(prevPageJson)) {
            guestFound = true
            guestInvisible = prevPageJson.contains(""""rpid":\s*${rpid}[^}]*?"invisible":\s*true""")
            targetPage = lastPage - 1
        }
    }

    // 4. 单调时序折半二分定位（若楼层暴涨且末页未命中）
    if (!guestFound && lastPage > 2 && sentAtSeconds > 0L) {
        var low = 1
        var high = lastPage - 2
        var steps = 0
        val maxBinarySteps = 5 // 限制最大二分探测次数为 5 次（覆盖 32 页/640 楼，兼顾性能与防频控）

        Logger.d("CommentFraud", "[时序二分] 末页未命中，启动二分收敛定位: 目标时间=$sentAtSeconds, 区间=[$low, $high]")

        while (low <= high && steps < maxBinarySteps) {
            steps++
            val mid = (low + high) / 2
            val midUrl = "https://api.bilibili.com/x/v2/reply/reply?oid=$aid&type=1&root=$rootId&pn=$mid&ps=20"
            val midJson = rawCurlGuest(midUrl) ?: break

            if (rpidPattern.containsMatchIn(midJson)) {
                guestFound = true
                guestInvisible = midJson.contains(""""rpid":\s*${rpid}[^}]*?"invisible":\s*true""")
                targetPage = mid
                Logger.d("CommentFraud", "[时序二分] 🎯 命中！在第 $mid 页成功捕获历史目标！")
                break
            }

            // 提取本页首尾时间戳，按单调性调整二分搜索区间
            val ctimeMatches = Regex(""""ctime":\s*(\d+)""").findAll(midJson).mapNotNull { it.groupValues[1].toLongOrNull() }.toList()
            val firstCtime = ctimeMatches.firstOrNull() ?: 0L
            val lastCtime = ctimeMatches.lastOrNull() ?: 0L

            if (firstCtime > 0L && lastCtime > 0L) {
                if (sentAtSeconds < firstCtime) {
                    high = mid - 1 // 目标时间更早，收缩到左半区
                } else if (sentAtSeconds > lastCtime) {
                    low = mid + 1  // 目标时间更晚，收缩到右半区
                } else {
                    targetPage = mid // 目标时间落于本页时间跨度内但未匹配上，标记本页并结束二分
                    break
                }
            } else {
                break
            }
        }
    }

    val guestProbe = CommentPresenceProbe(
        requestSucceeded = lastPageJson != null,
        found = guestFound,
        invisible = guestInvisible
    )

    // 若路人视角在目标页成功搜出，直接裁决状态
    if (guestProbe.requestSucceeded && guestProbe.found) {
        val status = resolveReplyFraudStatus(
            guestProbe = guestProbe,
            authProbe = CommentPresenceProbe(requestSucceeded = true, found = true),
            confirmedNotFoundAfterRetry = false
        )
        Logger.d("CommentFraud", "[楼中楼] ✅ 路人 rawCurl 命中(第 ${targetPage} 页)，最终判定=$status")
        return status
    }

    // 5. 路人端未命中，使用带有用户登录态的原生 Retrofit API 对收敛的目标页进行账号视角复验
    Logger.d("CommentFraud", "[楼中楼] Step3: 原生 auth 账号视角对第 $targetPage 页复验 rpid=$rpid")
    var authFound = false
    var authInvisible = false
    var authRequestSucceeded = false
    try {
        val authResp = api.getReplyReply(oid = aid, type = 1, root = rootId, pn = targetPage, ps = 20)
        if (authResp.code == 0) {
            authRequestSucceeded = true
            var match = findTargetRpid(authResp.data, rpid)
            if (!match.found && targetPage > 1) {
                val authPrevResp = api.getReplyReply(oid = aid, type = 1, root = rootId, pn = targetPage - 1, ps = 20)
                if (authPrevResp.code == 0) match = findTargetRpid(authPrevResp.data, rpid)
            }
            authFound = match.found
            authInvisible = match.invisible
        }
    } catch (e: Exception) {
        Logger.w("CommentFraud", "auth 探测异常: ${e.message}")
    }

    val authProbe = CommentPresenceProbe(
        requestSucceeded = authRequestSucceeded,
        found = authFound,
        invisible = authInvisible
    )

    val finalStatus = resolveReplyFraudStatus(
        guestProbe = guestProbe,
        authProbe = authProbe,
        confirmedNotFoundAfterRetry = !authFound
    )
    Logger.d(
        "CommentFraud",
        "[楼中楼] 判定结果=$finalStatus guest=$guestProbe auth=$authProbe (目标页: $targetPage)"
    )
    return finalStatus
}

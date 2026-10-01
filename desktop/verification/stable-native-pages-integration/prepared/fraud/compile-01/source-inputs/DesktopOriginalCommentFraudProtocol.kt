// OriginalSource: app/src/main/java/com/android/purebilibili/data/repository/CommentRepository.kt
// OriginalSHA256: 4950b91a708e4d22ddbc6b6f39ef6880879e175dad543ff1fd5b833518b2b820
package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.FORCE_COOKIE_HEADER
import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.*
import okhttp3.Call
import java.util.TreeMap

/** Original type=1 fraud detection. BGM subject type=47 does not change this
 * original protocol; persistence and UI are owned by the original BGM owner. */
internal class DesktopOriginalCommentFraudProtocol(
    private val api: BilibiliApi,
    private val rawCalls: Call.Factory,
    private val buvid3: () -> String?,
    private val signParams: suspend (Map<String, String>) -> Map<String, String>,
    private val ensureVisitor: suspend () -> Unit,
    private val checkOwned: () -> Unit,
) {
    private suspend fun ensureOwned() { currentCoroutineContext().ensureActive(); checkOwned() }
    private suspend fun <T> ownedCall(block: suspend () -> T): T {
        ensureOwned()
        return try { block().also { ensureOwned() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { ensureOwned(); throw failure }
    }
    private data class CommentTargetMatch(
        val found: Boolean,
        val invisible: Boolean
    )
    private const val DEFAULT_WAIT_MS = 5000L
    private const val IMAGE_EXTRA_WAIT_MS = 10000L
    private const val DELETE_CONFIRM_RETRY_DELAY_MS = 2200L

    private suspend fun rawCurlGuest(url: String): String? = withContext(Dispatchers.IO) {
        ensureOwned()
        val buvid = buvid3()
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .header("Origin", "https://www.bilibili.com")
            .header("Referer", "https://www.bilibili.com")
            .header("Accept", "application/json, text/plain, */*")
            .apply {
                if (!buvid.isNullOrBlank()) {
                    header("Cookie", "buvid3=$buvid;")
                }
            }
            .header(FORCE_COOKIE_HEADER, if (!buvid.isNullOrBlank()) "buvid3=$buvid;" else "")
            .build()

        try {
            awaitDesktopCommentFraudRaw(rawCalls.newCall(request), checkOwned)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {

            null
        }
    }

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
            ensureOwned()
            ensureVisitor()
            ensureOwned()

            // 等待分布式系统主从同步缓冲期
            val actualWait = when {
                waitMs >= 0 -> waitMs
                hasPictures -> DEFAULT_WAIT_MS + IMAGE_EXTRA_WAIT_MS
                else -> DEFAULT_WAIT_MS
            }
            if (actualWait > 0) {

                delay(actualWait)
                ensureOwned()
            }

            val isReply = rootId > 0


            if (isReply) {
                Result.success(checkReplyComment(aid, rpid, rootId, sentAtSeconds))
            } else {
                Result.success(checkRootComment(aid, rpid))
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {

            Result.success(CommentFraudStatus.UNKNOWN)
        }
    }

    private suspend fun checkReplyComment(
        aid: Long,
        rpid: Long,
        rootId: Long,
        sentAtSeconds: Long = 0L
    ): CommentFraudStatus {


        // 1. 路人 rawCurl 请求第 1 页
        val firstPageUrl = "https://api.bilibili.com/x/v2/reply/reply?oid=$aid&type=1&root=$rootId&pn=1&ps=20"
        val firstPageJson = rawCurlGuest(firstPageUrl) ?: return CommentFraudStatus.UNKNOWN

        // 根评论不存在或已被主站物理删除
        if (firstPageJson.contains("\"code\":12022") || firstPageJson.contains("\"code\": 12022")) {

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



            while (low <= high && steps < maxBinarySteps) {
                steps++
                val mid = (low + high) / 2
                val midUrl = "https://api.bilibili.com/x/v2/reply/reply?oid=$aid&type=1&root=$rootId&pn=$mid&ps=20"
                val midJson = rawCurlGuest(midUrl) ?: break

                if (rpidPattern.containsMatchIn(midJson)) {
                    guestFound = true
                    guestInvisible = midJson.contains(""""rpid":\s*${rpid}[^}]*?"invisible":\s*true""")
                    targetPage = mid

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

            return status
        }

        // 5. 路人端未命中，使用带有用户登录态的原生 Retrofit API 对收敛的目标页进行账号视角复验

        var authFound = false
        var authInvisible = false
        var authRequestSucceeded = false
        try {
            val authResp = ownedCall { api.getReplyReply(oid = aid, type = 1, root = rootId, pn = targetPage, ps = 20) }
            if (authResp.code == 0) {
                authRequestSucceeded = true
                var match = findTargetRpid(authResp.data, rpid)
                if (!match.found && targetPage > 1) {
                    val authPrevResp = ownedCall { api.getReplyReply(oid = aid, type = 1, root = rootId, pn = targetPage - 1, ps = 20) }
                    if (authPrevResp.code == 0) match = findTargetRpid(authPrevResp.data, rpid)
                }
                authFound = match.found
                authInvisible = match.invisible
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {

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

        return finalStatus
    }

    private suspend fun checkRootComment(aid: Long, rpid: Long): CommentFraudStatus {


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

            return resolveRootFraudStatus(
                guestSeekProbe = guestSeekProbe,
                authSeekProbe = CommentPresenceProbe(requestSucceeded = true, found = true),
                guestReplyPageVisible = null,
                confirmedNotFoundAfterRetry = false
            )
        }

        // 2. 路人未找到，原生 Retrofit API 账号视角复验

        val authSeekProbe = probeCommentPresenceBySeekRpid(
            apiClient = api,
            aid = aid,
            targetRpid = rpid
        )

        // 3. 区分 ShadowBan 与疑似审核中（通过单条回复页探测）
        var guestReplyPageVisible: Boolean? = null
        if (authSeekProbe.requestSucceeded && authSeekProbe.found) {

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

        return status
    }

    private suspend fun probeCommentPresenceBySeekRpid(
        apiClient: BilibiliApi,
        aid: Long,
        targetRpid: Long
    ): CommentPresenceProbe {
        return try {
            val params = TreeMap<String, String>().apply {
                put("oid", aid.toString())
                put("type", "1")
                put("mode", "2") // 时间排序
                put("next", "0") // 必须是 0（第 1 页起始）
                put("ps", "20")
                put("seek_rpid", targetRpid.toString())
            }
            val signedParams = signParams(params).also { ensureOwned() }
            val response = ownedCall { apiClient.getReplyList(signedParams) }
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

                    CommentPresenceProbe(
                        requestSucceeded = false,
                        found = false,
                        deletedHint = false
                    )
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {

            CommentPresenceProbe(
                requestSucceeded = false,
                found = false,
                deletedHint = false
            )
        }
    }

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

    private suspend fun confirmDeletedBySecondProbe(aid: Long, rpid: Long): Boolean {
        delay(DELETE_CONFIRM_RETRY_DELAY_MS)
        val guestRootUrl = "https://api.bilibili.com/x/v2/reply/main?oid=$aid&type=1&mode=2&next=0&ps=20"
        val guestRetryJson = rawCurlGuest(guestRootUrl)
        val rpidPattern = Regex(""""rpid":\s*${rpid}""")
        if (guestRetryJson == null || rpidPattern.containsMatchIn(guestRetryJson)) {
            return false
        }
        val authRetryProbe = probeCommentPresenceBySeekRpid(
            apiClient = api,
            aid = aid,
            targetRpid = rpid
        )
        return authRetryProbe.requestSucceeded && !authRetryProbe.found
    }
}

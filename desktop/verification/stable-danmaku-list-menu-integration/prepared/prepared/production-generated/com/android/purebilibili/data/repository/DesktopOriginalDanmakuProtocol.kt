package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Same owned Root API and CSRF. No Retrofit/client/cookie/account construction. */
internal class DesktopOriginalDanmakuProtocol(
    private val api:BilibiliApi,
    private val readCsrf:()->String?,
    private val assertOwned:()->Unit,
) {
    internal suspend fun getDanmakuThumbupState(
        cid: Long,
        dmid: Long
    ): Result<DanmakuThumbupState> = withContext(Dispatchers.IO) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        assertOwned()
        try {
            if (dmid <= 0L) {
                return@withContext Result.failure(IllegalArgumentException("弹幕ID无效"))
            }

            val response = api.getDanmakuThumbupStats(
                oid = cid,
                ids = dmid.toString()
            )
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            assertOwned()

            if (response.code != 0) {
                val message = response.message.ifEmpty { "查询弹幕投票状态失败 (${response.code})" }
                return@withContext Result.failure(Exception(message))
            }

            val state = resolveDanmakuThumbupState(dmid = dmid, data = response.data)
                ?: return@withContext Result.failure(Exception("未找到该弹幕投票信息"))

            Result.success(state)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            assertOwned()
            Result.failure(e)
        }
    }

    suspend fun recallDanmaku(
        cid: Long,
        dmid: Long
    ): Result<String> = withContext(Dispatchers.IO) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        assertOwned()
        try {
            val csrf = readCsrf()
            if (csrf.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("请先登录"))
            }

            
            val response = api.recallDanmaku(cid = cid, dmid = dmid, csrf = csrf)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            assertOwned()
            
            if (response.code == 0) {
                Result.success(response.message)
            } else {
                val errorMsg = when (response.code) {
                    -101 -> "请先登录"
                    -111 -> "鉴权失败，请重新登录"
                    -400 -> "请求参数错误"
                    36301 -> "撤回次数已用完" 
                    36302 -> "弹幕发送超过2分钟，无法撤回"
                    36303 -> "该弹幕无法撤回"
                    else -> response.message.ifEmpty { "撤回失败 (${response.code})" }
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            assertOwned()
            Result.failure(e)
        }
    }

    suspend fun likeDanmaku(
        cid: Long,
        dmid: Long,
        like: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        assertOwned()
        try {
            val csrf = readCsrf()
            if (csrf.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("请先登录"))
            }

            val op = if (like) 1 else 2
            
            val response = api.likeDanmaku(oid = cid, dmid = dmid, op = op, csrf = csrf)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            assertOwned()
            
            if (response.code == 0) {
                Result.success(Unit)
            } else {
                val errorMsg = when (response.code) {
                    -101 -> "请先登录"
                    -111 -> "鉴权失败，请重新登录"
                    -400 -> "请求参数错误"
                    65004 -> "已经点过赞了"
                    65005 -> "已经取消点赞了"
                    else -> response.message.ifEmpty { "操作失败 (${response.code})" }
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            assertOwned()
            Result.failure(e)
        }
    }

    suspend fun reportDanmaku(
        cid: Long,
        dmid: Long,
        reason: Int,
        content: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        assertOwned()
        try {
            val csrf = readCsrf()
            if (csrf.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("请先登录"))
            }

            
            val response = api.reportDanmaku(cid = cid, dmid = dmid, reason = reason, content = content, csrf = csrf)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            assertOwned()
            
            if (response.code == 0) {
                Result.success(Unit)
            } else {
                val errorMsg = when (response.code) {
                    -101 -> "请先登录"
                    -111 -> "鉴权失败，请重新登录"
                    -400 -> "请求参数错误"
                    else -> response.message.ifEmpty { "举报失败 (${response.code})" }
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            assertOwned()
            Result.failure(e)
        }
    }
}

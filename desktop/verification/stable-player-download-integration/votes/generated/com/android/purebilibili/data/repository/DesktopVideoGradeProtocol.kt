// GENERATED from app/src/main/java/com/android/purebilibili/data/repository/DanmakuRepository.kt; do not edit.
// LF-normalized SHA-256: d09592b8d7e5e3fa5ae0d0f9e15dc13ca9378edf8fd185ebc6537d7613c7cff5
package com.android.purebilibili.data.repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
private fun mapSendDanmakuErrorMessage(code: Int, fallbackMessage: String): String {
    return when (code) {
        -101 -> "请先登录"
        -102 -> "账号被封禁"
        -111 -> "鉴权失败，请重新登录"
        -400 -> "请求参数错误"
        -509 -> "请求过于频繁，请稍后再试"
        36700 -> "系统升级中，请稍后再试"
        36701 -> "弹幕包含被禁止的内容"
        36702 -> "弹幕长度超出限制"
        36703 -> "发送频率过快，请稍后再试"
        36704 -> "当前视频暂不允许发送弹幕"
        36705 -> "当前账号等级不足，无法发送该弹幕"
        36706 -> "当前账号等级不足，无法发送顶端弹幕"
        36707 -> "当前账号等级不足，无法发送底端弹幕"
        36708 -> "当前账号暂无彩色弹幕权限"
        36709 -> "当前账号等级不足，无法发送高级弹幕"
        36710 -> "当前账号暂无该弹幕样式权限"
        36711 -> "该视频禁止发送弹幕"
        36712 -> "当前账号等级限制，弹幕长度上限更低"
        36718 -> "当前账号不是大会员，无法发送渐变彩色弹幕"
        else -> fallbackMessage.ifEmpty { "发送弹幕失败 ($code)" }
    }
}
internal class DesktopVideoGradeProtocol(private val api: com.android.purebilibili.core.network.BilibiliApi) {
    suspend fun submitGradeDanmaku(
            aid: Long,
            cid: Long,
            progress: Long,
            gradeId: String,
            gradeScore: Int,
            csrf: String
        ): Result<Unit> = withContext(Dispatchers.IO) {
            try {
                if (csrf.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("请先登录"))
                }
                val id = gradeId.toLongOrNull()
                if (id == null) {
                    return@withContext Result.failure(Exception("缺少打分 ID"))
                }
                val response = api.gradeDanmaku(
                    aid = aid,
                    cid = cid,
                    progress = progress,
                    gradeId = id,
                    gradeScore = gradeScore,
                    csrf = csrf
                )
                if (response.code == 0) {
                    com.android.purebilibili.core.util.Logger.d("DanmakuRepo", "✅ Grade danmaku submitted: grade_id=$gradeId score=$gradeScore")
                    Result.success(Unit)
                } else {
                    android.util.Log.e("DanmakuRepo", "❌ gradeDanmaku failed: ${response.code} - ${response.message}")
                    Result.failure(Exception(mapSendDanmakuErrorMessage(response.code, response.message)))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.e("DanmakuRepo", "❌ submitGradeDanmaku exception: ${e.message}", e)
                Result.failure(e)
            }
        }
}

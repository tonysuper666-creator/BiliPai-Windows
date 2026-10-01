package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
import kotlinx.coroutines.*
internal class DesktopOriginalDanmakuCloudRuleProtocol(
    private val api:BilibiliApi, private val readCsrf:()->String?, private val assertOwned:()->Unit,
) {
    suspend fun getDanmakuCloudFilterRules(): Result<DanmakuCloudFilterRules> =
        withContext(Dispatchers.IO) {
            assertOwned()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            try {
                val response = api.getDanmakuFilterRules()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                assertOwned()
                if (response.code != 0) {
                    return@withContext Result.failure(Exception(response.message.ifEmpty { "同步云端弹幕屏蔽规则失败" }))
                }
                val data = response.data
                    ?: return@withContext Result.failure(Exception("同步云端弹幕屏蔽规则失败"))
                val rules = buildList {
                    addAll(data.rule)
                    addAll(data.rule1)
                    addAll(data.rule2)
                }.map { DanmakuCloudFilterRule(id = it.id, type = it.type, filter = it.filter) }
                Result.success(DanmakuCloudFilterRules(rules = rules, toast = data.toast))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                assertOwned()
                Result.failure(e)
            }
        }

    /** 添加云端弹幕屏蔽规则（type: 0=关键词, 1=正则, 2=UID crc32 hex） */

    suspend fun addDanmakuCloudFilterRule(type: Int, filter: String): Result<DanmakuCloudFilterRule> =
        withContext(Dispatchers.IO) {
            assertOwned()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            try {
                val csrf = readCsrf()
                if (csrf.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("请先登录"))
                }
                val response = api.addDanmakuFilterRule(type = type, filter = filter, csrf = csrf)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                assertOwned()
                if (response.code != 0) {
                    return@withContext Result.failure(Exception(response.message.ifEmpty { "添加云端弹幕屏蔽规则失败" }))
                }
                val data = response.data
                    ?: return@withContext Result.failure(Exception("添加云端弹幕屏蔽规则失败"))
                Result.success(
                    DanmakuCloudFilterRule(id = data.id, type = data.type, filter = data.filter)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                assertOwned()
                Result.failure(e)
            }
        }

    /** 删除云端弹幕屏蔽规则 */

    suspend fun deleteDanmakuCloudFilterRule(id: Long): Result<Unit> =
        withContext(Dispatchers.IO) {
            assertOwned()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            try {
                val csrf = readCsrf()
                if (csrf.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("请先登录"))
                }
                val response = api.deleteDanmakuFilterRule(ids = id, csrf = csrf)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                assertOwned()
                if (response.code != 0) {
                    Result.failure(Exception(response.message.ifEmpty { "删除云端弹幕屏蔽规则失败" }))
                } else {
                    Result.success(Unit)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                assertOwned()
                Result.failure(e)
            }
        }

    /** Full-video loading is retained only for offline asset export. Playback uses single segments. */
}

package com.android.purebilibili.data.repository

import com.android.purebilibili.core.database.entity.CommentFraudRecord
import com.android.purebilibili.core.util.Logger
import com.android.purebilibili.data.model.CommentFraudStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class DesktopOriginalCommentFraudRepository(
    private val dao: DesktopCommentFraudDao,
    private val checkStatus: suspend (Long, Long, Long) -> Result<CommentFraudStatus>,
    private val deleteComment: suspend (Long, Long) -> Result<Unit>,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun getDao() = dao

    /**
     * 保存或更新评论反诈记录 (支持完整元数据)
     */
    suspend fun saveRecord(
        rpid: Long,
        oid: Long,
        type: Int = 1,
        root: Long = 0L,
        parent: Long = 0L,
        uid: Long = 0L,
        sourceId: String? = null,
        message: String,
        status: CommentFraudStatus,
        initialStatus: CommentFraudStatus? = null,
        postTime: Long = 0L,
        originUrl: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        if (rpid <= 0L) return@withContext
        try {
            val existing = getDao().getRecordByRpid(rpid)
            val record = CommentFraudRecord(
                rpid = rpid,
                oid = oid,
                type = type,
                root = root,
                parent = parent,
                uid = if (uid > 0L) uid else (existing?.uid ?: 0L),
                source_id = sourceId ?: existing?.source_id,
                origin_url = originUrl ?: existing?.origin_url,
                message = message.ifBlank { existing?.message.orEmpty() },
                initial_status = (initialStatus?.name ?: existing?.initial_status) ?: status.name,
                status = status.name,
                post_time = if (postTime > 0L) postTime else (existing?.post_time ?: 0L),
                timestamp = timestamp
            )
            getDao().insertOrUpdate(record)

        } catch (cancelled: CancellationException) { throw cancelled
        } catch (e: Exception) {

        }
    }

    fun getAllRecordsFlow(): Flow<List<CommentFraudRecord>> {
        return getDao().getAllRecordsFlow()
    }

    /**
     * 再次复检（复检成功若包含官方 ctime 顺便自愈补齐）
     */
    suspend fun recheckRecord(
        record: CommentFraudRecord
    ): Result<CommentFraudStatus> = withContext(Dispatchers.IO) {
        try {

            val result = checkStatus(record.oid, record.rpid, record.root)

            val newStatus = result.getOrDefault(CommentFraudStatus.UNKNOWN)
            val updatedRecord = record.copy(
                status = newStatus.name,
                timestamp = System.currentTimeMillis()
            )
            getDao().insertOrUpdate(updatedRecord)

            Result.success(newStatus)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (e: Exception) {

            Result.failure(e)
        }
    }

    suspend fun deleteBiliComment(
        record: CommentFraudRecord
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {

            val deleteResult = deleteComment(record.oid, record.rpid)
            if (deleteResult.isSuccess) {
                getDao().deleteByRpid(record.rpid)

                Result.success(Unit)
            } else {
                val error = deleteResult.exceptionOrNull() ?: Exception("删评失败")
                Result.failure(error)
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteLocalRecord(rpid: Long) = withContext(Dispatchers.IO) {
        getDao().deleteByRpid(rpid)
    }

    suspend fun clearAllRecords() = withContext(Dispatchers.IO) {
        getDao().clearAll()
    }

    suspend fun exportToJson(): String = withContext(Dispatchers.IO) {
        val records = getDao().getAllRecords()
        json.encodeToString(records)
    }

    suspend fun importFromJson(jsonContent: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val importedList = json.decodeFromString<List<CommentFraudRecord>>(jsonContent)
            if (importedList.isNotEmpty()) {
                getDao().insertAll(importedList)
            }
            Result.success(importedList.size)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (e: Exception) {

            Result.failure(e)
        }
    }
}

package com.android.purebilibili.data.repository

import com.android.purebilibili.core.database.entity.CommentFraudRecord
import kotlinx.coroutines.flow.Flow

internal interface DesktopCommentFraudDao {

    suspend fun insertOrUpdate(record: CommentFraudRecord)

    suspend fun insertAll(records: List<CommentFraudRecord>)

    /** 
     * 优先按真实的官方发评时间 post_time 倒序排列；
     * 复检老评论只会更新状态，防止老条目跳到列表顶部！
     */
    fun getAllRecordsFlow(): Flow<List<CommentFraudRecord>>

    suspend fun getAllRecords(): List<CommentFraudRecord>

    suspend fun getRecordByRpid(rpid: Long): CommentFraudRecord?

    suspend fun deleteByRpid(rpid: Long)

    suspend fun clearAll()
}

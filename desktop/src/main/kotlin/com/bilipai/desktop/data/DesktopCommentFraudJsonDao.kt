package com.bilipai.desktop.data

import com.android.purebilibili.core.database.entity.CommentFraudRecord
import com.android.purebilibili.data.repository.DesktopCommentFraudDao
import com.bilipai.desktop.update.UpdateStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Room storage binding for original records. Root supplies its existing state directory
 * and the actual SessionStore owner admission; this creates no account/HTTP/preferences store. */
internal class DesktopCommentFraudJsonDao(
    stateDirectory: Path,
    private val mid: Long,
    private val isOwned: () -> Boolean,
    private val admit: (() -> Unit) -> Boolean,
) : DesktopCommentFraudDao, AutoCloseable {
    private val lock=Any()
    private var closed=false
    private val json=Json { ignoreUnknownKeys=true;isLenient=true;prettyPrint=true;encodeDefaults=true }
    private val serializer=ListSerializer(CommentFraudRecord.serializer())
    private val directory=stateDirectory.resolve("comment_fraud_records").resolve(mid.toString())
    private val file=directory.resolve("records.json")
    private var records=linkedMapOf<Long,CommentFraudRecord>()
    private val flow=MutableStateFlow<List<CommentFraudRecord>>(emptyList())
    init {
        require(mid>=0)
        owned {
            UpdateStorage.verifiedRoot(directory)
            if(Files.exists(file,NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(file,NOFOLLOW_LINKS) && UpdateStorage.existingPathWithoutLinks(file)==file.toAbsolutePath().normalize())
                records=json.decodeFromString(serializer,Files.readString(file)).associateByTo(linkedMapOf()){it.rpid}
            }
            publish()
        }
    }
    private fun <T> owned(block:()->T):T {
        var result:Any?=null
        if(!admit { synchronized(lock) {
                if(closed || !isOwned())throw CancellationException("Comment record owner retired")
                result=block()
                if(closed || !isOwned())throw CancellationException("Comment record owner retired")
            } })throw CancellationException("Comment record account epoch retired")
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun sorted()=records.values.sortedByDescending { if(it.post_time>0)it.post_time else it.timestamp }
    private fun publish(){flow.value=sorted()}
    private fun persist(next:LinkedHashMap<Long,CommentFraudRecord>) {
        check(mid>0){"请先登录后保存评论记录"}
        UpdateStorage.existingPathWithoutLinks(directory)
        if(Files.exists(file,NOFOLLOW_LINKS))require(Files.isRegularFile(file,NOFOLLOW_LINKS) && !Files.isSymbolicLink(file))
        val temporary=Files.createTempFile(directory,"records-",".tmp")
        try {
            Files.writeString(temporary,json.encodeToString(serializer,next.values.toList()))
            if(closed || !isOwned())throw CancellationException("Comment record owner retired")
            Files.move(temporary,file,ATOMIC_MOVE,REPLACE_EXISTING)
            records=next;publish()
        } finally { Files.deleteIfExists(temporary) }
    }
    override suspend fun insertOrUpdate(record:CommentFraudRecord)=owned { persist(LinkedHashMap(records).apply{put(record.rpid,record)}) }
    override suspend fun insertAll(records:List<CommentFraudRecord>)=owned { persist(LinkedHashMap(this.records).apply{records.forEach{put(it.rpid,it)}}) }
    override fun getAllRecordsFlow():Flow<List<CommentFraudRecord>> = owned { flow.asStateFlow() }
    override suspend fun getAllRecords():List<CommentFraudRecord> = owned { sorted() }
    override suspend fun getRecordByRpid(rpid:Long):CommentFraudRecord? = owned { records[rpid] }
    override suspend fun deleteByRpid(rpid:Long)=owned { persist(LinkedHashMap(records).apply{remove(rpid)}) }
    override suspend fun clearAll()=owned { persist(linkedMapOf()) }
    override fun close() { synchronized(lock) { closed=true;records.clear();flow.value=emptyList() } }
}

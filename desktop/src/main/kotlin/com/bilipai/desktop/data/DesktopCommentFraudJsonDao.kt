package com.bilipai.desktop.data

import com.android.purebilibili.core.database.entity.CommentFraudRecord
import com.android.purebilibili.data.repository.DesktopCommentFraudDao
import com.bilipai.desktop.update.UpdateStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val mutations=Mutex()
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
    private suspend fun <T> operation(block:()->T):T {
        val caller=currentCoroutineContext()
        val page=caller[DesktopCommentFraudRecordOperation]
        return owned {
            caller.ensureActive();page?.checkpoint()
            // Preserve actual SessionStore -> DAO owner -> ImageLifetime order. No
            // caller enters page admission and then waits for the account monitor.
            if(page!=null)page.withAdmission { caller.ensureActive();block() } else block()
        }
    }
    private suspend fun mutateRecords(transform:(LinkedHashMap<Long,CommentFraudRecord>)->LinkedHashMap<Long,CommentFraudRecord>) {
        mutations.withLock {
            val caller=currentCoroutineContext()
            val page=caller[DesktopCommentFraudRecordOperation]
            fun checkpoint() {
                caller.ensureActive();page?.checkpoint()
                synchronized(lock) {
                    if(closed || !isOwned())throw CancellationException("Comment record owner retired")
                }
            }
            val next=operation { check(mid>0){"请先登录后保存评论记录"};transform(LinkedHashMap(records)) }
            checkpoint()
            val encoded=json.encodeToString(serializer,next.values.toList())
            checkpoint()
            UpdateStorage.existingPathWithoutLinks(directory)
            if(Files.exists(file,NOFOLLOW_LINKS))require(Files.isRegularFile(file,NOFOLLOW_LINKS) && !Files.isSymbolicLink(file))
            val temporary=Files.createTempFile(directory,"records-",".tmp")
            try {
                Files.newBufferedWriter(temporary).use { writer ->
                    var offset=0
                    while(offset<encoded.length) {
                        checkpoint()
                        val count=minOf(32*1024,encoded.length-offset)
                        writer.write(encoded,offset,count);offset+=count
                    }
                }
                checkpoint()
                operation {
                    UpdateStorage.existingPathWithoutLinks(directory)
                    if(Files.exists(file,NOFOLLOW_LINKS))require(Files.isRegularFile(file,NOFOLLOW_LINKS) && !Files.isSymbolicLink(file))
                    Files.move(temporary,file,ATOMIC_MOVE,REPLACE_EXISTING)
                    records=next;publish()
                }
            } finally { Files.deleteIfExists(temporary) }
        }
    }
    override suspend fun insertOrUpdate(record:CommentFraudRecord)=mutateRecords { it.apply{put(record.rpid,record)} }
    override suspend fun insertAll(records:List<CommentFraudRecord>)=mutateRecords { next->next.apply{records.forEach{put(it.rpid,it)}} }
    override fun getAllRecordsFlow():Flow<List<CommentFraudRecord>> = owned { flow.asStateFlow() }
    override suspend fun getAllRecords():List<CommentFraudRecord> = operation { sorted() }
    override suspend fun getRecordByRpid(rpid:Long):CommentFraudRecord? = operation { records[rpid] }
    override suspend fun deleteByRpid(rpid:Long)=mutateRecords { it.apply{remove(rpid)} }
    override suspend fun clearAll()=mutateRecords { linkedMapOf() }
    override fun close() { synchronized(lock) { closed=true;records.clear();flow.value=emptyList() } }
}

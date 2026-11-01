package com.bilipai.desktop.player

import com.android.purebilibili.data.model.response.*
import kotlin.test.*

/** Typed scripted cursor envelope for legacy fixture data; never a production adapter. */
internal class DesktopTypedCommentFixture {
    val mainCalls = mutableListOf<List<Any?>>()
    val subCalls = mutableListOf<List<Any?>>()
    private val responses = mutableMapOf<List<Any?>, CommentMainListContinuation>()
    suspend fun main(oid:Long,type:Int,page:Int,mode:Int,continuation:CommentMainListContinuation?,
        read:suspend ()->Result<ReplyData>):Result<ReplyData> {
        assertTrue(oid>0 && type>0 && page>0)
        mainCalls += listOf(oid,type,page,mode,continuation)
        if(page==1) assertNull(continuation)
        else {
            val token=assertNotNull(continuation)
            assertEquals(CommentMainListRoute.REST_MAIN,token.route)
            assertTrue(token.next > 0 || !token.offset.isNullOrBlank())
            responses[listOf(oid,type,mode,page)]?.let { assertEquals(it,token) }
        }
        return read().map { data ->
            val token = if(data.cursor.isEnd) null else data.mainListContinuation ?:
                if(data.cursor.next > 0 || data.grpcNextOffset.isNotBlank())
                    CommentMainListContinuation(CommentMainListRoute.REST_MAIN,data.cursor.next.toLong(),data.grpcNextOffset)
                else null
            if(token!=null) responses[listOf(oid,type,mode,page+1)]=token
            data.copy(mainListContinuation=token)
        }
    }
    fun sub(oid:Long,type:Int,rootId:Long,mode:Int,offset:String?,target:Long,supplement:Boolean) {
        assertTrue(oid>0 && type>0 && rootId>0 && target>=0)
        subCalls += listOf(oid,type,rootId,mode,offset,target,supplement)
    }
}

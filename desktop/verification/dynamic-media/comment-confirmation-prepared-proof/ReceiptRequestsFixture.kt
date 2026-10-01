package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.*
import com.android.purebilibili.feature.video.viewmodel.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.*
import kotlin.test.*

private val replyJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }
private fun rawReply(id: Long, root: Long = 0, parent: Long = root, likes: Int = 8, action: Int = 0): ReplyItem =
    replyJson.decodeFromString("""{"rpid":$id,"oid":100,"mid":88,"root":$root,"parent":$parent,"count":3,"rcount":3,"like":$likes,"action":$action,"content":{"message":"Reply $id"},"member":{"mid":"88","uname":"Raw member"}}""")
private fun replyData(items: List<ReplyItem>, total: Int = 3, next: String = "more", end: Boolean = false, root: ReplyItem? = null): ReplyData =
    replyJson.decodeFromString<ReplyData>("""{"page":{"count":$total},"cursor":{"all_count":$total,"is_end":$end},"grpc_next_offset":"$next"}""").copy(replies=items,root=root,grpcNextOffset=next)
private val subject: DynamicItem = replyJson.decodeFromString("""{"id_str":"100","type":"DYNAMIC_TYPE_WORD","basic":{"comment_id_str":"100","comment_type":17},"modules":{"module_dynamic":{"desc":{"text":"Original raw subject"}},"module_stat":{"comment":{"count":3}}}}""")

internal class FixtureReplyRequests : DesktopDynamicReplyRequests {
    var active = true
    var loggedIn = true
    val calls = mutableListOf<String>()
    var main: suspend (Int, Int, String?) -> ReplyData = { page, _, _ ->
        if(page==1)replyData(listOf(rawReply(701),rawReply(702))) else replyData(listOf(rawReply(702),rawReply(703)),next="",end=true)
    }
    var sub: suspend (Int, String?, Long) -> ReplyData = { _, _, _ ->replyData(listOf(rawReply(710,701)),root=rawReply(701)) }
    var mutation: suspend (String) -> Unit = {}
    override fun isOwned()=active
    override fun hasCsrf()=loggedIn
    override suspend fun getDynamicDetail(id:String):Result<DynamicItem> { calls+="detail:$id";return Result.success(subject) }
    override suspend fun getCommentCountForSubject(oid:Long,type:Int):Result<Int> { calls+="count:$oid:$type";return Result.success(3) }
    override suspend fun getCommentsForSubject(oid:Long,type:Int,page:Int,ps:Int,mode:Int,paginationOffset:String?,fallbackOnMissingLocation:Boolean):Result<ReplyData> {
        calls+="main:$oid:$type:$page:$ps:$mode:$paginationOffset:$fallbackOnMissingLocation"
        return Result.success(main(page,mode,paginationOffset))
    }
    override suspend fun getSortedSubCommentsForSubject(oid:Long,type:Int,rootId:Long,mode:Int,paginationOffset:String?,targetReplyId:Long):Result<ReplyData> {
        calls+="sub:$oid:$type:$rootId:$mode:$paginationOffset:$targetReplyId"
        return Result.success(sub(mode,paginationOffset,targetReplyId))
    }
    override suspend fun addCommentForSubject(oid:Long,type:Int,message:String,root:Long,parent:Long):Result<ReplyItem?> {
        calls+="post:$oid:$type:$root:$parent:$message";mutation("post");return Result.success(rawReply(800,root,parent))
    }
    override suspend fun likeCommentForSubject(oid:Long,type:Int,rpid:Long,like:Boolean):Result<Unit> {
        calls+="like:$rpid:$like";return runCatching{mutation("like")}
    }
    override suspend fun hateCommentForSubject(oid:Long,type:Int,rpid:Long,hate:Boolean):Result<Unit> {
        calls+="hate:$rpid:$hate";return runCatching{mutation("hate")}
    }
    override suspend fun deleteCommentForSubject(oid:Long,type:Int,rpid:Long):Result<Unit> {
        calls+="delete:$rpid";mutation("delete");return Result.success(Unit)
    }
    override suspend fun setCommentTopForSubject(oid:Long,type:Int,rpid:Long,isCurrentlyTop:Boolean):Result<Unit> {
        calls+="top:$rpid:$isCurrentlyTop";return Result.success(Unit)
    }
    override suspend fun reportCommentForSubject(oid:Long,type:Int,rpid:Long,reason:Int):Result<Unit> {
        calls+="report:$rpid:$reason";return Result.success(Unit)
    }
}


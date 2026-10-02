package com.android.purebilibili.feature.video.viewmodel

import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopOriginalCommentFraudRepository
import com.bilipai.desktop.data.DesktopDynamicCardOperations
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.ui.DesktopDynamicReplyOperationsBinding
import okhttp3.RequestBody

/** Required typed-subject original operations. There is no dynamic/item surrogate. */
internal interface DesktopVideoCommentRequests {
    fun isOwned(): Boolean
    fun currentMid(): Long
    suspend fun getCommentsForSubject(oid:Long,type:Int,page:Int,ps:Int=20,mode:Int=3,paginationOffset:String?=null,fallbackOnMissingLocation:Boolean=false):Result<ReplyData>
    suspend fun getSortedSubCommentsForSubject(oid:Long,type:Int,rootId:Long,mode:Int,paginationOffset:String?=null,targetReplyId:Long=0):Result<ReplyData>
    suspend fun getDialogCommentsForSubject(oid:Long,type:Int,rootId:Long,dialogId:Long,page:Int,paginationOffset:String?=null):Result<ReplyData>
    suspend fun uploadCommentPicture(source:String,index:Int):Result<ReplyPicture>
    suspend fun addCommentForSubject(oid:Long,type:Int,message:String,root:Long=0,parent:Long=0,pictures:List<ReplyPicture> = emptyList(),syncToDynamic:Boolean=false):Result<ReplyItem?>
    suspend fun likeCommentForSubject(oid:Long,type:Int,rpid:Long,like:Boolean):Result<Unit>
    suspend fun hateCommentForSubject(oid:Long,type:Int,rpid:Long,hate:Boolean):Result<Unit>
    suspend fun deleteCommentForSubject(oid:Long,type:Int,rpid:Long):Result<Unit>
    suspend fun setCommentTopForSubject(oid:Long,type:Int,rpid:Long,isCurrentlyTop:Boolean):Result<Unit>
    suspend fun reportCommentForSubject(oid:Long,type:Int,rpid:Long,reason:Int,content:String=""):Result<Unit>
    suspend fun checkCommentStatus(aid:Long,rpid:Long,rootId:Long=0,hasPictures:Boolean=false,sentAtSeconds:Long=0,waitMs:Long = -1):Result<CommentFraudStatus>
    suspend fun saveFraudRecord(rpid:Long,oid:Long,type:Int=1,root:Long=0,message:String,status:CommentFraudStatus,initialStatus:CommentFraudStatus?=null)
}

/** Reuses the sole operations and the exact existing streaming picture binding. */
internal class DesktopVideoCommentOperationsBinding(
    private val operations: DesktopDynamicCardOperations,
    private val repository: DesktopRepository,
    imageProvider: suspend(String)->Triple<String?,String?,RequestBody>,
    private val records: DesktopOriginalCommentFraudRepository,
    private val checkStatus: suspend(Long,Long,Long,Boolean,Long,Long)->Result<CommentFraudStatus>,
    private val publishedRecord: (ReplyItem,Long,Int,Long,Long,String,Long)->Unit,
):DesktopVideoCommentRequests {
    private val images=DesktopDynamicReplyOperationsBinding(operations,
        { isOwned() && repository.account.value != null && !repository.authCookies()["bili_jct"].isNullOrBlank() },
        { Result.failure(IllegalStateException("A typed music subject has no DynamicItem detail")) },imageProvider)
    override fun isOwned()=operations.isOwned()
    override fun currentMid()=repository.account.value?.mid ?: 0L
    override suspend fun getCommentsForSubject(oid:Long,type:Int,page:Int,ps:Int,mode:Int,paginationOffset:String?,fallbackOnMissingLocation:Boolean)=operations.getCommentsForSubject(oid,type,page,ps,mode,paginationOffset,fallbackOnMissingLocation)
    override suspend fun getSortedSubCommentsForSubject(oid:Long,type:Int,rootId:Long,mode:Int,paginationOffset:String?,targetReplyId:Long)=operations.getSortedSubCommentsForSubject(oid,type,rootId,mode,paginationOffset,targetReplyId)
    override suspend fun getDialogCommentsForSubject(oid:Long,type:Int,rootId:Long,dialogId:Long,page:Int,paginationOffset:String?)=operations.getDialogCommentsForSubject(oid,type,rootId,dialogId,page,paginationOffset)
    override suspend fun uploadCommentPicture(source:String,index:Int)=images.uploadCommentPicture(source,index)
    override suspend fun addCommentForSubject(oid:Long,type:Int,message:String,root:Long,parent:Long,pictures:List<ReplyPicture>,syncToDynamic:Boolean)=operations.addCommentForSubject(oid,type,message,root,parent,pictures,syncToDynamic,publishedRecord)
    override suspend fun likeCommentForSubject(oid:Long,type:Int,rpid:Long,like:Boolean)=operations.likeCommentForSubject(oid,type,rpid,like)
    override suspend fun hateCommentForSubject(oid:Long,type:Int,rpid:Long,hate:Boolean)=operations.hateCommentForSubject(oid,type,rpid,hate)
    override suspend fun deleteCommentForSubject(oid:Long,type:Int,rpid:Long)=operations.deleteCommentForSubject(oid,type,rpid)
    override suspend fun setCommentTopForSubject(oid:Long,type:Int,rpid:Long,isCurrentlyTop:Boolean)=operations.setCommentTopForSubject(oid,type,rpid,isCurrentlyTop)
    override suspend fun reportCommentForSubject(oid:Long,type:Int,rpid:Long,reason:Int,content:String)=operations.reportCommentForSubject(oid,type,rpid,reason,content)
    override suspend fun checkCommentStatus(aid:Long,rpid:Long,rootId:Long,hasPictures:Boolean,sentAtSeconds:Long,waitMs:Long)=checkStatus(aid,rpid,rootId,hasPictures,sentAtSeconds,waitMs)
    override suspend fun saveFraudRecord(rpid:Long,oid:Long,type:Int,root:Long,message:String,status:CommentFraudStatus,initialStatus:CommentFraudStatus?)=records.saveRecord(rpid,oid,type,root,message=message,status=status,initialStatus=initialStatus)
}

// Paste inside existing DesktopDynamicCardOperations; no package/class/API/model producer.
// Requires the frozen editor's existing private uploadEditorCommentImage (one upload body).
private val guestCommentApi = guestWeb.create(BilibiliApi::class.java)
private val commentGrpc = com.android.purebilibili.data.repository.DesktopDynamicCommentGrpc(grpc::request, ::assertOwned)
private val commentProtocol = com.android.purebilibili.data.repository.DesktopDynamicCommentProtocol(
    api, guestCommentApi, commentGrpc,
    { assertOwned(); !repository.authCookies()["SESSDATA"].isNullOrEmpty() },
    { params -> assertOwned(); repository.signWebParams(params).also { assertOwned() } },
    ::assertOwned,
)

suspend fun getCommentsForSubject(oid:Long,type:Int,page:Int,ps:Int=20,mode:Int=3,paginationOffset:String?=null,fallbackOnMissingLocation:Boolean=false):Result<ReplyData> = result { read { commentProtocol.getCommentsForSubject(oid,type,page,ps,mode,paginationOffset,fallbackOnMissingLocation).getOrThrow() } }

suspend fun getCommentCountForSubject(oid:Long,type:Int):Result<Int> = result { read { commentProtocol.getCommentCountForSubject(oid,type).getOrThrow() } }

suspend fun getSortedSubCommentsForSubject(oid:Long,type:Int,rootId:Long,mode:Int,paginationOffset:String?=null,targetReplyId:Long=0L):Result<ReplyData> = result { read { commentProtocol.getSortedSubCommentsForSubject(oid,type,rootId,mode,paginationOffset,targetReplyId).getOrThrow() } }

suspend fun getSubCommentsForSubject(oid:Long,type:Int,rootId:Long,page:Int,ps:Int=20,paginationOffset:String?=null,preferRestPaging:Boolean=true):Result<ReplyData> = result { read { commentProtocol.getSubCommentsForSubject(oid,type,rootId,page,ps,paginationOffset,preferRestPaging).getOrThrow() } }

suspend fun getDialogCommentsForSubject(oid:Long,type:Int,rootId:Long,dialogId:Long,page:Int,paginationOffset:String?=null):Result<ReplyData> = result { read { commentProtocol.getDialogCommentsForSubject(oid,type,rootId,dialogId,page,paginationOffset).getOrThrow() } }

suspend fun translateReply(type:Long,oid:Long,rpid:Long):Result<String?> = result { read { commentGrpc.translateReply(type,oid,rpid).getOrThrow() } }

suspend fun uploadCommentImage(fileName:String,mimeType:String,bytes:ByteArray):Result<ReplyPicture> = result { mutate { csrf -> uploadEditorCommentImage(csrf,fileName,mimeType,bytes) } }

    suspend fun addCommentForSubject(
        oid: Long,
        type: Int,
        message: String,
        root: Long = 0,
        parent: Long = 0,
        pictures: List<ReplyPicture> = emptyList(),
        syncToDynamic: Boolean = false,
        onPublishedRecord: ((ReplyItem, Long, Int, Long, Long, String, Long) -> Unit)? = null
    ): Result<ReplyItem?> = result { mutate { csrf ->
val picturePayload = buildCommentPicturesPayload(pictures)
            
            val response = api.addReply(
                oid = oid,
                type = type,
                message = message,
                root = root.takeIf { it > 0L },
                parent = parent.takeIf { it > 0L },
                pictures = picturePayload,
                syncToDynamic = resolveCommentSyncToDynamicField(syncToDynamic),
                csrf = csrf
            )
            coroutineContext.ensureActive(); assertOwned()

            
            if (response.code == 0) {
                val reply = response.data?.reply
                if (reply != null && reply.rpid > 0L) {
                    val serverPostTime = if (reply.ctime > 0L) reply.ctime * 1000L else System.currentTimeMillis()
                    
                    // Owned optional original-record seam; null is explicitly unbound.
                    coroutineContext.ensureActive(); assertOwned()
                    onPublishedRecord?.invoke(reply, oid, type, root, parent, message, serverPostTime)
                    coroutineContext.ensureActive(); assertOwned()
                }
                // 立刻返回给 UI 渲染
                reply
            } else {

                val errorMsg = when (response.code) {
                    -101 -> "请先登录"
                    -102 -> "账号被封禁"
                    -509 -> "请求过于频繁"
                    12002 -> "评论区已关闭"
                    12015 -> "需要评论验证码"
                    12016 -> "评论内容包含敏感信息"
                    12025 -> "评论字数过多"
                    12035 -> "您已被UP主拉黑"
                    12051 -> "重复评论，请勿刷屏"
                    else -> response.message.ifEmpty { "发送失败 (${response.code})" }
                }
                throw Exception(errorMsg)
            }
        
} }

    suspend fun likeCommentForSubject(
        oid: Long,
        type: Int,
        rpid: Long,
        like: Boolean
    ): Result<Unit> = result { mutate { csrf ->
val response = api.likeReply(
                oid = oid,
                type = type,
                rpid = rpid,
                action = if (like) 1 else 0,
                csrf = csrf
            )
            coroutineContext.ensureActive(); assertOwned()

            
            if (response.code == 0) {
                Unit
            } else {
                throw Exception(response.message.ifEmpty { "操作失败" })
            }
        
} }

    suspend fun hateCommentForSubject(
        oid: Long,
        type: Int,
        rpid: Long,
        hate: Boolean
    ): Result<Unit> = result { mutate { csrf ->
val response = api.hateReply(
                oid = oid,
                type = type,
                rpid = rpid,
                action = if (hate) 1 else 0,
                csrf = csrf
            )
            coroutineContext.ensureActive(); assertOwned()

            
            if (response.code == 0) {
                Unit
            } else {
                throw Exception(response.message.ifEmpty { "操作失败" })
            }
        
} }

    suspend fun deleteCommentForSubject(
        oid: Long,
        type: Int,
        rpid: Long,
    ): Result<Unit> = result { mutate { csrf ->
val response = api.deleteReply(
                oid = oid,
                type = type,
                rpid = rpid,
                csrf = csrf
            )
            coroutineContext.ensureActive(); assertOwned()

            
            if (response.code == 0) {
                Unit
            } else {
                val errorMsg = when (response.code) {
                    -403 -> "无权删除此评论"
                    12022 -> "评论已被删除"
                    else -> response.message.ifEmpty { "删除失败" }
                }
                throw Exception(errorMsg)
            }
        
} }

    suspend fun setCommentTopForSubject(
        oid: Long,
        type: Int,
        rpid: Long,
        isCurrentlyTop: Boolean,
    ): Result<Unit> = result { mutate { csrf ->
val response = api.setReplyTop(
                oid = oid,
                type = type,
                rpid = rpid,
                action = resolveCommentTopActionField(isCurrentlyTop),
                csrf = csrf
            )
            coroutineContext.ensureActive(); assertOwned()


            if (response.code == 0) {
                Unit
            } else {
                throw Exception(response.message.ifEmpty { "置顶操作失败" })
            }
        
} }

    suspend fun reportCommentForSubject(
        oid: Long,
        type: Int,
        rpid: Long,
        reason: Int,
        content: String = "",
    ): Result<Unit> = result { mutate { csrf ->
val response = api.reportReply(
                oid = oid,
                type = type,
                rpid = rpid,
                reason = reason,
                content = content,
                csrf = csrf
            )
            coroutineContext.ensureActive(); assertOwned()

            
            if (response.code == 0) {
                Unit
            } else {
                val errorMsg = when (response.code) {
                    12008 -> "已经举报过了"
                    12019 -> "举报过于频繁"
                    else -> response.message.ifEmpty { "举报失败" }
                }
                throw Exception(errorMsg)
            }
        
} }

private fun buildCommentPicturesPayload(pictures:List<ReplyPicture>):String? {
    if (pictures.isEmpty()) return null
    // Original ReplyPicture already has exactly the four original CommentPicturePayload fields.
    // Emit defaults to match the original private payload's four required fields; no new DTO.
    return kotlinx.serialization.json.Json(json) { encodeDefaults = true }.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(ReplyPicture.serializer()), pictures)
}

    private fun resolveCommentSyncToDynamicField(syncToDynamic: Boolean): Int? {
        return if (syncToDynamic) 1 else null
    }

    private fun resolveCommentTopActionField(isCurrentlyTop: Boolean): Int {
        return if (isCurrentlyTop) 0 else 1
    }

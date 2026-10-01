package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.data.DesktopDynamicCardOperations

/** An existing session's original endpoint binding, never another account/API.
 * The detail loader must implement the original web/opus/desktop/seed policy;
 * passing a web-only request is explicitly not a complete detail binding. */
internal interface DesktopDynamicReplyRequests {
    fun isOwned(): Boolean
    fun hasCsrf(): Boolean
    suspend fun getDynamicDetail(id: String): Result<DynamicItem>
    suspend fun getCommentCountForSubject(oid: Long, type: Int): Result<Int>
    suspend fun getCommentsForSubject(oid: Long, type: Int, page: Int, ps: Int = 20, mode: Int = 3,
        paginationOffset: String? = null, fallbackOnMissingLocation: Boolean = false): Result<ReplyData>
    suspend fun getSortedSubCommentsForSubject(oid: Long, type: Int, rootId: Long, mode: Int,
        paginationOffset: String? = null, targetReplyId: Long = 0): Result<ReplyData>
    suspend fun addCommentForSubject(oid: Long, type: Int, message: String, root: Long = 0, parent: Long = 0): Result<ReplyItem?>
    suspend fun likeCommentForSubject(oid: Long, type: Int, rpid: Long, like: Boolean): Result<Unit>
    suspend fun hateCommentForSubject(oid: Long, type: Int, rpid: Long, hate: Boolean): Result<Unit>
    suspend fun deleteCommentForSubject(oid: Long, type: Int, rpid: Long): Result<Unit>
    suspend fun setCommentTopForSubject(oid: Long, type: Int, rpid: Long, isCurrentlyTop: Boolean): Result<Unit>
    suspend fun reportCommentForSubject(oid: Long, type: Int, rpid: Long, reason: Int): Result<Unit>
}

internal class DesktopDynamicReplyOperationsBinding(
    private val operations: DesktopDynamicCardOperations,
    private val csrfAvailable: () -> Boolean,
    private val detailLoader: suspend (String) -> Result<DynamicItem>,
) : DesktopDynamicReplyRequests {
    override fun isOwned() = operations.isOwned()
    override fun hasCsrf() = isOwned() && csrfAvailable()
    override suspend fun getDynamicDetail(id: String) = detailLoader(id)
    override suspend fun getCommentCountForSubject(oid: Long, type: Int) = operations.getCommentCountForSubject(oid, type)
    override suspend fun getCommentsForSubject(oid: Long, type: Int, page: Int, ps: Int, mode: Int,
        paginationOffset: String?, fallbackOnMissingLocation: Boolean) = operations.getCommentsForSubject(
            oid, type, page, ps, mode, paginationOffset, fallbackOnMissingLocation)
    override suspend fun getSortedSubCommentsForSubject(oid: Long, type: Int, rootId: Long, mode: Int,
        paginationOffset: String?, targetReplyId: Long) = operations.getSortedSubCommentsForSubject(
            oid, type, rootId, mode, paginationOffset, targetReplyId)
    override suspend fun addCommentForSubject(oid: Long, type: Int, message: String, root: Long, parent: Long) =
        operations.addCommentForSubject(oid, type, message, root, parent)
    override suspend fun likeCommentForSubject(oid: Long, type: Int, rpid: Long, like: Boolean) = operations.likeCommentForSubject(oid, type, rpid, like)
    override suspend fun hateCommentForSubject(oid: Long, type: Int, rpid: Long, hate: Boolean) = operations.hateCommentForSubject(oid, type, rpid, hate)
    override suspend fun deleteCommentForSubject(oid: Long, type: Int, rpid: Long) = operations.deleteCommentForSubject(oid, type, rpid)
    override suspend fun setCommentTopForSubject(oid: Long, type: Int, rpid: Long, isCurrentlyTop: Boolean) = operations.setCommentTopForSubject(oid, type, rpid, isCurrentlyTop)
    override suspend fun reportCommentForSubject(oid: Long, type: Int, rpid: Long, reason: Int) = operations.reportCommentForSubject(oid, type, rpid, reason)
}

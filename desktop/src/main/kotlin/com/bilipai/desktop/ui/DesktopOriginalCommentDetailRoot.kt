package com.bilipai.desktop.ui
import androidx.compose.runtime.*
import com.android.purebilibili.feature.comment.*
import com.android.purebilibili.feature.video.viewmodel.DesktopVideoCommentRequests
import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.navigation3.BiliPaiNavKey
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** Complete original CommentDetail UI/VM on one actual NavEntry, independent
 * of the ordinary playback Assembly. All protocols/assets/settings/records are
 * borrowed from the already mounted same Root owner. */
@Composable internal fun DesktopOriginalCommentDetailRootHost(
    key: BiliPaiNavKey.CommentDetail,
    routes: DesktopOriginalRootRouteAssembly,
    active: Boolean,
) {
    val shared = LocalDesktopOriginalCommentRootOwner.current
    val messageLink = LocalDesktopOriginalMessageLinkNavigation.current
    val gate = routes.root.entry.gate
    androidx.compose.runtime.key(key, shared, gate) {
        val open = remember { AtomicBoolean(true) }
        val scope = remember { CoroutineScope(shared.scope.coroutineContext + SupervisorJob(shared.scope.coroutineContext[Job])) }
        fun owns() = open.get() && scope.isActive && shared.isOwned() && gate.owns() && routes.containsEntry(key)
        fun commit(action: () -> Unit): Boolean {
            var applied = false
            return gate.commit { if (owns()) { action(); applied=true } } && applied
        }
        val requests = remember(shared, scope) { DesktopOriginalCommentEntryRequests(shared.requests, ::owns, ::commit) }
        val detail = remember(scope, requests) { CommentDetailViewModel(scope,requests,::commit) }
        LaunchedEffect(scope, routes, key) {
            snapshotFlow { routes.containsEntry(key) }.collect { retained ->
                if (!retained) scope.cancel("Original comment NavEntry removed")
            }
        }
        DisposableEffect(scope) { onDispose { open.set(false);scope.cancel() } }
        if (owns()) CommentDetailScreen(oid=key.oid,rootId=key.rootId,targetId=key.targetId,
            type=key.type,enterUri=key.enterUri,
            onBack={ if(active) routes.callbackFor(key) { routes.back() } },
            onOpenLink=messageLink,
            onUserClick={ mid -> if(active) routes.callbackFor(key) { routes.push(BiliPaiNavKey.Space(mid)) } },
            viewModel=detail,requests=requests,isCurrentPage=active && routes.currentKey==key,
            admitUiAction={ action ->
                var admitted=false
                commit { if(active && routes.currentKey==key) { action();admitted=true } } && admitted
            })
    }
}

/** Every delegate call uses this entry's real caller Job. The shared protocol
 * still supplies its existing captured account/epoch/HTTP admission; Kotlin
 * interface delegation does not substitute its internal isOwned implementation.
 * Both short entry checks stay outside network/body/file waits. */
internal class DesktopOriginalCommentEntryRequests(
    private val delegate: DesktopVideoCommentRequests,
    private val entryOwned: () -> Boolean,
    private val commitEntry: ((() -> Unit) -> Boolean),
) : DesktopVideoCommentRequests {
    override fun isOwned() = entryOwned() && delegate.isOwned()
    private fun checkEntry() {
        var admitted=false
        if (!commitEntry { if (isOwned()) admitted=true } || !admitted)
            throw CancellationException("Original comment entry retired")
    }
    private suspend fun <T> request(action:suspend()->T):T {
        currentCoroutineContext().ensureActive();checkEntry()
        val result=action()
        currentCoroutineContext().ensureActive();checkEntry()
        return result
    }
    override fun currentMid():Long {
        var mid=0L
        checkEntry()
        if (!commitEntry { if (!isOwned()) throw CancellationException("Original comment account retired");mid=delegate.currentMid() })
            throw CancellationException("Original comment account retired")
        return mid
    }
    override suspend fun getCommentsForSubject(oid:Long,type:Int,page:Int,ps:Int,mode:Int,paginationOffset:String?,fallbackOnMissingLocation:Boolean)=request { delegate.getCommentsForSubject(oid,type,page,ps,mode,paginationOffset,fallbackOnMissingLocation) }
    override suspend fun getSortedSubCommentsForSubject(oid:Long,type:Int,rootId:Long,mode:Int,paginationOffset:String?,targetReplyId:Long)=request { delegate.getSortedSubCommentsForSubject(oid,type,rootId,mode,paginationOffset,targetReplyId) }
    override suspend fun getDialogCommentsForSubject(oid:Long,type:Int,rootId:Long,dialogId:Long,page:Int,paginationOffset:String?)=request { delegate.getDialogCommentsForSubject(oid,type,rootId,dialogId,page,paginationOffset) }
    override suspend fun uploadCommentPicture(source:String,index:Int)=request { delegate.uploadCommentPicture(source,index) }
    override suspend fun addCommentForSubject(oid:Long,type:Int,message:String,root:Long,parent:Long,pictures:List<ReplyPicture>,syncToDynamic:Boolean)=request { delegate.addCommentForSubject(oid,type,message,root,parent,pictures,syncToDynamic) }
    override suspend fun likeCommentForSubject(oid:Long,type:Int,rpid:Long,like:Boolean)=request { delegate.likeCommentForSubject(oid,type,rpid,like) }
    override suspend fun hateCommentForSubject(oid:Long,type:Int,rpid:Long,hate:Boolean)=request { delegate.hateCommentForSubject(oid,type,rpid,hate) }
    override suspend fun deleteCommentForSubject(oid:Long,type:Int,rpid:Long)=request { delegate.deleteCommentForSubject(oid,type,rpid) }
    override suspend fun setCommentTopForSubject(oid:Long,type:Int,rpid:Long,isCurrentlyTop:Boolean)=request { delegate.setCommentTopForSubject(oid,type,rpid,isCurrentlyTop) }
    override suspend fun reportCommentForSubject(oid:Long,type:Int,rpid:Long,reason:Int,content:String)=request { delegate.reportCommentForSubject(oid,type,rpid,reason,content) }
    override suspend fun checkCommentStatus(aid:Long,rpid:Long,rootId:Long,hasPictures:Boolean,sentAtSeconds:Long,waitMs:Long)=request { delegate.checkCommentStatus(aid,rpid,rootId,hasPictures,sentAtSeconds,waitMs) }
    override suspend fun saveFraudRecord(rpid:Long,oid:Long,type:Int,root:Long,message:String,status:CommentFraudStatus,initialStatus:CommentFraudStatus?)=request { delegate.saveFraudRecord(rpid,oid,type,root,message,status,initialStatus) }
}

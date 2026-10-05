package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import com.android.purebilibili.core.store.DesktopOriginalReplySettings
import java.util.concurrent.atomic.AtomicBoolean

/** Construction only: the four original domains share one retained detail entry.
 * Root supplies its existing comment owner, atomic Store -> entry admission and
 * the sole Playback owner's current state. No API/client, credential, cache,
 * subject generation, native player or independent supplement loader is created.
 */
internal class DesktopOriginalVideoDomainOwners private constructor(
    context: DesktopPluginContext,
    private val root: DesktopOriginalCommentRootOwner,
    private val stillEntryOwned: () -> Boolean,
    private val commitIfEntryCurrent: ((() -> Unit) -> Boolean),
    currentPlaybackState: () -> VideoPlaybackUiState,
    analytics: DesktopOriginalVideoInteractionAnalytics,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val job = SupervisorJob(checkNotNull(root.scope.coroutineContext[Job]))
    val scope = CoroutineScope(root.scope.coroutineContext + job)

    private fun owned() = !closed.get() && scope.isActive && root.isOwned() &&
        root.requests.isOwned() && stillEntryOwned()

    private fun assertOwned() {
        if (!owned()) throw CancellationException("Original video domains entry retired")
    }

    private fun commit(block: () -> Unit): Boolean {
        if (!owned()) return false
        var applied = false
        val admitted = commitIfEntryCurrent {
            if (owned()) { block(); applied = true }
        }
        return admitted && applied
    }

    private suspend fun <T> request(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive(); assertOwned()
        return block().also { currentCoroutineContext().ensureActive(); assertOwned() }
    }

    // A guarded view over the already constructed typed request owner. The
    // image stream, original raw parsers and fraud-record authority remain there.
    private val requests = object : DesktopVideoCommentRequests {
        override fun isOwned() = owned()
        override fun currentMid(): Long {
            var mid: Long? = null
            if (!commit { mid = root.requests.currentMid() }) throw CancellationException("Original comment identity retired")
            return checkNotNull(mid)
        }
        override suspend fun getCommentsForSubject(oid: Long, type: Int, page: Int, ps: Int, mode: Int, paginationOffset: String?, fallbackOnMissingLocation: Boolean) =
            request { root.requests.getCommentsForSubject(oid, type, page, ps, mode, paginationOffset, fallbackOnMissingLocation) }
        override suspend fun getSortedSubCommentsForSubject(oid: Long, type: Int, rootId: Long, mode: Int, paginationOffset: String?, targetReplyId: Long) =
            request { root.requests.getSortedSubCommentsForSubject(oid, type, rootId, mode, paginationOffset, targetReplyId) }
        override suspend fun getDialogCommentsForSubject(oid: Long, type: Int, rootId: Long, dialogId: Long, page: Int, paginationOffset: String?) =
            request { root.requests.getDialogCommentsForSubject(oid, type, rootId, dialogId, page, paginationOffset) }
        override suspend fun uploadCommentPicture(source: String, index: Int) =
            request { root.requests.uploadCommentPicture(source, index) }
        override suspend fun addCommentForSubject(oid: Long, type: Int, message: String, root: Long, parent: Long, pictures: List<ReplyPicture>, syncToDynamic: Boolean) =
            request { this@DesktopOriginalVideoDomainOwners.root.requests.addCommentForSubject(oid, type, message, root, parent, pictures, syncToDynamic) }
        override suspend fun likeCommentForSubject(oid: Long, type: Int, rpid: Long, like: Boolean) =
            request { root.requests.likeCommentForSubject(oid, type, rpid, like) }
        override suspend fun hateCommentForSubject(oid: Long, type: Int, rpid: Long, hate: Boolean) =
            request { root.requests.hateCommentForSubject(oid, type, rpid, hate) }
        override suspend fun deleteCommentForSubject(oid: Long, type: Int, rpid: Long) =
            request { root.requests.deleteCommentForSubject(oid, type, rpid) }
        override suspend fun setCommentTopForSubject(oid: Long, type: Int, rpid: Long, isCurrentlyTop: Boolean) =
            request { root.requests.setCommentTopForSubject(oid, type, rpid, isCurrentlyTop) }
        override suspend fun reportCommentForSubject(oid: Long, type: Int, rpid: Long, reason: Int, content: String) =
            request { root.requests.reportCommentForSubject(oid, type, rpid, reason, content) }
        override suspend fun checkCommentStatus(aid: Long, rpid: Long, rootId: Long, hasPictures: Boolean, sentAtSeconds: Long, waitMs: Long) =
            request { root.requests.checkCommentStatus(aid, rpid, rootId, hasPictures, sentAtSeconds, waitMs) }
        override suspend fun saveFraudRecord(rpid: Long, oid: Long, type: Int, root: Long, message: String, status: CommentFraudStatus, initialStatus: CommentFraudStatus?) =
            request { this@DesktopOriginalVideoDomainOwners.root.requests.saveFraudRecord(rpid, oid, type, root, message, status, initialStatus) }
    }

    private val coinBalance = root.operations.originalVideoCoinBalanceLoader()
    val engagement = VideoEngagementViewModel(DesktopOriginalVideoEngagementEnvironment(
        context, scope, root.operations.originalVideoEngagementActions(analytics),
        VideoCoinBalanceLoader { request { coinBalance.load() } }, ::owned,
        { block -> commit(block) },
    ))
    val comments = VideoCommentViewModel(scope, requests)
    val composer = VideoComposerViewModel(DesktopOriginalVideoComposerEnvironment(
        scope, ::owned, ::commit, requests,
        commentInfo = { (currentPlaybackState() as? VideoPlaybackUiState.Success)?.info },
        loadCommentEmotePackages = { request { root.operations.getBgmEmotePackages() } },
        searchCommentMentionUsers = { keyword -> request { root.operations.searchMentionUsers(keyword) } },
        commentFeedback = root.feedback,
    ))
    private val commentReceiptJob = scope.launch {
        composer.commentSentEvent.collect { receipt ->
            val fraudEnabled = DesktopOriginalReplySettings.getCommentFraudDetectionEnabled(context).first()
            currentCoroutineContext().ensureActive(); assertOwned()
            // Existing VM owns typed-AID/account admission; do not borrow the
            // current UI AID for a late, already dispatched original mutation.
            comments.onExternalCommentSent(receipt.aid, receipt.reply, fraudEnabled)
        }
    }
    val supplement = VideoSupplementViewModel(
        DesktopOriginalVideoSupplementEnvironment(scope, ::owned, ::commit),
        VideoSupplementLoader { subject ->
            request {
                // Original DomainEffects already supplies the Success seed.
                // Its existing 300 ms deferred job only rereads that same owner;
                // network loads/polling remain exclusively in PlaybackViewModel.
                val state = currentPlaybackState() as? VideoPlaybackUiState.Success
                if (state != null && state.info.bvid == subject.bvid &&
                    state.info.cid == subject.cid && state.info.aid == subject.aid) state.toSupplementSeed()
                else null
            }
        },
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        job.cancel()
        supplement.close()
        composer.close()
    }

    /** Root retires its gate first, then calls this outside Store/entry locks. */
    suspend fun closeAndJoin() { close(); job.join() }

    companion object {
        fun create(
            context: DesktopPluginContext,
            root: DesktopOriginalCommentRootOwner,
            capturedEpoch: Long,
            stillEntryOwned: () -> Boolean,
            commitIfEntryCurrent: ((() -> Unit) -> Boolean),
            currentPlaybackState: () -> VideoPlaybackUiState,
            analytics: DesktopOriginalVideoInteractionAnalytics,
        ): DesktopOriginalVideoDomainOwners {
            if (root.operations.expectedEpoch != capturedEpoch || !root.scope.isActive ||
                !root.isOwned() || !root.requests.isOwned() || !stillEntryOwned()) {
                throw CancellationException("Original video domains construction owner retired")
            }
            val owners = DesktopOriginalVideoDomainOwners(context, root, stillEntryOwned,
                commitIfEntryCurrent, currentPlaybackState, analytics)
            if (!owners.owned()) {
                owners.close()
                throw CancellationException("Original video domains owner retired during construction")
            }
            return owners
        }
    }
}

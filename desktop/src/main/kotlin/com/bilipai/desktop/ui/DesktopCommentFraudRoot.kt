package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.data.repository.DesktopOriginalCommentFraudRepository
import com.android.purebilibili.data.repository.savePublishedCommentRecord
import com.android.purebilibili.data.model.response.ReplyItem
import com.bilipai.desktop.data.*
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** One original-record DAO for the Root's actual account epoch. All screens share it. */
internal class DesktopCommentFraudRoot(
    repository: DesktopRepository,
    private val session: DesktopDynamicCardSession,
    stateDirectory: Path,
    parentScope: CoroutineScope,
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private fun owned() = alive.get() && session.isOwned()
    fun isOwned() = owned()
    private val operations = DesktopDynamicCardOperations(repository, session.expectedEpoch, ::owned, session.emotes)
    private val dao = DesktopCommentFraudJsonDao(stateDirectory, repository.account.value?.mid ?: 0L,
        operations::isOwned, operations::withOwnedEditorImageAdmission)
    val records = DesktopOriginalCommentFraudRepository(dao,
        checkStatus = { oid, rpid, root -> operations.checkCommentStatus(oid, rpid, root, waitMs = 0L) },
        deleteComment = { oid, rpid -> operations.deleteCommentForSubject(oid, 1, rpid) })
    fun recordPublished(reply: ReplyItem, oid: Long, type: Int, root: Long, parent: Long, message: String, serverPostTime: Long) {
        if (owned()) scope.launch {
            ensureActive()
            if (owned()) records.savePublishedCommentRecord(reply, oid, type, root, parent, message, serverPostTime)
        }
    }
    override fun close() { alive.set(false); scope.cancel(); dao.close() }
}

@Composable internal fun rememberDesktopCommentFraudRoot(
    repository: DesktopRepository,
    session: DesktopDynamicCardSession,
    stateDirectory: Path,
): DesktopCommentFraudRoot {
    val scope = rememberCoroutineScope()
    val root = remember(repository, session, stateDirectory) { DesktopCommentFraudRoot(repository, session, stateDirectory, scope) }
    DisposableEffect(root) { onDispose { root.close() } }
    return root
}

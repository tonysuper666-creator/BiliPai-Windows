package com.bilipai.desktop.settings

import androidx.compose.runtime.*
import com.android.purebilibili.core.database.entity.CommentFraudRecord
import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.repository.DesktopOriginalCommentFraudRepository
import com.bilipai.desktop.data.DesktopCommentFraudRecordOperation
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.swing.Swing

/** A page-owned facade around the already mounted Root's sole original record repository.
 * It constructs no DAO, Store, account, HTTP client, or alternative record schema. */
internal class DesktopCommentFraudHistoryRecords(
    private val original: DesktopOriginalCommentFraudRepository,
    private val owns: () -> Boolean,
    private val admit: ((() -> Unit) -> Boolean),
) {
    private fun checkpoint() {
        if (!owns()) throw CancellationException("Comment fraud history page retired")
    }
    private suspend fun <T> owned(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive(); checkpoint()
        return withContext(DesktopCommentFraudRecordOperation(owns, admit)) {
            block().also { currentCoroutineContext().ensureActive(); checkpoint() }
        }
    }
    fun getAllRecordsFlow(): Flow<List<CommentFraudRecord>> {
        checkpoint()
        return original.getAllRecordsFlow().map { checkpoint(); it }
    }
    suspend fun recheckRecord(record: CommentFraudRecord): Result<CommentFraudStatus> = owned { original.recheckRecord(record) }
    suspend fun deleteBiliComment(record: CommentFraudRecord): Result<Unit> = owned { original.deleteBiliComment(record) }
    suspend fun deleteLocalRecord(rpid: Long) = owned { original.deleteLocalRecord(rpid) }
    suspend fun clearAllRecords() = owned { original.clearAllRecords() }
    suspend fun exportToJson(): String = owned { original.exportToJson() }
    suspend fun importFromJson(content: String): Result<Int> = owned { original.importFromJson(content) }
}

/** Required ownership is the captured Ready Root, its credential epoch and the exact
 * current Settings page object. Root supplies its actual imageLifetime.withCommit. */
internal class DesktopCommentFraudHistoryBindings(
    originalRecords: DesktopOriginalCommentFraudRepository,
    parentScope: CoroutineScope,
    private val window: Window,
    private val owns: () -> Boolean,
    private val admit: ((() -> Unit) -> Boolean),
    private val onNotice: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val chooser = AtomicReference<FileDialog?>()
    val scope = CoroutineScope(parentScope.coroutineContext + job + CoroutineExceptionHandler { _, error ->
        if (error !is CancellationException) failure(error)
    })
    val records = DesktopCommentFraudHistoryRecords(originalRecords, ::isOwned, admit)
    val files = DesktopCommentFraudHistoryFiles(::checkpoint, ::commit)

    fun isOwned(): Boolean = alive.get() && job.isActive && owns()
    fun checkpoint() {
        if (!isOwned()) throw CancellationException("Comment fraud history page retired")
    }
    private fun commit(block: () -> Unit) {
        checkpoint()
        if (!admit { checkpoint(); block(); checkpoint() })
            throw CancellationException("Comment fraud history Root admission rejected")
    }
    private fun failure(error: Throwable) {
        EventQueue.invokeLater {
            if (isOwned()) {
                try { commit { onFailure(error) } }
                catch (_: CancellationException) { }
            }
        }
    }
    fun notice(message: String) {
        if (!isOwned()) return
        if (!EventQueue.isDispatchThread()) {
            EventQueue.invokeLater { if (isOwned()) notice(message) }
            return
        }
        try { commit { onNotice(message) } }
        catch (_: CancellationException) { }
    }
    fun uiAction(block: () -> Unit) {
        try { checkpoint(); block() }
        catch (_: CancellationException) { }
        catch (error: Exception) { failure(error) }
    }
    fun copyText(text: String) {
        check(EventQueue.isDispatchThread()) { "Comment record clipboard requires the actual EDT" }
        commit { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    /** Native file selectors belong to this leaf; disposal and credential retirement close
     * their Windows dialog. No callback can publish into a later Settings page instance. */
    fun choose(export: Boolean, request: String, onSelected: (DesktopCommentFraudFileSelection?) -> Unit) {
        if (!isOwned()) return
        if (export) require(request == Path.of(request).fileName.toString() && request.endsWith(".json"))
        else require(request == "application/json")
        scope.launch {
            val caller = currentCoroutineContext()
            val ownedDialog = AtomicReference<FileDialog?>()
            fun disposeOwnedDialog() {
                ownedDialog.get()?.let { dialog -> EventQueue.invokeLater { dialog.dispose() } }
            }
            val closer = caller.job.invokeOnCompletion { disposeOwnedDialog() }
            // Credential retirement can precede Compose disposal while a modal pumps EDT.
            // This watcher is an explicitly owned resource, cancelled in finally. It must
            // survive caller cancellation long enough to release the blocking native dialog.
            val retirement = CoroutineScope(Dispatchers.Default).launch {
                while (isActive) {
                    if (!caller.isActive || !isOwned()) { disposeOwnedDialog(); return@launch }
                    delay(40L)
                }
            }
            try {
                val selectedPath = withContext(Dispatchers.Swing) {
                    caller.ensureActive(); checkpoint()
                    check(window.isDisplayable) { "Comment record chooser requires the mounted Root window" }
                    val dialog = when (window) {
                        is Frame -> FileDialog(window, if (export) "导出反诈历史 JSON" else "导入反诈历史 JSON", if (export) FileDialog.SAVE else FileDialog.LOAD)
                        is Dialog -> FileDialog(window, if (export) "导出反诈历史 JSON" else "导入反诈历史 JSON", if (export) FileDialog.SAVE else FileDialog.LOAD)
                        else -> error("Comment record chooser requires the actual Root Frame or Dialog")
                    }
                    if (!chooser.compareAndSet(null, dialog)) {
                        dialog.dispose()
                        error("A comment record file selector is already open")
                    }
                    ownedDialog.set(dialog)
                    try {
                        dialog.isMultipleMode = false
                        dialog.setFilenameFilter { _, name -> name.endsWith(".json", ignoreCase = true) }
                        if (export) dialog.file = request
                        // Final EDT ownership check after native allocation, before display.
                        caller.ensureActive(); checkpoint()
                        dialog.isVisible = true
                        caller.ensureActive(); checkpoint()
                        dialog.file?.let { Path.of(dialog.directory, it) }
                    } finally { ownedDialog.compareAndSet(dialog, null); chooser.compareAndSet(dialog, null); dialog.dispose() }
                }
                caller.ensureActive(); checkpoint()
                val selection = selectedPath?.let { files.select(it, export) }
                caller.ensureActive(); checkpoint()
                onSelected(selection)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure(error) }
            finally { retirement.cancel(); closer.dispose() }
        }
    }
    private fun disposeChooser() {
        chooser.get()?.let { dialog -> EventQueue.invokeLater { dialog.dispose() } }
    }
    override fun close() {
        // Retire on the same publication gate used by the DAO's final commit. If Root
        // already rejects admission, retirement still runs locally and unconditionally.
        try { admit { alive.set(false) } }
        finally { alive.set(false); job.cancel(); disposeChooser() }
        // The shared Root originalRecords repository and DAO remain owned by Root.
    }
}

internal class DesktopCommentFraudFileLauncher(
    private val context: DesktopCommentFraudHistoryBindings,
    private val export: Boolean,
    private val onSelected: (DesktopCommentFraudFileSelection?) -> Unit,
) {
    fun launch(request: String) = context.choose(export, request, onSelected)
}

@Composable internal fun rememberDesktopCommentFraudImportLauncher(
    context: DesktopCommentFraudHistoryBindings,
    onSelected: (DesktopCommentFraudFileSelection?) -> Unit,
): DesktopCommentFraudFileLauncher {
    val latest by rememberUpdatedState(onSelected)
    return remember(context) { DesktopCommentFraudFileLauncher(context, false) { latest(it) } }
}

@Composable internal fun rememberDesktopCommentFraudExportLauncher(
    context: DesktopCommentFraudHistoryBindings,
    onSelected: (DesktopCommentFraudFileSelection?) -> Unit,
): DesktopCommentFraudFileLauncher {
    val latest by rememberUpdatedState(onSelected)
    return remember(context) { DesktopCommentFraudFileLauncher(context, true) { latest(it) } }
}

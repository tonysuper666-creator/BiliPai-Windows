package com.bilipai.desktop.settings

import androidx.compose.runtime.*
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.backup.DesktopBackupUpdateHold
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.ui.DesktopImageSaveLifetime
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import com.bilipai.desktop.ui.DesktopOriginalRootRouteAssembly
import com.bilipai.desktop.ui.DesktopReadyOriginalRootHandle
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

/** The share editor borrows the actual Ready Root, entry gate and global document.
 * Closing a presentation permanently retires its callbacks, including captured previews.
 * Parsing, staging and CAS stay in the existing service/file adapters outside this gate. */
@Composable
internal fun DesktopSettingsShareRootHost(
    routes: DesktopOriginalRootRouteAssembly,
    handleReference: AtomicReference<DesktopReadyOriginalRootHandle?>,
    entryKey: BiliPaiNavKey,
    page: DesktopSettingsPage.Detail,
    navigator: DesktopSettingsNavigator,
    pluginContext: DesktopPluginContext,
    imageLifetime: DesktopImageSaveLifetime,
    updateHold: DesktopBackupUpdateHold,
    active: Boolean,
    completeBackupContent: @Composable (onDismiss: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!active) return
    val pageIdentity = SettingsSharePageIdentity(page, navigator.state.value, routes.sourceNavigationRevision)
    val activeNow by rememberUpdatedState(active)
    val dismissNow by rememberUpdatedState(onDismiss)
    val capturedHandle = remember(routes, pageIdentity) { handleReference.get() } ?: return
    val owns = {
        activeNow && handleReference.get() === capturedHandle && capturedHandle.isActive() &&
            capturedHandle.route.get() === routes && routes.owns() && routes.currentKey == entryKey &&
            routes.sourceNavigationRevision == pageIdentity.rootRevision &&
            navigator.state.value === pageIdentity.navigation && navigator.state.value.current === page && imageLifetime.isActive()
    }
    val admission = remember(routes, pageIdentity, capturedHandle, imageLifetime) {
        { action: () -> Unit ->
            var applied = false
            val accepted = routes.root.entry.gate.commit {
                imageLifetime.withCommit { if (owns()) { action(); applied = true } }
            }
            accepted && applied
        }
    }
    var showCompleteBackup by remember(routes, pageIdentity, capturedHandle) { mutableStateOf(false) }
    if (showCompleteBackup) {
        // Replace the share peer rather than stacking modal dialogs. The original ZIP
        // editor mounts the same UpdateHold in this composition apply, after the old
        // share editor is disposed; no coroutine/native call happens between them.
        completeBackupContent { admission { if (owns()) showCompleteBackup = false } }
        return
    }
    val binding = remember(routes, pageIdentity, capturedHandle, pluginContext, imageLifetime) {
        DesktopSettingsShareBindings(pluginContext, owns, admission)
    }
    DisposableEffect(binding, updateHold) {
        if (!updateHold.mountEditor(binding)) binding.close()
        onDispose { binding.close(); updateHold.unmountEditor(binding) }
    }
    DesktopSettingsShareDialog(binding, updateHold, onOpenCompleteBackup = {
        // The UI admits this only while idle. Retire captured JSON callbacks before
        // requesting the existing full-backup presentation in the same Settings entry.
        binding.tryCommit { binding.retire(); showCompleteBackup = true }
    }) {
        // The original Navigator pop is short and belongs to this same entry.
        binding.tryCommit { binding.retire(); dismissNow() }
    }
}

private class SettingsSharePageIdentity(
    private val page: DesktopSettingsPage.Detail,
    val navigation: DesktopSettingsNavigationState,
    val rootRevision: Long,
) {
    override fun equals(other: Any?) = other is SettingsSharePageIdentity && other.page === page &&
        other.navigation === navigation && other.rootRevision == rootRevision
    override fun hashCode() = 31 * (31 * System.identityHashCode(page) + System.identityHashCode(navigation)) + rootRevision.hashCode()
}

/** No Store or coroutine scope is constructed here. UI supplies its composition-owned job. */
internal class DesktopSettingsShareBindings(
    pluginContext: DesktopPluginContext,
    private val owns: () -> Boolean,
    private val admit: ((() -> Unit) -> Boolean),
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val chooser = AtomicReference<FileDialog?>()
    val context = DesktopOriginalPlayerSettingsContext(pluginContext, ::isCurrent, ::admitCurrent)
    val service = DesktopSettingsShareService(context)
    // This existing adapter is a UTF-8 file capability, not a record/schema authority.
    val files = DesktopCommentFraudHistoryFiles(::checkpoint, ::commit)

    fun isCurrent(): Boolean = alive.get() && owns()
    fun checkpoint() {
        if (!isCurrent()) throw CancellationException("Settings share presentation retired")
    }
    private fun admitCurrent(action: () -> Unit): Boolean {
        var applied = false
        val accepted = admit { if (isCurrent()) { action(); applied = true } }
        return accepted && applied
    }
    fun commit(action: () -> Unit) {
        checkpoint()
        if (!admitCurrent { checkpoint(); action() })
            throw CancellationException("Settings share Root admission rejected")
    }
    fun tryCommit(action: () -> Unit): Boolean = try { commit(action); true }
        catch (_: CancellationException) { false }

    /** Only the native selector is scheduled on EDT. Neither its modal loop nor payload IO
     * runs while a Root lock is held. Cancellation/disposal release this exact selector. */
    suspend fun chooseFile(window: Window, export: Boolean, fileName: String? = null): Path? {
        val caller = currentCoroutineContext()
        caller.ensureActive(); checkpoint()
        return suspendCancellableCoroutine<Path?> { continuation ->
            val allocated = AtomicReference<FileDialog?>()
            continuation.invokeOnCancellation {
                allocated.get()?.let { dialog -> EventQueue.invokeLater { dialog.dispose() } }
            }
            EventQueue.invokeLater {
                if (!continuation.isActive) return@invokeLater
                var dialog: FileDialog? = null
                try {
                    caller.ensureActive(); checkpoint()
                    check(window.isDisplayable && window.isShowing) { "Settings selector requires its mounted dialog" }
                    val title = if (export) "导出设置 JSON" else "导入设置 JSON"
                    val mode = if (export) FileDialog.SAVE else FileDialog.LOAD
                    val created = when (window) {
                        is Dialog -> FileDialog(window, title, mode)
                        is Frame -> FileDialog(window, title, mode)
                        else -> error("Settings selector requires an actual Frame or Dialog")
                    }
                    dialog = created
                    if (!chooser.compareAndSet(null, created)) error("A settings file selector is already open")
                    allocated.set(created)
                    created.isMultipleMode = false
                    created.setFilenameFilter { _, name -> name.endsWith(".json", ignoreCase = true) }
                    if (export) {
                        require(fileName != null && Path.of(fileName).fileName.toString() == fileName && fileName.endsWith(".json"))
                        created.file = fileName
                    }
                    caller.ensureActive(); checkpoint()
                    created.isVisible = true
                    caller.ensureActive(); checkpoint()
                    val selected = created.file?.let { name ->
                        val leaf = if (export && !name.endsWith(".json", ignoreCase = true)) "$name.json" else name
                        Path.of(created.directory, leaf)
                    }
                    continuation.resume(selected)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    dialog?.let { allocated.compareAndSet(it, null); chooser.compareAndSet(it, null); it.dispose() }
                }
            }
        }.also { caller.ensureActive(); checkpoint() }
    }

    fun retire() {
        alive.set(false)
        chooser.get()?.let { dialog -> EventQueue.invokeLater { dialog.dispose() } }
    }
    override fun close() { retire() }
}

package com.bilipai.desktop.platform.files

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

/** Extracted from the settings share selector. No Root lock is held across the modal
 * loop. Cancellation/retirement dispose this exact peer on EDT and discard its result. */
class AwtDesktopFileDialogs internal constructor(
    private val checkpoint: () -> Unit,
    private val factory: NativeFileDialogFactory,
) : DesktopFileDialogs {
    constructor(checkpoint: () -> Unit = {}) : this(checkpoint, AwtNativeFileDialogFactory)

    private class Selection {
        val peer = AtomicReference<NativeFileDialogPeer?>()
    }
    private val alive = AtomicBoolean(true)
    private val active = AtomicReference<Selection?>()

    private fun checkCurrent() {
        if (!alive.get()) throw CancellationException("File dialog presentation retired")
        checkpoint()
    }

    override suspend fun chooseFile(owner: () -> Window, request: DesktopFileDialogRequest): Path? {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        checkCurrent()
        val selection = Selection()
        check(active.compareAndSet(null, selection)) { "A file selector is already open" }
        return suspendCancellableCoroutine<Path?> { continuation ->
            continuation.invokeOnCancellation { disposeOnEventThread(selection) }
            EventQueue.invokeLater {
                val outcome = try {
                    if (!continuation.isActive) return@invokeLater
                    caller.ensureActive()
                    checkCurrent()
                    val peer = factory.create(owner, request)
                    selection.peer.set(peer)
                    val result = try {
                        caller.ensureActive()
                        checkCurrent()
                        val selected = peer.show()
                        caller.ensureActive()
                        checkCurrent()
                        selected.path()
                    } finally {
                        selection.peer.compareAndSet(peer, null)
                        peer.close()
                    }
                    Result.success(result)
                } catch (error: Throwable) {
                    Result.failure(error)
                } finally {
                    active.compareAndSet(selection, null)
                }
                if (continuation.isActive) outcome.fold(
                    { continuation.resume(it) }, { continuation.resumeWithException(it) },
                )
            }
        }.also { caller.ensureActive(); checkCurrent() }
    }

    private fun disposeOnEventThread(selection: Selection) {
        selection.peer.get()?.let { peer -> EventQueue.invokeLater { peer.close() } }
    }

    override fun close() {
        alive.set(false)
        active.get()?.let(::disposeOnEventThread)
    }
}

internal data class NativeFileSelection(val directory: String?, val file: String?) {
    fun path(): Path? {
        val leaf = file ?: return null
        requireFileLeaf(leaf)
        check(!directory.isNullOrBlank()) { "Native file selector returned a file without a directory" }
        val parent = Path.of(directory)
        check(parent.isAbsolute) { "Native file selector returned a relative directory" }
        // Preserve the exact native destination, including extensionless names. Adding a
        // suffix here could bypass the native overwrite confirmation for a different file.
        return parent.resolve(leaf).normalize()
    }
}

internal interface NativeFileDialogPeer : AutoCloseable {
    fun show(): NativeFileSelection
}

internal fun interface NativeFileDialogFactory {
    fun create(owner: () -> Window, request: DesktopFileDialogRequest): NativeFileDialogPeer
}

private object AwtNativeFileDialogFactory : NativeFileDialogFactory {
    override fun create(owner: () -> Window, request: DesktopFileDialogRequest): NativeFileDialogPeer {
        check(EventQueue.isDispatchThread()) { "Native file dialogs require the AWT event thread" }
        val window = owner()
        check(window.isDisplayable && window.isShowing) { "File selector requires its mounted owner" }
        val mode = when (request.mode) {
            DesktopFileDialogMode.OPEN -> FileDialog.LOAD
            DesktopFileDialogMode.SAVE -> FileDialog.SAVE
        }
        val dialog = when (window) {
            is Dialog -> FileDialog(window, request.title, mode)
            is Frame -> FileDialog(window, request.title, mode)
            else -> error("File selector requires an actual Frame or Dialog")
        }
        try {
            dialog.isMultipleMode = false
            if (request.extensions.isNotEmpty()) dialog.setFilenameFilter { _, name ->
                request.extensions.any { name.endsWith(".$it", ignoreCase = true) }
            }
            request.suggestedFileName?.let { dialog.file = it }
        } catch (failure: Throwable) {
            dialog.dispose()
            throw failure
        }
        return object : NativeFileDialogPeer {
            override fun show(): NativeFileSelection {
                dialog.isVisible = true
                return NativeFileSelection(dialog.directory, dialog.file)
            }
            override fun close() { dialog.dispose() }
        }
    }
}

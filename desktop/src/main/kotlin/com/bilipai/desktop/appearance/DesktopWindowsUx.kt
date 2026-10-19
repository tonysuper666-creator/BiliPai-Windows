package com.bilipai.desktop.appearance

import androidx.compose.runtime.staticCompositionLocalOf
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Desktop UX flags (spec 5.2): the windows_ux namespace of the application preference file.
 *
 * Every call is non-throwing. A change shows in settings at once (so a rail toggle does not wait for the
 * disk) and is queued for one IO worker. The worker writes only the keys that the command changes, through
 * DesktopPluginStore.updateFromSnapshot (temporary file, fsync, atomic replace, a namespace that is not an
 * object is refused). A failed write keeps the session value and publishes writeError. Only the windows_ux
 * namespace is ever written; the window zoom namespace has its own owner and is never touched here.
 */
internal interface DesktopWindowsUx {
    val settings: StateFlow<DesktopWindowsUxSettings>
    val writeError: StateFlow<String?>

    /**
     * Applies the command to settings now and queues the write. False when nothing can take the command: the owner
     * is closed, or this is the read-only default of LocalDesktopWindowsUx. Never throws.
     */
    fun submit(command: DesktopWindowsUxCommand): Boolean
}

/**
 * Mutable settings in memory, with no file. A fixture that toggles flags provides its own instance through
 * LocalDesktopWindowsUx, so that no state leaks into the next fixture of the same JVM.
 */
internal class DesktopWindowsUxMemory(initial: DesktopWindowsUxSettings = DesktopWindowsUxSettings()) : DesktopWindowsUx {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<DesktopWindowsUxSettings> = state.asStateFlow()
    override val writeError: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

    override fun submit(command: DesktopWindowsUxCommand): Boolean {
        state.update { desktopWindowsUxAfter(it, command) }
        return true
    }
}

/**
 * What LocalDesktopWindowsUx yields when nothing provides it: the defaults, immutable. Compose keeps the default
 * of a CompositionLocal in one holder per JVM, so every fixture that provides nothing shares this single object.
 * That is only safe because it can never change: submit refuses every command.
 */
internal object DesktopWindowsUxDefaults : DesktopWindowsUx {
    override val settings: StateFlow<DesktopWindowsUxSettings> = MutableStateFlow(DesktopWindowsUxSettings()).asStateFlow()
    override val writeError: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

    override fun submit(command: DesktopWindowsUxCommand): Boolean = false
}

/**
 * The persisted owner of the windows_ux flags.
 *
 * It has the shape of the display-scale controller (one IO worker over an unlimited channel, a non-throwing
 * submit, close() retires the owner) with one deliberate difference. The display-scale controller is the owner of
 * an original-player setting: it writes through updateOriginalFromSnapshot with a Root-minted preference permit, a
 * registered window context and an owns() check. The windows_ux flags are desktop-only and have no such owner, so
 * this controller writes through the plain DesktopPluginStore.updateFromSnapshot, the call DesktopThemePrefs makes
 * as well. The store still refuses a stale (frozen) facade. Root is asked to confirm that these writes need no
 * permit; until then the difference is recorded here and in the slice notes.
 *
 * close() stops accepting commands and lets the worker finish the commands that are already queued. It does not
 * wait. A path that must not lose the last change (saving the window bounds at exit, slice W3) calls closeAndJoin()
 * with a timeout instead.
 */
internal class DesktopWindowsUxController(
    private val store: DesktopPluginStore,
    parentScope: CoroutineScope,
) : DesktopWindowsUx, AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val requests = Channel<DesktopWindowsUxCommand>(Channel.UNLIMITED)
    private val namespaceUnreadable: Boolean =
        runCatching { store.requireObjectNamespace(WINDOWS_UX_NAMESPACE) }.isFailure
    private val snapshot: StateFlow<DesktopPreferenceSnapshot> = runCatching { store.snapshot(WINDOWS_UX_NAMESPACE) }
        .getOrElse { MutableStateFlow(DesktopPreferenceSnapshot(JsonObject(emptyMap()))) }
    private val state = MutableStateFlow(read(snapshot.value))
    private val mutableWriteError = MutableStateFlow<String?>(null)

    // Guarded by this: writes in flight, and whether a failed write left the session ahead of the file.
    private var pending = 0
    private var diverged = false

    override val settings: StateFlow<DesktopWindowsUxSettings> = state.asStateFlow()
    override val writeError: StateFlow<String?> = mutableWriteError.asStateFlow()

    private val collector: Job
    private val worker: Job

    init {
        // Another owner of the same file may change it. Adopt that only while no write of ours is in flight.
        collector = scope.launch { snapshot.collect { adopt() } }
        worker = scope.launch(Dispatchers.IO) {
            for (command in requests) {
                var failed = false
                try {
                    write(command)
                    mutableWriteError.value = null
                } catch (cancelled: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw cancelled
                    failed = true
                    mutableWriteError.value = DesktopWindowsUxStrings.WRITE_FAILED
                } catch (_: Exception) {
                    failed = true
                    mutableWriteError.value = DesktopWindowsUxStrings.WRITE_FAILED
                }
                settle(failed)
            }
        }
        // The worker ends when the channel is closed and drained, or when the parent scope is cancelled.
        worker.invokeOnCompletion { job.cancel() }
    }

    private fun read(source: DesktopPreferenceSnapshot): DesktopWindowsUxSettings {
        val decoded = decodeDesktopWindowsUx(source)
        return if (namespaceUnreadable) decoded.copy(error = DesktopWindowsUxStrings.UNREADABLE) else decoded
    }

    // Reads the current snapshot, not the emitted one, so that a late emission cannot undo a newer settled state.
    @Synchronized private fun adopt() {
        if (pending == 0 && !diverged) state.value = read(snapshot.value)
    }

    @Synchronized private fun settle(failed: Boolean) {
        pending -= 1
        if (failed) diverged = true
        if (pending == 0 && !diverged) state.value = read(snapshot.value)
    }

    @Synchronized private fun enqueue(command: DesktopWindowsUxCommand): Boolean {
        if (!requests.trySend(command).isSuccess) return false
        pending += 1
        state.update { desktopWindowsUxAfter(it, command) }
        return true
    }

    private fun write(command: DesktopWindowsUxCommand) {
        val edits = desktopWindowsUxEdits(command)
        if (desktopWindowsUxEffectiveEdits(store.preferences(WINDOWS_UX_NAMESPACE), edits).isEmpty()) return
        store.updateFromSnapshot(WINDOWS_UX_NAMESPACE) { edits }
    }

    override fun submit(command: DesktopWindowsUxCommand): Boolean {
        if (!alive.get()) return false
        return try { enqueue(command) } catch (_: Exception) { false }
    }

    override fun close() {
        if (!alive.compareAndSet(true, false)) return
        try {
            requests.close()
            collector.cancel()
        } catch (_: Exception) {
            // Closing must not fail the owner that disposes it.
        }
    }

    /**
     * close(), then waits until every command accepted before the close has been written or has failed. Bound the
     * wait with a timeout: each write is a file replacement with an fsync. Returns at once when the owner already
     * ended. Throws only the cancellation of the caller.
     */
    suspend fun closeAndJoin() {
        close()
        worker.join()
    }
}

/** The actual Root provides the persisted controller; anything else sees the read-only defaults. */
internal val LocalDesktopWindowsUx = staticCompositionLocalOf<DesktopWindowsUx> { DesktopWindowsUxDefaults }

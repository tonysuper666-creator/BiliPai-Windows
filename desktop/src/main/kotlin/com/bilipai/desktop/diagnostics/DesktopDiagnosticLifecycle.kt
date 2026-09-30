package com.bilipai.desktop.diagnostics

import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.player.PlayerState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The same retained process owner is drained by Main, restore and Runtime disposal. */
internal class DesktopDiagnosticLifecycle(val diagnostics: DesktopDiagnostics,
    private val nativeShare:DesktopNativeCrashShare?=null) {
    private suspend fun requestNativeShare(onLateFailure:()->Unit):Boolean {
        return nativeShare?.shareSnapshot(onLateFailure)?:false
    }
    init {require(nativeShare==null || nativeShare.diagnostics===diagnostics){"原生分享必须复用同一诊断记录器"}}
    val crashPrompt = DesktopCrashPromptController(diagnostics,if(nativeShare==null)null else ::requestNativeShare,{nativeShare?.error?.value})
    private val gate = Any()
    private val shutdown = Mutex()
    private val observers = mutableListOf<Job>()
    private var closing = false
    private var completed = false

    fun observePlayback(state: StateFlow<PlayerState>, scope: CoroutineScope): Job = synchronized(gate) {
        check(!closing) { "诊断观察已经停止" }
        diagnostics.observePlayback(state, scope).also { observers.add(it) }
    }

    suspend fun shutdownForRestore(): Unit = withContext(NonCancellable) {
        shutdown.withLock {
            if (completed) return@withLock
            val jobs = synchronized(gate) { closing = true; observers.toList() }
            jobs.forEach { it.cancel() }
            nativeShare?.retire() // Reject late queued preparation before waiting for the prompt operation.
            crashPrompt.shutdownForRestore()
            nativeShare?.shutdownForRestore()
            jobs.forEach { it.join() }
            diagnostics.shutdownForRestore()
            completed = true
        }
    }
}

/** Initialization failures remain explicit, with no fabricated working consent state. */
internal fun openDesktopDiagnostics(store: DesktopPluginStore, version: String): Result<DesktopDiagnostics> = try {
    val diagnostics = DesktopDiagnostics(store, version)
    try {
        DesktopDiagnosticsBridge.install(diagnostics)
        Result.success(diagnostics)
    } catch (failure: Exception) {
        diagnostics.close()
        throw failure
    }
} catch (failure: Exception) {
    Result.failure(failure)
}

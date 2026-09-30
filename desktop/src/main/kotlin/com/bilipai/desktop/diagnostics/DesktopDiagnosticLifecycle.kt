package com.bilipai.desktop.diagnostics

import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.player.PlayerState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The same retained process owner is drained by Main, restore and Runtime disposal. */
internal class DesktopDiagnosticLifecycle(val diagnostics: DesktopDiagnostics) {
    val crashPrompt = DesktopCrashPromptController(diagnostics)
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
            crashPrompt.shutdownForRestore()
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

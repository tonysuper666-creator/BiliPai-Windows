package com.bilipai.desktop.player

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** One writer preserves UI submission order; rapid slider updates retain the newest complete snapshot. */
internal class DesktopPlayerPreferencesWriter(private val save: (PlayerPreferences) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val snapshots = Channel<PlayerPreferences>(Channel.CONFLATED)
    private val closed = AtomicBoolean()
    private val mutableFailure = MutableStateFlow(false)
    val failed = mutableFailure.asStateFlow()
    private val worker = scope.launch {
        for (snapshot in snapshots) {
            try { save(snapshot); mutableFailure.value = false }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableFailure.value = true }
        }
    }
    fun submit(snapshot: PlayerPreferences): Boolean = !closed.get() && snapshots.trySend(snapshot).isSuccess
    suspend fun flushAndClose() {
        closed.set(true); snapshots.close(); worker.join(); scope.cancel()
    }
    override fun close() { runBlocking { flushAndClose() } }
}

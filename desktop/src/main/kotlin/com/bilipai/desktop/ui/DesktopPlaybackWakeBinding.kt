package com.bilipai.desktop.ui

import com.bilipai.desktop.player.PlayerState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect

/** Native playback, rather than navigation/viewport visibility, owns the power request. */
internal class DesktopPlaybackWakeBinding(
    private val hasSource: () -> Boolean,
    private val audioOnly: Boolean = false,
    private val diagnostic: (Throwable) -> Unit = { System.err.println("BiliPai keep-awake: ${it.message}") },
    private val acquire: (DesktopWindowsVideoWakeLease.Mode) -> AutoCloseable = { mode ->
        DesktopWindowsVideoWakeLease.acquire(hasSource, diagnostic, mode)
    },
) : AutoCloseable {
    private var mode: DesktopWindowsVideoWakeLease.Mode? = null
    private var lease: AutoCloseable? = null
    private var closed = false

    @Synchronized fun update(state: PlayerState) {
        if (closed) return
        val next = if (!hasSource() || state.paused || state.ended || state.error != null ||
            (!state.loading && state.videoCodec == null && state.audioCodec == null)) null
        else if (audioOnly || state.audioOnly || (!state.loading && state.videoCodec == null))
            DesktopWindowsVideoWakeLease.Mode.AUDIO else DesktopWindowsVideoWakeLease.Mode.VIDEO
        if (next == mode) return
        // Acquire first so VIDEO <-> AUDIO preserves SYSTEM through the handoff.
        val replacement = try { next?.let(acquire) }
        catch (failure: Exception) {
            lease?.close(); lease = null; mode = null
            diagnostic(failure)
            return
        }
        val previous = lease
        lease = replacement; mode = next
        previous?.close()
    }

    suspend fun observe(states: StateFlow<PlayerState>) {
        try { states.collect { update(it) } } finally { close() }
    }

    @Synchronized override fun close() {
        closed = true
        lease?.close(); lease = null; mode = null
    }
}

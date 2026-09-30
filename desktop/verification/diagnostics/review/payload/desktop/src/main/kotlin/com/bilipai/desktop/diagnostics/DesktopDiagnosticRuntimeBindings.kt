package com.bilipai.desktop.diagnostics

import com.bilipai.desktop.player.PlayerState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Finite native state only: titles, URLs, track/subtitle text and error messages are excluded. */
internal fun formatDesktopPlaybackDiagnostic(state:PlayerState):String =
    "ready=${state.ready}, loading=${state.loading}, paused=${state.paused}, nativePaused=${state.nativePaused}, "+
        "frameReady=${state.firstVideoFrameReady}, cachePaused=${state.pausedForCache}, "+
        "hwIntent=${state.hardwareDecodeEnabled}, softwareRecovery=${state.softwareDecodingRequested}, "+
        "hardware=${safeCodecToken(state.hardwareDecoder)}, video=${safeCodecToken(state.videoCodec)}, "+
        "audio=${safeCodecToken(state.audioCodec)}, audioOnly=${state.audioOnly}, ended=${state.ended}"

private fun safeCodecToken(value:String?):String = value?.takeIf{it.matches(Regex("[a-zA-Z0-9_.-]{1,40}"))} ?: "unknown"

/** Root supplies its retained main/native-audio state and lifecycle scope; page disposal is irrelevant. */
internal fun DesktopDiagnostics.observePlayback(state:StateFlow<PlayerState>,scope:CoroutineScope):Job = scope.launch {
    combine(state,enhancedEnabled){snapshot,enabled ->snapshot to enabled}
        .map{(snapshot,enabled)-> Triple(if(enabled)formatDesktopPlaybackDiagnostic(snapshot) else null,
            snapshot.failure?.let{it.kind.name to it.nativeCode},snapshot.failure?.sourceVersion)}
        .distinctUntilChanged().collect{(summary,failure,_) ->
            if(summary!=null)record("I","PlaybackDiagnostics",summary)
            if(failure!=null)record("E","PlaybackDiagnostics","kind=${failure.first}, nativeCode=${failure.second}")
        }
}

/** Root may install this at startup; snapshots remain local independently of Firebase. */
internal class DesktopDiagnosticUncaughtHandler(
    private val diagnostics:DesktopDiagnostics,
    private val previous:Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread:Thread,throwable:Throwable) {
        try {runCatching {diagnostics.persistLocalCrash(throwable)}}
        finally {previous?.uncaughtException(thread,throwable)}
    }
}

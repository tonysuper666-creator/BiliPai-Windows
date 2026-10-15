package com.bilipai.desktop.player

import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReferenceArray

/** Explicit isolated-runtime probe. Fixed stages and numbers only: no source,
 * account, URL, native message payload, window title or input text is recorded.
 * Off by default; at most eight lines per stage and no writer thread or file. */
internal enum class MpvRuntimeProbeStage {
    AWT_MOUSE, AWT_SPACE, WINDOW_SPACE, SURFACE_ADMISSION, ORIGINAL_REVEAL,
    NATIVE_CLIENT_MESSAGE, NVIDIA_SUBMITTED_MESSAGE, NVIDIA_PRESENTATION,
    WINDOW_PREVIEW, WINDOW_UNHANDLED, NVIDIA_RECEIPT_SIZE, NVIDIA_OUTPUT_SIZE,
}

internal class MpvRuntimeProbeRecorder(
    val enabled: Boolean,
    private val emit: (String) -> Unit,
) {
    private val counts = AtomicIntegerArray(MpvRuntimeProbeStage.entries.size)
    private data class Values(val first: Long, val second: Long, val flags: Int)
    private val latest = AtomicReferenceArray<Values?>(MpvRuntimeProbeStage.entries.size)
    fun recordChanged(stage: MpvRuntimeProbeStage, first: Long = 0, second: Long = 0, flags: Int = 0) {
        if (!enabled) return
        val value = Values(first, second, flags)
        if (latest.getAndSet(stage.ordinal, value) != value) record(stage, first, second, flags)
    }
    fun record(stage: MpvRuntimeProbeStage, first: Long = 0, second: Long = 0, flags: Int = 0) {
        if (!enabled) return
        val index = stage.ordinal
        while (true) {
            val count = counts.get(index)
            if (count >= 8) return
            if (counts.compareAndSet(index, count, count + 1)) break
        }
        emit("BILIPAI_RUNTIME_PROBE stage=${stage.name} first=$first second=$second flags=$flags")
    }
}

internal object MpvRuntimeProbe {
    private val recorder = MpvRuntimeProbeRecorder(
        java.lang.Boolean.getBoolean("bilipai.test.runtimeProbe"), System.err::println)
    val enabled: Boolean get() = recorder.enabled
    fun record(stage: MpvRuntimeProbeStage, first: Long = 0, second: Long = 0, flags: Int = 0) =
        recorder.record(stage, first, second, flags)
    fun recordChanged(stage: MpvRuntimeProbeStage, first: Long = 0, second: Long = 0, flags: Int = 0) =
        recorder.recordChanged(stage, first, second, flags)
}

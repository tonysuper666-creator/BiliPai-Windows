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
    NVIDIA_SYNCHRONIZATION,
    NVIDIA_NATIVE_OUTPUT_GATE,
    NVIDIA_NATIVE_RENDER_FLAGS,
}

internal class MpvRuntimeProbeRecorder(
    val enabled: Boolean,
    private val emit: (String) -> Unit,
) {
    private val counts = AtomicIntegerArray(MpvRuntimeProbeStage.entries.size)
    private data class Values(val first: Long, val second: Long, val flags: Int)
    private val latest = AtomicReferenceArray<Values?>(MpvRuntimeProbeStage.entries.size)
    /** Fixed diagnostic emitted by the pinned swapchain; never an activation receipt. */
    fun nvidiaOutputGate(prefix: String, text: String) {
        if (!enabled || prefix != "vo/gpu/d3d11" || text.length > 32) return
        val line = text.removeSuffix("\n").removeSuffix("\r")
        val marker = "BILIPAI_RTX_PRESENT_GATE "
        if (!line.startsWith(marker)) return
        val word = line.substring(marker.length)
        val gate = word.toIntOrNull()?.takeIf { it in 0..11 && word == it.toString() } ?: return
        recordChanged(MpvRuntimeProbeStage.NVIDIA_NATIVE_OUTPUT_GATE, gate.toLong())
    }
    /** Pinned renderer eligibility bits only; never queue/display activation proof. */
    fun nvidiaRenderFlags(prefix: String, text: String) {
        if (!enabled || prefix != "vo/gpu" || text.length > 32) return
        val line = text.removeSuffix("\n").removeSuffix("\r")
        val marker = "BILIPAI_RTX_RENDER_FLAGS "
        if (!line.startsWith(marker)) return
        val word = line.substring(marker.length)
        val mask = word.toIntOrNull()?.takeIf { it in 0..32767 && word == it.toString() } ?: return
        recordChanged(MpvRuntimeProbeStage.NVIDIA_NATIVE_RENDER_FLAGS, mask.toLong())
    }
    /** Fixed numeric observations only; unknown native values never become log text. */
    fun nvidiaSynchronization(mode: String?, active: String?, interpolation: String?, drops: String?) {
        if (!enabled) return
        val modeCode = when (mode) {
            "audio" -> 0L; "display-resample" -> 1L; "display-tempo" -> 2L
            "display-vdrop" -> 3L; "display-adrop" -> 4L; "display-desync" -> 5L
            "desync" -> 6L; "display-resample-vdrop" -> 7L
            "display-resample-desync" -> 8L; else -> -1L
        }
        val dropCount = drops?.toLongOrNull()?.takeIf { it in 0L..Int.MAX_VALUE.toLong() } ?: -1L
        val flags = (if (active == "yes") 1 else 0) or
            (if (active == "yes" || active == "no") 2 else 0) or
            (if (interpolation == "yes") 4 else 0) or
            (if (interpolation == "yes" || interpolation == "no") 8 else 0)
        recordChanged(MpvRuntimeProbeStage.NVIDIA_SYNCHRONIZATION, modeCode, dropCount, flags)
    }
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
    fun nvidiaOutputGate(prefix: String, text: String) = recorder.nvidiaOutputGate(prefix, text)
    fun nvidiaRenderFlags(prefix: String, text: String) = recorder.nvidiaRenderFlags(prefix, text)
    fun nvidiaSynchronization(mode: String?, active: String?, interpolation: String?, drops: String?) =
        recorder.nvidiaSynchronization(mode, active, interpolation, drops)
    fun record(stage: MpvRuntimeProbeStage, first: Long = 0, second: Long = 0, flags: Int = 0) =
        recorder.record(stage, first, second, flags)
    fun recordChanged(stage: MpvRuntimeProbeStage, first: Long = 0, second: Long = 0, flags: Int = 0) =
        recorder.recordChanged(stage, first, second, flags)
}

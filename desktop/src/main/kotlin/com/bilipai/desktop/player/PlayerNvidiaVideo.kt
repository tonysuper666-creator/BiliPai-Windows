package com.bilipai.desktop.player

import com.sun.jna.Memory
import com.sun.jna.Pointer

enum class NvidiaVideoBackend { DRIVER, VEYRA_CORE }

/** Explicit same-size intent is distinct from the default withdrawal options. */
data class NvidiaVideoOptions(val scale: Double = 1.0, val hdr: Boolean = false,
    val nativeResolutionProcessing: Boolean = false,
    val backend: NvidiaVideoBackend = NvidiaVideoBackend.DRIVER,
    val qualityLevel: Int = 4,
    val srEnabled: Boolean = true,
    val intensityPercent: Int = 100) {
    internal fun requireValid(): NvidiaVideoOptions {
        require(scale.isFinite() && scale in 1.0..4.0) { "NVIDIA video scale must be between 1 and 4." }
        require(!nativeResolutionProcessing || scale == 1.0) { "Native-resolution processing requires unity scale." }
        require(qualityLevel in 1..4) { "NVIDIA video quality must be between 1 and 4." }
        require(intensityPercent in setOf(50, 75, 100)) { "Video output intensity must be 50, 75 or 100 percent." }
        require(backend == NvidiaVideoBackend.VEYRA_CORE || intensityPercent == 100) {
            "The driver route cannot apply application output intensity."
        }
        require(srEnabled || (backend == NvidiaVideoBackend.VEYRA_CORE && hdr && scale == 1.0 &&
            !nativeResolutionProcessing)) { "HDR-only requires the shared core, HDR and unity dimensions." }
        return this
    }
    internal val requiresFilter: Boolean get() = scale > 1.0 || hdr || nativeResolutionProcessing
    internal val effectMask: Int get() = (if (srEnabled) 1 else 0) or (if (hdr) 2 else 0)
    internal fun filterArguments(): String {
        requireValid()
        require(srEnabled) { "The driver route cannot disable super resolution independently." }
        return "d3d11vpp=scale=$scale:scaling-mode=nvidia:nvidia-true-hdr=${if (hdr) "yes" else "no"}"
    }
}

/** Exact queued withdrawal identity; a receipt, not another native owner. */
internal data class NvidiaVideoClearReceipt(
    val configurationVersion: Long,
    val source: OwnedPlaybackSourceSnapshot?,
)

/** Observed driver acceptance and processed frames do not prove Tensor utilization. */
data class NvidiaVideoState(
    val configurationVersion: Long = 0,
    val sourceVersion: Long = 0,
    val requestedScale: Double = 1.0,
    val hdrRequested: Boolean = false,
    val driverVsrAccepted: Boolean = false,
    val driverHdrAccepted: Boolean = false,
    val active: Boolean = false,
    val hdrConversionActive: Boolean = false,
    val pending: Boolean = false,
    val error: String? = null,
    val inputWidth: Int = 0,
    val inputHeight: Int = 0,
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    val outputTransfer: String? = null,
    val targetTransfer: String? = null,
    val targetPrimaries: String? = null,
    val gpuName: String? = null,
    val gpuVendorId: Int? = null,
    val currentGpuContext: String? = null,
    /** Confirmed unsupported hardware/output; distinct from an attempted processing failure. */
    val unavailableReason: String? = null,
    /** Validated declared source patch on the loaded DLL; never driver/effect capability. */
    val nativeResolutionPatchAvailable: Boolean = false,
    val nativeResolutionProcessingRequested: Boolean = false,
    /** ACK + owned filter + current matching frame. Same-size AI effect remains unproven. */
    val nativeResolutionAttemptAccepted: Boolean = false,
    val veyraAvailable: Boolean = false,
    val backend: NvidiaVideoBackend = NvidiaVideoBackend.DRIVER,
    /** Exact receipt plus current owned frame, never display/effect completion. */
    val veyraSubmitted: Boolean = false,
    /** Requested shared-core quality. Driver d3d11vpp has no quality option. */
    val requestedQualityLevel: Int = 4,
    val srEnabledRequested: Boolean = true,
    /** Application output weight requested for this exact configuration, never native SDK Strength. */
    val requestedIntensityPercent: Int = 100,
)

internal fun nvidiaHdrTransfer(transfer: String?): Boolean = transfer in setOf("pq", "hlg", "st2084", "smpte2084")
internal fun nvidiaHdrTarget(transfer: String?, primaries: String?): Boolean =
    nvidiaHdrTransfer(transfer) || (transfer == "linear" && primaries in setOf("bt.709", "bt.2020"))

internal data class NvidiaFrameObservation(
    val inputWidth: Int, val inputHeight: Int, val outputWidth: Int, val outputHeight: Int,
    val outputTransfer: String?, val targetTransfer: String?, val targetPrimaries: String?,
    val frameAfterConfiguration: Boolean, val ownFilterPresent: Boolean, val displayHdrEnabled: Boolean,
)

/** A native format readback is only accepted after this configuration has produced a frame. */
internal fun observeNvidiaVideo(previous: NvidiaVideoState, options: NvidiaVideoOptions,
    frame: NvidiaFrameObservation): NvidiaVideoState {
    if (options.backend == NvidiaVideoBackend.VEYRA_CORE) {
        val dimensions = frame.inputWidth > 0 && frame.inputHeight > 0 &&
            frame.outputWidth == Math.rint(frame.inputWidth * options.scale).toInt() &&
            frame.outputHeight == Math.rint(frame.inputHeight * options.scale).toInt()
        val submitted = previous.veyraSubmitted && frame.frameAfterConfiguration && frame.ownFilterPresent &&
            dimensions && previous.error == null && previous.unavailableReason == null
        return previous.copy(inputWidth = frame.inputWidth, inputHeight = frame.inputHeight,
            outputWidth = frame.outputWidth, outputHeight = frame.outputHeight,
            outputTransfer = frame.outputTransfer, targetTransfer = frame.targetTransfer, targetPrimaries = frame.targetPrimaries,
            active = false, hdrConversionActive = false, nativeResolutionAttemptAccepted = false,
            pending = options.requiresFilter && previous.error == null && previous.unavailableReason == null && !submitted,
            veyraSubmitted = submitted)
    }
    // Pinned vf_d3d11vpp truncates a float product, then rounds odd sizes UP to even.
    fun scaled(size: Int): Int = (size * options.scale.toFloat()).toInt().let { it + it % 2 }
    val dimensions = frame.inputWidth > 0 && frame.inputHeight > 0 &&
        frame.outputWidth == scaled(frame.inputWidth) && frame.outputHeight == scaled(frame.inputHeight)
    val usable = previous.error == null && previous.unavailableReason == null
    val processed = frame.frameAfterConfiguration && frame.ownFilterPresent && dimensions && usable
    val vsr = options.srEnabled && options.scale > 1.0 && previous.driverVsrAccepted && processed
    val hdr = options.hdr && previous.driverHdrAccepted && processed && nvidiaHdrTransfer(frame.outputTransfer)
    val hdrPresented = hdr && frame.displayHdrEnabled && nvidiaHdrTarget(frame.targetTransfer, frame.targetPrimaries)
    val sameSizeAttempt = options.srEnabled && options.nativeResolutionProcessing && previous.nativeResolutionPatchAvailable &&
        options.scale == 1.0 && previous.driverVsrAccepted && processed &&
        frame.inputWidth == frame.outputWidth && frame.inputHeight == frame.outputHeight
    val scalingReady = if (options.nativeResolutionProcessing) sameSizeAttempt else options.scale <= 1.0 || vsr
    return previous.copy(inputWidth = frame.inputWidth, inputHeight = frame.inputHeight,
        outputWidth = frame.outputWidth, outputHeight = frame.outputHeight,
        outputTransfer = frame.outputTransfer, targetTransfer = frame.targetTransfer, targetPrimaries = frame.targetPrimaries,
        active = (vsr || hdrPresented) && (options.scale <= 1.0 || vsr) && (!options.hdr || hdrPresented), hdrConversionActive = hdr,
        nativeResolutionAttemptAccepted = sameSizeAttempt,
        pending = options.requiresFilter && usable && !(scalingReady && (!options.hdr || hdrPresented)))
}

internal sealed interface NvidiaNativeMessage {
    data class VeyraSubmitted(val sourceVersion: Long, val configurationVersion: Long,
        val streamGeneration: Long, val sequence: Long, val width: Int, val height: Int) : NvidiaNativeMessage
    data class VeyraFailure(val sourceVersion: Long, val configurationVersion: Long,
        val streamGeneration: Long, val code: Int, val stage: String) : NvidiaNativeMessage
    data object VsrAccepted : NvidiaNativeMessage
    data object HdrAccepted : NvidiaNativeMessage
    data class Failure(val safeMessage: String) : NvidiaNativeMessage
    data class FilterFailed(val label: String) : NvidiaNativeMessage
    data class DeviceName(val name: String) : NvidiaNativeMessage
    data class DeviceVendor(val vendorId: Int) : NvidiaNativeMessage
}

/** Only fixed pinned-mpv messages are decoded. Arbitrary native text is never retained. */
internal fun parseNvidiaNativeMessage(prefix: String, text: String): NvidiaNativeMessage? {
    if (text.length > 1024) return null
    val line = text.trim()
    // Fixed f_output_chain.c runtime-disable report. The vf option still retains
    // this failed filter's label, so a name/label property read is not sufficient.
    if (prefix == "vf") {
        // The pinned C message may have one terminal LF; no control/multiline payload is accepted.
        val fixedLine = text.removeSuffix("\n")
        if (fixedLine.any { it.code < 32 || it.code == 127 }) return null
        Regex("Disabling filter (bilipai-nvidia-[0-9]{1,19}) because it has failed\\.").matchEntire(fixedLine)?.let {
            return NvidiaNativeMessage.FilterFailed(it.groupValues[1])
        }
        return null
    }
    if (prefix == "bilipai-rtx") {
        val match = Regex("RTX SDK accepted; GPU frame SUBMITTED session=([0-9]{1,19}) config-generation=([0-9]{1,19}) stream-generation=([0-9]{1,19}) sequence=([0-9]{1,19}) size=([0-9]{1,5})x([0-9]{1,5}); display completion not asserted\\.").matchEntire(line)
        if (match != null) {
            val source = match.groupValues[1].toLongOrNull() ?: return null
            val configuration = match.groupValues[2].toLongOrNull() ?: return null
            val stream = match.groupValues[3].toLongOrNull() ?: return null
            val sequence = match.groupValues[4].toLongOrNull() ?: return null
            val width = match.groupValues[5].toIntOrNull() ?: return null
            val height = match.groupValues[6].toIntOrNull() ?: return null
            if (source > 0 && configuration > 0 && stream >= configuration && sequence > 0 && width in 1..16384 && height in 1..16384)
                return NvidiaNativeMessage.VeyraSubmitted(source, configuration, stream, sequence, width, height)
        }
        val failure = Regex("RTX SDK failure session=([0-9]{1,19}) config-generation=([0-9]{1,19}) stream-generation=([0-9]{1,19}) code=(-?[0-9]{1,10}) stage=(bridge-unavailable|process-bypass|seek-reset-retired); [^\\r\\n]*").matchEntire(line)
        if (failure != null) {
            val source = failure.groupValues[1].toLongOrNull() ?: return null
            val configuration = failure.groupValues[2].toLongOrNull() ?: return null
            val stream = failure.groupValues[3].toLongOrNull() ?: return null
            val code = failure.groupValues[4].toIntOrNull() ?: return null
            if (source > 0 && configuration > 0 && stream >= configuration)
                return NvidiaNativeMessage.VeyraFailure(source, configuration, stream, code, failure.groupValues[5])
        }
        return null
    }
    if (prefix == "d3d11vpp") return when {
        line == "NVIDIA RTX Super Resolution enabled." -> NvidiaNativeMessage.VsrAccepted
        line == "NVIDIA RTX Video HDR enabled." -> NvidiaNativeMessage.HdrAccepted
        line == "NVIDIA RTX Video HDR not supported." -> NvidiaNativeMessage.Failure("当前驱动不支持 RTX 视频 HDR，已恢复原画播放")
        line.startsWith("Failed to enable NVIDIA RTX Super Resolution: ") -> NvidiaNativeMessage.Failure("驱动拒绝 RTX 视频超分，已恢复原画播放")
        line.startsWith("Failed to enable NVIDIA RTX Video HDR: ") -> NvidiaNativeMessage.Failure("驱动拒绝 RTX 视频 HDR，已恢复原画播放")
        line.startsWith("VideoProcessorBlt failed.") -> NvidiaNativeMessage.Failure("NVIDIA 视频处理失败，已恢复原画播放")
        else -> null
    }
    if (prefix == "vo/gpu/d3d11" || prefix == "vo/gpu-next/d3d11") {
        // common/msg.c splits the helper's four-line block into separate log events.
        Regex("Device Name: ([^\\r\\n]{1,160})").matchEntire(line)?.let { match ->
            val name = match.groupValues[1]
            if (name.any { it.code < 32 || it.code == 127 } || name.contains("://")) return null
            return NvidiaNativeMessage.DeviceName(name)
        }
        Regex("Device ID: ([0-9a-fA-F]{4}):[0-9a-fA-F]{4} \\(rev [0-9a-fA-F]{2}\\)").matchEntire(line)?.let {
            return NvidiaNativeMessage.DeviceVendor(it.groupValues[1].toInt(16))
        }
    }
    return null
}

internal data class NvidiaNativeFilter(val name: String, val label: String?)

/** Read only names/labels; no vf arguments, paths, media addresses or credentials are copied. */
internal object MpvNvidiaVideoProperties {
    fun filters(native: MpvNative, handle: Pointer): List<NvidiaNativeFilter>? = Memory(16).use { root ->
        root.clear()
        if (native.mpv_get_property(handle, "vf", 6, root) < 0) return@use null
        try {
            if (root.getInt(8) != 7) return@use null
            val array = root.getPointer(0) ?: return@use emptyList()
            val count = array.getInt(0)
            if (count !in 0..256) return@use null
            val values = array.getPointer(8) ?: return@use if (count == 0) emptyList() else null
            (0 until count).map { index ->
                val node = values.share(index * 16L)
                if (node.getInt(8) != 8) return@use null
                val map = node.getPointer(0) ?: return@use null
                val entries = map.getInt(0)
                if (entries !in 0..16) return@use null
                val keys = map.getPointer(16) ?: return@use null
                val fields = map.getPointer(8) ?: return@use null
                var name: String? = null; var label: String? = null
                repeat(entries) { entry ->
                    val key = bounded(keys.getPointer(entry * 8L), 64)
                    if (key == "name" || key == "label") {
                        val value = fields.share(entry * 16L)
                        if (value.getInt(8) == 1) {
                            val text = bounded(value.getPointer(0), 128)
                            if (key == "name") name = text else label = text
                        }
                    }
                }
                NvidiaNativeFilter(name ?: return@use null, label)
            }
        } finally { native.mpv_free_node_contents(root) }
    }
    private fun bounded(pointer: Pointer?, maximum: Int): String? {
        pointer ?: return null
        var count = 0
        while (count <= maximum && pointer.getByte(count.toLong()) != 0.toByte()) count++
        if (count > maximum) return null
        return String(pointer.getByteArray(0, count), Charsets.UTF_8)
    }
}

package com.bilipai.desktop.player

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer

/** Authenticated producer and held-file identity; consumed only after the existing Native.load succeeds. */
internal data class DesktopVeyraPresentationQualification(
    val producerVariant: String,
    val protocolVersion: Int,
    val filterSourceManifestSha256: String,
    val mpvDllSha256: String,
    val coreModuleSha256: String,
) {
    fun matches(binding: DesktopVeyraVerifiedBinding): Boolean =
        producerVariant == "bilipai-veyra-rtx-present-v1" && protocolVersion == 2 &&
            filterSourceManifestSha256 == binding.installedIdentity.filterSourceManifestSha256 &&
            mpvDllSha256 == binding.installedIdentity.mpvDllSha256 &&
            coreModuleSha256 == binding.installedIdentity.moduleSha256 &&
            listOf(filterSourceManifestSha256, mpvDllSha256, coreModuleSha256).all { it.matches(Regex("[0-9a-f]{64}")) }
}

/** Exact native token; rational source PTS is never replaced by time-pos or a guessed media clock. */
internal data class VeyraPresentedFrame(
    val valid: Boolean, val freshRenderer: Boolean, val hdrOutputProved: Boolean,
    val tokenVersion: Long, val session: Long, val configuration: Long, val stream: Long,
    val sequence: Long, val ptsNumerator: Long, val ptsDenominator: Long, val frameId: Long,
    val inputWidth: Int, val inputHeight: Int, val width: Int, val height: Int,
    val effects: Int, val transport: Int, val hdrPeakNits: Int,
    val sourceKind: Int, val outputIntent: Int,
    val epoch: Long, val presentCount: Long, val presentRefreshCount: Long, val syncQpc: Long,
    val dxgiFormat: Int, val dxgiColorSpace: Int,
    val targetTransfer: Int, val targetPrimaries: Int, val framebufferTransfer: Int, val framebufferPrimaries: Int,
    val adapterLuidHex: String,
) {
    fun sameToken(other: VeyraPresentedFrame): Boolean =
        tokenVersion == other.tokenVersion && session == other.session && configuration == other.configuration &&
            stream == other.stream && sequence == other.sequence && ptsNumerator == other.ptsNumerator &&
            ptsDenominator == other.ptsDenominator && frameId == other.frameId &&
            inputWidth == other.inputWidth && inputHeight == other.inputHeight && width == other.width && height == other.height &&
            effects == other.effects && transport == other.transport && hdrPeakNits == other.hdrPeakNits &&
            sourceKind == other.sourceKind && outputIntent == other.outputIntent &&
            adapterLuidHex == other.adapterLuidHex
}

internal data class VeyraPresentationSnapshot(
    val serial: Long, val epoch: Long, val reason: Int, val presentHresult: Int, val statisticsHresult: Int,
    val queued: VeyraPresentedFrame, val displayed: VeyraPresentedFrame,
)

/** Bounded x64 libmpv NODE decoding. A get failure/malformed map is unknown and never reuses a prior valid DTO. */
internal object MpvVeyraPresentationProperties {
    private sealed interface Value {
        data class Number(val value: Long) : Value
        data class Flag(val value: Boolean) : Value
        data class Text(val value: String) : Value
        data class Fields(val value: Map<String, Value>) : Value
    }
    private val topKeys = setOf("schema", "serial", "epoch", "reason", "present-hresult", "statistics-hresult", "queued", "displayed")
    private val frameKeys = setOf("valid", "fresh-renderer", "hdr-output-proved", "token-version", "session", "configuration-generation",
        "stream-generation", "sequence", "pts-numerator", "pts-denominator", "frame-id", "input-width", "input-height", "width", "height",
        "effects", "transport", "hdr-peak-nits", "source-kind", "output-intent", "epoch", "present-count", "present-refresh-count", "sync-qpc", "dxgi-format", "dxgi-color-space",
        "target-transfer", "target-primaries", "framebuffer-transfer", "framebuffer-primaries", "adapter-luid-hex")

    fun read(native: MpvNative, handle: Pointer): VeyraPresentationSnapshot? {
        if (Native.POINTER_SIZE != 8) return null
        return try { Memory(16).use { root ->
            root.clear()
            if (native.mpv_get_property(handle, "bilipai-rtx-presentation", 6, root) < 0) return@use null
            try {
                val top = (decode(root, 0) as? Value.Fields)?.value ?: return@use null
                require(top.keys == topKeys && top.number("schema") == 2L)
                VeyraPresentationSnapshot(top.positive("serial"), top.positive("epoch"), top.int("reason", 0..5),
                    top.int("present-hresult"), top.int("statistics-hresult"),
                    frame((top["queued"] as? Value.Fields)?.value ?: return@use null),
                    frame((top["displayed"] as? Value.Fields)?.value ?: return@use null))
            } catch (_: IllegalArgumentException) { null }
            finally { native.mpv_free_node_contents(root) }
        } } catch (_: RuntimeException) { null }
    }
    private fun decode(node: Pointer, depth: Int): Value? {
        return when (node.getInt(8)) {
        1 -> bounded(node.getPointer(0), 16)?.let { Value.Text(it) }
        3 -> when (val flag = node.getInt(0)) { 0, 1 -> Value.Flag(flag == 1); else -> null }
        4 -> Value.Number(node.getLong(0))
        8 -> {
            if (depth > 1) return null
            val list = node.getPointer(0) ?: return null
            val count = list.getInt(0)
            if (count !in 1..32) return null
            val values = list.getPointer(8) ?: return null
            val keys = list.getPointer(16) ?: return null
            val fields = linkedMapOf<String, Value>()
            for (index in 0 until count) {
                val key = bounded(keys.getPointer(index * 8L), 32) ?: return null
                if (fields.containsKey(key)) return null
                fields[key] = decode(values.share(index * 16L), depth + 1) ?: return null
            }
            Value.Fields(fields)
        }
        else -> null
        }
    }
    private fun bounded(pointer: Pointer?, maximum: Int): String? {
        pointer ?: return null
        var length = 0
        while (length <= maximum && pointer.getByte(length.toLong()) != 0.toByte()) length++
        if (length > maximum) return null
        val bytes = pointer.getByteArray(0, length)
        if (bytes.any { it.toInt() !in 32..126 }) return null
        return String(bytes, Charsets.US_ASCII)
    }
    private fun Map<String, Value>.number(key: String): Long = (this[key] as? Value.Number)?.value ?: throw IllegalArgumentException()
    private fun Map<String, Value>.positive(key: String): Long = number(key).also { require(it > 0) }
    private fun Map<String, Value>.int(key: String, range: IntRange = Int.MIN_VALUE..Int.MAX_VALUE): Int = number(key).let {
        require(it >= range.first.toLong() && it <= range.last.toLong()); it.toInt()
    }
    private fun Map<String, Value>.flag(key: String): Boolean = (this[key] as? Value.Flag)?.value ?: throw IllegalArgumentException()
    private fun frame(fields: Map<String, Value>): VeyraPresentedFrame {
        require(fields.keys == frameKeys)
        val frame = VeyraPresentedFrame(fields.flag("valid"), fields.flag("fresh-renderer"), fields.flag("hdr-output-proved"),
            fields.number("token-version"), fields.number("session"), fields.number("configuration-generation"), fields.number("stream-generation"),
            fields.number("sequence"), fields.number("pts-numerator"), fields.number("pts-denominator"), fields.number("frame-id"),
            fields.int("input-width", 0..16384), fields.int("input-height", 0..16384), fields.int("width", 0..16384), fields.int("height", 0..16384),
            fields.int("effects", 0..3), fields.int("transport", 0..2), fields.int("hdr-peak-nits", 0..2000),
            fields.int("source-kind", 0..1), fields.int("output-intent", 0..2), // native HDR remains disabled
            fields.number("epoch"), fields.number("present-count"), fields.number("present-refresh-count"), fields.number("sync-qpc"),
            fields.int("dxgi-format", 0..255), fields.int("dxgi-color-space", 0..255), fields.int("target-transfer", 0..64),
            fields.int("target-primaries", 0..64), fields.int("framebuffer-transfer", 0..64), fields.int("framebuffer-primaries", 0..64),
            (fields["adapter-luid-hex"] as? Value.Text)?.value ?: throw IllegalArgumentException())
        require(frame.adapterLuidHex.matches(Regex("[0-9a-f]{16}")))
        if (frame.valid) {
            require(frame.tokenVersion == 2L && frame.sourceKind == 1 && frame.freshRenderer && frame.session > 0 && frame.configuration > 0 &&
                frame.stream >= frame.configuration && frame.sequence > 0 && frame.frameId > 0 && frame.ptsDenominator == 1_000_000L &&
                frame.inputWidth > 0 && frame.inputHeight > 0 && frame.width > 0 && frame.height > 0 &&
                frame.epoch > 0 && frame.presentCount in 1L..0xffffffffL && frame.adapterLuidHex != "0000000000000000")
            require((frame.effects == 1 && frame.outputIntent == 1 && frame.transport == 1 && frame.hdrPeakNits == 0 && !frame.hdrOutputProved) ||
                ((frame.effects == 3 || (frame.effects == 2 &&
                    frame.width == frame.inputWidth && frame.height == frame.inputHeight)) &&
                    frame.outputIntent == 2 && frame.transport == 2 && frame.hdrPeakNits in 400..2000 && frame.hdrOutputProved))
        }
        return frame
    }
}

/** One existing native actor owns this tracker. No clock estimate or property-provided ID creates source authority. */
internal class VeyraPresentationTracker {
    private var serial = 0L
    private var epoch = 0L
    private var streamFloor = 0L
    private var awaitingStream = false
    private var seekEventPending = false
    private var ambiguousSeek = false
    private var previous: VeyraPresentedFrame? = null
    private var lastAdvanceNanos = 0L

    fun reset() {
        serial = 0; epoch = 0; streamFloor = 0; awaitingStream = false
        seekEventPending = false; ambiguousSeek = false; previous = null; lastAdvanceNanos = 0
    }
    fun retireStream(submittedStream: Long?) {
        if (!awaitingStream) streamFloor = maxOf(streamFloor, previous?.stream ?: 0, submittedStream ?: 0)
        awaitingStream = true; previous = null; lastAdvanceNanos = 0
    }
    fun beginSeek(submittedStream: Long?) {
        // NULL-data SEEK events cannot identify a second queued/coalesced request, or an unknown old stream.
        if (awaitingStream || seekEventPending || (previous == null && submittedStream == null)) ambiguousSeek = true
        retireStream(submittedStream)
        seekEventPending = true
    }
    fun onSeekEvent(submittedStream: Long?) {
        if (seekEventPending) {
            seekEventPending = false
            previous = null; lastAdvanceNanos = 0
        } else {
            if (previous == null && submittedStream == null) ambiguousSeek = true
            retireStream(submittedStream)
        }
    }
    fun accept(snapshot: VeyraPresentationSnapshot?, sourceVersion: Long, configurationVersion: Long,
        submitted: NvidiaNativeMessage.VeyraSubmitted?, inputWidth: Int, inputHeight: Int, options: NvidiaVideoOptions,
        actuallyPaused: Boolean, nowNanos: Long): VeyraPresentedFrame? {
        // An absent notification or ambiguous successive seek never promotes a frame. Only real source/filter reset clears it.
        if (seekEventPending || ambiguousSeek) return null
        snapshot ?: return null
        if (snapshot.serial < serial || snapshot.epoch < epoch) return null
        serial = snapshot.serial
        val epochChanged = snapshot.epoch != epoch
        epoch = snapshot.epoch
        if (epochChanged) { previous = null; lastAdvanceNanos = 0 }
        val queued = snapshot.queued; val displayed = snapshot.displayed
        if (snapshot.reason != 2 || snapshot.presentHresult != 0 || snapshot.statisticsHresult != 0 ||
            !queued.valid || !displayed.valid || !queued.freshRenderer || !displayed.freshRenderer ||
            queued.epoch != epoch || displayed.epoch != epoch || submitted == null ||
            submitted.sourceVersion != sourceVersion || submitted.configurationVersion != configurationVersion ||
            queued.session != sourceVersion || displayed.session != sourceVersion ||
            queued.configuration != configurationVersion || displayed.configuration != configurationVersion ||
            queued.stream != submitted.streamGeneration || displayed.stream != submitted.streamGeneration ||
            displayed.stream <= streamFloor || queued.adapterLuidHex != displayed.adapterLuidHex ||
            displayed.sequence > queued.sequence || displayed.sequence < submitted.sequence ||
            displayed.presentCount > queued.presentCount || displayed.presentRefreshCount <= 0 || displayed.syncQpc <= 0 ||
            displayed.targetTransfer != displayed.framebufferTransfer || displayed.targetPrimaries != displayed.framebufferPrimaries ||
            displayed.dxgiFormat != queued.dxgiFormat || displayed.dxgiColorSpace != queued.dxgiColorSpace ||
            displayed.targetTransfer != queued.targetTransfer || displayed.targetPrimaries != queued.targetPrimaries ||
            queued.targetTransfer != queued.framebufferTransfer || queued.targetPrimaries != queued.framebufferPrimaries ||
            displayed.dxgiFormat !in setOf(10, 24, 28, 87) || displayed.dxgiColorSpace !in setOf(0, 1, 12, 17) ||
            displayed.targetTransfer == 0 || displayed.targetPrimaries == 0) return null
        // Native OPT_FLOAT is converted to double before lrint; preserve that exact input rounding.
        val nativeScale = options.scale.toFloat().toDouble()
        val width = Math.rint(inputWidth * nativeScale).toInt(); val height = Math.rint(inputHeight * nativeScale).toInt()
        fun configured(frame: VeyraPresentedFrame): Boolean = frame.inputWidth == inputWidth && frame.inputHeight == inputHeight &&
            frame.width == width && frame.height == height && frame.sourceKind == 1 &&
            frame.outputIntent == (if (options.hdr) 2 else 1) && frame.effects == options.effectMask &&
            frame.transport == (if (options.hdr) 2 else 1) && frame.hdrPeakNits == (if (options.hdr) 1000 else 0)
        if ((!options.srEnabled && (options.backend != NvidiaVideoBackend.VEYRA_CORE || !options.hdr ||
                options.scale != 1.0 || options.nativeResolutionProcessing)) ||
            inputWidth <= 0 || inputHeight <= 0 || !configured(queued) || !configured(displayed) ||
            (options.hdr && (!displayed.hdrOutputProved || !queued.hdrOutputProved ||
                !((displayed.dxgiFormat == 24 && displayed.dxgiColorSpace == 12) ||
                    (displayed.dxgiFormat == 10 && displayed.dxgiColorSpace == 1))))) return null
        if (queued.sequence == displayed.sequence && !queued.sameToken(displayed)) return null
        val old = previous
        if (old != null && old.stream == displayed.stream) {
            if (old.adapterLuidHex != displayed.adapterLuidHex || displayed.sequence < old.sequence ||
                displayed.frameId < old.frameId || displayed.presentCount < old.presentCount ||
                (displayed.sequence == old.sequence && !displayed.sameToken(old))) return null
        }
        if (old == null || !displayed.sameToken(old) || displayed.presentCount != old.presentCount) lastAdvanceNanos = nowNanos
        previous = displayed; awaitingStream = false
        // A paused exact last displayed frame remains evidence. Playing without new proof times out conservatively.
        if (!actuallyPaused && nowNanos - lastAdvanceNanos > 1_000_000_000L) return null
        return displayed
    }
}

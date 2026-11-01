package com.bilipai.desktop.hardware

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import com.bilipai.desktop.nvidia.nvidiaDriverPublicX100FromText

@Serializable internal enum class HardwareVendor { NVIDIA, AMD, INTEL, OTHER, SOFTWARE }
@Serializable internal enum class VideoCodec { AV1, HEVC, AVC }
@Serializable internal enum class HardwareSupport { UNKNOWN, NO, YES }
@Serializable internal enum class VideoEnhancement { NONE, NVIDIA_VSR, INTEL_VPE }
@Serializable internal enum class GlassEffects { REDUCED, FULL }

/** A driver configuration is evidence for these dimensions only, never for every stream. */
@Serializable internal data class DecodeCapability(
    val codec: VideoCodec, val width: Int, val height: Int, val bitDepth: Int,
    val support: HardwareSupport, val profile: String,
)
@Serializable internal data class HardwareAdapter(
    val luid: String, val vendor: HardwareVendor, val vendorId: Int, val deviceId: Int,
    val name: String, val driver: String?, val dedicatedBytes: Long,
    val integrated: Boolean, val physical: Boolean, val decoders: List<DecodeCapability> = emptyList(),
    val stableId:String?=null,
)
@Serializable internal data class HardwareDisplay(
    val adapterLuid: String, val target: Int, val width: Int, val height: Int,
    val refreshNumerator: Int, val refreshDenominator: Int, val scalePercent: Int?,
    val hdrSupported: HardwareSupport, val hdrEnabled: HardwareSupport,
    val sourceName: String, val windowOnDisplay: Boolean = false,
    val stableId:String?=null,
) {
    val refreshHz: Double get() = if (refreshDenominator > 0) refreshNumerator.toDouble() / refreshDenominator else 0.0
}
@Serializable internal data class HardwareAudioEndpoint(
    val idHash: String, val exclusivePcm48k: HardwareSupport,
)
@Serializable internal data class HardwareSnapshot(
    val complete: Boolean = false, val adapters: List<HardwareAdapter> = emptyList(),
    val displays: List<HardwareDisplay> = emptyList(), val cpu: String = "unknown",
    val logicalProcessors: Int = 0, val memoryBytes: Long = 0,
    val audio: List<HardwareAudioEndpoint> = emptyList(), val defaultAudioIdHash: String? = null,
    val errors: List<String> = emptyList(),
) {
    /** LUID is session identity only. Persist hashed PnP identities so reboot does not retry failed rungs. */
    fun fingerprint(): String = hardwareHash("3|" + adapters.sortedBy { it.identity() }.joinToString { a ->
        "${a.identity()}:${a.driver}:${a.dedicatedBytes}"
    } + "|" + displays.map { d ->
        "${adapters.firstOrNull {it.luid==d.adapterLuid}?.identity()}:${d.stableId ?: d.target}:${d.width}:${d.height}:${d.refreshNumerator}/${d.refreshDenominator}:${d.scalePercent}:${d.hdrSupported}:${d.hdrEnabled}"
    }.sorted().joinToString() + "|$memoryBytes|$logicalProcessors|$defaultAudioIdHash|" + audio.sortedBy {it.idHash}.toString())
}
private fun HardwareAdapter.identity()=stableId ?: "$vendorId:$deviceId:$name:$physical"

internal fun hardwareHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

@Serializable internal data class DecodeMeasurement(
    val adapterLuid: String, val codec: VideoCodec, val width: Int, val height: Int,
    val hardwareDecoder: String, val decodedFrames: Long, val droppedFrames: Long,
    val elapsedMillis: Long, val passed: Boolean, val reason: String,
    val adapterIdentity:String?=null,
)
/** Published only by the real playback owner after presentation proof, never by the hidden test. */
@Serializable internal data class EnhancementMeasurement(
    val adapterLuid: String, val enhancement: VideoEnhancement, val hdr: Boolean,
    val quality: Int, val presentedFrames: Long, val droppedFrames: Long,
    val sustainedMillis: Long, val proved: Boolean, val failed: Boolean,
    val adapterIdentity:String?=null,
)
@Serializable internal data class HardwareOverrides(
    val automatic: Boolean = true, val preferredCodec: VideoCodec? = null,
    val maxHeight: Int? = null, val enhancement: VideoEnhancement? = null,
    val hdr: Boolean? = null, val fullGlass: Boolean? = null, val exclusiveAudio: Boolean = false,
)
@Serializable internal data class HardwareLearning(
    val fingerprint: String, val decodes: List<DecodeMeasurement> = emptyList(),
    val enhancements: List<EnhancementMeasurement> = emptyList(), val selfTestComplete: Boolean = false,
    val selfTestReason: String = "pending", val overrides: HardwareOverrides = HardwareOverrides(),
)
@Serializable internal data class HardwarePlaybackPolicy(
    val fingerprint: String, val adapterLuid: String?, val codecPreference: List<VideoCodec>,
    val maxHeight: Int, val maxFps: Int, val hwdec: String,
    val enhancementCandidate: VideoEnhancement, val enhancement: VideoEnhancement,
    val enhancementQuality: Int, val hdr: Boolean, val glass: GlassEffects,
    val exclusiveAllowed: Boolean, val exclusiveRequested: Boolean,
    val optimized: Boolean, val reasons: List<String>, val validatedDecodes: List<DecodeMeasurement> = emptyList(),
)

/** Pure policy. Renderer identity must come from the playback owner on hybrid machines. */
internal fun decideHardwarePolicy(
    snapshot: HardwareSnapshot, learning: HardwareLearning? = null, rendererLuid: String? = null,
): HardwarePlaybackPolicy {
    val fingerprint = snapshot.fingerprint()
    val learned = learning?.takeIf { it.fingerprint == fingerprint }
    val override = learned?.overrides?.takeUnless { it.automatic } ?: HardwareOverrides()
    val displayAdapters = snapshot.displays.filter { it.windowOnDisplay }.map { it.adapterLuid }.distinct()
    val adapter = snapshot.adapters.firstOrNull { it.luid == rendererLuid }
        ?: snapshot.adapters.singleOrNull { it.luid in displayAdapters }
        ?: snapshot.adapters.singleOrNull { it.vendor != HardwareVendor.SOFTWARE }
    val reasons = mutableListOf<String>()
    if (!snapshot.complete) reasons += "probe_incomplete"
    if (adapter == null) reasons += "renderer_unknown"
    val decodes = learned?.decodes.orEmpty().filter { (it.adapterLuid == adapter?.luid || adapter?.stableId!=null && it.adapterIdentity==adapter.stableId) && snapshot.complete &&
        adapter!=null && adapter.decoders.any { cap -> cap.codec==it.codec && cap.width==it.width && cap.height==it.height && cap.bitDepth==8 && cap.support==HardwareSupport.YES } }
        .map {it.copy(adapterLuid=checkNotNull(adapter).luid)}
    // A real 4K test is required before requesting a 4K stream. No test means safe AVC.
    val successful = decodes.filter { it.passed && it.hardwareDecoder in setOf("d3d11va", "d3d11va-copy", "nvdec", "nvdec-copy") &&
        it.decodedFrames > 0 && it.droppedFrames >= 0 && it.droppedFrames.toDouble() / it.decodedFrames < 0.02 }
    val codecs = VideoCodec.entries.filter { codec -> successful.any { it.codec == codec } }.toMutableList()
    val preferred = override.preferredCodec
    if (preferred in codecs) { codecs.remove(preferred); codecs.add(0, preferred!!) }
    val selected = codecs.firstOrNull()
    val measuredHeight = successful.filter { it.codec == selected }.maxOfOrNull { it.height } ?: 720
    if (codecs.isEmpty()) { codecs += VideoCodec.AVC; reasons += "safe_avc_fallback" }
    val memoryLow = snapshot.memoryBytes <= 0 || snapshot.memoryBytes < 8L * 1024 * 1024 * 1024
    val height = minOf(measuredHeight, override.maxHeight?.coerceIn(360, 2160) ?: 2160,
        if (memoryLow) 1080 else 2160)
    val nvidiaDriver=nvidiaDriverPublicX100FromText(adapter?.driver)
    val rtxSeries=adapter?.name?.let {Regex("(?i)\\bRTX\\s+(20|30|40|50)\\d{2}").find(it)?.groupValues?.get(1)}
    val vsrDriverFloor=when(rtxSeries){"20"->54584;"30","40"->53118;"50"->57216;else->Int.MAX_VALUE}
    val candidate = when {
        adapter?.physical != true || !snapshot.complete -> VideoEnhancement.NONE
        adapter.vendor == HardwareVendor.NVIDIA && (nvidiaDriver ?: 0)>=vsrDriverFloor -> VideoEnhancement.NVIDIA_VSR
        adapter.vendor == HardwareVendor.INTEL -> VideoEnhancement.INTEL_VPE
        else -> VideoEnhancement.NONE
    }
    val enhancements = learned?.enhancements.orEmpty().filter { it.adapterLuid == adapter?.luid || adapter?.stableId!=null && it.adapterIdentity==adapter.stableId }
    val good = enhancements.filter { it.enhancement == candidate && it.quality in 1..4 && it.proved && !it.failed &&
        it.presentedFrames >= 120 && it.sustainedMillis >= 10_000 && it.droppedFrames >= 0 &&
        it.droppedFrames.toDouble() / it.presentedFrames < 0.02 }
    val failed = enhancements.filter { it.failed }.map { it.quality }.minOrNull()
    val quality = good.filter { failed == null || it.quality < failed }.maxOfOrNull { it.quality } ?: 0
    val enhancement = if (quality > 0 && (override.enhancement == null || override.enhancement == candidate)) candidate else VideoEnhancement.NONE
    val targets = snapshot.displays.filter { it.windowOnDisplay }
    val hdr = enhancement == VideoEnhancement.NVIDIA_VSR && (nvidiaDriver ?: 0)>=55123 && override.hdr != false && targets.isNotEmpty() &&
        targets.all { it.hdrSupported == HardwareSupport.YES && it.hdrEnabled == HardwareSupport.YES } &&
        good.any { it.hdr && it.quality == quality } && enhancements.none { it.hdr && it.failed }
    if (candidate != VideoEnhancement.NONE && quality == 0) reasons += "enhancement_awaits_real_playback"
    val fullGlass = !memoryLow && adapter?.integrated == false && snapshot.complete && override.fullGlass != false
    val audio = snapshot.audio.singleOrNull { it.idHash == snapshot.defaultAudioIdHash }
    val exclusive = audio?.exclusivePcm48k == HardwareSupport.YES
    return HardwarePlaybackPolicy(fingerprint, adapter?.luid, codecs, height, if (successful.isEmpty()) 30 else 60,
        if (successful.isEmpty()) "no" else "d3d11va", candidate, enhancement, quality, hdr,
        if (fullGlass) GlassEffects.FULL else GlassEffects.REDUCED, exclusive, exclusive && override.exclusiveAudio,
        snapshot.complete && learned?.selfTestComplete == true, reasons,successful)
}

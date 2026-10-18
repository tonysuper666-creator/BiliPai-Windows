package com.bilipai.desktop.nvidia

// Pure NVIDIA GPU classification for the Windows port. Nothing here performs I/O:
// no native call, no clock, no registry, no device creation. The probe layer hands in
// adapter evidence; this file turns it into one capability verdict.
//
// Hex output is produced by hand (never String.format) so no locale can change a digit.
// Every string template in this package uses braces around the variable because the
// Chinese text that follows would otherwise extend the identifier.

/** First (and only) vendor id the enhancement path considers. */
internal const val NVIDIA_VENDOR_ID: Int = 0x10DE

/** DXGI_ADAPTER_FLAG_SOFTWARE. */
internal const val NVIDIA_DXGI_SOFTWARE_FLAG: Int = 2

/** The literal the player used before auto-detection existed. */
internal const val NVIDIA_LEGACY_ADAPTER_PIN: String = "NVIDIA"

internal enum class NvidiaGeneration { UNKNOWN, PRE_RTX, TURING, AMPERE, ADA, BLACKWELL, NEWER }

/** Declaration order is best first, so a larger ordinal is a lower tier. */
internal enum class NvidiaTier { ULTRA, HIGH, BALANCED, LIGHT }

internal enum class NvidiaSkuClass { S, A, B, C, D }

internal enum class NvidiaUnsupportedReason {
    PRE_RTX, NOT_PHYSICAL, PIN_UNSAFE, PHYSICAL_UNVERIFIED, VRAM_LOW, DRIVER_OLD,
}

internal enum class NvidiaUnknownReason { PROBE_FAILED }

/** Result of the PnP path check done by the probe layer (see classifyNvidiaAdapterPath). */
internal enum class NvidiaPhysicalVerdict { PHYSICAL, VIRTUAL, UNKNOWN }

internal enum class NvidiaIdentitySource { NAME, PCI_ID, ID_RANGE, NONE }

internal enum class NvidiaCapabilityNote {
    CROSS_ADAPTER,
    MOBILE_STEP_DOWN,
    DRIVER_ADVISORY,
    PHYSICAL_ASSUMED_UNIQUE,
    VRAM_UNKNOWN,
    UNKNOWN_MODEL,
    PROFESSIONAL_PART,
    OBSERVED_IDENTITY,
}

internal fun NvidiaTier.stepDown(): NvidiaTier = when (this) {
    NvidiaTier.ULTRA -> NvidiaTier.HIGH
    NvidiaTier.HIGH -> NvidiaTier.BALANCED
    NvidiaTier.BALANCED -> NvidiaTier.LIGHT
    NvidiaTier.LIGHT -> NvidiaTier.LIGHT
}

/** The lower of two tiers (the larger ordinal). */
internal fun lowerNvidiaTier(first: NvidiaTier, second: NvidiaTier): NvidiaTier =
    if (first.ordinal >= second.ordinal) first else second

// GPU-CLASSIFY-TABLE-BEGIN
// Secondary identity table: research-verified PCI device ids only. The name wins when it parses.
internal data class NvidiaPciModel(
    val deviceId: Int,
    val generation: NvidiaGeneration,
    val model: Int,
    val ti: Boolean,
    val mobile: Boolean,
)

internal val NVIDIA_PCI_MODELS: List<NvidiaPciModel> = listOf(
    NvidiaPciModel(0x2C02, NvidiaGeneration.BLACKWELL, 5080, ti = false, mobile = false),
    NvidiaPciModel(0x2B85, NvidiaGeneration.BLACKWELL, 5090, ti = false, mobile = false),
    NvidiaPciModel(0x2C05, NvidiaGeneration.BLACKWELL, 5070, ti = true, mobile = false),
    NvidiaPciModel(0x2F04, NvidiaGeneration.BLACKWELL, 5070, ti = false, mobile = false),
    NvidiaPciModel(0x2D04, NvidiaGeneration.BLACKWELL, 5060, ti = true, mobile = false),
    NvidiaPciModel(0x2D05, NvidiaGeneration.BLACKWELL, 5060, ti = false, mobile = false),
    NvidiaPciModel(0x2C19, NvidiaGeneration.BLACKWELL, 5080, ti = false, mobile = true),
    NvidiaPciModel(0x2684, NvidiaGeneration.ADA, 4090, ti = false, mobile = false),
    NvidiaPciModel(0x2704, NvidiaGeneration.ADA, 4080, ti = false, mobile = false),
    NvidiaPciModel(0x2786, NvidiaGeneration.ADA, 4070, ti = false, mobile = false),
    NvidiaPciModel(0x2882, NvidiaGeneration.ADA, 4060, ti = false, mobile = false),
    NvidiaPciModel(0x2206, NvidiaGeneration.AMPERE, 3080, ti = false, mobile = false),
    NvidiaPciModel(0x2504, NvidiaGeneration.AMPERE, 3060, ti = false, mobile = false),
    NvidiaPciModel(0x1E04, NvidiaGeneration.TURING, 2080, ti = true, mobile = false),
    NvidiaPciModel(0x1F06, NvidiaGeneration.TURING, 2060, ti = false, mobile = false),
)

// Id-range fallback: generation only. The boundaries are inferred and unverified, so an
// unlisted id is an unknown model, never an unsupported one.
internal data class NvidiaIdRange(val first: Int, val last: Int, val generation: NvidiaGeneration)

internal val NVIDIA_ID_RANGES: List<NvidiaIdRange> = listOf(
    NvidiaIdRange(0x1E00, 0x21FF, NvidiaGeneration.TURING),
    NvidiaIdRange(0x2200, 0x25FF, NvidiaGeneration.AMPERE),
    NvidiaIdRange(0x2680, 0x28FF, NvidiaGeneration.ADA),
    NvidiaIdRange(0x2900, 0x2FFF, NvidiaGeneration.BLACKWELL),
)

// SKU class table (desktop parts). costFactor is at most 1.0 and scales the tier rate base.
// A Ti row is only used for a name that carries the Ti modifier; every other suffix
// (SUPER, D, Ti without its own row) falls back to the base row and never demotes.
// qualityCeilingOverride lowers the tier quality ceiling for the row.
internal data class NvidiaSkuRow(
    val model: Int,
    val ti: Boolean,
    val generation: NvidiaGeneration,
    val skuClass: NvidiaSkuClass,
    val costFactor: Double,
    val qualityCeilingOverride: Int? = null,
)

internal val NVIDIA_SKU_ROWS: List<NvidiaSkuRow> = listOf(
    NvidiaSkuRow(model = 5090, ti = false, generation = NvidiaGeneration.BLACKWELL, skuClass = NvidiaSkuClass.S, costFactor = 1.0),
    NvidiaSkuRow(model = 5080, ti = false, generation = NvidiaGeneration.BLACKWELL, skuClass = NvidiaSkuClass.S, costFactor = 1.0),
    NvidiaSkuRow(model = 5070, ti = true, generation = NvidiaGeneration.BLACKWELL, skuClass = NvidiaSkuClass.S, costFactor = 0.5),
    NvidiaSkuRow(model = 5070, ti = false, generation = NvidiaGeneration.BLACKWELL, skuClass = NvidiaSkuClass.A, costFactor = 0.8),
    NvidiaSkuRow(model = 5060, ti = true, generation = NvidiaGeneration.BLACKWELL, skuClass = NvidiaSkuClass.B, costFactor = 1.0),
    NvidiaSkuRow(model = 5060, ti = false, generation = NvidiaGeneration.BLACKWELL, skuClass = NvidiaSkuClass.B, costFactor = 1.0),
    NvidiaSkuRow(model = 5050, ti = false, generation = NvidiaGeneration.BLACKWELL, skuClass = NvidiaSkuClass.C, costFactor = 1.0),
    NvidiaSkuRow(model = 4090, ti = false, generation = NvidiaGeneration.ADA, skuClass = NvidiaSkuClass.S, costFactor = 1.0),
    NvidiaSkuRow(model = 4080, ti = false, generation = NvidiaGeneration.ADA, skuClass = NvidiaSkuClass.S, costFactor = 0.5),
    NvidiaSkuRow(model = 4070, ti = true, generation = NvidiaGeneration.ADA, skuClass = NvidiaSkuClass.A, costFactor = 1.0),
    NvidiaSkuRow(model = 4070, ti = false, generation = NvidiaGeneration.ADA, skuClass = NvidiaSkuClass.A, costFactor = 0.7),
    NvidiaSkuRow(model = 4060, ti = true, generation = NvidiaGeneration.ADA, skuClass = NvidiaSkuClass.B, costFactor = 1.0),
    NvidiaSkuRow(model = 4060, ti = false, generation = NvidiaGeneration.ADA, skuClass = NvidiaSkuClass.C, costFactor = 1.0),
    NvidiaSkuRow(model = 3090, ti = false, generation = NvidiaGeneration.AMPERE, skuClass = NvidiaSkuClass.A, costFactor = 1.0),
    NvidiaSkuRow(model = 3080, ti = false, generation = NvidiaGeneration.AMPERE, skuClass = NvidiaSkuClass.A, costFactor = 0.9),
    NvidiaSkuRow(model = 3070, ti = false, generation = NvidiaGeneration.AMPERE, skuClass = NvidiaSkuClass.B, costFactor = 1.0),
    NvidiaSkuRow(model = 3060, ti = true, generation = NvidiaGeneration.AMPERE, skuClass = NvidiaSkuClass.B, costFactor = 1.0),
    NvidiaSkuRow(model = 3060, ti = false, generation = NvidiaGeneration.AMPERE, skuClass = NvidiaSkuClass.C, costFactor = 1.0),
    NvidiaSkuRow(model = 3050, ti = false, generation = NvidiaGeneration.AMPERE, skuClass = NvidiaSkuClass.D, costFactor = 0.6, qualityCeilingOverride = 1),
    NvidiaSkuRow(model = 2080, ti = false, generation = NvidiaGeneration.TURING, skuClass = NvidiaSkuClass.B, costFactor = 0.8),
    NvidiaSkuRow(model = 2070, ti = false, generation = NvidiaGeneration.TURING, skuClass = NvidiaSkuClass.C, costFactor = 0.8),
    NvidiaSkuRow(model = 2060, ti = false, generation = NvidiaGeneration.TURING, skuClass = NvidiaSkuClass.C, costFactor = 0.8),
    NvidiaSkuRow(model = 2050, ti = false, generation = NvidiaGeneration.TURING, skuClass = NvidiaSkuClass.D, costFactor = 0.6, qualityCeilingOverride = 1),
)

// Tier rules on D3D-visible dedicated VRAM, first match wins. Comparisons allow 3 percent slack.
internal const val NVIDIA_TIER_ULTRA_MIN_VRAM_MIB: Long = 11_500L
internal const val NVIDIA_TIER_HIGH_MIN_VRAM_MIB: Long = 7_500L
internal const val NVIDIA_TIER_BALANCED_MIN_VRAM_MIB: Long = 5_500L
internal const val NVIDIA_TIER_LIGHT_MIN_VRAM_MIB: Long = 3_500L
internal const val NVIDIA_UNKNOWN_MODEL_BALANCED_MIN_VRAM_MIB: Long = 8_192L
internal const val NVIDIA_VRAM_SLACK_PERCENT: Long = 3L
internal const val NVIDIA_UNKNOWN_MODEL_COST_FACTOR: Double = 0.7

// Driver policy. Public driver numbers are stored in hundredths (617.42 is 61742).
// Only the Blackwell floor blocks; the advisory bound and the HDR floor never block SR.
internal const val NVIDIA_BLACKWELL_MIN_DRIVER_X100: Int = 57_216
internal const val NVIDIA_HDR_MIN_DRIVER_X100: Int = 55_123
internal const val NVIDIA_DRIVER_ADVISORY_BELOW_X100: Int = 54_584
// GPU-CLASSIFY-TABLE-END

private fun nvidiaHex(value: Int, digits: Int): String =
    (value.toLong() and 0xFFFFFFFFL).toString(16).padStart(digits, '0')

/** Lowercase 16 digit LUID text, high part first, as the frame provenance prints it. */
internal fun nvidiaLuidHex(luidHigh: Int, luidLow: Int): String = nvidiaHex(luidHigh, 8) + nvidiaHex(luidLow, 8)

/** Boot-independent identity: never contains a LUID. */
internal fun nvidiaGpuKey(deviceId: Int, subSysId: Int, revision: Int): String =
    "10de:" + nvidiaHex(deviceId, 4) + ":" + nvidiaHex(subSysId, 8) + ":" + nvidiaHex(revision, 2)

internal fun nvidiaPciIdentity(vendorId: Int, deviceId: Int, subSysId: Int, revision: Int): String =
    nvidiaHex(vendorId, 4) + ":" + nvidiaHex(deviceId, 4) + ":" + nvidiaHex(subSysId, 8) + ":" + nvidiaHex(revision, 2)

/** Same rule as the Java inventory: unknown flag bits are not evidence of a software adapter. */
internal fun nvidiaIsSoftwareAdapter(vendorId: Int, deviceId: Int, flags: Int): Boolean =
    (flags == 0 || flags == NVIDIA_DXGI_SOFTWARE_FLAG) &&
        ((flags and NVIDIA_DXGI_SOFTWARE_FLAG) != 0 || (vendorId == 0x1414 && deviceId == 0x008C))

/** One DXGI adapter as the probe reports it. Dedicated memory of zero or less means unknown. */
internal data class NvidiaAdapterEvidence(
    val vendorId: Int,
    val deviceId: Int,
    val subSysId: Int,
    val revision: Int,
    val description: String,
    val dedicatedVideoMemoryBytes: Long,
    val luidLow: Int,
    val luidHigh: Int,
    val flags: Int,
    /** Attached outputs, or -1 when unknown. */
    val outputCount: Int,
    val physical: NvidiaPhysicalVerdict,
    /** User-mode driver version as four packed 16-bit parts, high part first, or null. */
    val umdVersion: Long? = null,
) {
    val luidHex: String get() = nvidiaLuidHex(luidHigh, luidLow)
    val isSoftware: Boolean get() = nvidiaIsSoftwareAdapter(vendorId, deviceId, flags)
    val pciIdentity: String get() = nvidiaPciIdentity(vendorId, deviceId, subSysId, revision)
}

internal data class NvidiaProbeInput(
    /** False when the DXGI inventory failed or was incomplete. Fail closed. */
    val dxgiComplete: Boolean,
    /** Every adapter in IDXGIFactory1::EnumAdapters1 order, including non NVIDIA ones. */
    val adapters: List<NvidiaAdapterEvidence>,
    /** LUID text of the adapter that owns the video window's monitor, when known. */
    val displayAdapterLuidHex: String? = null,
)

internal sealed interface NvidiaAdapterPin {
    /** The exact DXGI description, safe to pass to the player's adapter option. */
    data class Exact(val value: String) : NvidiaAdapterPin

    /** The pre-detection literal "NVIDIA". */
    data object Legacy : NvidiaAdapterPin

    /** No adapter option may be set. */
    data object None : NvidiaAdapterPin
}

/** The text for the player's adapter option, or null when no option may be set. */
internal fun NvidiaAdapterPin.optionValue(): String? = when (this) {
    is NvidiaAdapterPin.Exact -> value
    is NvidiaAdapterPin.Legacy -> NVIDIA_LEGACY_ADAPTER_PIN
    is NvidiaAdapterPin.None -> null
}

internal data class NvidiaGpuInfo(
    val gpuKey: String,
    val description: String,
    val generation: NvidiaGeneration,
    val deviceId: Int,
    val subSysId: Int,
    val revision: Int,
    val luidHex: String,
    val vramMiB: Long?,
    val mobile: Boolean,
    val professional: Boolean,
    val driverPublicX100: Int?,
)

/**
 * skuClass null means an unknown model: tiered by VRAM only and never above BALANCED.
 * assumed is true when class A is assumed (professional part, or a NEWER model without a row).
 */
internal data class NvidiaSkuInfo(
    val model: Int?,
    val skuClass: NvidiaSkuClass?,
    val costFactor: Double,
    val qualityCeilingOverride: Int?,
    val assumed: Boolean,
)

internal sealed interface NvidiaCapability {
    data object NotEvaluated : NvidiaCapability

    data object Probing : NvidiaCapability

    data object Absent : NvidiaCapability

    /** pin is the adapter option still wanted for decode steering; None unless the pin is safe. */
    data class Unsupported(
        val reason: NvidiaUnsupportedReason,
        val gpu: NvidiaGpuInfo? = null,
        val pin: NvidiaAdapterPin = NvidiaAdapterPin.None,
    ) : NvidiaCapability

    /** An API failure. It never means that no GPU exists. */
    data class Unknown(val reason: NvidiaUnknownReason = NvidiaUnknownReason.PROBE_FAILED) : NvidiaCapability

    data class Capable(
        val gpu: NvidiaGpuInfo,
        val tier: NvidiaTier,
        val sku: NvidiaSkuInfo,
        val pin: NvidiaAdapterPin,
        val notes: Set<NvidiaCapabilityNote>,
    ) : NvidiaCapability
}

/** The adapter option wanted for each verdict: legacy while undecided, none when only virtual. */
internal fun nvidiaPinFor(capability: NvidiaCapability): NvidiaAdapterPin = when (capability) {
    is NvidiaCapability.Capable -> capability.pin
    is NvidiaCapability.Unsupported -> capability.pin
    is NvidiaCapability.Absent -> NvidiaAdapterPin.None
    is NvidiaCapability.NotEvaluated -> NvidiaAdapterPin.Legacy
    is NvidiaCapability.Probing -> NvidiaAdapterPin.Legacy
    is NvidiaCapability.Unknown -> NvidiaAdapterPin.Legacy
}

// ---- Driver numbers -------------------------------------------------------------------

/**
 * Public driver number in hundredths from the four Windows driver fields:
 * (field3 mod 10) * 10000 + field4, so 32.0.16.1742 is 61742 (617.42).
 * Valid only when field2 is 0 and field1 is 26..99; anything else is unknown (null).
 */
internal fun nvidiaDriverPublicX100(field1: Int, field2: Int, field3: Int, field4: Int): Int? {
    if (field2 != 0 || field1 < 26 || field1 > 99) return null
    if (field3 < 0 || field4 < 0 || field4 > 9999) return null
    return (field3 % 10) * 10_000 + field4
}

/** Same conversion from a packed LARGE_INTEGER of four 16-bit parts, high part first. */
internal fun nvidiaDriverPublicX100FromPacked(packed: Long): Int? {
    if (packed <= 0L) return null
    return nvidiaDriverPublicX100(
        ((packed ushr 48) and 0xFFFFL).toInt(),
        ((packed ushr 32) and 0xFFFFL).toInt(),
        ((packed ushr 16) and 0xFFFFL).toInt(),
        (packed and 0xFFFFL).toInt(),
    )
}

internal fun formatNvidiaDriverPublic(publicX100: Int): String =
    (publicX100 / 100).toString() + "." + (publicX100 % 100).toString().padStart(2, '0')

/** Unknown drivers never block, so an unknown driver passes the HDR gate. */
internal fun nvidiaHdrDriverOk(publicX100: Int?): Boolean =
    publicX100 == null || publicX100 >= NVIDIA_HDR_MIN_DRIVER_X100

// ---- Physical adapter check -----------------------------------------------------------

private const val PCI_PATH_PREFIX = "\\\\?\\PCI#VEN_10DE&DEV_"

/**
 * Pure part of the physical adapter check. The path comes from the display configuration
 * adapter name query (null when that query failed). Physical means a PCI enumerated NVIDIA
 * path whose DEV equals the DXGI device id; every other non empty path (ROOT, SWD, USB, a
 * different vendor or device) is virtual even if the name, memory and driver look identical.
 */
internal fun classifyNvidiaAdapterPath(devicePath: String?, dxgiDeviceId: Int): NvidiaPhysicalVerdict {
    if (devicePath == null) return NvidiaPhysicalVerdict.UNKNOWN
    val path = devicePath.trim().uppercase()
    if (path.isEmpty()) return NvidiaPhysicalVerdict.UNKNOWN
    if (!path.startsWith(PCI_PATH_PREFIX)) return NvidiaPhysicalVerdict.VIRTUAL
    val rest = path.substring(PCI_PATH_PREFIX.length)
    if (rest.length < 4) return NvidiaPhysicalVerdict.VIRTUAL
    if (rest.length > 4 && rest[4] != '&' && rest[4] != '#') return NvidiaPhysicalVerdict.VIRTUAL
    return if (rest.substring(0, 4) == nvidiaHex(dxgiDeviceId, 4).uppercase()) {
        NvidiaPhysicalVerdict.PHYSICAL
    } else {
        NvidiaPhysicalVerdict.VIRTUAL
    }
}

// ---- Name parsing and identity --------------------------------------------------------

private val NAME_NOISE = Regex("[^A-Z0-9 \\-]")
private val NAME_SPACES = Regex("\\s+")
private val PRO_QUADRO_RTX = Regex("\\bQUADRO RTX \\d{4}\\b")
private val PRO_TITAN_RTX = Regex("\\bTITAN RTX\\b")
private val PRO_RTX_A = Regex("\\bRTX A\\d{3,4}\\b")
private val PRO_RTX_ADA = Regex("\\bRTX \\d{4}( SFF)? ADA\\b")
private val PRO_RTX_PRO = Regex("\\bRTX PRO \\d{4}\\b")
private val GEFORCE_RTX = Regex("\\bRTX (\\d{4})(?: ?(TI SUPER|TI|SUPER|D))?(?![A-Z0-9])")
private val PRE_RTX_NAME = Regex("\\b(GTX|MX ?\\d|GT ?\\d)")
private val LEGACY_PRO_NAME = Regex("\\b(QUADRO|TITAN)\\b")
private val MOBILE_NAME = Regex("\\b(LAPTOP|MAX-Q|MOBILE)\\b")
private val VIRTUAL_NAME = Regex("\\b(VIRTUAL|REMOTE|INDIRECT|IDD|BASIC RENDER)\\b")

private fun normalizeNvidiaName(description: String): String {
    val upper = description.uppercase().replace("NVIDIA", " ").replace("GEFORCE", " ")
    return NAME_SPACES.replace(NAME_NOISE.replace(upper, " "), " ").trim()
}

/** True when the name says laptop, Max-Q or mobile. */
internal fun nvidiaNameIsMobile(description: String): Boolean =
    MOBILE_NAME.containsMatchIn(normalizeNvidiaName(description))

/**
 * A virtual-sounding name (virtual, remote, indirect, IDD, basic render) may only exclude an
 * adapter. It never proves one physical, because the twin of a real card can carry its exact name.
 */
internal fun nvidiaNameLooksVirtual(description: String): Boolean =
    VIRTUAL_NAME.containsMatchIn(normalizeNvidiaName(description))

internal data class NvidiaNameParse(
    val generation: NvidiaGeneration,
    /** The four digit base model, or null for professional and pre-RTX names. */
    val modelNumber: Int?,
    val tiVariant: Boolean,
    val professional: Boolean,
    val mobile: Boolean,
)

internal fun nvidiaGenerationForModel(model: Int): NvidiaGeneration = when (model / 1000) {
    2 -> NvidiaGeneration.TURING
    3 -> NvidiaGeneration.AMPERE
    4 -> NvidiaGeneration.ADA
    5 -> NvidiaGeneration.BLACKWELL
    in 6..9 -> NvidiaGeneration.NEWER
    else -> NvidiaGeneration.UNKNOWN
}

/**
 * Professional and legacy names are checked before the GeForce pattern, because a professional
 * name such as an Ada generation card would otherwise parse as another generation.
 * Returns null when the name says nothing about the product line.
 */
internal fun parseNvidiaAdapterName(description: String): NvidiaNameParse? {
    val text = normalizeNvidiaName(description)
    if (text.isEmpty()) return null
    val mobile = MOBILE_NAME.containsMatchIn(text)
    if (PRO_QUADRO_RTX.containsMatchIn(text) || PRO_TITAN_RTX.containsMatchIn(text))
        return NvidiaNameParse(NvidiaGeneration.TURING, null, false, true, mobile)
    if (PRO_RTX_A.containsMatchIn(text))
        return NvidiaNameParse(NvidiaGeneration.AMPERE, null, false, true, mobile)
    if (PRO_RTX_ADA.containsMatchIn(text))
        return NvidiaNameParse(NvidiaGeneration.ADA, null, false, true, mobile)
    if (PRO_RTX_PRO.containsMatchIn(text))
        return NvidiaNameParse(NvidiaGeneration.BLACKWELL, null, false, true, mobile)
    val geforce = GEFORCE_RTX.find(text)
    if (geforce != null) {
        val model = geforce.groupValues[1].toInt()
        val suffix = geforce.groupValues[2]
        return NvidiaNameParse(nvidiaGenerationForModel(model), model, suffix.startsWith("TI"), false, mobile)
    }
    if (PRE_RTX_NAME.containsMatchIn(text) || LEGACY_PRO_NAME.containsMatchIn(text))
        return NvidiaNameParse(NvidiaGeneration.PRE_RTX, null, false, false, mobile)
    return null
}

internal data class NvidiaModelIdentity(
    val generation: NvidiaGeneration,
    val modelNumber: Int?,
    val tiVariant: Boolean,
    val professional: Boolean,
    val mobile: Boolean,
    val source: NvidiaIdentitySource,
)

/** Name first, then the PCI id table, then the id range (generation only). */
internal fun identifyNvidiaModel(description: String, deviceId: Int): NvidiaModelIdentity {
    val pci = NVIDIA_PCI_MODELS.firstOrNull { it.deviceId == deviceId }
    val parsed = parseNvidiaAdapterName(description)
    if (parsed != null && parsed.generation != NvidiaGeneration.UNKNOWN) {
        return NvidiaModelIdentity(
            parsed.generation, parsed.modelNumber, parsed.tiVariant, parsed.professional,
            parsed.mobile || (pci != null && pci.mobile), NvidiaIdentitySource.NAME,
        )
    }
    val nameMobile = nvidiaNameIsMobile(description)
    if (pci != null) {
        return NvidiaModelIdentity(
            pci.generation, pci.model, pci.ti, false, pci.mobile || nameMobile, NvidiaIdentitySource.PCI_ID,
        )
    }
    val range = NVIDIA_ID_RANGES.firstOrNull { deviceId >= it.first && deviceId <= it.last }
    if (range != null) {
        return NvidiaModelIdentity(range.generation, null, false, false, nameMobile, NvidiaIdentitySource.ID_RANGE)
    }
    return NvidiaModelIdentity(NvidiaGeneration.UNKNOWN, null, false, false, nameMobile, NvidiaIdentitySource.NONE)
}

// ---- SKU class and tiers --------------------------------------------------------------

internal fun lookupNvidiaSkuRow(model: Int, ti: Boolean): NvidiaSkuRow? {
    if (ti) {
        val exact = NVIDIA_SKU_ROWS.firstOrNull { it.model == model && it.ti }
        if (exact != null) return exact
    }
    return NVIDIA_SKU_ROWS.firstOrNull { it.model == model && !it.ti }
}

internal fun resolveNvidiaSku(identity: NvidiaModelIdentity): NvidiaSkuInfo {
    if (identity.professional) {
        return NvidiaSkuInfo(null, NvidiaSkuClass.A, NVIDIA_UNKNOWN_MODEL_COST_FACTOR, null, true)
    }
    val model = identity.modelNumber
    if (model != null) {
        val row = lookupNvidiaSkuRow(model, identity.tiVariant)
        if (row != null) {
            return NvidiaSkuInfo(row.model, row.skuClass, row.costFactor, row.qualityCeilingOverride, false)
        }
    }
    if (identity.generation == NvidiaGeneration.NEWER) {
        return NvidiaSkuInfo(model, NvidiaSkuClass.A, NVIDIA_UNKNOWN_MODEL_COST_FACTOR, null, true)
    }
    return NvidiaSkuInfo(model, null, NVIDIA_UNKNOWN_MODEL_COST_FACTOR, null, false)
}

private fun nvidiaClassTier(skuClass: NvidiaSkuClass): NvidiaTier = when (skuClass) {
    NvidiaSkuClass.S -> NvidiaTier.ULTRA
    NvidiaSkuClass.A -> NvidiaTier.HIGH
    NvidiaSkuClass.B -> NvidiaTier.BALANCED
    NvidiaSkuClass.C -> NvidiaTier.LIGHT
    NvidiaSkuClass.D -> NvidiaTier.LIGHT
}

private fun nvidiaSkuClassRank(skuClass: NvidiaSkuClass?): Int = when (skuClass) {
    NvidiaSkuClass.S -> 5
    NvidiaSkuClass.A -> 4
    NvidiaSkuClass.B -> 3
    NvidiaSkuClass.C -> 2
    NvidiaSkuClass.D -> 1
    null -> 0
}

private fun nvidiaVramMeets(vramMiB: Long, minimumMiB: Long): Boolean =
    vramMiB * 100L >= minimumMiB * (100L - NVIDIA_VRAM_SLACK_PERCENT)

/** Whole MiB of dedicated memory, or null when the adapter reported none. */
internal fun nvidiaVramMiB(dedicatedVideoMemoryBytes: Long): Long? =
    if (dedicatedVideoMemoryBytes <= 0L) null else dedicatedVideoMemoryBytes / (1024L * 1024L)

private fun nvidiaUnknownModelTier(vramMiB: Long?): NvidiaTier? {
    if (vramMiB == null) return NvidiaTier.LIGHT
    if (nvidiaVramMeets(vramMiB, NVIDIA_UNKNOWN_MODEL_BALANCED_MIN_VRAM_MIB)) return NvidiaTier.BALANCED
    if (nvidiaVramMeets(vramMiB, NVIDIA_TIER_LIGHT_MIN_VRAM_MIB)) return NvidiaTier.LIGHT
    return null
}

/**
 * Tier before the mobile and cross-adapter step-downs, or null when VRAM is too low.
 * Unknown VRAM gives min(class tier, HIGH). An unknown model is tiered by VRAM only.
 */
internal fun nvidiaTierFor(sku: NvidiaSkuInfo, vramMiB: Long?): NvidiaTier? {
    val skuClass = sku.skuClass
    if (skuClass == null) return nvidiaUnknownModelTier(vramMiB)
    if (vramMiB == null) return lowerNvidiaTier(nvidiaClassTier(skuClass), NvidiaTier.HIGH)
    val strong = skuClass == NvidiaSkuClass.S
    val high = strong || skuClass == NvidiaSkuClass.A
    val middle = high || skuClass == NvidiaSkuClass.B
    return when {
        strong && nvidiaVramMeets(vramMiB, NVIDIA_TIER_ULTRA_MIN_VRAM_MIB) -> NvidiaTier.ULTRA
        high && nvidiaVramMeets(vramMiB, NVIDIA_TIER_HIGH_MIN_VRAM_MIB) -> NvidiaTier.HIGH
        middle && nvidiaVramMeets(vramMiB, NVIDIA_TIER_BALANCED_MIN_VRAM_MIB) -> NvidiaTier.BALANCED
        nvidiaVramMeets(vramMiB, NVIDIA_TIER_LIGHT_MIN_VRAM_MIB) -> NvidiaTier.LIGHT
        else -> null
    }
}

// ---- LUID comparison and adapter pin --------------------------------------------------

private fun normalizeNvidiaLuidHex(text: String?): String? {
    if (text == null) return null
    val value = text.trim().lowercase().removePrefix("0x")
    if (value.length != 16) return null
    for (ch in value) {
        if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) return null
    }
    return value
}

/**
 * Compares two 16 digit LUID texts, accepting either 32-bit half order because the order the
 * player prints its LUID in is unverified. Case and a 0x prefix are ignored. Malformed text
 * never matches.
 */
internal fun luidMatches(expectedHex: String, observedHex: String?): Boolean {
    val expected = normalizeNvidiaLuidHex(expectedHex) ?: return false
    val observed = normalizeNvidiaLuidHex(observedHex) ?: return false
    val swapped = expected.substring(8) + expected.substring(0, 8)
    return observed == expected || observed == swapped
}

internal fun luidMatches(expectedHigh: Int, expectedLow: Int, observedHex: String?): Boolean =
    luidMatches(nvidiaLuidHex(expectedHigh, expectedLow), observedHex)

private fun isPrintableAsciiDescription(text: String): Boolean {
    if (text.isEmpty() || text.length > 128) return false
    for (ch in text) {
        if (ch.code < 0x20 || ch.code > 0x7E) return false
    }
    return true
}

/**
 * Value for the player's adapter option. The value is the primary adapter's exact description
 * when that is printable ASCII of length 1..128, otherwise the legacy literal. The pin is safe
 * only when the first adapter in enumeration order whose description starts with the value
 * (case-insensitive prefix) is the primary itself; an unsafe pin sets no option at all.
 */
internal fun resolveMpvAdapterPin(
    primary: NvidiaAdapterEvidence,
    orderedAdapters: List<NvidiaAdapterEvidence>,
): NvidiaAdapterPin {
    val exactPinnable = isPrintableAsciiDescription(primary.description)
    val value = if (exactPinnable) primary.description else NVIDIA_LEGACY_ADAPTER_PIN
    val first = orderedAdapters.firstOrNull { it.description.startsWith(value, ignoreCase = true) }
        ?: return NvidiaAdapterPin.None
    if (first.luidLow != primary.luidLow || first.luidHigh != primary.luidHigh) return NvidiaAdapterPin.None
    return if (exactPinnable) NvidiaAdapterPin.Exact(value) else NvidiaAdapterPin.Legacy
}

// ---- Primary selection and classification --------------------------------------------

internal sealed interface NvidiaPrimarySelection {
    data class Selected(val primary: NvidiaAdapterEvidence, val assumedUnique: Boolean) : NvidiaPrimarySelection

    /** The inventory already decides the verdict: Absent, Unsupported or Unknown. */
    data class Rejected(val capability: NvidiaCapability) : NvidiaPrimarySelection
}

private class NvidiaRankedCandidate(
    val adapter: NvidiaAdapterEvidence,
    val ownsDisplay: Boolean,
    val hasOutput: Boolean,
    val classRank: Int,
    val vramMiB: Long,
)

/** The probe verdict, except that a virtual-sounding name always makes the adapter virtual. */
private fun nvidiaEffectiveVerdict(adapter: NvidiaAdapterEvidence): NvidiaPhysicalVerdict =
    if (nvidiaNameLooksVirtual(adapter.description)) NvidiaPhysicalVerdict.VIRTUAL else adapter.physical

private fun nvidiaCandidateBetter(candidate: NvidiaRankedCandidate, current: NvidiaRankedCandidate): Boolean {
    if (candidate.ownsDisplay != current.ownsDisplay) return candidate.ownsDisplay
    if (candidate.hasOutput != current.hasOutput) return candidate.hasOutput
    if (candidate.classRank != current.classRank) return candidate.classRank > current.classRank
    if (candidate.vramMiB != current.vramMiB) return candidate.vramMiB > current.vramMiB
    return false
}

/**
 * Picks the physical NVIDIA adapter to enhance on. A virtual twin is excluded even when it
 * reports an identical RTX identity, and so is an adapter whose name sounds virtual. When the physical check failed (verdict UNKNOWN) an adapter
 * is accepted only when no other adapter shares its PCI identity. Among several physical GPUs
 * the one that owns the display, then one with an attached output, then the highest class,
 * then the most VRAM wins; enumeration order breaks the last tie.
 */
internal fun selectNvidiaPrimary(input: NvidiaProbeInput): NvidiaPrimarySelection {
    if (!input.dxgiComplete) {
        return NvidiaPrimarySelection.Rejected(NvidiaCapability.Unknown(NvidiaUnknownReason.PROBE_FAILED))
    }
    val candidates = input.adapters.filter { it.vendorId == NVIDIA_VENDOR_ID && !it.isSoftware }
    if (candidates.isEmpty()) return NvidiaPrimarySelection.Rejected(NvidiaCapability.Absent)
    val physical = candidates.filter { nvidiaEffectiveVerdict(it) == NvidiaPhysicalVerdict.PHYSICAL }
    var assumedUnique = false
    val pool: List<NvidiaAdapterEvidence>
    if (physical.isNotEmpty()) {
        pool = physical
    } else {
        val unverified = candidates.filter { nvidiaEffectiveVerdict(it) == NvidiaPhysicalVerdict.UNKNOWN }
        if (unverified.isEmpty()) {
            return NvidiaPrimarySelection.Rejected(
                NvidiaCapability.Unsupported(NvidiaUnsupportedReason.NOT_PHYSICAL),
            )
        }
        val unique = unverified.filter { adapter -> candidates.count { it.pciIdentity == adapter.pciIdentity } == 1 }
        if (unique.isEmpty()) {
            return NvidiaPrimarySelection.Rejected(
                NvidiaCapability.Unsupported(NvidiaUnsupportedReason.PHYSICAL_UNVERIFIED),
            )
        }
        pool = unique
        assumedUnique = true
    }
    val display = input.displayAdapterLuidHex
    var best: NvidiaRankedCandidate? = null
    for (adapter in pool) {
        val sku = resolveNvidiaSku(identifyNvidiaModel(adapter.description, adapter.deviceId))
        val ranked = NvidiaRankedCandidate(
            adapter = adapter,
            ownsDisplay = display != null && display.equals(adapter.luidHex, ignoreCase = true),
            hasOutput = adapter.outputCount > 0,
            classRank = nvidiaSkuClassRank(sku.skuClass),
            vramMiB = nvidiaVramMiB(adapter.dedicatedVideoMemoryBytes) ?: 0L,
        )
        val current = best
        if (current == null || nvidiaCandidateBetter(ranked, current)) best = ranked
    }
    val chosen = best ?: return NvidiaPrimarySelection.Rejected(NvidiaCapability.Absent)
    return NvidiaPrimarySelection.Selected(chosen.adapter, assumedUnique)
}

private fun classifyNvidiaPrimary(
    primary: NvidiaAdapterEvidence,
    input: NvidiaProbeInput,
    assumedUnique: Boolean,
): NvidiaCapability {
    val identity = identifyNvidiaModel(primary.description, primary.deviceId)
    val driver = primary.umdVersion?.let { nvidiaDriverPublicX100FromPacked(it) }
    val vram = nvidiaVramMiB(primary.dedicatedVideoMemoryBytes)
    val pin = resolveMpvAdapterPin(primary, input.adapters)
    val gpu = NvidiaGpuInfo(
        gpuKey = nvidiaGpuKey(primary.deviceId, primary.subSysId, primary.revision),
        description = primary.description,
        generation = identity.generation,
        deviceId = primary.deviceId,
        subSysId = primary.subSysId,
        revision = primary.revision,
        luidHex = primary.luidHex,
        vramMiB = vram,
        mobile = identity.mobile,
        professional = identity.professional,
        driverPublicX100 = driver,
    )
    if (identity.generation == NvidiaGeneration.PRE_RTX) {
        return NvidiaCapability.Unsupported(NvidiaUnsupportedReason.PRE_RTX, gpu, pin)
    }
    if (identity.generation == NvidiaGeneration.BLACKWELL && driver != null &&
        driver < NVIDIA_BLACKWELL_MIN_DRIVER_X100
    ) {
        return NvidiaCapability.Unsupported(NvidiaUnsupportedReason.DRIVER_OLD, gpu, pin)
    }
    val sku = resolveNvidiaSku(identity)
    val baseTier = nvidiaTierFor(sku, vram)
        ?: return NvidiaCapability.Unsupported(NvidiaUnsupportedReason.VRAM_LOW, gpu, pin)
    if (pin is NvidiaAdapterPin.None) {
        return NvidiaCapability.Unsupported(NvidiaUnsupportedReason.PIN_UNSAFE, gpu, NvidiaAdapterPin.None)
    }
    val notes = linkedSetOf<NvidiaCapabilityNote>()
    if (assumedUnique) notes.add(NvidiaCapabilityNote.PHYSICAL_ASSUMED_UNIQUE)
    if (vram == null) notes.add(NvidiaCapabilityNote.VRAM_UNKNOWN)
    if (sku.skuClass == null) notes.add(NvidiaCapabilityNote.UNKNOWN_MODEL)
    if (identity.professional) notes.add(NvidiaCapabilityNote.PROFESSIONAL_PART)
    if (identity.generation != NvidiaGeneration.BLACKWELL && driver != null &&
        driver < NVIDIA_DRIVER_ADVISORY_BELOW_X100
    ) {
        notes.add(NvidiaCapabilityNote.DRIVER_ADVISORY)
    }
    var tier = baseTier
    if (identity.mobile) {
        tier = tier.stepDown()
        notes.add(NvidiaCapabilityNote.MOBILE_STEP_DOWN)
    }
    val display = input.displayAdapterLuidHex
    val crossAdapter = primary.outputCount == 0 ||
        (display != null && !display.equals(primary.luidHex, ignoreCase = true))
    if (crossAdapter) {
        tier = tier.stepDown()
        notes.add(NvidiaCapabilityNote.CROSS_ADAPTER)
    }
    return NvidiaCapability.Capable(gpu, tier, sku, pin, notes.toSet())
}

/**
 * The single verdict for one probe result. Order of hard declines: not an RTX part, Blackwell
 * driver below the floor, VRAM below the floor, unsafe adapter pin.
 */
internal fun classifyNvidiaCapability(input: NvidiaProbeInput): NvidiaCapability {
    val selection = selectNvidiaPrimary(input)
    return when (selection) {
        is NvidiaPrimarySelection.Rejected -> selection.capability
        is NvidiaPrimarySelection.Selected -> classifyNvidiaPrimary(selection.primary, input, selection.assumedUnique)
    }
}

/**
 * Verdict for a probe that failed but whose player-logged LUID passed the physical check.
 * The identity comes from the logged name and device id; VRAM is unknown, so the tier is the
 * class tier capped at HIGH. The adapter option was fixed before this was known, so the pin is
 * the legacy literal.
 */
internal fun classifyObservedNvidiaGpu(
    description: String?,
    deviceId: Int?,
    luidHex: String?,
    driverPublicX100: Int?,
): NvidiaCapability {
    val text = description.orEmpty()
    val identity = identifyNvidiaModel(text, deviceId ?: 0)
    val gpu = NvidiaGpuInfo(
        gpuKey = nvidiaGpuKey(deviceId ?: 0, 0, 0),
        description = text,
        generation = identity.generation,
        deviceId = deviceId ?: 0,
        subSysId = 0,
        revision = 0,
        luidHex = luidHex.orEmpty(),
        vramMiB = null,
        mobile = identity.mobile,
        professional = identity.professional,
        driverPublicX100 = driverPublicX100,
    )
    if (identity.generation == NvidiaGeneration.PRE_RTX) {
        return NvidiaCapability.Unsupported(NvidiaUnsupportedReason.PRE_RTX, gpu, NvidiaAdapterPin.Legacy)
    }
    if (identity.generation == NvidiaGeneration.BLACKWELL && driverPublicX100 != null &&
        driverPublicX100 < NVIDIA_BLACKWELL_MIN_DRIVER_X100
    ) {
        return NvidiaCapability.Unsupported(NvidiaUnsupportedReason.DRIVER_OLD, gpu, NvidiaAdapterPin.Legacy)
    }
    val sku = resolveNvidiaSku(identity)
    val baseTier = nvidiaTierFor(sku, null) ?: NvidiaTier.LIGHT
    var tier = lowerNvidiaTier(baseTier, NvidiaTier.HIGH)
    val notes = linkedSetOf(NvidiaCapabilityNote.OBSERVED_IDENTITY, NvidiaCapabilityNote.VRAM_UNKNOWN)
    if (sku.skuClass == null) notes.add(NvidiaCapabilityNote.UNKNOWN_MODEL)
    if (identity.professional) notes.add(NvidiaCapabilityNote.PROFESSIONAL_PART)
    if (identity.mobile) {
        tier = tier.stepDown()
        notes.add(NvidiaCapabilityNote.MOBILE_STEP_DOWN)
    }
    return NvidiaCapability.Capable(gpu, tier, sku, NvidiaAdapterPin.Legacy, notes.toSet())
}

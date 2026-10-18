package com.bilipai.desktop.nvidia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Loader for the shared golden vectors. The same file feeds the Python data checks, so a vector
 * edit that drifts from the Kotlin core fails in the JVM tests.
 */
internal object NvidiaGoldenVectors {
    private const val RESOURCE = "/nvidia-gpu-classification-vectors.json"

    private val document: JsonObject by lazy {
        val stream = requireNotNull(NvidiaGoldenVectors::class.java.getResourceAsStream(RESOURCE)) {
            "Missing test resource $RESOURCE"
        }
        Json.parseToJsonElement(stream.use { it.readBytes() }.toString(Charsets.UTF_8)).jsonObject
    }

    fun objects(key: String): List<JsonObject> = document.getValue(key).jsonArray.map { it.jsonObject }

    /** The objects of an array element (for example the rows of a ladder). */
    fun objectsOf(element: JsonElement): List<JsonObject> = element.jsonArray.map { it.jsonObject }

    fun hex(text: String): Int = text.trim().removePrefix("0x").removePrefix("0X").toLong(16).toInt()

    fun string(source: JsonObject, key: String): String = source.getValue(key).jsonPrimitive.content

    fun stringOrNull(source: JsonObject, key: String): String? = source[key]?.jsonPrimitive?.contentOrNull

    fun int(source: JsonObject, key: String): Int = source.getValue(key).jsonPrimitive.int

    fun long(source: JsonObject, key: String): Long = source.getValue(key).jsonPrimitive.long

    fun double(source: JsonObject, key: String): Double = source.getValue(key).jsonPrimitive.double

    fun bool(source: JsonObject, key: String, default: Boolean = false): Boolean {
        val element = source[key] ?: return default
        if (element is JsonNull) return default
        return element.jsonPrimitive.content == "true"
    }

    fun strings(source: JsonObject, key: String): List<String> =
        source.getValue(key).jsonArray.map { it.jsonPrimitive.content }

    /** An integer array, or null when the key is missing or JSON null. */
    fun ints(source: JsonObject, key: String): List<Int>? {
        val element = source[key] ?: return null
        if (element is JsonNull) return null
        return element.jsonArray.map { it.jsonPrimitive.int }
    }

    /** Four driver fields packed like the LARGE_INTEGER the user-mode driver version query returns. */
    fun pack(fields: List<Int>): Long =
        (fields[0].toLong() shl 48) or (fields[1].toLong() shl 32) or (fields[2].toLong() shl 16) or fields[3].toLong()

    /** One gpus vector as a single physical adapter that owns its display. */
    fun gpuEvidence(vector: JsonObject): NvidiaAdapterEvidence {
        val driver = ints(vector, "driver")
        val vramMiB = long(vector, "vramMiB")
        return NvidiaAdapterEvidence(
            vendorId = NVIDIA_VENDOR_ID,
            deviceId = hex(string(vector, "deviceId")),
            subSysId = 0x10431234,
            revision = 0xA1,
            description = string(vector, "description"),
            dedicatedVideoMemoryBytes = vramMiB * 1_048_576L,
            luidLow = 0x1000,
            luidHigh = 0,
            flags = 0,
            outputCount = 1,
            physical = NvidiaPhysicalVerdict.PHYSICAL,
            umdVersion = if (driver == null) null else pack(driver),
        )
    }

    fun gpuInput(vector: JsonObject): NvidiaProbeInput {
        val evidence = gpuEvidence(vector)
        return NvidiaProbeInput(true, listOf(evidence), evidence.luidHex)
    }

    /** One scenario adapter; the physical verdict comes from the pure path check. */
    fun scenarioEvidence(adapter: JsonObject): NvidiaAdapterEvidence {
        val deviceId = hex(string(adapter, "deviceId"))
        val driver = ints(adapter, "umd")
        return NvidiaAdapterEvidence(
            vendorId = hex(string(adapter, "vendorId")),
            deviceId = deviceId,
            subSysId = hex(string(adapter, "subSysId")),
            revision = hex(string(adapter, "revision")),
            description = string(adapter, "description"),
            dedicatedVideoMemoryBytes = long(adapter, "vramMiB") * 1_048_576L,
            luidLow = hex(string(adapter, "luidLow")),
            luidHigh = int(adapter, "luidHigh"),
            flags = int(adapter, "flags"),
            outputCount = int(adapter, "outputCount"),
            physical = classifyNvidiaAdapterPath(stringOrNull(adapter, "pnpPath"), deviceId),
            umdVersion = if (driver == null) null else pack(driver),
        )
    }

    fun scenarioInput(scenario: JsonObject): NvidiaProbeInput = NvidiaProbeInput(
        dxgiComplete = bool(scenario, "dxgiComplete", true),
        adapters = scenario.getValue("adapters").jsonArray.map { scenarioEvidence(it.jsonObject) },
        displayAdapterLuidHex = stringOrNull(scenario, "displayAdapterLuid"),
    )

    fun pinText(pin: NvidiaAdapterPin): String = when (pin) {
        is NvidiaAdapterPin.Exact -> "exact:" + pin.value
        is NvidiaAdapterPin.Legacy -> "legacy"
        is NvidiaAdapterPin.None -> "none"
    }

    fun activationName(state: DesktopNvidiaActivationState): String = when (state) {
        is DesktopNvidiaActivationState.Off -> "OFF"
        is DesktopNvidiaActivationState.Legacy -> "LEGACY"
        is DesktopNvidiaActivationState.Probing -> "PROBING"
        is DesktopNvidiaActivationState.NoNvidia -> "NO_NVIDIA"
        is DesktopNvidiaActivationState.Unsupported -> "UNSUPPORTED"
        is DesktopNvidiaActivationState.Unidentified -> "UNIDENTIFIED"
        is DesktopNvidiaActivationState.Standby -> "STANDBY"
        is DesktopNvidiaActivationState.PausedGame -> "PAUSED_GAME"
        is DesktopNvidiaActivationState.PausedBattery -> "PAUSED_BATTERY"
        is DesktopNvidiaActivationState.PausedRemote -> "PAUSED_REMOTE"
        is DesktopNvidiaActivationState.FailedRemembered -> "FAILED_REMEMBERED"
        is DesktopNvidiaActivationState.FailedThisVideo -> "FAILED_THIS_VIDEO"
        is DesktopNvidiaActivationState.FailedExtendedRange -> "FAILED_EXTENDED_RANGE"
        is DesktopNvidiaActivationState.Allowed -> "ALLOWED"
    }

    fun observedGpu(source: JsonObject?): DesktopNvidiaObservedGpu? {
        if (source == null) return null
        return DesktopNvidiaObservedGpu(
            description = stringOrNull(source, "description"),
            deviceId = stringOrNull(source, "deviceId")?.let { hex(it) },
            luidHex = stringOrNull(source, "luid"),
            verifiedPhysical = bool(source, "verified"),
        )
    }
}

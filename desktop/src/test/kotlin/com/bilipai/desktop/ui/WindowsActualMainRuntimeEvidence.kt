package com.bilipai.desktop.ui

import kotlinx.serialization.json.*

/** Read-only diagnostics: no AWT/native types, window creation or renderer authority. */
internal data class WindowsMainBackendSelectionObservation(
    val frameSerial: Long? = null,
    val capturedWindowIdentity: Int? = null,
    val windowIdentityMatches: Boolean = false,
    val windowShowing: Boolean? = null,
    val windowDisplayable: Boolean? = null,
    val rootOwnershipCurrent: Boolean = false,
    val routeFrameCurrent: Boolean = false,
    val renderApi: String? = null,
    val unavailableReason: String? = null,
    val diagnosticExceptionType: String? = null,
)

internal object WindowsActualMainRuntimeEvidence {
    const val MAX_VALUE_LENGTH = 256
    private val JVM_PROPERTY_KEYS = listOf(
        "java.runtime.version", "java.version", "java.vm.name",
        "java.vm.version", "java.vm.vendor", "java.vendor",
    )
    internal data class JvmProperties(val values: JsonObject, val errors: JsonObject, val truncated: JsonArray)

    /** Actual fixture uses System.getProperty. Injection only tests the fixed allowlist/bounds. */
    fun readJvmProperties(reader: (String) -> String? = System::getProperty): JvmProperties {
        val errors = mutableMapOf<String, JsonElement>()
        val truncated = mutableListOf<JsonElement>()
        val values = buildJsonObject {
            for (key in JVM_PROPERTY_KEYS) {
                val value = runCatching { reader(key) }.getOrElse { error ->
                    errors[key] = JsonPrimitive(error.javaClass.name.take(MAX_VALUE_LENGTH))
                    null
                }
                if (value != null && value.length > MAX_VALUE_LENGTH) truncated += JsonPrimitive(key)
                put(key, value?.take(MAX_VALUE_LENGTH)?.let(::JsonPrimitive) ?: JsonNull)
            }
        }
        return JvmProperties(values, JsonObject(errors), JsonArray(truncated))
    }
    fun capture(phase: String, backend: WindowsMainBackendSelectionObservation): JsonObject {
        val properties = readJvmProperties()
        val runtime = runCatching { Runtime.version().toString() }
        val pid = runCatching { ProcessHandle.current().pid() }
        return buildJsonObject {
            put("schema", 1); put("diagnosticOnly", true)
            put("observationPhase", phase.take(MAX_VALUE_LENGTH))
            put("executingJvmProcessId", pid.getOrNull()?.let(::JsonPrimitive) ?: JsonNull)
            put("runtimeVersion", runtime.getOrNull()?.take(MAX_VALUE_LENGTH)?.let(::JsonPrimitive) ?: JsonNull)
            put("javaProperties", properties.values)
            put("javaPropertyReadErrors", properties.errors)
            put("truncatedJavaProperties", properties.truncated)
            put("actualMainSystemPropertiesRecorded", properties.errors.isEmpty() && runtime.isSuccess && pid.isSuccess)
            put("rootFrameSerial", backend.frameSerial?.let(::JsonPrimitive) ?: JsonNull)
            put("capturedWindowIdentity", backend.capturedWindowIdentity?.let(::JsonPrimitive) ?: JsonNull)
            put("windowIdentityMatches", backend.windowIdentityMatches)
            put("windowShowing", backend.windowShowing?.let(::JsonPrimitive) ?: JsonNull)
            put("windowDisplayable", backend.windowDisplayable?.let(::JsonPrimitive) ?: JsonNull)
            put("rootOwnershipCurrent", backend.rootOwnershipCurrent)
            put("routeFrameCurrent", backend.routeFrameCurrent)
            put("skikoRenderApiSelection", backend.renderApi?.take(MAX_VALUE_LENGTH)?.let(::JsonPrimitive) ?: JsonNull)
            put("actualMainSkikoRenderApiMeasured", backend.renderApi != null && backend.unavailableReason == null &&
                backend.windowIdentityMatches && backend.windowShowing == true && backend.windowDisplayable == true &&
                backend.rootOwnershipCurrent && backend.routeFrameCurrent)
            put("backendObservationScope", "PUBLIC_COMPOSE_WINDOW_CURRENT_BACKEND_SELECTION")
            put("backendUnavailableReason", backend.unavailableReason?.take(MAX_VALUE_LENGTH)?.let(::JsonPrimitive) ?: JsonNull)
            put("diagnosticExceptionType", backend.diagnosticExceptionType?.take(MAX_VALUE_LENGTH)?.let(::JsonPrimitive) ?: JsonNull)
            put("fixtureDefaultRendererPolicyMarker", "DIRECT3D")
            put("physicalPresentationProven", false); put("mpvRendererMeasured", false)
        }
    }
}

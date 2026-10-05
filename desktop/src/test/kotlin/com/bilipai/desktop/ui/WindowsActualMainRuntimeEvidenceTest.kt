package com.bilipai.desktop.ui

import kotlinx.serialization.json.*
import kotlin.test.*

/** CPU-only evidence contracts, not an actual Main/window/backend acceptance test. */
class WindowsActualMainRuntimeEvidenceTest {
    @Test fun executingJvmReadoutUsesActualRuntimeAndProcess() {
        val receipt = WindowsActualMainRuntimeEvidence.capture("CPU_SELF_READ_TEST",
            WindowsMainBackendSelectionObservation(unavailableReason = "NO_WINDOW_IN_CPU_TEST"))
        assertEquals(ProcessHandle.current().pid(), receipt.getValue("executingJvmProcessId").jsonPrimitive.long)
        assertEquals(Runtime.version().toString().take(256), receipt.getValue("runtimeVersion").jsonPrimitive.content)
        assertEquals(System.getProperty("java.vendor")?.take(256),
            receipt.getValue("javaProperties").jsonObject.getValue("java.vendor").jsonPrimitive.contentOrNull)
        assertTrue(receipt.getValue("diagnosticOnly").jsonPrimitive.boolean)
        assertFalse(receipt.getValue("actualMainSkikoRenderApiMeasured").jsonPrimitive.boolean)
        assertFalse(receipt.getValue("physicalPresentationProven").jsonPrimitive.boolean)
        assertFalse(receipt.getValue("mpvRendererMeasured").jsonPrimitive.boolean)
    }
    @Test fun onlyFixedJvmKeysAreReadAndEveryValueIsBounded() {
        val read = mutableListOf<String>()
        val properties = WindowsActualMainRuntimeEvidence.readJvmProperties { key ->
            read += key
            if (key == "java.vendor") "v".repeat(1024) else "fixture"
        }
        val expected = listOf("java.runtime.version", "java.version", "java.vm.name",
            "java.vm.version", "java.vm.vendor", "java.vendor")
        assertEquals(expected, read)
        assertEquals(expected.toSet(), properties.values.keys)
        assertTrue(properties.values.values.all { it.jsonPrimitive.content.length <= 256 })
        assertEquals(256, properties.values.getValue("java.vendor").jsonPrimitive.content.length)
        assertEquals(listOf("java.vendor"), properties.truncated.map { it.jsonPrimitive.content })
        assertTrue(properties.errors.isEmpty())
        assertFalse("user.home" in properties.values)
        assertFalse("java.home" in properties.values)
    }
    @Test fun propertyReadFailureIsExplicitAndDoesNotStopOtherAllowedReads() {
        val properties = WindowsActualMainRuntimeEvidence.readJvmProperties { key ->
            if (key == "java.vendor") throw SecurityException("This message must never enter evidence")
            "read"
        }
        assertEquals(JsonNull, properties.values.getValue("java.vendor"))
        assertEquals("java.lang.SecurityException", properties.errors.getValue("java.vendor").jsonPrimitive.content)
        assertEquals("read", properties.values.getValue("java.version").jsonPrimitive.content)
        assertFalse(properties.errors.toString().contains("This message"))
    }
    @Test fun policyMarkerAndUnownedSelectionCannotBecomeActualBackendEvidence() {
        val receipt = WindowsActualMainRuntimeEvidence.capture("CPU_UNAVAILABLE_TEST",
            WindowsMainBackendSelectionObservation(renderApi = "DIRECT3D", unavailableReason = "ROOT_OR_ROUTE_OWNER_RETIRED"))
        assertEquals("DIRECT3D", receipt.getValue("fixtureDefaultRendererPolicyMarker").jsonPrimitive.content)
        assertFalse(receipt.getValue("actualMainSkikoRenderApiMeasured").jsonPrimitive.boolean)
        assertEquals("ROOT_OR_ROUTE_OWNER_RETIRED", receipt.getValue("backendUnavailableReason").jsonPrimitive.content)
        val unowned = WindowsActualMainRuntimeEvidence.capture("CPU_UNOWNED_TEST",
            WindowsMainBackendSelectionObservation(renderApi = "DIRECT3D"))
        assertFalse(unowned.getValue("actualMainSkikoRenderApiMeasured").jsonPrimitive.boolean)
    }
}

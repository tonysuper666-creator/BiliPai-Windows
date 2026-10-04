package com.bilipai.desktop.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopWindowsDxgiAdaptersTest {
    private fun adapter(vendor: Int, device: Int, flags: Int = 0, name: String = "fixture") =
        DesktopWindowsDxgiAdapters.Adapter(vendor, device, flags, name)

    private fun snapshot(vararg adapters: DesktopWindowsDxgiAdapters.Adapter) =
        DesktopWindowsDxgiAdapters.Snapshot(true, adapters.toList(), null)

    @Test fun basicRenderDriverWithoutSoftwareFlagUsesBitblt() {
        assertTrue(snapshot(adapter(0x1414, 0x008c)).useBitblt())
    }

    @Test fun everyAdapterMustBeSoftwareBeforeChangingPresentation() {
        assertTrue(snapshot(adapter(0x1414, 0x008c), adapter(0x1234, 0x5678, 2)).useBitblt())
        assertFalse(snapshot(adapter(0x1414, 0x008c), adapter(0x10de, 0x2c02)).useBitblt())
    }

    @Test fun amdAndIntelHardwareDoNotDependOnNvidiaPresence() {
        for (vendor in listOf(0x1002, 0x8086)) {
            assertFalse(snapshot(adapter(vendor, 0x1234)).useBitblt())
            assertFalse(snapshot(adapter(0x1414, 0x008c), adapter(vendor, 0x1234)).useBitblt())
        }
    }

    @Test fun hardwareNvidiaRetainsFlipWithOrWithoutBasicAdapter() {
        assertFalse(snapshot(adapter(0x10de, 0x2c02)).useBitblt())
        assertFalse(snapshot(adapter(0x10de, 0x2c02), adapter(0x1414, 0x008c, 2)).useBitblt())
    }

    @Test fun descriptionAndVendorAloneCannotForgeSoftwareClassification() {
        assertFalse(snapshot(adapter(0x1414, 0x008d, name = "Microsoft Basic Render Driver")).useBitblt())
        assertFalse(snapshot(adapter(0x1234, 0x008c, name = "Microsoft Basic Render Driver")).useBitblt())
        assertFalse(snapshot(adapter(0x1414, 0x008d, 1)).useBitblt())
    }

    @Test fun partialOrFailedInventoriesCannotChangePresentation() {
        val software = listOf(adapter(0x1414, 0x008c))
        assertFalse(DesktopWindowsDxgiAdapters.Snapshot(false, software, "dxgi-enumerate-failed").useBitblt())
        assertFalse(DesktopWindowsDxgiAdapters.Snapshot(false, software, "dxgi-topology-changed").useBitblt())
        assertFalse(DesktopWindowsDxgiAdapters.Snapshot(true, software, "fixture-error").useBitblt())
    }

    @Test fun emptyInventoryKeepsDefaultPresentation() {
        assertFalse(snapshot().useBitblt())
    }

    @Test fun unknownOrReservedFlagsKeepDefaultEvenForBasicIds() {
        for (flags in listOf(1, 3, 6, -1)) {
            assertFalse(snapshot(adapter(0x1414, 0x008c, flags)).useBitblt())
        }
    }

    @Test fun capturedInventoryDoesNotChangeWhenCallerListChanges() {
        val captured = mutableListOf(adapter(0x1414, 0x008c))
        val result = DesktopWindowsDxgiAdapters.Snapshot(true, captured, null)
        captured += adapter(0x10de, 0x2c02)
        assertTrue(result.useBitblt())
    }
}

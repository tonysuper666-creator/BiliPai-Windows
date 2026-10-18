package com.bilipai.desktop.player

import com.sun.jna.Memory
import com.sun.jna.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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

    // ---- Detailed inventory: synthetic DXGI_ADAPTER_DESC1 blocks, no native call ----

    /** An x64 DXGI_ADAPTER_DESC1 block written field by field at the documented offsets. */
    private fun desc1(
        vendor: Int = 0x10de, device: Int = 0x2c02, subSys: Int = 0x8a4a1043.toInt(), revision: Int = 0xa1,
        videoMemory: Long = 15_978L * 1024L * 1024L, luidLow: Int = 0xe782, luidHigh: Int = 0, flags: Int = 0,
        name: String = "NVIDIA GeForce RTX 5080",
    ): Memory {
        val block = Memory(312)
        block.clear()
        for (index in name.indices) block.setShort(index * 2L, name[index].code.toShort())
        block.setInt(256, vendor); block.setInt(260, device); block.setInt(264, subSys); block.setInt(268, revision)
        block.setLong(272, videoMemory)
        block.setLong(280, 0x1111_1111_1111_1111L) // DedicatedSystemMemory: must not leak into any field
        block.setLong(288, 0x2222_2222_2222_2222L) // SharedSystemMemory: must not leak into any field
        block.setInt(296, luidLow); block.setInt(300, luidHigh); block.setInt(304, flags)
        return block
    }

    @Test fun parseDesc1DecodesEveryFieldFromItsDocumentedOffset() {
        desc1().use { block ->
            val detail = DesktopWindowsDxgiAdapters.parseDesc1(block)
            assertEquals(0x10de, detail.vendorId()); assertEquals(0x2c02, detail.deviceId())
            assertEquals(0x8a4a1043.toInt(), detail.subSysId()); assertEquals(0xa1, detail.revision())
            assertEquals(15_978L * 1024L * 1024L, detail.dedicatedVideoMemory())
            assertEquals(0xe782, detail.luidLow()); assertEquals(0, detail.luidHigh())
            assertEquals(0, detail.flags()); assertEquals("NVIDIA GeForce RTX 5080", detail.description())
            assertEquals(-1, detail.outputCount(), "a block alone says nothing about the attached outputs")
            assertFalse(detail.isSoftware())
            assertEquals("000000000000e782", detail.luidHex())
        }
    }

    @Test fun documentedDescriptorLayoutIsTheCheckedX64One() {
        assertEquals(312, DesktopWindowsDxgiAdapters.DESC1_SIZE)
        assertEquals(256, DesktopWindowsDxgiAdapters.DESC1_VENDOR_OFFSET)
        assertEquals(260, DesktopWindowsDxgiAdapters.DESC1_DEVICE_OFFSET)
        assertEquals(264, DesktopWindowsDxgiAdapters.DESC1_SUBSYS_OFFSET)
        assertEquals(268, DesktopWindowsDxgiAdapters.DESC1_REVISION_OFFSET)
        assertEquals(272, DesktopWindowsDxgiAdapters.DESC1_DEDICATED_VIDEO_MEMORY_OFFSET)
        assertEquals(280, DesktopWindowsDxgiAdapters.DESC1_DEDICATED_SYSTEM_MEMORY_OFFSET)
        assertEquals(288, DesktopWindowsDxgiAdapters.DESC1_SHARED_SYSTEM_MEMORY_OFFSET)
        assertEquals(296, DesktopWindowsDxgiAdapters.DESC1_LUID_LOW_OFFSET)
        assertEquals(300, DesktopWindowsDxgiAdapters.DESC1_LUID_HIGH_OFFSET)
        assertEquals(304, DesktopWindowsDxgiAdapters.DESC1_FLAGS_OFFSET)
    }

    @Test fun parseDesc1KeepsHighBitValuesAsRawBitsAndBoundsTheDescription() {
        desc1(luidLow = 0x80000001.toInt(), luidHigh = -2, subSys = -1, name = "A".repeat(128)).use { block ->
            val detail = DesktopWindowsDxgiAdapters.parseDesc1(block)
            assertEquals(0x80000001.toInt(), detail.luidLow()); assertEquals(-2, detail.luidHigh())
            assertEquals(-1, detail.subSysId())
            assertEquals("fffffffe80000001", detail.luidHex())
            assertEquals(128, detail.description().length, "a description without a NUL stops at 128 characters")
        }
        desc1(name = "RTX\u0007Bad\u0000Tail").use { block ->
            assertEquals("RTX?Bad", DesktopWindowsDxgiAdapters.parseDesc1(block).description(),
                "control characters become question marks and the first NUL ends the text")
        }
        desc1(videoMemory = -1L).use { block ->
            assertEquals(0L, DesktopWindowsDxgiAdapters.parseDesc1(block).dedicatedVideoMemory(),
                "an impossible negative size reads as unknown, never as a huge value")
        }
    }

    @Test fun luidHexIsSixteenLowercaseDigitsHighPartFirst() {
        assertEquals("000000000000e782", DesktopWindowsDxgiAdapters.luidHex(0, 0xe782))
        assertEquals("00000001000b95ad", DesktopWindowsDxgiAdapters.luidHex(1, 0xb95ad))
        assertEquals("ffffffffffffffff", DesktopWindowsDxgiAdapters.luidHex(-1, -1))
        assertEquals("0000000180000000", DesktopWindowsDxgiAdapters.luidHex(1, Int.MIN_VALUE))
        assertEquals("0000000000000000", DesktopWindowsDxgiAdapters.luidHex(0, 0))
    }

    @Test fun detailSoftwareRuleIsExactlyTheExistingAdapterRule() {
        val flagsList = listOf(0, 1, 2, 3, 6, -1)
        for ((vendor, device) in listOf(0x1414 to 0x008c, 0x1414 to 0x008d, 0x1234 to 0x008c, 0x10de to 0x2c02)) {
            for (flags in flagsList) {
                val detail = DesktopWindowsDxgiAdapters.AdapterDetail(vendor, device, 0, 0, 0L, 1, 0, flags, "fixture", -1)
                assertEquals(adapter(vendor, device, flags).isSoftware(), detail.isSoftware(), "$vendor:$device flags=$flags")
            }
        }
        assertTrue(DesktopWindowsDxgiAdapters.AdapterDetail(0x1414, 0x008c, 0, 0, 0L, 1, 0, 0, "x", -1).isSoftware())
        assertFalse(DesktopWindowsDxgiAdapters.AdapterDetail(0x10de, 0x2c02, 0, 0, 0L, 1, 0, 0, "x", -1).isSoftware())
    }

    @Test fun withOutputCountChangesOnlyTheCount() {
        desc1().use { block ->
            val detail = DesktopWindowsDxgiAdapters.parseDesc1(block)
            val counted = detail.withOutputCount(2)
            assertEquals(2, counted.outputCount()); assertEquals(-1, detail.outputCount())
            assertEquals(detail.withOutputCount(2), counted)
            assertEquals(detail, counted.withOutputCount(-1))
            assertNotEquals(detail, counted)
        }
    }

    @Test fun detailedSnapshotCopiesTheCallerList() {
        desc1().use { block ->
            val captured = mutableListOf(DesktopWindowsDxgiAdapters.parseDesc1(block))
            val snapshot = DesktopWindowsDxgiAdapters.DetailedSnapshot(true, captured, null)
            captured += DesktopWindowsDxgiAdapters.parseDesc1(block).withOutputCount(1)
            assertEquals(1, snapshot.adapters().size)
            assertTrue(snapshot.complete())
        }
    }

    @Test fun existingFourArgumentAdapterAndSnapshotKeepTheirShape() {
        val fourArguments = DesktopWindowsDxgiAdapters.Adapter(0x10de, 0x2c02, 0, "NVIDIA GeForce RTX 5080")
        assertEquals(0x10de, fourArguments.vendorId()); assertEquals(0x2c02, fourArguments.deviceId())
        assertEquals(0, fourArguments.flags()); assertEquals("NVIDIA GeForce RTX 5080", fourArguments.description())
        assertFalse(snapshot(fourArguments).useBitblt())
    }

    @Test fun detailedProbeAndDriverQueryAreUnknownWhereThereIsNoDxgi() {
        // On a Windows host the real call would enumerate the real adapters, so only other hosts run it.
        if (Platform.isWindows()) return
        val detailed = DesktopWindowsDxgiAdapters.probeDetailed()
        assertFalse(detailed.complete()); assertTrue(detailed.adapters().isEmpty())
        assertEquals("non-windows", detailed.error())
        assertEquals(0L, DesktopWindowsDxgiAdapters.umdVersionFor(0xe782, 0))
    }
}

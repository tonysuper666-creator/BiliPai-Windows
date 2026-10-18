package com.bilipai.desktop.nvidia

import com.bilipai.desktop.player.NvidiaVideoBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopNvidiaStatusTextTest {
    private val gpu = "NVIDIA GeForce RTX 5080"
    private val separator = DesktopNvidiaStatusText.SEPARATOR

    private fun line(
        kind: DesktopNvidiaStatusKind,
        route: NvidiaVideoBackend = NvidiaVideoBackend.VEYRA_CORE,
        tier: NvidiaTier? = NvidiaTier.ULTRA,
        tierWordEnabled: Boolean = false,
        crossAdapter: Boolean = false,
        hdrPresented: Boolean = false,
        reason: NvidiaUnsupportedReason? = null,
        qualityLevel: Int? = null,
    ): String = desktopNvidiaStatusLine(
        DesktopNvidiaStatusInput(
            kind = kind, gpuDescription = gpu, route = route, tier = tier, unsupportedReason = reason,
            crossAdapter = crossAdapter, inputWidth = 1920, inputHeight = 1080, outputWidth = 2880, outputHeight = 1620,
            hdrPresented = hdrPresented, qualityLevel = qualityLevel,
        ),
        tierWordEnabled,
    )

    @Test fun shortNameStripsVendorAndFamilyPrefixes() {
        assertEquals("RTX 5080", desktopNvidiaGpuShortName("NVIDIA GeForce RTX 5080"))
        assertEquals("RTX 5080", desktopNvidiaGpuShortName("GeForce RTX 5080"))
        assertEquals("RTX A2000", desktopNvidiaGpuShortName("NVIDIA RTX A2000"))
        assertEquals("RTX 5080 Laptop GPU", desktopNvidiaGpuShortName("  nvidia   geforce  RTX 5080 Laptop GPU "))
        assertEquals("NVIDIAX 1", desktopNvidiaGpuShortName("NVIDIAX 1"), "only whole words are stripped")
        assertEquals("RTX 5080", desktopNvidiaGpuShortName("NVIDIA GeForce\u0001RTX 5080"))
        assertEquals(DesktopNvidiaStatusText.DEFAULT_GPU_LABEL, desktopNvidiaGpuShortName(null))
        assertEquals(DesktopNvidiaStatusText.DEFAULT_GPU_LABEL, desktopNvidiaGpuShortName("NVIDIA GeForce"))
        assertEquals(DesktopNvidiaStatusText.DEFAULT_GPU_LABEL, desktopNvidiaGpuShortName("   "))
    }

    @Test fun shippedSwitchKeepsTierNamesOutOfEveryLine() {
        assertFalse(TIER_WORD_ENABLED)
        val word = assertNotNull(desktopNvidiaTierWord(NvidiaTier.ULTRA, NvidiaVideoBackend.VEYRA_CORE, true))
        for (kind in DesktopNvidiaStatusKind.entries) {
            assertFalse(line(kind).contains(word), kind.name)
        }
        val withWord = line(DesktopNvidiaStatusKind.ACTIVE, tierWordEnabled = true)
        assertTrue(withWord.startsWith("RTX 5080" + separator + word))
    }

    @Test fun everyTierGetsItsOwnWordOnlyOnTheSharedCoreRoute() {
        val words = NvidiaTier.entries.map { desktopNvidiaTierWord(it, NvidiaVideoBackend.VEYRA_CORE, true) }
        assertEquals(4, words.toSet().size)
        for (word in words) assertNotNull(word)
        for (tier in NvidiaTier.entries) {
            assertNull(desktopNvidiaTierWord(tier, NvidiaVideoBackend.DRIVER, true))
            assertNull(desktopNvidiaTierWord(tier, NvidiaVideoBackend.VEYRA_CORE, false))
        }
    }

    @Test fun activeLineOnTheSharedCoreShowsSizesAndHdrOnlyWhenPresented() {
        val plain = line(DesktopNvidiaStatusKind.ACTIVE)
        assertEquals("RTX 5080" + separator + DesktopNvidiaStatusText.ACTIVE_VEYRA_TAIL + "（1920×1080 → 2880×1620）", plain)
        val hdr = line(DesktopNvidiaStatusKind.ACTIVE, hdrPresented = true)
        assertEquals(plain + DesktopNvidiaStatusText.ACTIVE_HDR_SUFFIX, hdr)
        val noSizes = desktopNvidiaStatusLine(DesktopNvidiaStatusInput(DesktopNvidiaStatusKind.ACTIVE, gpu, NvidiaVideoBackend.VEYRA_CORE))
        assertEquals("RTX 5080" + separator + DesktopNvidiaStatusText.ACTIVE_VEYRA_TAIL, noSizes)
    }

    @Test fun driverRouteNeverClaimsAConfirmedResult() {
        val active = line(DesktopNvidiaStatusKind.ACTIVE, route = NvidiaVideoBackend.DRIVER, hdrPresented = true)
        assertEquals("RTX 5080" + separator + DesktopNvidiaStatusText.ACTIVE_DRIVER_TAIL, active)
        assertTrue(active.contains("无法确认"))
        for (kind in listOf(DesktopNvidiaStatusKind.PENDING, DesktopNvidiaStatusKind.SUBMITTED, DesktopNvidiaStatusKind.PROOF_DELAYED)) {
            assertEquals(
                "RTX 5080" + separator + DesktopNvidiaStatusText.DRIVER_NOT_ACTIVE_TAIL,
                line(kind, route = NvidiaVideoBackend.DRIVER), kind.name,
            )
        }
        assertEquals("RTX 5080" + separator + DesktopNvidiaStatusText.PENDING_TAIL, line(DesktopNvidiaStatusKind.PENDING))
        assertEquals("RTX 5080" + separator + DesktopNvidiaStatusText.SUBMITTED_TAIL, line(DesktopNvidiaStatusKind.SUBMITTED))
        assertEquals("RTX 5080" + separator + DesktopNvidiaStatusText.PROOF_DELAYED_TAIL, line(DesktopNvidiaStatusKind.PROOF_DELAYED))
    }

    @Test fun crossAdapterSuffixOnlyOnTheLivePipelineLines() {
        val suffix = DesktopNvidiaStatusText.CROSS_ADAPTER_SUFFIX
        for (kind in listOf(
            DesktopNvidiaStatusKind.STANDBY, DesktopNvidiaStatusKind.WAITING, DesktopNvidiaStatusKind.DIRECT,
            DesktopNvidiaStatusKind.OVER_BUDGET, DesktopNvidiaStatusKind.PENDING, DesktopNvidiaStatusKind.SUBMITTED,
            DesktopNvidiaStatusKind.ACTIVE, DesktopNvidiaStatusKind.STEPPED_DOWN,
        )) {
            assertTrue(line(kind, crossAdapter = true).endsWith(suffix), kind.name)
            assertFalse(line(kind, crossAdapter = false).contains(suffix), kind.name)
        }
        for (kind in listOf(DesktopNvidiaStatusKind.FAILED_REMEMBERED, DesktopNvidiaStatusKind.PAUSED_GAME, DesktopNvidiaStatusKind.OFF)) {
            assertFalse(line(kind, crossAdapter = true).contains(suffix), kind.name)
        }
    }

    @Test fun steppedDownLineCarriesTheLevelAndNeverMoreThanFour() {
        assertEquals(
            "RTX 5080" + separator + "已自动降至质量 2/4 以保持流畅（本视频内保持）",
            line(DesktopNvidiaStatusKind.STEPPED_DOWN, qualityLevel = 2),
        )
        assertTrue(line(DesktopNvidiaStatusKind.STEPPED_DOWN, qualityLevel = 9).contains("4/4"))
        assertTrue(line(DesktopNvidiaStatusKind.STEPPED_DOWN, qualityLevel = 0).contains("1/4"))
    }

    @Test fun requestedDetailExistsOnlyOnTheSharedCoreRouteAndSaysRequested() {
        val text = assertNotNull(desktopNvidiaRequestedDetail(NvidiaVideoBackend.VEYRA_CORE, 4, "完整"))
        assertEquals("已请求：质量 4/4 · 强度 完整", text)
        assertNull(desktopNvidiaRequestedDetail(NvidiaVideoBackend.DRIVER, 4, "完整"))
        assertTrue(DesktopNvidiaStatusText.requestedDetail(7, "x").contains("4/4"))
    }

    @Test fun unsupportedReasonsHaveTheirOwnWording() {
        val preRtx = line(DesktopNvidiaStatusKind.UNSUPPORTED, reason = NvidiaUnsupportedReason.PRE_RTX)
        assertEquals(DesktopNvidiaStatusText.preRtx("RTX 5080"), preRtx)
        assertTrue(preRtx.contains("RTX 20"))
        assertEquals(DesktopNvidiaStatusText.driverOld("RTX 5080"), line(DesktopNvidiaStatusKind.UNSUPPORTED, reason = NvidiaUnsupportedReason.DRIVER_OLD))
        assertEquals(DesktopNvidiaStatusText.vramLow("RTX 5080"), line(DesktopNvidiaStatusKind.UNSUPPORTED, reason = NvidiaUnsupportedReason.VRAM_LOW))
        assertEquals(DesktopNvidiaStatusText.NOT_PHYSICAL, line(DesktopNvidiaStatusKind.UNSUPPORTED, reason = NvidiaUnsupportedReason.NOT_PHYSICAL))
        for (reason in listOf(NvidiaUnsupportedReason.PIN_UNSAFE, NvidiaUnsupportedReason.PHYSICAL_UNVERIFIED, null)) {
            assertEquals(DesktopNvidiaStatusText.PIN_UNSAFE, line(DesktopNvidiaStatusKind.UNSUPPORTED, reason = reason))
        }
    }

    @Test fun onlyTheBlackwellDriverTextPrintsAVersionNumber() {
        assertTrue(DesktopNvidiaStatusText.driverOld("RTX 5080").contains(formatNvidiaDriverPublic(NVIDIA_BLACKWELL_MIN_DRIVER_X100)))
        val versionLike = Regex("\\d{3}\\.\\d{2}")
        assertFalse(versionLike.containsMatchIn(DesktopNvidiaStatusText.DRIVER_ADVISORY))
        assertFalse(versionLike.containsMatchIn(DesktopNvidiaStatusText.HDR_DRIVER_OLD))
        for (kind in DesktopNvidiaStatusKind.entries) {
            if (kind == DesktopNvidiaStatusKind.UNSUPPORTED) continue
            assertFalse(versionLike.containsMatchIn(line(kind)), kind.name)
        }
    }

    @Test fun offLineDependsOnWhetherTheGpuIsKnown() {
        assertEquals(
            "RTX 5080" + separator + DesktopNvidiaStatusText.OFF_GPU_KNOWN_TAIL, line(DesktopNvidiaStatusKind.OFF),
        )
        assertEquals(
            DesktopNvidiaStatusText.OFF_GPU_UNKNOWN,
            desktopNvidiaStatusLine(DesktopNvidiaStatusInput(DesktopNvidiaStatusKind.OFF)),
        )
        assertEquals(
            DesktopNvidiaStatusText.OFF_GPU_UNKNOWN,
            desktopNvidiaStatusLine(DesktopNvidiaStatusInput(DesktopNvidiaStatusKind.OFF, gpuDescription = "  ")),
        )
    }

    @Test fun switchSubtitleFollowsCapability() {
        assertEquals(DesktopNvidiaStatusText.SWITCH_SUBTITLE_CAPABLE, desktopNvidiaSwitchSubtitle(true))
        assertEquals(DesktopNvidiaStatusText.SWITCH_SUBTITLE_NOT_CAPABLE, desktopNvidiaSwitchSubtitle(false))
    }
}

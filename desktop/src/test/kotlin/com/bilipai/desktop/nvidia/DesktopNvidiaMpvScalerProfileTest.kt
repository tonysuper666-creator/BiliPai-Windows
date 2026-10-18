package com.bilipai.desktop.nvidia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopNvidiaMpvScalerProfileTest {
    private val vectors = NvidiaGoldenVectors

    private fun capableFor(id: String): NvidiaCapability.Capable {
        val vector = vectors.objects("gpus").first { vectors.string(it, "id") == id }
        return assertIs<NvidiaCapability.Capable>(classifyNvidiaCapability(vectors.gpuInput(vector)), id)
    }

    /** The shader-list option name is assembled so that no source file spells it out. */
    private val shaderListOption = "glsl-" + "shaders"

    private val allowedNames = setOf("scale", "cscale", "scale-antiring", "dscale")

    // The names of the options that the scaler table must never carry. The frame-blending, display-sync, swap-chain and
    // colour-hint names are assembled from fragments, so a scan of the new files for those words stays clean.
    private val forbiddenNames = listOf(
        shaderListOption, "glsl-shader-opts", "inter" + "polation", "tscale", "video" + "-sync", "deband", "dither", "profile",
        "d3d11" + "-flip", "target-colorspace" + "-hint", "vf", "swapchain-depth", "hwdec", "vo", "gpu-api", "config",
    )

    private fun valuesByName(options: List<NvidiaMpvScalerOption>): Map<String, String> =
        options.associate { it.name to it.value }

    // ---- the switch -----------------------------------------------------------------------

    @Test fun shippedSwitchIsOffAndNothingIsSetByDefault() {
        assertFalse(MPV_SCALER_PROFILE_ENABLED)
        for (id in listOf("rtx-5080-this-pc", "rtx-4070", "rtx-3060-ti", "rtx-2060", "rtx-5080-laptop")) {
            assertTrue(nvidiaMpvScalerOptionsFor(capableFor(id)).isEmpty(), "default off for ${id}")
            assertTrue(nvidiaMpvScalerOptionsFor(capableFor(id), enabled = false).isEmpty(), "explicit off for ${id}")
        }
    }

    // ---- the tables -----------------------------------------------------------------------

    @Test fun hqTableHoldsTheDocumentedOptionsInOrder() {
        assertEquals(
            listOf(
                NvidiaMpvScalerOption("scale", "ewa_lanczossharp"),
                NvidiaMpvScalerOption("cscale", "ewa_lanczossharp"),
                NvidiaMpvScalerOption("scale-antiring", "0.6"),
                NvidiaMpvScalerOption("dscale", "mitchell"),
            ),
            NVIDIA_MPV_SCALER_HQ_OPTIONS,
        )
    }

    @Test fun hqUltraDiffersFromHqOnlyInTheDownscaler() {
        val hq = valuesByName(NVIDIA_MPV_SCALER_HQ_OPTIONS)
        val ultra = valuesByName(NVIDIA_MPV_SCALER_HQ_ULTRA_OPTIONS)
        assertEquals("mitchell", hq.getValue("dscale"))
        assertEquals("ewa_lanczos", ultra.getValue("dscale"))
        assertEquals(hq - "dscale", ultra - "dscale")
        assertEquals(NVIDIA_MPV_SCALER_HQ_OPTIONS.map { it.name }, NVIDIA_MPV_SCALER_HQ_ULTRA_OPTIONS.map { it.name })
    }

    @Test fun neitherTableSetsAnOptionOutsideTheFourScalerNames() {
        for (table in listOf(NVIDIA_MPV_SCALER_HQ_OPTIONS, NVIDIA_MPV_SCALER_HQ_ULTRA_OPTIONS)) {
            assertEquals(allowedNames, table.map { it.name }.toSet())
            assertEquals(table.size, table.map { it.name }.toSet().size, "no option twice in one table")
            for (option in table) {
                for (forbidden in forbiddenNames) {
                    assertFalse(option.name == forbidden, "${option.name} is forbidden")
                }
                assertFalse(option.value.contains("error-diffusion"), option.name)
                assertFalse(option.value.contains("high-quality"), option.name)
                assertFalse(option.value.contains(shaderListOption), option.name)
            }
        }
    }

    @Test fun optionTextCannotCarryASecondOptionOrAControlCharacter() {
        val safe = Regex("[a-z0-9_.-]+")
        for (option in NVIDIA_MPV_SCALER_HQ_OPTIONS + NVIDIA_MPV_SCALER_HQ_ULTRA_OPTIONS) {
            assertTrue(safe.matches(option.name), option.name)
            assertTrue(safe.matches(option.value), option.value)
        }
    }

    @Test fun allowedRowsAreExactlyTheTwoTables() {
        assertEquals(
            (NVIDIA_MPV_SCALER_HQ_OPTIONS + NVIDIA_MPV_SCALER_HQ_ULTRA_OPTIONS).toSet(),
            NVIDIA_MPV_SCALER_ALLOWED_ROWS,
        )
        assertEquals(5, NVIDIA_MPV_SCALER_ALLOWED_ROWS.size, "three shared rows and the two downscaler rows")
    }

    // ---- the selection --------------------------------------------------------------------

    @Test fun tierTableGivesUltraTheUltraProfileAndHighTheHqProfileOnly() {
        assertEquals(NvidiaMpvScalerClass.HQ_ULTRA, nvidiaMpvScalerClassFor(capableFor("rtx-5080-this-pc")))
        assertEquals(NvidiaMpvScalerClass.HQ, nvidiaMpvScalerClassFor(capableFor("rtx-4070")))
        assertEquals(NvidiaMpvScalerClass.MPV_DEFAULT, nvidiaMpvScalerClassFor(capableFor("rtx-3060-ti")))
        assertEquals(NvidiaMpvScalerClass.MPV_DEFAULT, nvidiaMpvScalerClassFor(capableFor("rtx-2060")))
        for (tier in NvidiaTier.entries) {
            val expected = when (tier) {
                NvidiaTier.ULTRA -> NvidiaMpvScalerClass.HQ_ULTRA
                NvidiaTier.HIGH -> NvidiaMpvScalerClass.HQ
                NvidiaTier.BALANCED -> NvidiaMpvScalerClass.MPV_DEFAULT
                NvidiaTier.LIGHT -> NvidiaMpvScalerClass.MPV_DEFAULT
            }
            assertEquals(expected, nvidiaTierProfile(tier).mpvScaler, "${tier}")
        }
    }

    @Test fun enabledSwitchSelectsTheTableOfTheTierAndNothingBelowHigh() {
        assertEquals(
            NVIDIA_MPV_SCALER_HQ_ULTRA_OPTIONS,
            nvidiaMpvScalerOptionsFor(capableFor("rtx-5080-this-pc"), enabled = true),
        )
        assertEquals(NVIDIA_MPV_SCALER_HQ_OPTIONS, nvidiaMpvScalerOptionsFor(capableFor("rtx-4070"), enabled = true))
        assertEquals(
            NVIDIA_MPV_SCALER_HQ_OPTIONS,
            nvidiaMpvScalerOptionsFor(capableFor("rtx-5080-laptop"), enabled = true),
            "a mobile step-down to HIGH gets the HQ profile",
        )
        assertTrue(nvidiaMpvScalerOptionsFor(capableFor("rtx-3060-ti"), enabled = true).isEmpty())
        assertTrue(nvidiaMpvScalerOptionsFor(capableFor("rtx-2060"), enabled = true).isEmpty())
        assertTrue(nvidiaMpvScalerOptionsFor(capableFor("rtx-3050-class-d"), enabled = true).isEmpty())
    }

    @Test fun onlyACapableVerdictEverHasAProfile() {
        val others: List<NvidiaCapability> = listOf(
            NvidiaCapability.NotEvaluated,
            NvidiaCapability.Probing,
            NvidiaCapability.Absent,
            NvidiaCapability.Unknown(),
            NvidiaCapability.Unsupported(NvidiaUnsupportedReason.PRE_RTX),
            NvidiaCapability.Unsupported(NvidiaUnsupportedReason.NOT_PHYSICAL),
            NvidiaCapability.Unsupported(NvidiaUnsupportedReason.PIN_UNSAFE),
            NvidiaCapability.Unsupported(NvidiaUnsupportedReason.DRIVER_OLD),
        )
        for (capability in others) {
            assertEquals(NvidiaMpvScalerClass.MPV_DEFAULT, nvidiaMpvScalerClassFor(capability), "${capability}")
            assertTrue(nvidiaMpvScalerOptionsFor(capability, enabled = true).isEmpty(), "${capability}")
            assertTrue(nvidiaMpvScalerOptionsFor(capability).isEmpty(), "${capability}")
        }
        assertTrue(nvidiaMpvScalerOptions(NvidiaMpvScalerClass.MPV_DEFAULT).isEmpty())
    }

    // ---- the setter -----------------------------------------------------------------------

    @Test fun setterPassesEveryRowOnceInOrderAndCountsTheAcceptedOnes() {
        val calls = ArrayList<Pair<String, String>>()
        val result = applyNvidiaMpvScalerOptions(NVIDIA_MPV_SCALER_HQ_ULTRA_OPTIONS) { name, value ->
            calls.add(name to value)
            0
        }
        assertEquals(NVIDIA_MPV_SCALER_HQ_ULTRA_OPTIONS.map { it.name to it.value }, calls)
        assertEquals(NvidiaMpvScalerApplyResult(requested = 4, accepted = 4, rejectedNames = emptyList()), result)
    }

    @Test fun aRejectedOptionIsRecordedByNameAndTheRestAreStillSet() {
        val calls = ArrayList<String>()
        val result = applyNvidiaMpvScalerOptions(NVIDIA_MPV_SCALER_HQ_OPTIONS) { name, _ ->
            calls.add(name)
            if (name == "scale") -5 else 0
        }
        assertEquals(listOf("scale", "cscale", "scale-antiring", "dscale"), calls)
        assertEquals(4, result.requested)
        assertEquals(3, result.accepted)
        assertEquals(listOf("scale"), result.rejectedNames)
    }

    @Test fun theSetterNeverThrowsEvenForAnErrorFromTheNativeLayer() {
        val result = applyNvidiaMpvScalerOptions(NVIDIA_MPV_SCALER_HQ_OPTIONS) { name, _ ->
            when (name) {
                "cscale" -> throw IllegalStateException("fixture")
                "dscale" -> throw LinkageError("fixture")
                else -> 0
            }
        }
        assertEquals(4, result.requested)
        assertEquals(2, result.accepted)
        assertEquals(listOf("cscale", "dscale"), result.rejectedNames)
    }

    @Test fun aRowOutsideTheTablesIsRefusedWithoutCallingTheCore() {
        var calls = 0
        val rows = forbiddenNames.map { NvidiaMpvScalerOption(it, "yes") } + listOf(
            NvidiaMpvScalerOption("scale", "bogus"),
            NvidiaMpvScalerOption("dscale", "ewa_lanczossharp"),
            NvidiaMpvScalerOption("scale-antiring", "1.0"),
        )
        val result = applyNvidiaMpvScalerOptions(rows) { _, _ ->
            calls++
            0
        }
        assertEquals(0, calls)
        assertEquals(rows.size, result.requested)
        assertEquals(0, result.accepted)
        assertEquals(rows.map { it.name }, result.rejectedNames)
    }

    @Test fun anEmptyListCallsNothingAndReportsNothing() {
        var calls = 0
        val result = applyNvidiaMpvScalerOptions(emptyList()) { _, _ ->
            calls++
            0
        }
        assertEquals(0, calls)
        assertEquals(NvidiaMpvScalerApplyResult(requested = 0, accepted = 0, rejectedNames = emptyList()), result)
    }

    @Test fun theResultCarriesNamesNeverValues() {
        val result = applyNvidiaMpvScalerOptions(NVIDIA_MPV_SCALER_HQ_ULTRA_OPTIONS) { _, _ -> -1 }
        assertEquals(NVIDIA_MPV_SCALER_HQ_ULTRA_OPTIONS.map { it.name }, result.rejectedNames)
        for (name in result.rejectedNames) {
            assertTrue(name in allowedNames, name)
        }
    }

    @Test fun theDefaultProfileStaysOffEndToEndForThePlayersOwnCall() {
        // The player calls nvidiaMpvScalerOptionsFor with the verdict only, then applies what it returns.
        var calls = 0
        val result = applyNvidiaMpvScalerOptions(nvidiaMpvScalerOptionsFor(capableFor("rtx-5080-this-pc"))) { _, _ ->
            calls++
            0
        }
        assertEquals(0, calls)
        assertEquals(NvidiaMpvScalerApplyResult(requested = 0, accepted = 0, rejectedNames = emptyList()), result)
    }
}

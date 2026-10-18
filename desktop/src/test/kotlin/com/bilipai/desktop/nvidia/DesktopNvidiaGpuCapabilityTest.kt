package com.bilipai.desktop.nvidia

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopNvidiaGpuCapabilityTest {
    private val vectors = NvidiaGoldenVectors

    private fun adapter(
        description: String,
        luidLow: Int,
        vendorId: Int = NVIDIA_VENDOR_ID,
        deviceId: Int = 0x2C02,
        outputs: Int = 1,
        physical: NvidiaPhysicalVerdict = NvidiaPhysicalVerdict.PHYSICAL,
        vramMiB: Long = 15_978L,
    ) = NvidiaAdapterEvidence(
        vendorId = vendorId,
        deviceId = deviceId,
        subSysId = 0x8A4A1043L.toInt(),
        revision = 0xA1,
        description = description,
        dedicatedVideoMemoryBytes = vramMiB * 1_048_576L,
        luidLow = luidLow,
        luidHigh = 0,
        flags = 0,
        outputCount = outputs,
        physical = physical,
    )

    private fun assertCapabilityMatches(expect: JsonObject, actual: NvidiaCapability, label: String) {
        when (vectors.string(expect, "kind")) {
            "capable" -> {
                val capable = assertIs<NvidiaCapability.Capable>(actual, label)
                assertEquals(NvidiaTier.valueOf(vectors.string(expect, "tier")), capable.tier, "$label tier")
                val expectedClass = vectors.stringOrNull(expect, "skuClass")?.let { NvidiaSkuClass.valueOf(it) }
                assertEquals(expectedClass, capable.sku.skuClass, "$label class")
                assertEquals(vectors.strings(expect, "notes").toSet(), capable.notes.map { it.name }.toSet(), "$label notes")
                val plan = buildNvidiaPlan(capable)
                if (expect.containsKey("generation")) {
                    assertEquals(
                        NvidiaGeneration.valueOf(vectors.string(expect, "generation")), capable.gpu.generation, "$label generation",
                    )
                    assertEquals(vectors.double(expect, "costFactor"), capable.sku.costFactor, "$label cost")
                    assertEquals(vectors.int(expect, "qualityCeiling"), plan.tierQuality, "$label quality")
                    assertEquals(vectors.bool(expect, "mobile"), capable.gpu.mobile, "$label mobile")
                }
                if (expect.containsKey("hdrDriverOk")) {
                    assertEquals(vectors.bool(expect, "hdrDriverOk"), plan.driverHdrOk, "$label hdr")
                }
                if (expect.containsKey("primaryLuid")) {
                    assertEquals(vectors.string(expect, "primaryLuid"), capable.gpu.luidHex, "$label luid")
                    assertEquals(vectors.string(expect, "pin"), vectors.pinText(capable.pin), "$label pin")
                    assertEquals(
                        vectors.bool(expect, "crossAdapter"), NvidiaCapabilityNote.CROSS_ADAPTER in capable.notes, "$label cross",
                    )
                }
            }
            "unsupported" -> {
                val unsupported = assertIs<NvidiaCapability.Unsupported>(actual, label)
                assertEquals(NvidiaUnsupportedReason.valueOf(vectors.string(expect, "reason")), unsupported.reason, label)
            }
            "absent" -> assertIs<NvidiaCapability.Absent>(actual, label)
            "unknown" -> {
                val unknown = assertIs<NvidiaCapability.Unknown>(actual, label)
                assertEquals(NvidiaUnknownReason.valueOf(vectors.string(expect, "reason")), unknown.reason, label)
            }
            else -> throw AssertionError("Unknown expectation kind in $label")
        }
    }

    // ---- shared golden vectors ------------------------------------------------------------

    @Test fun driverNumbersConvertLikeTheGoldenVectors() {
        for (item in vectors.objects("driverConversions")) {
            val fields = requireNotNull(vectors.ints(item, "fields"))
            val expected = vectors.stringOrNull(item, "publicX100")?.toInt()
            val actual = nvidiaDriverPublicX100(fields[0], fields[1], fields[2], fields[3])
            assertEquals(expected, actual, fields.toString())
            if (actual != null) assertEquals(vectors.string(item, "text"), formatNvidiaDriverPublic(actual))
        }
        assertEquals("617.42", formatNvidiaDriverPublic(61_742))
        assertEquals("546.01", formatNvidiaDriverPublic(54_601))
        assertEquals("576.02", formatNvidiaDriverPublic(57_602))
    }

    @Test fun packedDriverVersionsConvertLikeTheirFields() {
        for (item in vectors.objects("packedDrivers")) {
            val fields = requireNotNull(vectors.ints(item, "fields"))
            val packed = vectors.pack(fields)
            assertEquals(vectors.string(item, "packed"), packed.toString())
            assertEquals(vectors.string(item, "publicX100").toInt(), nvidiaDriverPublicX100FromPacked(packed))
        }
        assertNull(nvidiaDriverPublicX100FromPacked(0L))
        assertNull(nvidiaDriverPublicX100FromPacked(-1L))
    }

    @Test fun adapterPathsClassifyLikeTheGoldenVectors() {
        for (item in vectors.objects("paths")) {
            val deviceId = vectors.hex(vectors.string(item, "deviceId"))
            val verdict = classifyNvidiaAdapterPath(vectors.stringOrNull(item, "path"), deviceId)
            assertEquals(NvidiaPhysicalVerdict.valueOf(vectors.string(item, "verdict")), verdict, item.toString())
        }
    }

    @Test fun luidComparisonAcceptsEitherHalfOrderAndRejectsMalformedText() {
        for (item in vectors.objects("luidMatches")) {
            val expected = vectors.string(item, "expected")
            val observed = vectors.stringOrNull(item, "observed")
            assertEquals(vectors.bool(item, "match"), luidMatches(expected, observed), item.toString())
        }
        assertTrue(luidMatches(0, 0xE782, "0000e78200000000"))
        assertTrue(luidMatches(0, 0xE782, "000000000000E782"))
        assertFalse(luidMatches(0, 0xE782, "000000000000e783"))
        assertEquals("000000000000e782", nvidiaLuidHex(0, 0xE782))
        assertEquals("00000001ffffffff", nvidiaLuidHex(1, -1))
    }

    @Test fun everyGpuVectorClassifiesAsExpected() {
        val all = vectors.objects("gpus")
        assertTrue(all.size >= 60, "the vector set must stay broad")
        for (vector in all) {
            val id = vectors.string(vector, "id")
            val actual = classifyNvidiaCapability(vectors.gpuInput(vector))
            assertCapabilityMatches(vector.getValue("expect").jsonObject, actual, id)
        }
    }

    @Test fun vramLadderSelectsTheDocumentedTierForEveryClass() {
        for (ladder in vectors.objects("vramLadder")) {
            val label = vectors.string(ladder, "skuClass")
            for (row in vectors.objectsOf(ladder.getValue("rows"))) {
                val vramMiB = vectors.long(row, "vramMiB")
                val evidence = adapter(
                    vectors.string(ladder, "description"), 0x1000, deviceId = vectors.hex(vectors.string(ladder, "deviceId")),
                    vramMiB = vramMiB,
                )
                val actual = classifyNvidiaCapability(NvidiaProbeInput(true, listOf(evidence), evidence.luidHex))
                val expected = vectors.string(row, "expect")
                if (expected == "VRAM_LOW") {
                    val unsupported = assertIs<NvidiaCapability.Unsupported>(actual, "$label $vramMiB")
                    assertEquals(NvidiaUnsupportedReason.VRAM_LOW, unsupported.reason, "$label $vramMiB")
                } else {
                    val capable = assertIs<NvidiaCapability.Capable>(actual, "$label $vramMiB")
                    assertEquals(NvidiaTier.valueOf(expected), capable.tier, "$label $vramMiB")
                }
            }
        }
    }

    @Test fun unknownModelIsTieredByVramAndNeverAboveBalanced() {
        for (row in vectors.objects("unknownModelLadder")) {
            val vramMiB = vectors.long(row, "vramMiB")
            val evidence = adapter("NVIDIA Graphics Device", 0x1000, deviceId = 0x1234, vramMiB = vramMiB)
            val actual = classifyNvidiaCapability(NvidiaProbeInput(true, listOf(evidence), evidence.luidHex))
            val expected = vectors.string(row, "expect")
            if (expected == "VRAM_LOW") {
                assertEquals(NvidiaUnsupportedReason.VRAM_LOW, assertIs<NvidiaCapability.Unsupported>(actual).reason)
            } else {
                assertEquals(NvidiaTier.valueOf(expected), assertIs<NvidiaCapability.Capable>(actual).tier, "$vramMiB")
            }
        }
    }

    @Test fun everyScenarioClassifiesAndActivatesAsExpected() {
        val all = vectors.objects("scenarios")
        assertTrue(all.size >= 20, "the scenario set must stay broad")
        for (scenario in all) {
            val id = vectors.string(scenario, "id")
            val capability = classifyNvidiaCapability(vectors.scenarioInput(scenario))
            assertCapabilityMatches(scenario.getValue("expect").jsonObject, capability, id)
            for (activation in vectors.objectsOf(scenario.getValue("activations"))) {
                val env = activation["env"]?.jsonObject
                val state = resolveDesktopNvidiaActivation(
                    mode = if (vectors.stringOrNull(activation, "mode") == "OFF") DesktopNvidiaMode.OFF else DesktopNvidiaMode.AUTO,
                    capability = capability,
                    environment = DesktopNvidiaEnvironment(
                        gameLike = env != null && vectors.bool(env, "game"),
                        onBattery = env != null && vectors.bool(env, "battery"),
                        remoteOrVirtualOutput = env != null && vectors.bool(env, "remote"),
                    ),
                    observedGpu = vectors.observedGpu(activation["observed"]?.jsonObject),
                    sourceLoaded = vectors.bool(activation, "sourceLoaded"),
                    failureRemembered = vectors.bool(activation, "failureRemembered"),
                    extendedRangeFailedForSource = vectors.bool(activation, "extendedRangeFailed"),
                    guardGaveUpForSource = vectors.bool(activation, "guardGaveUpForSource"),
                )
                val label = "$id $activation"
                assertEquals(vectors.string(activation, "state"), vectors.activationName(state), label)
                assertEquals(vectors.bool(activation, "request"), state is DesktopNvidiaActivationState.Allowed, label)
                assertEquals(vectors.bool(activation, "request"), state.allowsRequest, label)
            }
        }
    }

    // ---- hand written cases, independent of the vector file -------------------------------

    @Test fun professionalNamesAreParsedBeforeTheGeForcePattern() {
        val ada = assertNotNull(parseNvidiaAdapterName("NVIDIA RTX 2000 Ada Generation"))
        assertEquals(NvidiaGeneration.ADA, ada.generation)
        assertTrue(ada.professional)
        assertNull(ada.modelNumber)
        val smallFormFactor = assertNotNull(parseNvidiaAdapterName("NVIDIA RTX 4000 SFF Ada Generation"))
        assertEquals(NvidiaGeneration.ADA, smallFormFactor.generation)
        val quadro = assertNotNull(parseNvidiaAdapterName("NVIDIA Quadro RTX 5000"))
        assertEquals(NvidiaGeneration.TURING, quadro.generation)
        assertTrue(quadro.professional)
        val ampere = assertNotNull(parseNvidiaAdapterName("NVIDIA RTX A2000 Laptop GPU"))
        assertEquals(NvidiaGeneration.AMPERE, ampere.generation)
        assertTrue(ampere.mobile)
        val pro = assertNotNull(parseNvidiaAdapterName("NVIDIA RTX PRO 6000 Blackwell Workstation Edition"))
        assertEquals(NvidiaGeneration.BLACKWELL, pro.generation)
        assertTrue(pro.professional)
    }

    @Test fun geForceNamesYieldBaseModelAndModifiers() {
        val ti = assertNotNull(parseNvidiaAdapterName("NVIDIA GeForce RTX 4070 Ti SUPER"))
        assertEquals(4070, ti.modelNumber)
        assertTrue(ti.tiVariant)
        assertEquals(NvidiaGeneration.ADA, ti.generation)
        val super5080 = assertNotNull(parseNvidiaAdapterName("NVIDIA GeForce RTX 5080 SUPER"))
        assertEquals(5080, super5080.modelNumber)
        assertFalse(super5080.tiVariant)
        val chinaVariant = assertNotNull(parseNvidiaAdapterName("NVIDIA GeForce RTX 5090D"))
        assertEquals(5090, chinaVariant.modelNumber)
        val withMemory = assertNotNull(parseNvidiaAdapterName("NVIDIA GeForce RTX 3050 6GB"))
        assertEquals(3050, withMemory.modelNumber)
        assertFalse(withMemory.tiVariant)
        val maxQ = assertNotNull(parseNvidiaAdapterName("NVIDIA GeForce RTX 3080 Max-Q"))
        assertTrue(maxQ.mobile)
        val next = assertNotNull(parseNvidiaAdapterName("NVIDIA GeForce RTX 6090"))
        assertEquals(NvidiaGeneration.NEWER, next.generation)
    }

    @Test fun preRtxAndForeignNamesAreNeverRtx() {
        for (name in listOf("NVIDIA GeForce GTX 1660 SUPER", "NVIDIA GeForce MX450", "NVIDIA GeForce GT 1030", "Quadro P2000", "NVIDIA TITAN Xp")) {
            assertEquals(NvidiaGeneration.PRE_RTX, assertNotNull(parseNvidiaAdapterName(name), name).generation, name)
        }
        assertNull(parseNvidiaAdapterName("AMD Radeon(TM) Graphics"))
        assertNull(parseNvidiaAdapterName("   "))
        assertNull(parseNvidiaAdapterName("NVIDIA Graphics Device"))
    }

    @Test fun nameBeatsPciTableAndTableBeatsIdRange() {
        val byName = identifyNvidiaModel("NVIDIA GeForce GTX 1660", 0x2184)
        assertEquals(NvidiaGeneration.PRE_RTX, byName.generation)
        assertEquals(NvidiaIdentitySource.NAME, byName.source)
        val byTable = identifyNvidiaModel("NVIDIA Graphics Device", 0x2C02)
        assertEquals(NvidiaIdentitySource.PCI_ID, byTable.source)
        assertEquals(5080, byTable.modelNumber)
        val byRange = identifyNvidiaModel("NVIDIA Graphics Device", 0x2F99)
        assertEquals(NvidiaIdentitySource.ID_RANGE, byRange.source)
        assertEquals(NvidiaGeneration.BLACKWELL, byRange.generation)
        assertNull(byRange.modelNumber)
        val none = identifyNvidiaModel("NVIDIA Graphics Device", 0x1234)
        assertEquals(NvidiaIdentitySource.NONE, none.source)
        assertEquals(NvidiaGeneration.UNKNOWN, none.generation)
    }

    @Test fun skuLookupUsesTheTiRowOnlyWhenOneExists() {
        assertEquals(1.0, assertNotNull(lookupNvidiaSkuRow(4070, true)).costFactor)
        assertEquals(0.7, assertNotNull(lookupNvidiaSkuRow(4070, false)).costFactor)
        assertEquals(NvidiaSkuClass.S, assertNotNull(lookupNvidiaSkuRow(5070, true)).skuClass)
        assertEquals(NvidiaSkuClass.A, assertNotNull(lookupNvidiaSkuRow(5070, false)).skuClass)
        assertEquals(NvidiaSkuClass.A, assertNotNull(lookupNvidiaSkuRow(3080, true)).skuClass)
        assertEquals(NvidiaSkuClass.B, assertNotNull(lookupNvidiaSkuRow(2080, true)).skuClass)
        assertNull(lookupNvidiaSkuRow(4050, false))
    }

    @Test fun skuTableKeepsItsDocumentedInvariants() {
        for (row in NVIDIA_SKU_ROWS) {
            assertTrue(row.costFactor > 0.0 && row.costFactor <= 1.0, "cost ${row.model}")
            if (row.skuClass == NvidiaSkuClass.D) assertEquals(1, row.qualityCeilingOverride, "class D ${row.model}")
            if (row.skuClass != NvidiaSkuClass.D) assertNull(row.qualityCeilingOverride, "override ${row.model}")
        }
        val keys = NVIDIA_SKU_ROWS.map { it.model to it.ti }
        assertEquals(keys.size, keys.toSet().size, "one row per model and Ti flag")
        assertEquals(NvidiaGeneration.BLACKWELL, NVIDIA_PCI_MODELS.first { it.deviceId == 0x2C02 }.generation)
        assertEquals(NvidiaSkuClass.S, lookupNvidiaSkuRow(5080, false)?.skuClass)
    }

    @Test fun unlistedNewerModelIsAssumedClassAAndNeverUltra() {
        val sku = resolveNvidiaSku(NvidiaModelIdentity(NvidiaGeneration.NEWER, 6090, false, false, false, NvidiaIdentitySource.NAME))
        assertEquals(NvidiaSkuClass.A, sku.skuClass)
        assertTrue(sku.assumed)
        assertEquals(NvidiaTier.HIGH, nvidiaTierFor(sku, 48_000L))
        val professional = resolveNvidiaSku(NvidiaModelIdentity(NvidiaGeneration.ADA, null, false, true, false, NvidiaIdentitySource.NAME))
        assertEquals(NvidiaSkuClass.A, professional.skuClass)
        assertEquals(NvidiaTier.HIGH, nvidiaTierFor(professional, 98_000L))
    }

    @Test fun unknownVramCapsTheClassTierAtHigh() {
        val strong = resolveNvidiaSku(identifyNvidiaModel("NVIDIA GeForce RTX 5080", 0x2C02))
        assertEquals(NvidiaTier.HIGH, nvidiaTierFor(strong, null))
        val light = resolveNvidiaSku(identifyNvidiaModel("NVIDIA GeForce RTX 3060", 0x2504))
        assertEquals(NvidiaTier.LIGHT, nvidiaTierFor(light, null))
        val unknownModel = resolveNvidiaSku(identifyNvidiaModel("NVIDIA Graphics Device", 0x1234))
        assertNull(unknownModel.skuClass)
        assertEquals(NvidiaTier.LIGHT, nvidiaTierFor(unknownModel, null))
    }

    @Test fun stepDownSaturatesAtLight() {
        assertEquals(NvidiaTier.HIGH, NvidiaTier.ULTRA.stepDown())
        assertEquals(NvidiaTier.BALANCED, NvidiaTier.HIGH.stepDown())
        assertEquals(NvidiaTier.LIGHT, NvidiaTier.BALANCED.stepDown())
        assertEquals(NvidiaTier.LIGHT, NvidiaTier.LIGHT.stepDown())
        assertEquals(NvidiaTier.LIGHT, lowerNvidiaTier(NvidiaTier.ULTRA, NvidiaTier.LIGHT))
        assertEquals(NvidiaTier.HIGH, lowerNvidiaTier(NvidiaTier.ULTRA, NvidiaTier.HIGH))
    }

    @Test fun gpuKeyNeverContainsALuid() {
        assertEquals("10de:2c02:8a4a1043:a1", nvidiaGpuKey(0x2C02, 0x8A4A1043L.toInt(), 0xA1))
        assertEquals("10de:0001:00000002:03", nvidiaGpuKey(1, 2, 3))
        val capable = assertIs<NvidiaCapability.Capable>(
            classifyNvidiaCapability(vectors.gpuInput(vectors.objects("gpus").first { vectors.string(it, "id") == "rtx-5080-this-pc" })),
        )
        assertFalse(capable.gpu.gpuKey.contains(capable.gpu.luidHex))
    }

    @Test fun softwareAdaptersAreNeverCandidates() {
        assertTrue(nvidiaIsSoftwareAdapter(0x1414, 0x008C, 0))
        assertTrue(nvidiaIsSoftwareAdapter(0x1234, 0x5678, 2))
        assertFalse(nvidiaIsSoftwareAdapter(0x10DE, 0x2C02, 0))
        assertFalse(nvidiaIsSoftwareAdapter(0x1414, 0x008C, 3))
        val software = NvidiaAdapterEvidence(
            NVIDIA_VENDOR_ID, 0x2C02, 0, 0, "NVIDIA GeForce RTX 5080", 16_000L * 1_048_576L, 7, 0, 2, 1,
            NvidiaPhysicalVerdict.PHYSICAL,
        )
        assertIs<NvidiaCapability.Absent>(classifyNvidiaCapability(NvidiaProbeInput(true, listOf(software))))
    }

    @Test fun incompleteInventoryNeverClassifiesAnAdapter() {
        val good = adapter("NVIDIA GeForce RTX 5080", 1)
        val rejected = assertIs<NvidiaPrimarySelection.Rejected>(selectNvidiaPrimary(NvidiaProbeInput(false, listOf(good))))
        assertIs<NvidiaCapability.Unknown>(rejected.capability)
        val selected = assertIs<NvidiaPrimarySelection.Selected>(selectNvidiaPrimary(NvidiaProbeInput(true, listOf(good))))
        assertEquals(good, selected.primary)
        assertFalse(selected.assumedUnique)
    }

    @Test fun virtualSoundingNamesOnlyExcludeAndNeverInclude() {
        for (name in listOf(
            "NVIDIA GeForce RTX 5080 Virtual Display", "NVIDIA RTX Remote Adapter", "Indirect Display Device",
            "NVIDIA IDD Display", "Microsoft Basic Render Driver",
        )) {
            assertTrue(nvidiaNameLooksVirtual(name), name)
        }
        for (name in listOf("NVIDIA GeForce RTX 5080", "NVIDIA GeForce RTX 4070 Ti SUPER", "NVIDIA Quadro RTX 5000", "NVIDIA RTX A2000")) {
            assertFalse(nvidiaNameLooksVirtual(name), name)
        }
        val virtualName = adapter("NVIDIA GeForce RTX 5080 Virtual Display", 1)
        val byName = assertIs<NvidiaCapability.Unsupported>(classifyNvidiaCapability(NvidiaProbeInput(true, listOf(virtualName))))
        assertEquals(NvidiaUnsupportedReason.NOT_PHYSICAL, byName.reason)
        val ordinaryNameButVirtualPath = adapter("NVIDIA GeForce RTX 5080", 2, physical = NvidiaPhysicalVerdict.VIRTUAL)
        val byPath = assertIs<NvidiaCapability.Unsupported>(
            classifyNvidiaCapability(NvidiaProbeInput(true, listOf(ordinaryNameButVirtualPath))),
        )
        assertEquals(NvidiaUnsupportedReason.NOT_PHYSICAL, byPath.reason)
        val unknownVerdict = adapter("NVIDIA GeForce RTX 5080 Virtual Display", 3, physical = NvidiaPhysicalVerdict.UNKNOWN)
        val stillExcluded = assertIs<NvidiaCapability.Unsupported>(
            classifyNvidiaCapability(NvidiaProbeInput(true, listOf(unknownVerdict))),
        )
        assertEquals(NvidiaUnsupportedReason.NOT_PHYSICAL, stillExcluded.reason)
    }

    // ---- adapter pin ----------------------------------------------------------------------

    @Test fun pinIsExactWhenThePrimaryIsTheFirstPrefixMatch() {
        val primary = adapter("NVIDIA GeForce RTX 5080", 1)
        val amd = adapter("AMD Radeon(TM) Graphics", 2, vendorId = 0x1002, deviceId = 0x164E)
        val twin = adapter("NVIDIA GeForce RTX 5080", 3, outputs = 0, physical = NvidiaPhysicalVerdict.VIRTUAL)
        val pin = resolveMpvAdapterPin(primary, listOf(primary, amd, twin))
        assertEquals(NvidiaAdapterPin.Exact("NVIDIA GeForce RTX 5080"), pin)
        assertEquals("NVIDIA GeForce RTX 5080", pin.optionValue())
    }

    @Test fun pinIsUnsafeWhenAnotherAdapterMatchesTheDescriptionFirst() {
        val primary = adapter("NVIDIA GeForce RTX 5080", 1)
        val twin = adapter("NVIDIA GeForce RTX 5080", 3, outputs = 0, physical = NvidiaPhysicalVerdict.VIRTUAL)
        val pin = resolveMpvAdapterPin(primary, listOf(twin, primary))
        assertEquals(NvidiaAdapterPin.None, pin)
        assertNull(pin.optionValue())
        val lowerCaseFirst = adapter("nvidia geforce rtx 5080 (copy)", 4)
        assertEquals(NvidiaAdapterPin.None, resolveMpvAdapterPin(primary, listOf(lowerCaseFirst, primary)))
        assertEquals(NvidiaAdapterPin.None, resolveMpvAdapterPin(primary, emptyList()))
    }

    @Test fun pinFallsBackToTheLegacyLiteralForUnprintableOrOverlongDescriptions() {
        val overlong = adapter("NVIDIA " + "x".repeat(130), 1)
        assertEquals(NvidiaAdapterPin.Legacy, resolveMpvAdapterPin(overlong, listOf(overlong)))
        val control = adapter("NVIDIA\u0001 GPU", 1)
        assertEquals(NvidiaAdapterPin.Legacy, resolveMpvAdapterPin(control, listOf(control)))
        assertEquals("NVIDIA", NvidiaAdapterPin.Legacy.optionValue())
        val other = adapter("NVIDIA GeForce RTX 5080", 2)
        assertEquals(NvidiaAdapterPin.None, resolveMpvAdapterPin(control, listOf(other, control)))
        val empty = adapter("", 1)
        assertEquals(NvidiaAdapterPin.None, resolveMpvAdapterPin(empty, listOf(empty)))
    }

    @Test fun pinForFollowsTheDocumentedVerdictTable() {
        val capable = assertIs<NvidiaCapability.Capable>(
            classifyNvidiaCapability(vectors.gpuInput(vectors.objects("gpus").first { vectors.string(it, "id") == "rtx-5080-this-pc" })),
        )
        assertEquals(capable.pin, nvidiaPinFor(capable))
        assertEquals(NvidiaAdapterPin.None, nvidiaPinFor(NvidiaCapability.Absent))
        assertEquals(NvidiaAdapterPin.None, nvidiaPinFor(NvidiaCapability.Unsupported(NvidiaUnsupportedReason.NOT_PHYSICAL)))
        assertEquals(NvidiaAdapterPin.Legacy, nvidiaPinFor(NvidiaCapability.NotEvaluated))
        assertEquals(NvidiaAdapterPin.Legacy, nvidiaPinFor(NvidiaCapability.Probing))
        assertEquals(NvidiaAdapterPin.Legacy, nvidiaPinFor(NvidiaCapability.Unknown()))
        val gtx = vectors.objects("gpus").first { vectors.string(it, "id") == "gtx-1660-name-beats-turing-id" }
        val declined = assertIs<NvidiaCapability.Unsupported>(classifyNvidiaCapability(vectors.gpuInput(gtx)))
        assertTrue(nvidiaPinFor(declined) is NvidiaAdapterPin.Exact, "decode steering survives a hard decline")
    }

    // ---- observed identity (probe failed) -------------------------------------------------

    @Test fun observedIdentityIsCappedAtHighAndKeepsTheLegacyPin() {
        val capable = assertIs<NvidiaCapability.Capable>(
            classifyObservedNvidiaGpu("NVIDIA GeForce RTX 5080", 0x2C02, "000000000000e782", null),
        )
        assertEquals(NvidiaTier.HIGH, capable.tier)
        assertEquals(NvidiaAdapterPin.Legacy, capable.pin)
        assertTrue(NvidiaCapabilityNote.OBSERVED_IDENTITY in capable.notes)
        assertTrue(NvidiaCapabilityNote.VRAM_UNKNOWN in capable.notes)
        val ordinary = assertIs<NvidiaCapability.Capable>(classifyObservedNvidiaGpu("NVIDIA GeForce RTX 3060", 0x2504, "x", null))
        assertEquals(NvidiaTier.LIGHT, ordinary.tier)
        val unnamed = assertIs<NvidiaCapability.Capable>(classifyObservedNvidiaGpu(null, null, null, null))
        assertEquals(NvidiaTier.LIGHT, unnamed.tier)
        val laptop = assertIs<NvidiaCapability.Capable>(
            classifyObservedNvidiaGpu("NVIDIA GeForce RTX 5080 Laptop GPU", 0x2C19, "x", null),
        )
        assertEquals(NvidiaTier.BALANCED, laptop.tier)
    }

    @Test fun observedPreRtxAndOldBlackwellDriverAreDeclined() {
        val gtx = assertIs<NvidiaCapability.Unsupported>(classifyObservedNvidiaGpu("NVIDIA GeForce GTX 1080", 0x1B80, "x", null))
        assertEquals(NvidiaUnsupportedReason.PRE_RTX, gtx.reason)
        val old = assertIs<NvidiaCapability.Unsupported>(classifyObservedNvidiaGpu("NVIDIA GeForce RTX 5080", 0x2C02, "x", 55_000))
        assertEquals(NvidiaUnsupportedReason.DRIVER_OLD, old.reason)
        assertIs<NvidiaCapability.Capable>(classifyObservedNvidiaGpu("NVIDIA GeForce RTX 5080", 0x2C02, "x", NVIDIA_BLACKWELL_MIN_DRIVER_X100))
    }

    @Test fun hdrDriverGateTreatsAnUnknownDriverAsOk() {
        assertTrue(nvidiaHdrDriverOk(null))
        assertTrue(nvidiaHdrDriverOk(NVIDIA_HDR_MIN_DRIVER_X100))
        assertFalse(nvidiaHdrDriverOk(NVIDIA_HDR_MIN_DRIVER_X100 - 1))
        assertEquals("551.23", formatNvidiaDriverPublic(NVIDIA_HDR_MIN_DRIVER_X100))
        assertEquals("572.16", formatNvidiaDriverPublic(NVIDIA_BLACKWELL_MIN_DRIVER_X100))
        assertEquals("545.84", formatNvidiaDriverPublic(NVIDIA_DRIVER_ADVISORY_BELOW_X100))
    }
}

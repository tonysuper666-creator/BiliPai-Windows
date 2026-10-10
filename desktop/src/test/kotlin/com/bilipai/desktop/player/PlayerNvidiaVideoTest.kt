package com.bilipai.desktop.player

import kotlin.test.*

class PlayerNvidiaVideoTest {
    @Test fun realSharedCoreArgumentsForwardEveryExposedQualityAndNeverInventADriverOption() {
        val hash = "a".repeat(64)
        val identity = DesktopVeyraInstalledIdentity("fixture", hash, hash, "fixture", hash, hash, hash, hash)
        val binding = DesktopVeyraVerifiedBinding(java.nio.file.Path.of("C:/fixture/mpv/libmpv-2.dll"),
            java.nio.file.Path.of("C:/fixture/core/bilipai_veyra_core.dll"), java.nio.file.Path.of("C:/fixture/runtime"),
            "00000000-0000-0000-0000-000000000001", hash, identity)
        assertEquals(4, DesktopNvidiaVideoPreferences().quality.nativeLevel)
        assertEquals(DesktopNvidiaVideoHdrMode.OFF, DesktopNvidiaVideoPreferences().hdrMode)
        val decision = resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, 16384, "bt.1886", null, false)
        val driverDefault = DesktopNvidiaVideoPreferences().optionsFor(decision, NvidiaVideoBackend.DRIVER)
        for (quality in DesktopNvidiaVideoQuality.entries) {
            val preferences = DesktopNvidiaVideoPreferences(quality = quality)
            val options = preferences.optionsFor(decision, NvidiaVideoBackend.VEYRA_CORE)
            val arguments = binding.filterArguments(options, 17, 23)
            assertTrue(arguments.contains(":quality=${quality.nativeLevel}:hdr=no:"))
            assertTrue(arguments.contains(":session=17:generation=23:scale=2.0:"))
            assertFalse(options.copy(backend = NvidiaVideoBackend.DRIVER).filterArguments().contains("quality"))
            assertEquals(driverDefault, preferences.optionsFor(decision, NvidiaVideoBackend.DRIVER))
        }
        for (invalid in listOf(0, 5)) assertFailsWith<IllegalArgumentException> {
            binding.filterArguments(NvidiaVideoOptions(2.0, qualityLevel = invalid), 17, 23)
        }
    }

    @Test fun unityWithoutHdrDoesNotRequestAFilterAndArgumentsAreBounded() {
        assertFalse(NvidiaVideoOptions().requireValid().requiresFilter)
        assertTrue(NvidiaVideoOptions(hdr = true).requiresFilter)
        assertTrue(NvidiaVideoOptions(2.0).requiresFilter)
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 0.9, 4.01))
            assertFailsWith<IllegalArgumentException> { NvidiaVideoOptions(value).requireValid() }
        assertEquals("d3d11vpp=scale=2.0:scaling-mode=nvidia:nvidia-true-hdr=no", NvidiaVideoOptions(2.0).filterArguments())
    }

    @Test fun theDriverAcceptingAnExtensionIsNotAProcessedFrame() {
        val requested = NvidiaVideoState(driverVsrAccepted = true)
        val frame = NvidiaFrameObservation(640, 360, 1280, 720, "bt.1886", "bt.1886", "bt.709", false, true, false)
        assertFalse(observeNvidiaVideo(requested, NvidiaVideoOptions(2.0), frame).active)
        assertTrue(observeNvidiaVideo(requested, NvidiaVideoOptions(2.0), frame).pending)
        assertTrue(observeNvidiaVideo(requested, NvidiaVideoOptions(2.0), frame.copy(frameAfterConfiguration = true)).active)
    }

    @Test fun optionsOrOtherFilterOutputCannotForgeNvidiaProcessing() {
        val frame = NvidiaFrameObservation(640, 360, 1280, 720, "bt.1886", "bt.1886", "bt.709", true, true, false)
        assertFalse(observeNvidiaVideo(NvidiaVideoState(), NvidiaVideoOptions(2.0), frame).active)
        assertFalse(observeNvidiaVideo(NvidiaVideoState(driverVsrAccepted = true), NvidiaVideoOptions(2.0), frame.copy(ownFilterPresent = false)).active)
        assertFalse(observeNvidiaVideo(NvidiaVideoState(driverVsrAccepted = true), NvidiaVideoOptions(2.0), frame.copy(outputWidth = 640)).active)
        assertFalse(observeNvidiaVideo(NvidiaVideoState(driverVsrAccepted = true, error = "Rejected"), NvidiaVideoOptions(2.0), frame).active)
    }

    @Test fun hdrConversionAndHdrPresentationAreDifferentObservations() {
        val accepted = NvidiaVideoState(driverHdrAccepted = true)
        val frame = NvidiaFrameObservation(640, 360, 640, 360, "pq", "bt.1886", "bt.709", true, true, true)
        val converted = observeNvidiaVideo(accepted, NvidiaVideoOptions(hdr = true), frame)
        assertTrue(converted.hdrConversionActive)
        assertFalse(converted.active)
        assertTrue(converted.pending)
        assertTrue(observeNvidiaVideo(accepted, NvidiaVideoOptions(hdr = true), frame.copy(targetTransfer = "pq", targetPrimaries = "bt.2020")).active)
        assertFalse(observeNvidiaVideo(accepted, NvidiaVideoOptions(hdr = true), frame.copy(targetTransfer = "pq", displayHdrEnabled = false)).active)
    }

    @Test fun bothRequestedPipelinesNeedIndependentAcceptance() {
        val frame = NvidiaFrameObservation(640, 360, 1280, 720, "pq", "pq", "bt.2020", true, true, true)
        val options = NvidiaVideoOptions(2.0, true)
        val vsrOnly = observeNvidiaVideo(NvidiaVideoState(driverVsrAccepted = true), options, frame)
        assertFalse(vsrOnly.active); assertTrue(vsrOnly.pending)
        val hdrOnly = observeNvidiaVideo(NvidiaVideoState(driverHdrAccepted = true), options, frame)
        assertFalse(hdrOnly.active); assertTrue(hdrOnly.pending)
        assertTrue(observeNvidiaVideo(NvidiaVideoState(driverVsrAccepted = true, driverHdrAccepted = true), options, frame).active)
    }

    @Test fun fractionalViewportScaleUsesTheNativesUpwardEvenPixelAlignment() {
        val accepted = NvidiaVideoState(driverVsrAccepted = true, driverHdrAccepted = true)
        val options = NvidiaVideoOptions(2055.0 / 1080.0, true)
        val frame = NvidiaFrameObservation(1920, 1080, 3654, 2056, "pq", "pq", "bt.2020", true, true, true)
        assertTrue(observeNvidiaVideo(accepted, options, frame).active)
        assertFalse(observeNvidiaVideo(accepted, options, frame.copy(outputWidth = 3652, outputHeight = 2054)).active)
    }

    @Test fun onlyFixedPinnedMessagesSupplySuccessAndFailureDoesNotRetainNativeText() {
        assertEquals(NvidiaNativeMessage.VsrAccepted, parseNvidiaNativeMessage("d3d11vpp", "NVIDIA RTX Super Resolution enabled.\n"))
        assertEquals(NvidiaNativeMessage.HdrAccepted, parseNvidiaNativeMessage("d3d11vpp", "NVIDIA RTX Video HDR enabled."))
        assertNull(parseNvidiaNativeMessage("ffmpeg/http", "NVIDIA RTX Super Resolution enabled."))
        assertNull(parseNvidiaNativeMessage("d3d11vpp", "NVIDIA RTX Super Resolution enabled. Cookie: secret"))
        val failed = assertIs<NvidiaNativeMessage.Failure>(parseNvidiaNativeMessage("d3d11vpp", "Failed to enable NVIDIA RTX Super Resolution: fixture-secret"))
        assertFalse(failed.safeMessage.contains("fixture-secret"))
    }

    @Test fun onlyExactOwnedLabelRuntimeDisableMessagesAreRecognized() {
        val line = "Disabling filter bilipai-nvidia-17 because it has failed."
        assertEquals(NvidiaNativeMessage.FilterFailed("bilipai-nvidia-17"), parseNvidiaNativeMessage("vf", "$line\n"))
        for (prefix in listOf("d3d11vpp", "af", "ffmpeg", "vf/other")) assertNull(parseNvidiaNativeMessage(prefix, line))
        for (other in listOf(line + " Cookie: private", line.replace("17", "12345678901234567890"),
            line.replace("bilipai-nvidia-17", "foreign-filter"), line.replace("17", "-17"), line.removeSuffix("."),
            " $line", "$line ", "\t$line", "$line\r", "$line\n\n", "$line\n$line", "$line\u0000", "$line\u007f"))
            assertNull(parseNvidiaNativeMessage("vf", other))
    }

    @Test fun actualGpuMetadataIsParsedAsThePinnedSeparateLineEvents() {
        assertEquals(NvidiaNativeMessage.DeviceName("NVIDIA GeForce RTX 5080"), parseNvidiaNativeMessage("vo/gpu/d3d11", "Device Name: NVIDIA GeForce RTX 5080\n"))
        assertEquals(NvidiaNativeMessage.DeviceVendor(0x10de), parseNvidiaNativeMessage("vo/gpu/d3d11", "Device ID: 10de:2c02 (rev a1)\n"))
        assertNull(parseNvidiaNativeMessage("ffmpeg", "Device ID: 10de:2c02 (rev a1)"))
        assertNull(parseNvidiaNativeMessage("vo/gpu/d3d11", "Device Name: http://fixture.invalid/private"))
        assertNull(parseNvidiaNativeMessage("vo/gpu/d3d11", "Device ID: 10de:2c02 (rev a1) private-data"))
    }

    @Test fun newerSourceAndConfigurationCannotBeChangedByAnOldOwner() {
        MpvPlayer().use { player ->
            val first = player.loadVersioned(PlaybackSource("file:///C:/owned-first.avi"))
            val initial = assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(first, NvidiaVideoOptions(2.0)))
            assertFalse(player.nvidiaVideoState.value.active)
            assertFalse(player.nvidiaVideoState.value.driverVsrAccepted)
            val replacement = player.loadVersioned(PlaybackSource("file:///C:/owned-second.avi"))
            assertNull(player.setNvidiaVideoEnhancementIfSourceVersion(first, NvidiaVideoOptions(3.0)))
            assertFalse(player.clearNvidiaVideoEnhancementIfConfigurationVersion(initial))
            val latest = assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(replacement, NvidiaVideoOptions(1.5)))
            assertEquals(latest, player.nvidiaVideoState.value.configurationVersion)
            assertEquals(1.5, player.nvidiaVideoState.value.requestedScale)
            assertEquals(replacement, player.nvidiaVideoState.value.sourceVersion)
            assertTrue(player.clearNvidiaVideoEnhancementIfConfigurationVersion(latest))
            assertEquals(1.0, player.nvidiaVideoState.value.requestedScale)
            assertFalse(player.nvidiaVideoState.value.pending)
        }
    }

    @Test fun sourceRecoveryRequiresFreshNativeAcknowledgementsAndKeepsUserControls() {
        MpvPlayer().use { player ->
            player.setVolume(17.0); player.setMuted(true); player.setSpeed(1.25)
            val source = player.loadVersioned(PlaybackSource("file:///C:/owned-recovery.avi"))
            val token = assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(source, NvidiaVideoOptions(2.0)))
            assertTrue(player.recoverSource(source, positionSeconds = 3.0, paused = true))
            assertFalse(player.clearNvidiaVideoEnhancementIfConfigurationVersion(token))
            assertFalse(player.nvidiaVideoState.value.active)
            assertFalse(player.nvidiaVideoState.value.driverVsrAccepted)
            assertEquals(source, player.currentSourceVersion)
            assertEquals(17.0, player.state.value.volume)
            assertTrue(player.state.value.muted)
            assertEquals(1.25, player.state.value.speed)
            assertTrue(player.state.value.paused)
        }
    }

    @Test fun sameVersionRecoveryCannotAcceptAnOptionsDecisionForTheOldSnapshot() {
        MpvPlayer().use { player ->
            val version = player.loadVersioned(PlaybackSource("file:///C:/owned-snapshot-recovery.avi"))
            val old = assertNotNull(player.currentSourceSnapshot())
            assertTrue(player.recoverSource(version, positionSeconds = 3.0, paused = false))
            assertEquals(version, player.currentSourceVersion)
            val before = player.nvidiaVideoState.value
            assertNull(player.setNvidiaVideoEnhancementIfSourceSnapshot(old, NvidiaVideoOptions(2.0, true)))
            assertEquals(before, player.nvidiaVideoState.value)
            assertNotNull(player.setNvidiaVideoEnhancementIfSourceSnapshot(assertNotNull(player.currentSourceSnapshot()), NvidiaVideoOptions(1.5)))
        }
    }

    @Test fun stopAndCloseRetirePendingEnhancementWithoutADevice() {
        val player = MpvPlayer()
        val source = player.loadVersioned(PlaybackSource("file:///C:/owned-close.avi"))
        val token = assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(source, NvidiaVideoOptions(2.0)))
        player.stop()
        assertFalse(player.nvidiaVideoState.value.active)
        assertFalse(player.nvidiaVideoState.value.pending)
        assertFalse(player.clearNvidiaVideoEnhancementIfConfigurationVersion(token))
        player.close()
        assertNull(player.setNvidiaVideoEnhancementIfSourceVersion(source, NvidiaVideoOptions(2.0)))
        assertFalse(player.nvidiaVideoState.value.pending)
    }
}

package com.bilipai.desktop.player

import java.nio.file.Files
import kotlin.test.*

/** Headless evidence ordering and real-player rejection paths. No HWND, DLL or native screenshots are exercised. */
class PlayerSelfTestFailureFrameDiagnosticTest {
    @Test fun sameOwnedSourceWithoutNativeSessionPreservesOriginalFailureAndAllPlaybackIntent() {
        MpvPlayer().use { player ->
            player.loadVersioned(PlaybackSource("file:///C:/fixture-native-diagnostic.avi", startPositionSeconds = 2.0, startPaused = true))
            val source = assertNotNull(player.currentSourceSnapshot())
            val state = player.state.value
            val primary = IllegalStateException("Original physical pixels were black")
            val fields = linkedMapOf<String, String>()
            withOutput { image ->
                assertSame(primary, PlayerSelfTest.observeFailedNativeFrame(player, source, image, primary, fields))
                assertEquals("capture-unavailable", fields["status"])
                assertEquals("CancellationException", fields["captureErrorType"])
                assertEquals("true", fields["sourceOwnedBefore"])
                assertEquals("true", fields["sourceOwnedAfter"])
                assertEquals(System.getProperty("java.runtime.version", "unavailable").take(128), fields["jvmRuntimeVersion"])
                assertEquals(System.getProperty("java.vm.version", "unavailable").take(128), fields["jvmVmVersion"])
                assertEquals(System.getProperty("java.vendor", "unavailable").take(128), fields["jvmVendor"])
                assertFalse(image.exists())
                assertTrue(java.io.File(image.parentFile, "${image.nameWithoutExtension}.json").isFile)
                assertTrue(primary.suppressed.isNotEmpty())
            }
            assertEquals(state, player.state.value)
            assertTrue(player.ownsSourceSnapshot(source))
            assertEquals("Original physical pixels were black", primary.message)
        }
    }

    @Test fun replacementSourceRejectsOldFailureCaptureWithoutTouchingSuccessorOrPrimaryFailure() {
        MpvPlayer().use { player ->
            player.loadVersioned(PlaybackSource("file:///C:/fixture-native-old.avi", startPaused = true))
            val old = assertNotNull(player.currentSourceSnapshot())
            player.loadVersioned(PlaybackSource("file:///C:/fixture-native-new.avi", startPositionSeconds = 7.0, startPaused = false))
            val successor = assertNotNull(player.currentSourceSnapshot())
            val state = player.state.value
            val primary = IllegalStateException("Original physical timeout")
            val fields = linkedMapOf<String, String>()
            withOutput { image ->
                assertSame(primary, PlayerSelfTest.observeFailedNativeFrame(player, old, image, primary, fields))
                assertEquals("source-retired", fields["status"])
                assertEquals("false", fields["sourceOwnedBefore"])
                assertEquals("false", fields["sourceOwnedAfter"])
                assertFalse(image.exists())
                assertTrue(primary.suppressed.isEmpty())
            }
            assertEquals(state, player.state.value)
            assertTrue(player.ownsSourceSnapshot(successor))
            assertFalse(player.ownsSourceSnapshot(old))
        }
    }

    @Test fun noSourceProducesSafeSidecarAndCannotConvertFailedPhysicalGateToSuccess() {
        MpvPlayer().use { player ->
            val state = player.state.value
            val primary = IllegalStateException("Original physical timeout")
            val fields = linkedMapOf<String, String>()
            withOutput { image ->
                assertSame(primary, PlayerSelfTest.observeFailedNativeFrame(player, null, image, primary, fields))
                assertEquals("source-unavailable", fields["status"])
                assertEquals("unavailable", fields["expectedSourceVersion"])
                assertEquals("failed; auxiliary capture cannot change the physical gate", fields["physicalResult"])
                assertFalse(image.exists())
                val report = java.io.File(image.parentFile, "${image.nameWithoutExtension}.json").readText()
                assertFalse(report.contains("file:///"))
                assertFalse(report.contains("PlaybackSource"))
                assertNull(player.currentSourceSnapshot())
            }
            assertEquals(state, player.state.value)
        }
    }

    @Test fun cpuFirstEvidenceOrderRetainsTheOriginalPhysicalFailureEvenWhenAllObservationsComplete() {
        val primary = IllegalStateException("physical timeout")
        val calls = mutableListOf<String>()
        val fields = linkedMapOf<String, String>()
        assertSame(primary, PlayerSelfTest.observeFailureScreenshotSequence(primary, fields,
            boundary = { calls += it }, software = { calls += "cpu" }, gpu = { calls += "gpu" }))
        assertEquals(listOf("before-cpu", "cpu", "after-cpu", "gpu", "after-gpu"), calls)
        assertEquals("true", fields["gpuHelperInvoked"])
        assertTrue(primary.suppressed.isEmpty())
    }

    @Test fun rejectedOrUnrestoredSoftwareCaptureStillObservesPhysicalPixelsButNeverCallsGpu() {
        val primary = IllegalStateException("physical timeout")
        val restorationFailure = IllegalStateException("restore not proven")
        val calls = mutableListOf<String>()
        val fields = linkedMapOf<String, String>()
        assertSame(primary, PlayerSelfTest.observeFailureScreenshotSequence(primary, fields,
            boundary = { calls += it }, software = { calls += "cpu"; throw restorationFailure },
            gpu = { error("GPU must not run after uncertain software restoration") }))
        assertEquals(listOf("before-cpu", "cpu", "after-cpu"), calls)
        assertEquals("false", fields["gpuHelperInvoked"])
        assertSame(restorationFailure, primary.suppressed.single())
    }

    @Test fun changedPhysicalOwnershipStopsSubsequentCaptureWithoutMaskingTheFirstFailure() {
        for (failedStage in listOf("before-cpu", "after-cpu")) {
            val primary = IllegalStateException("physical timeout")
            val ownershipFailure = IllegalStateException("owner or bounds retired")
            val calls = mutableListOf<String>()
            val fields = linkedMapOf<String, String>()
            assertSame(primary, PlayerSelfTest.observeFailureScreenshotSequence(primary, fields,
                boundary = { calls += it; if (it == failedStage) throw ownershipFailure },
                software = { calls += "cpu" }, gpu = { error("GPU must not run after boundary failure") }))
            assertEquals(if (failedStage == "before-cpu") listOf("before-cpu") else listOf("before-cpu", "cpu", "after-cpu"), calls)
            assertEquals("false", fields["gpuHelperInvoked"])
            assertSame(ownershipFailure, primary.suppressed.single())
        }
    }

    private fun withOutput(action: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("native-failure-diagnostic-")
        try { action(directory.resolve("native-player-smoke-failure-native-video.png").toFile()) }
        finally { Files.walk(directory).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}

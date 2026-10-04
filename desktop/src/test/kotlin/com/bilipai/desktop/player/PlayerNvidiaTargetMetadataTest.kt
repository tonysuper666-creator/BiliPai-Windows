package com.bilipai.desktop.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class PlayerNvidiaTargetMetadataTest {
    private fun recordedTarget(player: MpvPlayer) {
        @Suppress("UNCHECKED_CAST")
        val flow = MpvPlayer::class.java.getDeclaredField("mutableNvidiaVideo").apply { isAccessible = true }
            .get(player) as MutableStateFlow<NvidiaVideoState>
        // An input recorded as a native target observation; no renderer/driver PASS claim.
        flow.value = flow.value.copy(sourceVersion = player.currentSourceVersion,
            targetTransfer = "pq", targetPrimaries = "bt.2020", driverVsrAccepted = true,
            driverHdrAccepted = true, active = true, hdrConversionActive = true)
    }
    @Test fun configuringAndClearingCannotEraseTheSameSourceTargetOrKeepOldAcknowledgements() {
        MpvPlayer().use { player ->
            val source = player.loadVersioned(PlaybackSource("file:///C:/target-fixture.avi"))
            recordedTarget(player)
            val token = assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(source, NvidiaVideoOptions(1.0, true)))
            assertEquals("pq", player.nvidiaVideoState.value.targetTransfer)
            assertEquals("bt.2020", player.nvidiaVideoState.value.targetPrimaries)
            assertFalse(player.nvidiaVideoState.value.active)
            assertFalse(player.nvidiaVideoState.value.driverHdrAccepted)
            assertFalse(player.nvidiaVideoState.value.hdrConversionActive)
            assertTrue(player.clearNvidiaVideoEnhancementIfConfigurationVersion(token))
            assertEquals("pq", player.nvidiaVideoState.value.targetTransfer)
            assertEquals("bt.2020", player.nvidiaVideoState.value.targetPrimaries)
        }
    }
    @Test fun bothANewSourceAndSameVersionRecoveryMustWaitForFreshTargetMetadata() {
        MpvPlayer().use { player ->
            val first = player.loadVersioned(PlaybackSource("file:///C:/target-first.avi"))
            recordedTarget(player)
            assertTrue(player.recoverSource(first, positionSeconds = 2.0))
            assertNull(player.nvidiaVideoState.value.targetTransfer)
            assertNull(player.nvidiaVideoState.value.targetPrimaries)
            recordedTarget(player)
            val second = player.loadVersioned(PlaybackSource("file:///C:/target-second.avi"))
            assertTrue(second > first)
            assertEquals(second, player.nvidiaVideoState.value.sourceVersion)
            assertNull(player.nvidiaVideoState.value.targetTransfer)
            assertNull(player.nvidiaVideoState.value.targetPrimaries)
        }
    }
}

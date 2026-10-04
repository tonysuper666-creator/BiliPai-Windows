package com.bilipai.desktop.player

import kotlin.test.*
import org.junit.jupiter.api.Test

class DesktopWindowsAudioOutputPolicyTest {
    private val stereo = DesktopWindowsAudioPcm("s32", 48000, "stereo", 2)
    private val exclusive = DesktopWindowsAudioOutputPreferences(true, "wasapi/{private-synthetic-id}")
    private fun phase(requestedRevision: Long = 5, applied: Long? = 5, loaded: Boolean = true,
        driver: String? = "wasapi", device: String? = exclusive.effectiveDeviceId, exclusiveFlag: Boolean? = true,
        fallback: Boolean? = false, pcm: DesktopWindowsAudioPcm? = stereo, selected: Boolean = true, error: String? = null,
    ) = resolveWindowsAudioOutputPhase(exclusive, requestedRevision, applied, loaded, driver, device, exclusiveFlag, fallback, pcm, selected, error)

    @Test fun `default off keeps auto shared selection and never touches level speed filters or sample rate`() {
        val options = DesktopWindowsAudioOutputPreferences().nativeOptions()
        assertEquals("auto", options["audio-device"]); assertEquals("no", options["audio-exclusive"])
        assertEquals("no", options["audio-fallback-to-null"])
        for (key in listOf("volume", "mute", "speed", "af", "ao", "audio-samplerate", "audio-format")) assertFalse(key in options)
    }
    @Test fun `exclusive auto uses fixed official wasapi splitter and selected actual device stays exact`() {
        assertEquals("wasapi", DesktopWindowsAudioOutputPreferences(true).nativeOptions()["audio-device"])
        assertEquals("auto", DesktopWindowsAudioOutputPreferences(true).deviceId)
        assertEquals("wasapi/{private-synthetic-id}", exclusive.nativeOptions()["audio-device"])
        assertEquals("stereo", exclusive.nativeOptions()["audio-channels"])
        assertEquals("weak", exclusive.nativeOptions()["gapless-audio"])
        assertFalse("ao" in exclusive.nativeOptions())
    }
    @Test fun `foreign output drivers and control bytes are rejected rather than falling back`() {
        for (id in listOf("pulse/device", "alsa/default", "null", "", "wasapi/", "wasapi/device\nother"))
            assertFailsWith<IllegalArgumentException> { DesktopWindowsAudioOutputPreferences(true, id).nativeOptions() }
    }
    @Test fun `new request cannot borrow prior active AO acknowledgement`() {
        assertEquals(DesktopWindowsAudioOutputPhase.PENDING_NEXT_PLAY, phase(requestedRevision = 6))
        assertEquals(DesktopWindowsAudioOutputPhase.PENDING_NEXT_PLAY, phase(requestedRevision = 6, error = "previous output failed"))
        assertEquals(DesktopWindowsAudioOutputPhase.PENDING_NEXT_PLAY, phase(applied = null))
        assertEquals(DesktopWindowsAudioOutputPhase.OPENING, phase(loaded = false))
    }
    @Test fun `exclusive success requires real AO config no null fallback and valid stereo output PCM`() {
        assertEquals(DesktopWindowsAudioOutputPhase.ACTIVE_EXCLUSIVE, phase())
        assertEquals(DesktopWindowsAudioOutputPhase.OPENING, phase(driver = null))
        for (driver in listOf("null", "sdl", "pulse")) assertEquals(DesktopWindowsAudioOutputPhase.ERROR, phase(driver = driver))
        assertEquals(DesktopWindowsAudioOutputPhase.ERROR, phase(exclusiveFlag = false))
        assertEquals(DesktopWindowsAudioOutputPhase.ERROR, phase(exclusiveFlag = null))
        assertEquals(DesktopWindowsAudioOutputPhase.ERROR, phase(fallback = true))
        assertEquals(DesktopWindowsAudioOutputPhase.ERROR, phase(device = "wasapi/{different-id}"))
        assertEquals(DesktopWindowsAudioOutputPhase.OPENING, phase(pcm = stereo.copy(sampleRate = null)))
        assertEquals(DesktopWindowsAudioOutputPhase.ERROR, phase(pcm = stereo.copy(channelCount = 6)))
    }
    @Test fun `busy failure and missing audio never report requested exclusive active`() {
        assertEquals(DesktopWindowsAudioOutputPhase.ERROR, phase(error = "Device busy"))
        assertEquals(DesktopWindowsAudioOutputPhase.UNAVAILABLE, phase(selected = false))
    }
    @Test fun `same new load option failure is an error but older config failure remains pending`() {
        assertEquals(DesktopWindowsAudioOutputPhase.ERROR,
            resolveWindowsAudioOutputPhase(exclusive, 6, 5, false, null, null, null, null, null, true, "option rejected", 6))
        assertEquals(DesktopWindowsAudioOutputPhase.PENDING_NEXT_PLAY,
            resolveWindowsAudioOutputPhase(exclusive, 7, 5, false, null, null, null, null, null, true, "option rejected", 6))
    }
    @Test fun `malformed saved fields are safely off with visible error rather than throwing or retaining exclusive`() {
        val exclusiveKey = "exclusive"; val deviceKey = "device_id"
        val invalid = listOf(
            mapOf(exclusiveKey to kotlinx.serialization.json.JsonPrimitive("true")),
            mapOf(exclusiveKey to kotlinx.serialization.json.JsonPrimitive(1)),
            mapOf(exclusiveKey to kotlinx.serialization.json.JsonNull),
            mapOf(exclusiveKey to kotlinx.serialization.json.JsonObject(emptyMap())),
            mapOf(deviceKey to kotlinx.serialization.json.JsonPrimitive(17)),
            mapOf(deviceKey to kotlinx.serialization.json.JsonNull),
            mapOf(deviceKey to kotlinx.serialization.json.JsonArray(emptyList())),
        ) + listOf("", "null", "pulse/default", "wasapi/", "wasapi/a\u0000b", "wasapi/a\u007fb", "wasapi/" + "x".repeat(1024))
            .map { mapOf(deviceKey to kotlinx.serialization.json.JsonPrimitive(it), exclusiveKey to kotlinx.serialization.json.JsonPrimitive(true)) }
        for (values in invalid) {
            val snapshot = com.bilipai.desktop.plugins.DesktopPreferenceSnapshot(kotlinx.serialization.json.JsonObject(values))
            val result = DesktopWindowsAudioOutputController.decodeConfiguration(snapshot)
            assertEquals(DesktopWindowsAudioOutputPreferences(), result.preferences)
            assertNotNull(result.error)
        }
        val absent = DesktopWindowsAudioOutputController.decodeConfiguration(com.bilipai.desktop.plugins.DesktopPreferenceSnapshot(kotlinx.serialization.json.JsonObject(emptyMap())))
        assertEquals(DesktopWindowsAudioOutputPreferences(), absent.preferences); assertNull(absent.error)
    }
    @Test fun `shared output requires actual disabled exclusive readback independently of request`() {
        val desired = DesktopWindowsAudioOutputPreferences(false)
        assertEquals(DesktopWindowsAudioOutputPhase.ACTIVE_SHARED,
            resolveWindowsAudioOutputPhase(desired, 4, 4, true, "wasapi", "auto", false, false, stereo, true, null))
        assertEquals(DesktopWindowsAudioOutputPhase.ERROR,
            resolveWindowsAudioOutputPhase(desired, 4, 4, true, "wasapi", "auto", true, false, stereo, true, null))
    }
    @Test fun `retired actor binding cannot overwrite successor future intent or volume mute pause`() {
        val player = MpvPlayer()
        try {
            player.setVolume(17.0); player.setMuted(true); player.setSpeed(1.5); player.setPaused(true)
            val first = Any(); val successor = Any()
            player.bindWindowsAudioOutputOwner(first)
            player.requestWindowsAudioOutputPreferences(first, exclusive)
            player.bindWindowsAudioOutputOwner(successor)
            val newer = DesktopWindowsAudioOutputPreferences(false, "wasapi/{new-device}")
            player.requestWindowsAudioOutputPreferences(successor, newer)
            val accepted = player.windowsAudioOutput.value
            player.requestWindowsAudioOutputPreferences(first, exclusive)
            player.retireWindowsAudioOutputOwner(first)
            assertEquals(accepted, player.windowsAudioOutput.value)
            assertEquals(newer, player.windowsAudioOutput.value.requested)
            assertEquals(17.0, player.state.value.volume); assertTrue(player.state.value.muted)
            assertEquals(1.5, player.state.value.speed); assertTrue(player.state.value.paused)
            assertEquals(0L, player.currentSourceVersion)
            assertNull(player.currentSourceSnapshot())
        } finally { player.close() }
    }
    @Test fun `stale canonical snapshot emission cannot replace latest native future intent`() {
        val player = MpvPlayer()
        try {
            val owner = Any(); player.bindWindowsAudioOutputOwner(owner)
            val latest = DesktopWindowsAudioOutputPreferences(true, "wasapi/{latest}")
            player.requestWindowsAudioOutputPreferences(owner, latest) { true }
            val accepted = player.windowsAudioOutput.value
            player.requestWindowsAudioOutputPreferences(owner, DesktopWindowsAudioOutputPreferences()) { false }
            assertEquals(accepted, player.windowsAudioOutput.value)
        } finally { player.close() }
    }
}

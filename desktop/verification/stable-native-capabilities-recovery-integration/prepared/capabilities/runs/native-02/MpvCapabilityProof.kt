package com.bilipai.desktop.player

import com.bilipai.desktop.ui.DesktopOriginalMpvPlaybackCapabilities
import com.bilipai.desktop.ui.DesktopWindowsVideoDisplayCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

fun main() = runBlocking {
    var checks = 0
    fun verify(value: Boolean) { check(value); checks++ }
    val target = MpvSoftwareTarget()
    val player = MpvPlayer(target, useNullAudioOutput = true)
    val reported = mutableListOf<String>()
    // Injected monitor cases prove the original capability policy only. They
    // are explicitly not claims about this workstation's display hardware.
    val display = MutableStateFlow<DesktopWindowsVideoDisplayCapability>(
        DesktopWindowsVideoDisplayCapability.Available(false, false, false, 8))
    val caps = DesktopOriginalMpvPlaybackCapabilities(player, display, reported::add)
    verify(player.decoderCapabilities.value == null)
    verify(runCatching { caps.isHevcSupported() }.exceptionOrNull() is IllegalStateException)
    try {
        player.setHardwareDecodingEnabled(false)
        player.startSoftwareTransport()
        val observed = withTimeout(15_000) { player.decoderCapabilities.filterNotNull().first() }
        verify(observed is MpvDecoderCapabilities.Available)
        val decoded = observed as MpvDecoderCapabilities.Available
        withTimeout(5_000) { player.state.first { it.ready } }
        verify(player.decoderCapabilities.value === decoded)
        verify(decoded.decoders.isNotEmpty())
        verify(decoded.decoders.all { it.codec.isNotBlank() && it.driver.isNotBlank() })
        verify(decoded.supports("hevc"))
        verify(decoded.supports("av1"))
        verify(decoded.supports("eac3"))
        verify(caps.isHevcSupported() == decoded.supports("hevc"))
        verify(caps.isAv1Supported() == decoded.supports("av1"))
        verify(caps.isDolbyAtmosAudioSupported() == decoded.supports("eac3"))
        verify(caps.isDolbySoftwareAudioDecoderRequired() == decoded.supports("eac3"))
        verify(!caps.isHdrSupported())
        display.value = DesktopWindowsVideoDisplayCapability.Unavailable(87, "Fixture unavailable display")
        verify(!caps.isHdrSupported() && reported.last() == "Fixture unavailable display")
        display.value = DesktopWindowsVideoDisplayCapability.Available(true, false, false, 10)
        verify(caps.isHdrSupported() == decoded.supports("hevc"))
        verify(!caps.isDolbyVisionSupported())
        verify(reported.last().contains("Dolby Vision"))
        println("nativeVersion=" + player.state.value.nativeVersion)
        println("decoderCount=" + decoded.decoders.size)
        println("selectedActualDecoders=" + decoded.decoders.filter { it.codec in setOf("hevc", "av1", "eac3") })
    } finally {
        player.close()
    }
    verify(player.decoderCapabilities.value == null)
    verify(!player.state.value.ready)
    println("PASS checks=$checks; sameSession=true; mediaLoads=0; hardwareDisplayAccepted=false; MainShellAccepted=false")
}

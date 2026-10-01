package com.bilipai.desktop.ui

import com.bilipai.desktop.player.MpvDecoderCapabilities
import com.bilipai.desktop.player.MpvPlayer
import kotlinx.coroutines.flow.StateFlow

/** Original MediaUtils asks whether ANY decoder exists, including software.
 * This reads the same initialized MPV core, not the hwdec preference or the
 * currently playing codec. Explicit unavailable results retain the original
 * MediaUtils failure -> false selection policy and are reported to Root. */
internal class DesktopOriginalMpvPlaybackCapabilities(
    private val player: MpvPlayer,
    private val display: StateFlow<DesktopWindowsVideoDisplayCapability>,
    private val unavailable: (String) -> Unit,
) : DesktopOriginalVideoPlaybackCapabilities {
    private fun decodes(codec: String): Boolean = when (val value = player.decoderCapabilities.value) {
        is MpvDecoderCapabilities.Available -> value.supports(codec)
        is MpvDecoderCapabilities.Unavailable -> { unavailable(value.reason); false }
        null -> error("Root must mount the same native surface and await decoder capability before original selection")
    }
    override fun isHevcSupported(): Boolean = decodes("hevc")
    override fun isAv1Supported(): Boolean = decodes("av1")
    override fun isHdrSupported(): Boolean {
        if (!isHevcSupported()) return false
        return when (val value = display.value) {
            is DesktopWindowsVideoDisplayCapability.Available -> value.hdrSupported
            is DesktopWindowsVideoDisplayCapability.Unavailable -> { unavailable(value.reason); false }
        }
    }
    override fun isDolbyVisionSupported(): Boolean {
        // This package uses vo=gpu/D3D11 and has no specific Dolby Vision
        // profile/display negotiation. HEVC base-layer decode does not satisfy
        // MediaUtils' separate video/dolby-vision decoder/display requirement.
        unavailable("Current Windows video output has no Dolby Vision profile negotiation")
        return false
    }
    override fun isDolbyAtmosAudioSupported(): Boolean = decodes("eac3")
    override fun isDolbySoftwareAudioDecoderRequired(): Boolean = decodes("eac3")
    // Like upstream's FFmpeg extension, EAC3/JOC decode is not a claim of
    // object-based Atmos output or HDMI bitstream passthrough.
}

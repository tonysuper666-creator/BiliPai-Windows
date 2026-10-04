package com.bilipai.desktop.player

/** Application-owned output choice. The existing MPV volume/mute/speed/filter controls remain independent. */
internal data class DesktopWindowsAudioOutputPreferences(val exclusive: Boolean = false, val deviceId: String = "auto") {
    fun requireValid(): DesktopWindowsAudioOutputPreferences = apply {
        require(deviceId.length in 1..1024 && deviceId.none { it < ' ' || it == '\u007f' }) { "音频设备标识无效" }
        require(deviceId == "auto" || deviceId == "wasapi" || (deviceId.startsWith("wasapi/") && deviceId.length > 7)) {
            "仅支持 Windows WASAPI 音频设备"
        }
    }
    val effectiveDeviceId: String get() = if (exclusive && deviceId == "auto") "wasapi" else deviceId
    /** Do not pair ao=wasapi with an explicit audio-device: its driver prefix already selects the only AO. */
    fun nativeOptions(): Map<String, String> {
        requireValid()
        return linkedMapOf("audio-device" to effectiveDeviceId, "audio-exclusive" to if (exclusive) "yes" else "no",
            "audio-fallback-to-null" to "no", "audio-channels" to if (exclusive) "stereo" else "auto-safe",
            "gapless-audio" to "weak")
    }
}

internal enum class DesktopWindowsAudioOutputPhase { IDLE, PENDING_NEXT_PLAY, OPENING, ACTIVE_SHARED, ACTIVE_EXCLUSIVE, ERROR, UNAVAILABLE }
internal data class DesktopWindowsAudioPcm(val format: String?, val sampleRate: Int?, val channels: String?, val channelCount: Int?) {
    val available: Boolean get() = !format.isNullOrBlank() && sampleRate != null && sampleRate > 0 && channelCount != null && channelCount > 0
}
internal data class DesktopWindowsAudioOutputStatus(
    val phase: DesktopWindowsAudioOutputPhase = DesktopWindowsAudioOutputPhase.IDLE,
    val requested: DesktopWindowsAudioOutputPreferences = DesktopWindowsAudioOutputPreferences(),
    val requestedRevision: Long = 0, val appliedRevision: Long? = null,
    val sourceVersion: Long? = null, val playbackRevision: Long? = null,
    val activeDriver: String? = null, val configuredDeviceId: String? = null, val configuredExclusive: Boolean? = null,
    val sourcePcm: DesktopWindowsAudioPcm? = null, val outputPcm: DesktopWindowsAudioPcm? = null,
    val error: String? = null, val previousOutputError: String? = null,
)
internal data class DesktopWindowsAudioDevice(val name: String, val description: String)
internal data class DesktopWindowsAudioDeviceListState(val devices: List<DesktopWindowsAudioDevice> = emptyList(),
    val querying: Boolean = false, val available: Boolean = false, val error: String? = null)

/** Requires a new owned file's AO readback. Option acknowledgement or an earlier AO is insufficient. */
internal fun resolveWindowsAudioOutputPhase(requested: DesktopWindowsAudioOutputPreferences, requestedRevision: Long,
    appliedRevision: Long?, fileLoaded: Boolean, driver: String?, device: String?, exclusive: Boolean?,
    fallbackToNull: Boolean?, output: DesktopWindowsAudioPcm?, audioSelected: Boolean, error: String?,
    errorRevision: Long? = appliedRevision,
): DesktopWindowsAudioOutputPhase = when {
    error != null && errorRevision == requestedRevision -> DesktopWindowsAudioOutputPhase.ERROR
    appliedRevision != requestedRevision -> DesktopWindowsAudioOutputPhase.PENDING_NEXT_PLAY
    error != null -> DesktopWindowsAudioOutputPhase.ERROR
    !fileLoaded -> DesktopWindowsAudioOutputPhase.OPENING
    !audioSelected -> DesktopWindowsAudioOutputPhase.UNAVAILABLE
    driver == "null" -> DesktopWindowsAudioOutputPhase.ERROR
    driver.isNullOrBlank() || output?.available != true -> DesktopWindowsAudioOutputPhase.OPENING
    fallbackToNull != false || device != requested.effectiveDeviceId -> DesktopWindowsAudioOutputPhase.ERROR
    requested.exclusive && driver == "wasapi" && exclusive == true && output.channelCount == 2 -> DesktopWindowsAudioOutputPhase.ACTIVE_EXCLUSIVE
    requested.exclusive -> DesktopWindowsAudioOutputPhase.ERROR
    exclusive == false -> DesktopWindowsAudioOutputPhase.ACTIVE_SHARED
    else -> DesktopWindowsAudioOutputPhase.ERROR
}

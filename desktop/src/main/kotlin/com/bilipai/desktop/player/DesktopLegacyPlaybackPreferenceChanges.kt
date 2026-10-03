package com.bilipai.desktop.player

/** Legacy Window preferences describe user intent only for fields changed in this action.
 * Values last cached by that UI may differ from newer original-player choices. */
internal data class DesktopLegacyPlaybackPreferenceChanges(
    val hardwareDecode: Boolean,
    val volume: Boolean,
    val speed: Boolean,
    val muted: Boolean,
    val audioOnly: Boolean,
    val loop: Boolean,
) {
    companion object {
        fun between(previous: PlayerPreferences, next: PlayerPreferences): DesktopLegacyPlaybackPreferenceChanges {
            val before = previous.normalized()
            val after = next.normalized()
            return DesktopLegacyPlaybackPreferenceChanges(
                before.hardwareDecodeEnabled != after.hardwareDecodeEnabled,
                before.volume != after.volume,
                before.speed != after.speed,
                before.muted != after.muted,
                before.audioOnly != after.audioOnly,
                before.playbackMode != after.playbackMode,
            )
        }
    }

    fun dispatch(next: PlayerPreferences, onHardwareDecode: (Boolean) -> Unit,
        onVolume: (Double) -> Unit, onSpeed: (Double) -> Unit, onMuted: (Boolean) -> Unit,
        onAudioOnly: (Boolean) -> Unit, onLoop: (Boolean) -> Unit,
        listenOnly: Boolean = false, sleepAfterTrack: Boolean = false) {
        val values = next.normalized()
        if (hardwareDecode) onHardwareDecode(values.hardwareDecodeEnabled)
        if (volume) onVolume(values.volume)
        if (speed) onSpeed(values.speed)
        if (muted) onMuted(values.muted)
        // Listen owns a separate audio-only native actor; changing the global video
        // view mode must not turn its video decoder back on or replay its speed.
        if (audioOnly && !listenOnly) onAudioOnly(values.audioOnly)
        if (loop) onLoop(values.playbackMode == PlaybackMode.REPEAT_ONE && !sleepAfterTrack)
    }

    fun mergeInto(current: PlayerPreferences, next: PlayerPreferences): PlayerPreferences = next.normalized().copy(
        hardwareDecodeEnabled = if (hardwareDecode) next.hardwareDecodeEnabled else current.hardwareDecodeEnabled,
        volume = if (volume) next.volume else current.volume,
        speed = if (speed) next.speed else current.speed,
        muted = if (muted) next.muted else current.muted,
        audioOnly = if (audioOnly) next.audioOnly else current.audioOnly,
        playbackMode = if (loop) next.playbackMode else current.playbackMode,
    ).normalized()
}

/** Actual existing MPV commands, not a second preferences consumer or fake decoder. */
internal fun MpvPlayer.applyLegacyPreferenceChanges(changes: DesktopLegacyPlaybackPreferenceChanges,
    next: PlayerPreferences, listenOnly: Boolean = false, sleepAfterTrack: Boolean = false) {
    changes.dispatch(next, { setHardwareDecodingEnabled(it) }, ::setVolume, ::setSpeed,
        ::setMuted, ::setAudioOnly, ::setLoop, listenOnly, sleepAfterTrack)
}

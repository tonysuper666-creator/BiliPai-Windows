package com.bilipai.desktop.player

/** Named event bridge, never reinterpret MPV's numeric codes as Media3 codes. */
internal enum class DesktopLiveFailureCode {
    ERROR_CODE_BEHIND_LIVE_WINDOW,
    ERROR_CODE_IO_BAD_HTTP_STATUS,
    ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    ERROR_CODE_IO_UNSPECIFIED,
    ERROR_CODE_DECODING_FAILED,
    ERROR_CODE_DECODER_INIT_FAILED,
    ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
    UNKNOWN;

    companion object {
        fun from(failure: PlayerFailure): DesktopLiveFailureCode = when (failure.kind) {
            PlayerFailureKind.NETWORK -> ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            PlayerFailureKind.DECODER -> ERROR_CODE_DECODING_FAILED
            PlayerFailureKind.AUDIO_OUTPUT -> ERROR_CODE_AUDIO_TRACK_INIT_FAILED
            else -> UNKNOWN
        }
    }
}

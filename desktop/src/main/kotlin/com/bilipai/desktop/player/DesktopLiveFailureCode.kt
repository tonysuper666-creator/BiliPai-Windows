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
        fun from(failure: PlayerFailure): DesktopLiveFailureCode = when {
            failure.kind == PlayerFailureKind.NETWORK && failure.httpStatus != null -> ERROR_CODE_IO_BAD_HTTP_STATUS
            failure.kind == PlayerFailureKind.NETWORK && failure.networkTimedOut -> ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            failure.kind == PlayerFailureKind.NETWORK -> ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            failure.kind == PlayerFailureKind.DECODER -> ERROR_CODE_DECODING_FAILED
            // libmpv MPV_ERROR_AO_INIT_FAILED (-14) identifies initialization,
            // not an audio write failure. No generic MPV EOF/seek/cache signal
            // proves Media3 ERROR_CODE_BEHIND_LIVE_WINDOW.
            failure.kind == PlayerFailureKind.AUDIO_OUTPUT && failure.nativeCode == -14 -> ERROR_CODE_AUDIO_TRACK_INIT_FAILED
            else -> UNKNOWN
        }
    }
}

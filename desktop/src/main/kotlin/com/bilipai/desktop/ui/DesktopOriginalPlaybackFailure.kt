package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.VideoLoadError
import com.bilipai.desktop.data.BiliApiException
import com.bilipai.desktop.player.PlayerFailure
import com.bilipai.desktop.player.PlayerFailureKind
import com.bilipai.desktop.player.platform.DesktopMedia3ErrorCodes

/** currentSourceSnapshot returns a new wrapper. Compare through the existing
 * native full-source admission, not wrapper identity or numeric version alone. */
internal fun desktopOriginalNativeFailureCurrent(
    player: com.bilipai.desktop.player.MpvPlayer,
    accepted: DesktopOriginalVideoAcceptedPublication,
    failure: com.bilipai.desktop.player.PlayerFailure,
): Boolean = player.ownsSourceSnapshot(accepted.nativeSource) &&
    failure.sourceVersion == accepted.sourceVersion && player.state.value.failure === failure


/** Evidence projected by the installed original owner, not a second player state. */
internal data class DesktopOriginalPlaybackFailureEvidence(
    val reason: PlaybackFailureReason,
    val code: Int,
    val message: String,
    val automaticRecovery: Boolean,
    val decoder: Boolean = false,
    val premiumAudio: Boolean = false,
)

/** Preserve a reliable API body code before BiliApiException's IOException base. */
internal fun desktopOriginalVideoLoadError(failure: Throwable): VideoLoadError =
    desktopOriginalApiCode(failure)?.takeIf { it < 0 }?.let {
        VideoLoadError.ApiError(it, when (it) {
            -101 -> "请登录后重试"
            -404 -> "视频不存在或已被删除"
            -403, -10403, -62002 -> "此视频暂时无法播放"
            else -> "视频请求失败"
        })
    } ?: desktopWindowsVideoLoadError(failure)

internal fun desktopOriginalVideoLoadCanRetry(failure: Throwable): Boolean =
    if (desktopOriginalApiCode(failure)?.let { it < 0 } == true)
        desktopOriginalVideoLoadError(failure).isRetryable()
    else desktopWindowsVideoLoadCanRetry(failure)

private fun desktopOriginalApiCode(failure: Throwable): Int? = when (failure) {
    is BiliApiException -> failure.apiCode
    is com.android.purebilibili.data.repository.ContentRequestException -> failure.code
    else -> null
}

internal fun desktopOriginalApiFailure(error: VideoLoadError): DesktopOriginalPlaybackFailureEvidence {
    val network = DesktopMedia3ErrorCodes.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    return when (error) {
        VideoLoadError.NetworkError -> DesktopOriginalPlaybackFailureEvidence(PlaybackFailureReason.Network, network,
            "网络连接失败", true)
        VideoLoadError.Timeout -> DesktopOriginalPlaybackFailureEvidence(PlaybackFailureReason.Network,
            DesktopMedia3ErrorCodes.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, "加载超时", true)
        is VideoLoadError.ApiError -> when (error.code) {
            -101 -> DesktopOriginalPlaybackFailureEvidence(PlaybackFailureReason.Authentication, error.code, "请登录后重试", false)
            -403, -404, -10403, -62002 -> DesktopOriginalPlaybackFailureEvidence(PlaybackFailureReason.Restricted,
                error.code, error.toUserMessage(), false)
            -504, -502, -500 -> DesktopOriginalPlaybackFailureEvidence(PlaybackFailureReason.Network,
                DesktopMedia3ErrorCodes.ERROR_CODE_IO_BAD_HTTP_STATUS, error.toUserMessage(), true)
            // The existing UseCase owns HTTP/WBI cooldowns. Do not turn them into a retry loop.
            else -> DesktopOriginalPlaybackFailureEvidence(PlaybackFailureReason.Source, error.code, error.toUserMessage(), false)
        }
        VideoLoadError.VideoNotFound, VideoLoadError.RegionRestricted, VideoLoadError.VipRequired ->
            DesktopOriginalPlaybackFailureEvidence(PlaybackFailureReason.Restricted, 0, error.toUserMessage(), false)
        is VideoLoadError.RateLimited, is VideoLoadError.GlobalCooldown, VideoLoadError.WbiSignatureError ->
            DesktopOriginalPlaybackFailureEvidence(PlaybackFailureReason.Source, 0, error.toUserMessage(), false)
        else -> DesktopOriginalPlaybackFailureEvidence(PlaybackFailureReason.Source, 0,
            if (error is VideoLoadError.UnknownError) "视频加载失败，请重试" else error.toUserMessage(), true)
    }
}

internal fun desktopOriginalNativeFailure(failure: PlayerFailure, premiumAudio: Boolean): DesktopOriginalPlaybackFailureEvidence =
    DesktopOriginalPlaybackFailureEvidence(
        reason = when (failure.kind) {
            PlayerFailureKind.NETWORK -> PlaybackFailureReason.Network
            PlayerFailureKind.DECODER -> PlaybackFailureReason.Decoder
            PlayerFailureKind.AUDIO_OUTPUT -> PlaybackFailureReason.Audio
            else -> PlaybackFailureReason.Source
        },
        code = when (failure.kind) {
            PlayerFailureKind.NETWORK -> when {
                failure.httpStatus != null -> DesktopMedia3ErrorCodes.ERROR_CODE_IO_BAD_HTTP_STATUS
                failure.networkTimedOut -> DesktopMedia3ErrorCodes.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                else -> DesktopMedia3ErrorCodes.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            }
            else -> failure.nativeCode ?: 0
        },
        message = failure.safeMessage,
        // Local output/file failures cannot diagnose a remote CDN, or change persistent output settings.
        automaticRecovery = failure.kind in setOf(PlayerFailureKind.NETWORK, PlayerFailureKind.DECODER,
            PlayerFailureKind.UNSUPPORTED, PlayerFailureKind.UNKNOWN) || premiumAudio,
        decoder = failure.kind == PlayerFailureKind.DECODER,
        premiumAudio = premiumAudio,
    )

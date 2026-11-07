package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.VideoLoadError
import com.bilipai.desktop.data.BiliApiException

/** Windows HTTP interceptors use IOException so OkHttp delivers onFailure.
 * Preserve their reliable status before the original generic IOException branch.
 * This boundary has no transport, login, Cookie, retry, or preference side effect.
 */
internal fun desktopWindowsVideoLoadError(failure: Throwable): VideoLoadError =
    when ((failure as? BiliApiException)?.apiCode) {
        412 -> VideoLoadError.ApiError(412,
            "Bilibili 暂时限制了此请求（HTTP 412）。请稍后重试或登录后重试。")
        429 -> VideoLoadError.ApiError(429,
            "Bilibili 请求过于频繁（HTTP 429），请稍后重试。")
        else -> VideoLoadError.fromException(failure)
    }

/** Keep the retry availability previously supplied by IOException/NetworkError.
 * The original UseCase still enforces its existing cooldown before any request.
 */
internal fun desktopWindowsVideoLoadCanRetry(failure: Throwable): Boolean =
    when ((failure as? BiliApiException)?.apiCode) {
        412, 429 -> true
        else -> VideoLoadError.fromException(failure).isRetryable()
    }

/** Only controlled copy reaches the page. API messages, native errors and exception text stay in diagnostics. */
internal fun desktopWindowsVideoErrorMessage(error: VideoLoadError?): String = when (error) {
    VideoLoadError.NetworkError -> "请检查网络连接后重试。"
    VideoLoadError.Timeout -> "连接超时，请稍后重试。"
    VideoLoadError.VideoNotFound -> "视频不存在或已被删除，请返回选择其他视频。"
    VideoLoadError.RegionRestricted -> "该视频在当前地区不可用，请返回选择其他视频。"
    VideoLoadError.VipRequired -> "该视频需要大会员，请确认账号权限后重试。"
    is VideoLoadError.RateLimited, is VideoLoadError.GlobalCooldown, VideoLoadError.WbiSignatureError ->
        "请求暂时受限，请稍后重试。"
    else -> "请稍后重试，或返回选择其他视频。"
}

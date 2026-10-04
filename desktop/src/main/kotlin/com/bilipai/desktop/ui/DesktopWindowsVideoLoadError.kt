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

package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.CaptchaData
import com.android.purebilibili.feature.login.RiskVerifyParams
import java.util.Base64

data class DesktopStoredAccountInfo(val account: AccountSummary, val lastUsedAt: Long,
    val hasAccessToken: Boolean, val accessTokenPlatform: String)

/** Data receipt minted only by the existing primary Store after a real login save. */
data class DesktopLoginInstallationReceipt internal constructor(
    val sourceEpoch: Long,
    val sourceMid: Long?,
    val acceptedEpoch: Long,
    val acceptedMid: Long,
)

internal data class DesktopAppCredentials(val accessToken: String, val refreshToken: String,
    val platform: String, val expiresAt: Long = 0)

internal object DesktopLoginBase64 {
    const val DEFAULT = 0
    const val NO_WRAP = 2
    @Suppress("UNUSED_PARAMETER") fun decode(value: String, flags: Int): ByteArray = Base64.getMimeDecoder().decode(value)
    @Suppress("UNUSED_PARAMETER") fun encodeToString(value: ByteArray, flags: Int): String = Base64.getEncoder().encodeToString(value)
}

data class DesktopCaptchaResult(val challenge: String, val validate: String, val seccode: String)

sealed interface DesktopLoginResult {
    data class Complete(val account: AccountSummary) : DesktopLoginResult
    data class CaptchaRequired(val captcha: CaptchaData) : DesktopLoginResult
    data class RiskRequired(val params: RiskVerifyParams, val hiddenPhone: String, val message: String) : DesktopLoginResult
}

data class DesktopSmsSession(val phone: String, val countryCode: Int, val captchaKey: String)
sealed interface DesktopSmsResult {
    data class Sent(val session: DesktopSmsSession) : DesktopSmsResult
    data class CaptchaRequired(val captcha: CaptchaData) : DesktopSmsResult
}

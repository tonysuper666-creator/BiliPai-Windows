// Transition compat: upstream removed or changed these core-data APIs while the not-yet-migrated
// legacy video/profile copies still call the old shapes. Delete together with those copies.
package com.android.purebilibili.data.repository

import com.android.purebilibili.core.network.BilibiliApi
import com.bilipai.desktop.ui.DesktopProfileAccountPort

/** v0.2.5 core-data VideoLoadPolicy.resolveInitialStartQuality (upstream now takes only targetQuality). */
fun resolveInitialStartQuality(
    targetQuality: Int?,
    isAutoHighestQuality: Boolean,
    isLogin: Boolean,
    isVip: Boolean,
    auto1080pEnabled: Boolean
): Int = when {
    isAutoHighestQuality && isVip -> 127
    isAutoHighestQuality && isLogin -> 80
    isAutoHighestQuality -> 64
    targetQuality != null -> targetQuality
    isVip -> 116
    isLogin && auto1080pEnabled -> 80
    isLogin -> 64
    else -> 32
}

/** v0.2.5 core-data VideoLoadPolicy.shouldAcceptCachedPlayUrlForAutoHighest (removed upstream). */
fun shouldAcceptCachedPlayUrlForAutoHighest(
    isAutoHighestQuality: Boolean,
    isVip: Boolean,
    cachedDashVideoIds: List<Int>,
    minPremiumQuality: Int = 112
): Boolean {
    if (!isAutoHighestQuality || !isVip) return true
    return cachedDashVideoIds.any { it >= minPremiumQuality }
}

/** Upstream now requires an explicit account cookie. It is built like upstream ProfileAccountRepository,
 *  but from the desktop account store: Windows has no process-wide TokenManager. */
internal suspend fun legacyUpdateMemberSign(api: BilibiliApi, accounts: DesktopProfileAccountPort, userSign: String, csrf: String) =
    api.updateMemberSign(userSign = userSign, csrf = csrf, cookie = legacyMemberSignCookie(accounts, csrf))

internal suspend fun legacyMemberSignCookie(accounts: DesktopProfileAccountPort, csrf: String): String {
    val mid = requireNotNull(accounts.getActiveAccountMid()?.takeIf { it > 0 }) { "请先登录" }
    val session = requireNotNull(accounts.getAccounts().firstOrNull { it.mid == mid }?.sessData?.takeIf { it.isNotBlank() }) { "登录已失效，请重新登录" }
    require(csrf.isNotBlank()) { "登录已失效，请重新登录" }
    return "SESSDATA=$session; bili_jct=$csrf; DedeUserID=$mid"
}

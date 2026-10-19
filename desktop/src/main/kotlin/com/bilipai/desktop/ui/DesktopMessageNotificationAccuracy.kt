package com.bilipai.desktop.ui

import com.android.purebilibili.feature.message.notification.AccountNotificationState

/** The original DynAllPersonal preload refuses to run without an App access token ("a web cookie cannot
 * replace it"). This Windows tree only holds such a token for an account that signed in with the TV
 * authorization; a web-only session has none. The preload then fails before any request, no baseline is
 * committed, and the dynamic category stays inactive. This is the original message word for word.
 *
 * Scope of the token gate: the recent-author red-dot snapshot (portal and uplist) needs no token itself,
 * but the original Poller reads it only after the following-list read and the preload, both of which are
 * behind this gate. A web-only account therefore receives no dynamic notifications at all; the snapshot
 * endpoints give it nothing observable. */
internal const val DESKTOP_DYNAMIC_PRELOAD_APP_AUTHORIZATION_REASON = "动态预加载需要 App 登录授权，网页 Cookie 不能替代"

/** Null when the preload may run, otherwise the reason it cannot. Never a substitute result. */
internal fun desktopDynamicPreloadBlockedReason(accessToken: String?): String? =
    if (accessToken.isNullOrBlank()) DESKTOP_DYNAMIC_PRELOAD_APP_AUTHORIZATION_REASON else null

/** Stops the dynamic category before its first request when the preload could not run for any author.
 * The original Poller reads the following list only for that category, so this ends it with the reason
 * (the category rolls back and commits no baseline) instead of fetching pages that cannot be used. */
internal fun requireDesktopDynamicPreloadAuthorization(accessToken: String?) {
    desktopDynamicPreloadBlockedReason(accessToken)?.let { throw IllegalStateException(it) }
}

/** Unread items per notification group in a committed baseline; the Windows menu uses it to end a group
 * summary entry once the server says everything it summarised was read. */
internal fun desktopPendingSummaryCounts(state: AccountNotificationState): Map<String, Int> = mapOf(
    "dynamic" to state.pendingDynamicIds.size,
    "dm" to state.pendingPrivateSessionKeys.size,
)

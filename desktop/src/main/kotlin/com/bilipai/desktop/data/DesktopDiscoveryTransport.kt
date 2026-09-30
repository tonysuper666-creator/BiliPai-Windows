package com.bilipai.desktop.data

import com.android.purebilibili.core.network.applyDesktopMergedFeedHeaders
import com.android.purebilibili.core.network.policy.shouldStripMergedAppFeedCookies
import com.android.purebilibili.core.network.policy.HomeFeedAnonymizerRuntime
import com.android.purebilibili.core.network.policy.resolveHomeFeedCookieAnonymizerDecision
import okhttp3.HttpUrl
import okhttp3.Request

internal fun isDesktopMergedFeedRequest(url: HttpUrl): Boolean = shouldStripMergedAppFeedCookies(
    host = url.host, encodedPath = url.encodedPath, mobiApp = url.queryParameter("mobi_app"))

internal fun applyDesktopMergedRecommendationHeaders(builder: Request.Builder, url: HttpUrl, buvid: String): Request.Builder =
    if (isDesktopMergedFeedRequest(url)) applyDesktopMergedFeedHeaders(builder, buvid) else builder

/** Called after OkHttp's CookieJar and explicit forced-cookie override. */
internal fun stripDesktopMergedFeedCookie(request: Request): Request =
    if (isDesktopMergedFeedRequest(request.url)) request.newBuilder().removeHeader("Cookie").build() else request

internal fun stripDesktopAnonymousHomeFeedCookie(request: Request, enabled: Boolean = HomeFeedAnonymizerRuntime.enabled): Request =
    if (resolveHomeFeedCookieAnonymizerDecision(enabled, request.url.host, request.url.encodedPath))
        request.newBuilder().removeHeader("Cookie").build() else request

package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.*
import com.android.purebilibili.core.store.DesktopFeedSettings
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.ui.DesktopHomeProtocolEnvironment
import kotlinx.coroutines.*

internal fun shouldStartHomePreload(
    hasPreloadedData: Boolean,
    hasActivePreloadTask: Boolean
): Boolean {
    return !hasPreloadedData && !hasActivePreloadTask
}

internal fun shouldPrimeBuvidForHomePreload(feedApiType: DesktopFeedSettings.FeedApiType): Boolean {
    return feedApiType == DesktopFeedSettings.FeedApiType.MOBILE
}

internal fun shouldReuseInFlightPreloadForHomeRequest(
    idx: Int,
    isPreloading: Boolean,
    hasPreloadedData: Boolean
): Boolean {
    return idx == 0 && isPreloading && !hasPreloadedData
}

internal fun shouldReportHomeDataReadyForSplash(
    hasCompletedPreload: Boolean,
    hasPreloadedData: Boolean
): Boolean {
    return hasCompletedPreload || hasPreloadedData
}

internal fun resolveHomeFeedWbiKeys(
    cachedKeys: Pair<String, String>?,
    navWbiImg: WbiImg?
): Pair<String, String>? {
    if (cachedKeys != null) return cachedKeys
    val wbiImg = navWbiImg ?: return null
    val imgKey = wbiImg.img_url.substringAfterLast("/").substringBefore(".")
    val subKey = wbiImg.sub_url.substringAfterLast("/").substringBefore(".")
    return if (imgKey.isNotEmpty() && subKey.isNotEmpty()) imgKey to subKey else null
}

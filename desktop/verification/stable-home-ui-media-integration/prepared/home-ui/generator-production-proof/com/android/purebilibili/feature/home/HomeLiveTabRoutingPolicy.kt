// Original source app/src/main/java/com/android/purebilibili/feature/home/HomeLiveTabRoutingPolicy.kt
// LF SHA256 bc58b113c871cc7aac5e71698b2607b4bb69a10f74bf15e19450ad866f95f921
package com.android.purebilibili.feature.home



fun shouldEmbedLivePageInHomeTopTab(category: HomeCategory): Boolean =
    category == HomeCategory.LIVE

fun shouldEmbedBangumiPageInHomeTopTab(category: HomeCategory): Boolean =
    category == HomeCategory.ANIME

enum class HomeTopTabScrollTarget {
    FEED,
    LIVE,
    BANGUMI,
    PARTITION,
    SUBSCRIPTION,
}

fun resolveHomeTopTabScrollTarget(entry: HomeTopTabEntry?): HomeTopTabScrollTarget {
    return when (entry) {
        HomeTopTabEntry.Partition -> HomeTopTabScrollTarget.PARTITION
        HomeTopTabEntry.Subscriptions -> HomeTopTabScrollTarget.SUBSCRIPTION
        is HomeTopTabEntry.Category -> when {
            shouldEmbedLivePageInHomeTopTab(entry.category) -> HomeTopTabScrollTarget.LIVE
            shouldEmbedBangumiPageInHomeTopTab(entry.category) -> HomeTopTabScrollTarget.BANGUMI
            else -> HomeTopTabScrollTarget.FEED
        }
        null -> HomeTopTabScrollTarget.FEED
    }
}

package com.android.purebilibili.navigation
internal fun resolveBottomPagerNavigationDurationMillis(pageDistance: Int): Int {
    val distance = pageDistance.coerceAtLeast(2)
    return distance * 100 + 100
}

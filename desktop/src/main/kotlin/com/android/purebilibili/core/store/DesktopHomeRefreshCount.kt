package com.android.purebilibili.core.store

// Upstream v0.7.8 removed the home refresh count setting (DEFAULT_HOME_REFRESH_COUNT stays, now 12).
// The desktop keeps its existing 10..30 setting until that removal is followed on its own card.
internal const val MIN_HOME_REFRESH_COUNT = 10
internal const val MAX_HOME_REFRESH_COUNT = 30

internal fun normalizeHomeRefreshCount(count: Int): Int {
    return count.coerceIn(MIN_HOME_REFRESH_COUNT, MAX_HOME_REFRESH_COUNT)
}

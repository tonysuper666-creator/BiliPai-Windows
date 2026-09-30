// GENERATED from app/src/main/java/com/android/purebilibili/data/repository/DynamicFeedFetchPolicy.kt; do not edit.
// LF-normalized SHA-256: 983870a2751830f8a7fb3681fff7a1c2d124c27893c152320763e16376816d9d
package com.android.purebilibili.data.repository

internal const val DYNAMIC_EMPTY_PAGE_FETCH_LIMIT = 3

internal fun shouldContinueDynamicFetchAfterFilter(
    accumulatedVisibleCount: Int,
    hasMore: Boolean,
    previousOffset: String,
    nextOffset: String,
    pagesFetched: Int,
    maxPages: Int = DYNAMIC_EMPTY_PAGE_FETCH_LIMIT
): Boolean {
    if (accumulatedVisibleCount > 0) return false
    if (!hasMore) return false
    if (pagesFetched >= maxPages) return false

    val previous = previousOffset.trim()
    val next = nextOffset.trim()
    if (next.isBlank()) return false
    if (next == previous) return false

    return true
}

internal fun shouldContinueDynamicIncrementalFetch(
    accumulatedItemCount: Int,
    updateNum: Int,
    hasMore: Boolean,
    previousOffset: String,
    nextOffset: String
): Boolean {
    if (accumulatedItemCount >= updateNum.coerceAtLeast(0)) return false
    if (!hasMore) return false

    val previous = previousOffset.trim()
    val next = nextOffset.trim()
    if (next.isBlank()) return false
    if (next == previous) return false

    return true
}

internal fun resolveDynamicFeedUpdateBaseline(
    currentBaseline: String,
    responseBaseline: String,
    pagesFetched: Int
): String {
    if (pagesFetched > 0) return currentBaseline
    return responseBaseline.ifBlank { currentBaseline }
}

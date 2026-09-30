// GENERATED from app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicScreenStatePolicy.kt; do not edit.
// LF-normalized SHA-256: 49dec34dcbd22dd89f1468e18b87e99e309ff646badc836174b81065d39bff0c
package com.android.purebilibili.feature.dynamic
import com.android.purebilibili.core.util.*
import com.android.purebilibili.data.model.response.DynamicItem
import kotlinx.collections.immutable.toImmutableList

enum class DynamicFeedErrorSource {
    NONE,
    INITIAL_LOAD,
    REFRESH,
    APPEND
}

internal fun resolveDynamicTimelinePageForLoadStart(
    currentPage: DynamicTimelinePageState,
    refresh: Boolean,
    showLoading: Boolean
): DynamicTimelinePageState {
    val basePage = currentPage.copy(
        error = null,
        errorSource = DynamicFeedErrorSource.NONE
    )
    return when {
        refresh && showLoading -> basePage.copy(isLoading = true)
        !refresh -> basePage.copy(isLoading = true)
        else -> basePage
    }
}

internal fun resolveDynamicTimelinePageAfterSuccess(
    currentPage: DynamicTimelinePageState,
    incomingItems: List<DynamicItem>,
    isRefresh: Boolean,
    incrementalRefreshEnabled: Boolean,
    hasMore: Boolean
): DynamicTimelinePageState {
    val currentItems = currentPage.items
    val canUseIncrementalRefresh = canPerformIncrementalTimelineRefresh(
        isRefresh = isRefresh,
        incrementalRefreshEnabled = incrementalRefreshEnabled,
        isCachePlaceholder = currentPage.isCachePlaceholder,
        existingItems = currentItems,
        incomingItems = incomingItems
    )
    val mergedItems = when {
        canUseIncrementalRefresh -> sortDynamicTimelineItemsByPublishTime(
            prependDistinctByKey(
                existing = currentItems,
                incoming = incomingItems,
                keySelector = ::dynamicFeedItemKey
            )
        )
        isRefresh -> sortDynamicTimelineItemsByPublishTime(incomingItems)
        else -> appendDistinctByKey(
            existing = currentItems,
            incoming = incomingItems,
            keySelector = ::dynamicFeedItemKey
        )
    }
    val boundary = when {
        canUseIncrementalRefresh -> resolveIncrementalRefreshBoundary(
            existingKeys = currentItems.map(::dynamicFeedItemKey),
            mergedKeys = mergedItems.map(::dynamicFeedItemKey)
        )
        isRefresh -> IncrementalRefreshBoundary(null, 0)
        else -> IncrementalRefreshBoundary(
            currentPage.incrementalRefreshBoundaryKey,
            currentPage.incrementalPrependedCount
        )
    }
    return currentPage.copy(
        items = mergedItems.toImmutableList(),
        isLoading = false,
        error = null,
        hasMore = hasMore,
        incrementalRefreshBoundaryKey = boundary.boundaryKey,
        incrementalPrependedCount = boundary.prependedCount,
        errorSource = DynamicFeedErrorSource.NONE,
        isCachePlaceholder = false
    )
}

internal fun resolveDynamicTimelinePageAfterFailure(
    currentPage: DynamicTimelinePageState,
    errorMessage: String,
    refresh: Boolean
): DynamicTimelinePageState {
    val source = when {
        currentPage.items.isEmpty() -> DynamicFeedErrorSource.INITIAL_LOAD
        refresh -> DynamicFeedErrorSource.REFRESH
        else -> DynamicFeedErrorSource.APPEND
    }
    return currentPage.copy(
        isLoading = false,
        error = errorMessage,
        errorSource = source
    )
}

internal fun sortDynamicTimelineItemsByPublishTime(items: List<DynamicItem>): List<DynamicItem> {
    if (items.size <= 1) return items
    return items
        .mapIndexed { index, item -> index to item }
        .sortedWith(
            compareByDescending<Pair<Int, DynamicItem>> { (_, item) ->
                item.modules.module_author?.pub_ts ?: 0L
            }.thenBy { (index, _) -> index }
        )
        .map { (_, item) -> item }
}

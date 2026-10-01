// GENERATED from app/src/main/java/com/android/purebilibili/data/repository/DynamicRepository.kt; do not edit.
// LF-normalized SHA-256: 890574b7e97781458fcd6d393068d54c30864270ed914a33683d2815b3f0d63b
package com.android.purebilibili.data.repository
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

/** Original fetch loop/pagination; only its task-owned transport and cancellation are bound. */
internal class DesktopOriginalDynamicTimelineRepository(
    private val requestPage: suspend (type:String,offset:String,updateBaseline:String)->DynamicFeedResponse,
    private val stillOwned: ()->Boolean = {true},
) {
    private val feedPagination=DynamicFeedPaginationRegistry()
    /** One request's transient checkpoint; the original registry remains the only cursor owner. */
    fun checkpointForFollowChange(type:String)=feedPagination.snapshot(DynamicFeedScope.DYNAMIC_SCREEN,type)
    fun restoreAfterFollowChange(type:String,before:DynamicPaginationState) {
        feedPagination.updateState(DynamicFeedScope.DYNAMIC_SCREEN,type,before)
    }
    private suspend fun getPage(type:String,offset:String,updateBaseline:String):DynamicFeedResponse {
        currentCoroutineContext().ensureActive()
        if(!stillOwned()) throw kotlinx.coroutines.CancellationException("Dynamic source retired")
        val response=requestPage(type,offset,updateBaseline)
        currentCoroutineContext().ensureActive()
        if(!stillOwned()) throw kotlinx.coroutines.CancellationException("Dynamic source retired")
        return response
    }
    suspend fun getDynamicFeed(
        refresh: Boolean = false,
        scope: DynamicFeedScope = DynamicFeedScope.DYNAMIC_SCREEN,
        type: String = "all",
        incrementalRefresh: Boolean = false
    ): Result<DynamicFeedFetchResult> = withContext(Dispatchers.IO) {
        try {
            val paginationBeforeRefresh = feedPagination.snapshot(scope, type)
            val useIncrementalRefresh = shouldUseDynamicIncrementalRefresh(
                refresh = refresh,
                incrementalRefreshEnabled = incrementalRefresh,
                updateBaseline = paginationBeforeRefresh.updateBaseline
            )
            if (refresh && !useIncrementalRefresh) {
                feedPagination.reset(scope, type)
            }
            val paginationForPageUpdate = if (refresh && !useIncrementalRefresh) {
                DynamicPaginationState()
            } else {
                paginationBeforeRefresh
            }
            if (!feedPagination.hasMore(scope, type) && !refresh) {
                return@withContext Result.success(
                    DynamicFeedFetchResult(
                        items = emptyList(),
                        updateNum = 0,
                        usedUpdateBaseline = false,
                        nextOffset = feedPagination.offset(scope, type),
                        hasMore = false
                    )
                )
            }

            val visibleItems = mutableListOf<DynamicItem>()
            var pagesFetched = 0
            var fetchedItemCount = 0
            var reportedUpdateNum = 0
            var resolvedUpdateBaseline = paginationForPageUpdate.updateBaseline
            var requestOffset = if (refresh) "" else feedPagination.offset(scope, type)
            while (true) {
                val previousOffset = requestOffset
                val requestUpdateBaseline = if (previousOffset.isBlank() && useIncrementalRefresh) {
                    paginationBeforeRefresh.updateBaseline
                } else {
                    ""
                }
                val response = fetchDynamicFeedPageWithRetry {
                    getPage(
                        type = type,
                        offset = previousOffset,
                        updateBaseline = requestUpdateBaseline
                    )
                }.getOrElse { error ->
                    return@withContext Result.failure(error)
                }

                val data = response.data
                if (data == null) {
                    feedPagination.updateState(
                        scope = scope,
                        type = type,
                        state = resolveDynamicPaginationStateAfterPage(
                            paginationBeforeRefresh = paginationForPageUpdate,
                            responseOffset = previousOffset,
                            responseUpdateBaseline = "",
                            responseHasMore = false,
                            preserveExistingPagination = useIncrementalRefresh,
                            reportedUpdateNum = reportedUpdateNum
                        )
                    )
                    break
                }

                if (pagesFetched == 0) {
                    // 首包的 update_num 才是「相对 update_baseline 的新动态数」
                    reportedUpdateNum = data.update_num.coerceAtLeast(0)
                }
                resolvedUpdateBaseline = resolveDynamicFeedUpdateBaseline(
                    currentBaseline = resolvedUpdateBaseline,
                    responseBaseline = data.update_baseline,
                    pagesFetched = pagesFetched
                )

                // 更新分页状态
                requestOffset = data.offset
                feedPagination.updateState(
                    scope = scope,
                    type = type,
                    state = resolveDynamicPaginationStateAfterPage(
                        paginationBeforeRefresh = paginationForPageUpdate,
                        responseOffset = data.offset,
                        responseUpdateBaseline = resolvedUpdateBaseline,
                        responseHasMore = data.has_more,
                        preserveExistingPagination = useIncrementalRefresh,
                        reportedUpdateNum = reportedUpdateNum
                    )
                )

                // Keep folded/hidden cards in the timeline. PiliPlus only shrinks
                // `visible == false` items in UI and unfolds them from module_fold;
                // dropping them here swallows the hours between two visible posts.
                visibleItems += data.items
                fetchedItemCount += data.items.size
                pagesFetched += 1

                val shouldContinue = if (useIncrementalRefresh) {
                    shouldContinueDynamicIncrementalFetch(
                        accumulatedItemCount = fetchedItemCount,
                        updateNum = reportedUpdateNum,
                        hasMore = data.has_more,
                        previousOffset = previousOffset,
                        nextOffset = data.offset
                    )
                } else {
                    shouldContinueDynamicFetchAfterFilter(
                        accumulatedVisibleCount = visibleItems.size,
                        hasMore = data.has_more,
                        previousOffset = previousOffset,
                        nextOffset = data.offset,
                        pagesFetched = pagesFetched
                    )
                }
                if (!shouldContinue) {
                    break
                }
            }

            Result.success(
                DynamicFeedFetchResult(
                    items = visibleItems,
                    updateNum = reportedUpdateNum,
                    usedUpdateBaseline = useIncrementalRefresh,
                    nextOffset = requestOffset,
                    hasMore = feedPagination.hasMore(scope, type)
                )
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // Windows boundary never logs raw response/URL exception details.
            Result.failure(e)
        }
    }

    fun currentUpdateBaseline(
        scope: DynamicFeedScope = DynamicFeedScope.DYNAMIC_SCREEN,
        type: String = "all"
    ): String = feedPagination.updateBaseline(scope, type)

    fun hasMoreData(
        scope: DynamicFeedScope = DynamicFeedScope.DYNAMIC_SCREEN,
        type: String = "all"
    ): Boolean {
        return feedPagination.hasMore(scope, type)
    }

    fun syncPaginationAfterRefresh(
        scope: DynamicFeedScope,
        type: String = "all",
        offset: String,
        updateBaseline: String = "",
        hasMore: Boolean = true
    ) {
        feedPagination.update(
            scope = scope,
            type = type,
            offset = offset,
            updateBaseline = updateBaseline.ifBlank { feedPagination.updateBaseline(scope, type) },
            hasMore = hasMore
        )
    }

    private suspend fun fetchDynamicFeedPageWithRetry(
        request: suspend () -> DynamicFeedResponse
    ): Result<DynamicFeedResponse> {
        var lastError: Throwable? = null
        for (attempt in 1..DYNAMIC_FETCH_MAX_ATTEMPTS) {
            try {
                val response = request()
                if (response.code == 0) {
                    return Result.success(response)
                }
                val shouldRetry = attempt < DYNAMIC_FETCH_MAX_ATTEMPTS &&
                    isRetryableDynamicApiError(response.code, response.message)
                if (shouldRetry) {
                    delay(resolveDynamicRetryDelayMs(attempt))
                    continue
                }
                val message = resolveDynamicFriendlyErrorMessage(response.code, response.message)
                return Result.failure(Exception(message))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                val shouldRetry = attempt < DYNAMIC_FETCH_MAX_ATTEMPTS &&
                    isRetryableDynamicException(error)
                if (shouldRetry) {
                    delay(resolveDynamicRetryDelayMs(attempt))
                    continue
                }
                val message = resolveDynamicFriendlyErrorMessage(code = -1, message = error.message.orEmpty())
                return Result.failure(Exception(message, error))
            }
        }
        val message = resolveDynamicFriendlyErrorMessage(code = -1, message = lastError?.message.orEmpty())
        return Result.failure(Exception(message, lastError))
    }
}

internal fun shouldUseDynamicIncrementalRefresh(
    refresh: Boolean,
    incrementalRefreshEnabled: Boolean,
    updateBaseline: String
): Boolean {
    return refresh && incrementalRefreshEnabled && updateBaseline.isNotBlank()
}

internal fun resolveDynamicPaginationStateAfterPage(
    paginationBeforeRefresh: DynamicPaginationState,
    responseOffset: String,
    responseUpdateBaseline: String,
    responseHasMore: Boolean,
    preserveExistingPagination: Boolean,
    reportedUpdateNum: Int = -1
): DynamicPaginationState {
    val nextBaseline = responseUpdateBaseline.ifBlank {
        paginationBeforeRefresh.updateBaseline
    }
    val canPreserve = preserveExistingPagination &&
        paginationBeforeRefresh.offset.isNotBlank() &&
        reportedUpdateNum != 0

    return if (canPreserve) {
        paginationBeforeRefresh.copy(updateBaseline = nextBaseline)
    } else {
        DynamicPaginationState(
            offset = responseOffset,
            updateBaseline = nextBaseline,
            hasMore = responseHasMore
        )
    }
}

enum class DynamicFeedScope {
    DYNAMIC_SCREEN,
    HOME_FOLLOW
}

data class DynamicFeedFetchResult(
    val items: List<DynamicItem>,
    val updateNum: Int = 0,
    val usedUpdateBaseline: Boolean = false,
    val nextOffset: String = "",
    val hasMore: Boolean = true
)

internal data class DynamicPaginationState(
    var offset: String = "",
    var updateBaseline: String = "",
    var hasMore: Boolean = true
)

internal data class DynamicFeedPaginationKey(
    val scope: DynamicFeedScope,
    val type: String
)

internal class DynamicFeedPaginationRegistry {
    private val stateByScope = mutableMapOf<DynamicFeedPaginationKey, DynamicPaginationState>()

    fun reset(scope: DynamicFeedScope, type: String = "all") {
        stateByScope[DynamicFeedPaginationKey(scope = scope, type = type)] = DynamicPaginationState()
    }

    fun update(
        scope: DynamicFeedScope,
        type: String = "all",
        offset: String,
        updateBaseline: String = "",
        hasMore: Boolean
    ) {
        stateByScope[DynamicFeedPaginationKey(scope = scope, type = type)] =
            DynamicPaginationState(
                offset = offset,
                updateBaseline = updateBaseline,
                hasMore = hasMore
            )
    }

    fun updateState(
        scope: DynamicFeedScope,
        type: String = "all",
        state: DynamicPaginationState
    ) {
        stateByScope[DynamicFeedPaginationKey(scope = scope, type = type)] = state.copy()
    }

    fun snapshot(
        scope: DynamicFeedScope,
        type: String = "all"
    ): DynamicPaginationState {
        return stateByScope[DynamicFeedPaginationKey(scope = scope, type = type)]?.copy()
            ?: DynamicPaginationState()
    }

    fun offset(scope: DynamicFeedScope, type: String = "all"): String {
        return stateByScope[DynamicFeedPaginationKey(scope = scope, type = type)]?.offset.orEmpty()
    }

    fun updateBaseline(scope: DynamicFeedScope, type: String = "all"): String {
        return stateByScope[DynamicFeedPaginationKey(scope = scope, type = type)]?.updateBaseline.orEmpty()
    }

    fun updateBaseline(
        scope: DynamicFeedScope,
        type: String = "all",
        updateBaseline: String
    ) {
        val key = DynamicFeedPaginationKey(scope = scope, type = type)
        val current = stateByScope[key] ?: DynamicPaginationState()
        stateByScope[key] = current.copy(updateBaseline = updateBaseline)
    }

    fun hasMore(scope: DynamicFeedScope, type: String = "all"): Boolean {
        return stateByScope[DynamicFeedPaginationKey(scope = scope, type = type)]?.hasMore ?: true
    }
}

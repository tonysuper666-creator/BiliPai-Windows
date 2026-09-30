package com.android.purebilibili.feature.dynamic
import com.android.purebilibili.data.model.response.DynamicItem
import kotlinx.collections.immutable.*

data class DynamicUiState(
    val items: ImmutableList<DynamicItem> = persistentListOf(),
    val userItems: ImmutableList<DynamicItem> = persistentListOf(), //  [新增] 选中 UP主的动态
    val timelineRequestType: String = "all",
    val isLoading: Boolean = false,
    val error: String? = null,
    val userIsLoading: Boolean = false,
    val userError: String? = null,
    val hasMore: Boolean = true,
    val hasUserMore: Boolean = true, //  [新增] UP主动态是否有更多
    val incrementalRefreshBoundaryKey: String? = null,
    val incrementalPrependedCount: Int = 0,
    val errorSource: DynamicFeedErrorSource = DynamicFeedErrorSource.NONE,
    val timelinePages: PersistentMap<String, DynamicTimelinePageState> = persistentMapOf(),
    // 用户明确标记不感兴趣的动态 id，持久化后刷新和重启仍会过滤。
    val tempBannedDynamicIds: ImmutableSet<String> = persistentSetOf(),
    //  [新增] 关注 UP 列表未读（有更新的 mid 集合，来自 uplist 接口）
    val uplistUpdateMids: ImmutableSet<Long> = persistentSetOf()
)

internal fun resolveDynamicStateAfterAuthorUnfollow(
    currentState: DynamicUiState,
    authorMid: Long
): DynamicUiState {
    if (authorMid <= 0L) return currentState
    return mapDynamicTimelineItems(currentState) { items ->
        items.filterNot { it.modules.module_author?.mid == authorMid }
    }.copy(
        userItems = currentState.userItems.filterNot { it.modules.module_author?.mid == authorMid }.toImmutableList()
    )
}

internal fun resolveFollowedUsersAfterAuthorUnfollow(
    users: List<SidebarUser>,
    authorMid: Long
): List<SidebarUser> {
    if (authorMid <= 0L) return users
    return users.filterNot { it.uid == authorMid }
}

internal fun DynamicUiState.timelinePage(requestType: String): DynamicTimelinePageState {
    timelinePages[requestType]?.let { return it }
    if (timelineRequestType != requestType) return DynamicTimelinePageState()
    return DynamicTimelinePageState(
        items = items,
        isLoading = isLoading,
        error = error,
        hasMore = hasMore,
        incrementalRefreshBoundaryKey = incrementalRefreshBoundaryKey,
        incrementalPrependedCount = incrementalPrependedCount,
        errorSource = errorSource
    )
}

internal fun updateDynamicTimelinePage(
    currentState: DynamicUiState,
    requestType: String,
    transform: (DynamicTimelinePageState) -> DynamicTimelinePageState
): DynamicUiState {
    val updatedPage = transform(currentState.timelinePage(requestType))
    val updatedState = currentState.copy(
        timelinePages = currentState.timelinePages.put(requestType, updatedPage)
    )
    return if (currentState.timelineRequestType == requestType) {
        updatedState.copyActiveTimelinePage(requestType, updatedPage)
    } else {
        updatedState
    }
}

internal fun mapDynamicTimelineItems(
    currentState: DynamicUiState,
    transform: (List<DynamicItem>) -> List<DynamicItem>
): DynamicUiState {
    val requestTypes = currentState.timelinePages.keys + currentState.timelineRequestType
    return requestTypes.fold(currentState) { state, requestType ->
        updateDynamicTimelinePage(state, requestType) { page ->
            page.copy(items = transform(page.items).toImmutableList())
        }
    }
}

private fun DynamicUiState.copyActiveTimelinePage(
    requestType: String,
    page: DynamicTimelinePageState
): DynamicUiState {
    return copy(
        items = page.items,
        timelineRequestType = requestType,
        isLoading = page.isLoading,
        error = page.error,
        hasMore = page.hasMore,
        incrementalRefreshBoundaryKey = page.incrementalRefreshBoundaryKey,
        incrementalPrependedCount = page.incrementalPrependedCount,
        errorSource = page.errorSource
    )
}

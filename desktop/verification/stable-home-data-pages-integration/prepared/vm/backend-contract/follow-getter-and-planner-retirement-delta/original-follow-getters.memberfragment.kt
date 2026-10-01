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

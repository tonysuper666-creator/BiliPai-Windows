package com.bilipai.desktop.ui

/** What a dynamic feed shows in the slot where rows would be, once nothing is displayed. */
internal enum class DesktopDynamicEmptyState {
    /** Rows, a failure row or an active request already own the slot. */
    NONE,

    /** No verdict about emptiness exists yet: the first fetch has not settled, or more pages are still to be requested. */
    LOADING,

    /** Nothing is left to request and the feed really has no rows. */
    EMPTY,

    /** The feed has rows, but the current visibility filters hide all of them. */
    FILTERED,
}

/**
 * The empty verdict needs the first settled fetch. Before it, a cold start (or the first switch to a tab)
 * has `busy == false` for a moment although the request has not started, and the old rule showed the
 * empty-state text during that moment (the cold-start flash).
 *
 * `firstFetchSettled` is not `initialized`: a first response that the follow-state guard discards leaves the
 * timeline uninitialized but still settled, and that case must end in a verdict (the refresh button recovers it)
 * instead of an indefinite spinner.
 */
internal fun resolveDesktopDynamicEmptyState(
    firstFetchSettled: Boolean,
    busy: Boolean,
    failed: Boolean,
    hasPageItems: Boolean,
    hasDisplayedItems: Boolean,
): DesktopDynamicEmptyState = when {
    hasDisplayedItems || failed || busy -> DesktopDynamicEmptyState.NONE
    !firstFetchSettled -> DesktopDynamicEmptyState.LOADING
    hasPageItems -> DesktopDynamicEmptyState.FILTERED
    else -> DesktopDynamicEmptyState.EMPTY
}

/**
 * The selected-author feed shows only the rows of the current category, so a page that holds no video or article
 * row is a normal state: the automatic paging keeps requesting the next page and the slot must not announce
 * "nothing here" in between. Like the v0.4.6 DynamicScreen, emptiness is a verdict only once nothing is left to
 * request; while more pages exist the slot is the loading variant, and the manual load-more button stays hidden.
 */
internal fun resolveDesktopDynamicAuthorEmptyState(
    loading: Boolean,
    failed: Boolean,
    hasMore: Boolean,
    hasDisplayedItems: Boolean,
): DesktopDynamicEmptyState = when {
    hasDisplayedItems || failed || loading -> DesktopDynamicEmptyState.NONE
    hasMore -> DesktopDynamicEmptyState.LOADING
    else -> DesktopDynamicEmptyState.EMPTY
}

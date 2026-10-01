// Original source app/src/main/java/com/android/purebilibili/feature/home/policy/HomeBottomBarScrollPolicy.kt
// LF SHA256 602709fd478812fc9f571d82c164268d222339487581d53dac0d4f4a0ba96f22
package com.android.purebilibili.feature.home.policy



internal data class HomeBottomBarScrollState(
    val firstVisibleItem: Int,
    val scrollOffset: Int
)

internal data class HomeBottomBarScrollUpdate(
    val state: HomeBottomBarScrollState,
    val visibilityIntent: BottomBarVisibilityIntent?
)

internal fun reduceHomeBottomBarListScroll(
    previousState: HomeBottomBarScrollState,
    firstVisibleItem: Int,
    scrollOffset: Int,
    isVideoNavigating: Boolean,
    contentInteractionRestored: Boolean = false,
    topRevealThresholdPx: Int = 100,
    offsetHysteresisPx: Int = 200
): HomeBottomBarScrollUpdate {
    val nextState = HomeBottomBarScrollState(
        firstVisibleItem = firstVisibleItem,
        scrollOffset = scrollOffset
    )
    if (isVideoNavigating && !contentInteractionRestored) {
        return HomeBottomBarScrollUpdate(
            state = nextState,
            visibilityIntent = null
        )
    }

    val intent = when {
        firstVisibleItem == 0 && scrollOffset < topRevealThresholdPx -> BottomBarVisibilityIntent.SHOW
        firstVisibleItem > previousState.firstVisibleItem -> BottomBarVisibilityIntent.HIDE
        firstVisibleItem < previousState.firstVisibleItem -> BottomBarVisibilityIntent.SHOW
        scrollOffset > previousState.scrollOffset + offsetHysteresisPx -> BottomBarVisibilityIntent.HIDE
        scrollOffset < previousState.scrollOffset - offsetHysteresisPx -> BottomBarVisibilityIntent.SHOW
        else -> null
    }

    return HomeBottomBarScrollUpdate(
        state = nextState,
        visibilityIntent = intent
    )
}

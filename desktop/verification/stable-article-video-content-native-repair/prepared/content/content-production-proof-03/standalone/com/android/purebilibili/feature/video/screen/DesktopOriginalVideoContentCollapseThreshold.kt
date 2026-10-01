package com.android.purebilibili.feature.video.screen
internal fun isVideoDetailIntroScrollPastCollapseThreshold(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    thresholdPx: Int = 56
): Boolean {
    return firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset >= thresholdPx
}

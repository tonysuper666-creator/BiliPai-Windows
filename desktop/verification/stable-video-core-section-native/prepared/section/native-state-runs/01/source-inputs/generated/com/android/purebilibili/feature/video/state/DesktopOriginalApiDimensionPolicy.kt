package com.android.purebilibili.feature.video.state
internal fun resolveApiDimensionIsVertical(
    width: Int,
    height: Int,
    rotate: Int = 0
): Boolean {
    if (width <= 0 || height <= 0) return false
    val normalizedRotate = ((rotate % 360) + 360) % 360
    val shouldSwap = normalizedRotate == 90 || normalizedRotate == 270
    val effectiveWidth = if (shouldSwap) height else width
    val effectiveHeight = if (shouldSwap) width else height
    return effectiveHeight > effectiveWidth
}

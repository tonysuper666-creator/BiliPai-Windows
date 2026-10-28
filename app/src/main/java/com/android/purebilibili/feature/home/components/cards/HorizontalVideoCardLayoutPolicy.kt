package com.android.purebilibili.feature.home.components.cards

/**
 * Shared metrics for every side-by-side video row
 * (partition, related, space contributions, personal lists).
 *
 * Cover defaults to 16:10 and follows the selected video-cover ratio. Info column must **not** be height-locked to the cover, or
 * title + author + play/danmaku squeeze and wrap the danmaku count.
 */
internal const val HORIZONTAL_VIDEO_CARD_COVER_ASPECT_RATIO = 16f / 10f
internal const val HORIZONTAL_VIDEO_CARD_COVER_WIDTH_DP = 140
internal const val HORIZONTAL_VIDEO_CARD_COVER_INFO_GAP_DP = 10
internal const val HORIZONTAL_VIDEO_CARD_MIN_HEIGHT_DP = 90f
internal const val HORIZONTAL_VIDEO_CARD_INFO_PADDING_DP = 8
internal const val HORIZONTAL_VIDEO_CARD_INFO_SPACING_DP = 4
internal const val HORIZONTAL_VIDEO_CARD_ACTION_INSET_DP = 4
internal const val HORIZONTAL_VIDEO_CARD_ACTION_SIZE_DP = 48
internal const val HORIZONTAL_VIDEO_STAT_ROW_SPACING_DP = 8
internal const val HORIZONTAL_VIDEO_STAT_WRAP_SPACING_DP = 2
internal const val HORIZONTAL_VIDEO_STAT_ICON_SIZE_DP = 13
internal const val HORIZONTAL_VIDEO_STAT_ICON_TEXT_GAP_DP = 2

internal fun resolveHorizontalVideoCoverHeightDp(
    coverWidthDp: Float = HORIZONTAL_VIDEO_CARD_COVER_WIDTH_DP.toFloat(),
    coverAspectRatio: Float = HORIZONTAL_VIDEO_CARD_COVER_ASPECT_RATIO,
): Float {
    val safeRatio = coverAspectRatio.coerceAtLeast(1f)
    return coverWidthDp / safeRatio
}

internal fun resolveHorizontalVideoCardMinimumHeightDp(
    fontScale: Float,
    coverAspectRatio: Float = HORIZONTAL_VIDEO_CARD_COVER_ASPECT_RATIO,
): Float = maxOf(
    resolveHorizontalVideoCoverHeightDp(coverAspectRatio = coverAspectRatio),
    HORIZONTAL_VIDEO_CARD_MIN_HEIGHT_DP * fontScale.coerceAtLeast(1f),
)

@androidx.compose.runtime.Composable
internal fun horizontalVideoCardTypography() = com.android.purebilibili.core.ui.feedContentTypography(
    com.android.purebilibili.core.ui.FeedTitleHierarchy.Standard,
)

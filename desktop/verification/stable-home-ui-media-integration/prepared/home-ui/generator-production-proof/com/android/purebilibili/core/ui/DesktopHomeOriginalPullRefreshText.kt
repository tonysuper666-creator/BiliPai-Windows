// Original source design-system/src/main/java/com/android/purebilibili/core/ui/AdaptivePullToRefreshPolicy.kt
// LF SHA256 b176222984174db4c73fe4009bbf1401328d38889258dc45b03e33e5878a011f
package com.android.purebilibili.core.ui

fun resolveMiuixPullToRefreshTexts(): List<String> = listOf(
    "下拉刷新...",
    "松手刷新",
    "正在刷新...",
    "刷新完成",
)

/**
 * Top inset (dp) for the pull-to-refresh indicator relative to the
 * [AdaptivePullToRefreshBox] top edge.
 *
 * Use the **height of chrome that overlays the refresh box** (status bar + floating
 * top bar / home header). When the box is already laid out *below* a Scaffold
 * topBar (body already padded), pass **0**.
 *
 * Do **not** reuse home [listTopPadding] on other screens — each surface has its
 * own chrome stack.
 */

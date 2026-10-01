package com.android.purebilibili.feature.live

fun formatLiveViewerCount(count: Int): String {
    return when {
        count >= 100_000_000 -> "%.1f亿".format(count / 100_000_000f)
        count >= 10_000 -> "%.1f万".format(count / 10_000f)
        count > 0 -> count.toString()
        else -> "-"
    }
}

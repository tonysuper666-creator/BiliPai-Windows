// 文件路径: feature/video/VideoPlayerState.kt


package com.android.purebilibili.feature.video.state


import androidx.compose.runtime.*


internal fun shouldReuseMiniPlayerAtEntry(
    isMiniPlayerActive: Boolean,
    miniPlayerBvid: String?,
    miniPlayerCid: Long,
    hasMiniPlayerInstance: Boolean,
    requestBvid: String,
    requestCid: Long
): Boolean {
    if (!isMiniPlayerActive || !hasMiniPlayerInstance) return false
    if (miniPlayerBvid != requestBvid) return false
    if (requestCid <= 0L) return miniPlayerCid > 0L
    return miniPlayerCid > 0L && miniPlayerCid == requestCid
}

/**
 * 栈顶已不是本详情（合集列表再进另一集、详情压详情等）时，应立刻挂起本地 player，
 * 否则上一级画面仍在后台出声，新详情只有封面/黑屏。
 */

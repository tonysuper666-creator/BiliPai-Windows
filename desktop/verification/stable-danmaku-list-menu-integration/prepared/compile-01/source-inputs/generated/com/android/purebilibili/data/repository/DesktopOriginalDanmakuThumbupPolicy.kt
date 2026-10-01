package com.android.purebilibili.data.repository
import com.android.purebilibili.data.model.response.DanmakuThumbupStatsItem

internal data class DanmakuThumbupState(
    val likes: Int,
    val liked: Boolean
)

internal fun resolveDanmakuThumbupState(
    dmid: Long,
    data: Map<String, DanmakuThumbupStatsItem>
): DanmakuThumbupState? {
    val key = dmid.toString()
    val matched = data[key] ?: return null
    return DanmakuThumbupState(
        likes = matched.likes.coerceAtLeast(0),
        liked = matched.userLike == 1
    )
}

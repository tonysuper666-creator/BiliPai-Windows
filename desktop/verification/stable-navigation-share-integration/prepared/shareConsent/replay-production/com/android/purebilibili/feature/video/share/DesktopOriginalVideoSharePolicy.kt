package com.android.purebilibili.feature.video.share
import com.android.purebilibili.data.model.response.FollowingUser

internal fun resolveVideoShareRecipientIds(
    selectedIds: Set<Long>,
    followings: List<com.android.purebilibili.data.model.response.FollowingUser>,
    selfMid: Long,
): List<Long> = followings.asSequence()
    .map { it.mid }
    .filter { it > 0L && it != selfMid && it in selectedIds }
    .distinct()
    .toList()

internal enum class VideoShareStyle {
    LINK,
    CARD,
}

internal fun resolveVideoShareCardMetaLine(payload: VideoSharePayload): String {
    val parts = buildList {
        if (payload.upName.isNotBlank()) {
            add("UP主：${payload.upName}")
        }
        if (payload.playCountText.isNotBlank()) {
            add("播放：${payload.playCountText}")
        }
    }
    return parts.joinToString("  ·  ")
}

/**
 * 宿主 App 常把 Display Name / 文件名当消息标题，因此用净化后的视频标题命名。
 */

internal fun resolveVideoShareCardFileName(payload: VideoSharePayload): String {
    val rawTitle = payload.title.ifBlank { payload.bvid }.ifBlank { "video" }
    val sanitized = rawTitle
        .map { ch ->
            if (ch.isLetterOrDigit() || ch in "._- 《》【】（）()、，。！？") ch else '_'
        }
        .joinToString("")
        .trim()
        .replace(Regex("\\s+"), "_")
        .take(40)
        .trim('_')
        .ifBlank { payload.bvid.ifBlank { "video" } }
    return "BiliPai_share_card_$sanitized.jpg"
}

internal fun resolveVideoShareChooserTitle(payload: VideoSharePayload): String {
    return "分享「${payload.title}」"
}

/**
 * 同一分享包下可响应 ACTION_SEND 的 Activity 候选。
 */

internal enum class VideoShareTarget { BILIBILI_FRIENDS,SYSTEM_SHARE,SAVE_CARD,COPY_LINK,MORE }

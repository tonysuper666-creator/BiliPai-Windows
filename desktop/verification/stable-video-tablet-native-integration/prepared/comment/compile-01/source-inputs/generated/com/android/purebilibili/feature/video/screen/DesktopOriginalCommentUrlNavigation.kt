package com.android.purebilibili.feature.video.screen

internal sealed interface CommentUrlNavigationTarget {
    data class Video(val videoId: String) : CommentUrlNavigationTarget
    data class Search(val keyword: String) : CommentUrlNavigationTarget
    data class Space(val mid: Long) : CommentUrlNavigationTarget
}

internal fun resolveCommentUrlNavigationTarget(rawUrl: String): CommentUrlNavigationTarget? {
    val url = rawUrl.trim()
    if (url.isEmpty()) return null
    return when (val target = com.android.purebilibili.core.util.BilibiliNavigationTargetParser.parse(url)) {
        is com.android.purebilibili.core.util.BilibiliNavigationTarget.Video -> {
            target.videoId.trim()
                .takeIf { it.isNotEmpty() }
                ?.let(CommentUrlNavigationTarget::Video)
        }

        is com.android.purebilibili.core.util.BilibiliNavigationTarget.Search -> {
            target.keyword.trim()
                .takeIf { it.isNotEmpty() }
                ?.let(CommentUrlNavigationTarget::Search)
        }

        is com.android.purebilibili.core.util.BilibiliNavigationTarget.Space -> {
            target.mid.takeIf { it > 0L }?.let(CommentUrlNavigationTarget::Space)
        }

        else -> null
    }
}

// Original source app/src/main/java/com/android/purebilibili/navigation/AppNavigation.kt
// LF SHA256 715c42cfa7030c5627faafe995b17f05957b89f3d04481834b683f1be66c2df8
package com.android.purebilibili.navigation

import com.bilipai.desktop.ui.encodeDesktopVideoRouteCover

object VideoRoute {
    const val base = "video"
    const val route = "$base/{bvid}?cid={cid}&cover={cover}&startAudio={startAudio}&autoPortrait={autoPortrait}&fullscreen={fullscreen}&resumePositionMs={resumePositionMs}&commentRootRpid={commentRootRpid}&commentTargetRpid={commentTargetRpid}&initialVertical={initialVertical}&directPortraitEntry={directPortraitEntry}"

    internal fun resolveVideoRoutePath(
        bvid: String,
        cid: Long,
        encodedCover: String,
        startAudio: Boolean,
        autoPortrait: Boolean,
        fullscreen: Boolean = false,
        resumePositionMs: Long = 0L,
        commentRootRpid: Long = 0L,
        commentTargetRpid: Long = 0L,
        initialVertical: Boolean = false,
        directPortraitEntry: Boolean = false,
    ): String {
        val initialVerticalQuery = if (initialVertical) "&initialVertical=true" else ""
        val directPortraitQuery = if (directPortraitEntry) "&directPortraitEntry=true" else ""
        return "$base/$bvid?cid=$cid&cover=$encodedCover&startAudio=$startAudio&autoPortrait=$autoPortrait&fullscreen=$fullscreen&resumePositionMs=${resumePositionMs.coerceAtLeast(0L)}&commentRootRpid=${commentRootRpid.coerceAtLeast(0L)}&commentTargetRpid=${commentTargetRpid.coerceAtLeast(0L)}$initialVerticalQuery$directPortraitQuery"
    }

    // 构建 helper
    fun createRoute(
        bvid: String,
        cid: Long,
        coverUrl: String,
        startAudio: Boolean = false,
        autoPortrait: Boolean = false,
        fullscreen: Boolean = false,
        resumePositionMs: Long = 0L,
        commentRootRpid: Long = 0L,
        commentTargetRpid: Long = 0L,
        initialVertical: Boolean = false,
        directPortraitEntry: Boolean = false,
    ): String {
        val encodedCover = encodeDesktopVideoRouteCover(coverUrl)
        return resolveVideoRoutePath(
            bvid = bvid,
            cid = cid,
            encodedCover = encodedCover,
            startAudio = startAudio,
            autoPortrait = autoPortrait,
            fullscreen = fullscreen,
            resumePositionMs = resumePositionMs,
            commentRootRpid = commentRootRpid,
            commentTargetRpid = commentTargetRpid,
            initialVertical = initialVertical,
            directPortraitEntry = directPortraitEntry,
        )
    }
}


internal fun navigateOriginalDynamicCollection(mediaId:Long, ownerMid:Long, title:String, url:String,
 onFavorite:(String,Long,Long,String)->Unit, onWeb:(String,String)->Unit) {
    if (mediaId > 0L && url.contains("medialist/detail/ml", ignoreCase = true)) {
        onFavorite("favorite", mediaId, ownerMid, title)
    } else if (url.isNotBlank()) {
        onWeb(url, title)
    }
}

internal fun navigateOriginalDynamicCourse(url:String, title:String,
 onPlayer:(Long,Long,Boolean)->Unit, onWeb:(String,String)->Unit) {
    val courseNav = com.android.purebilibili.feature.bangumi.policy.parseCourseNavigation(url)
    if (courseNav != null) {
        onPlayer(courseNav.seasonId, courseNav.epId, true)
    } else if (url.isNotBlank()) {
        onWeb(url, title)
    }
}

// Original source app/src/main/java/com/android/purebilibili/navigation/AppNavigation.kt
// LF SHA256 218267eba2d04714c57d0a67d319d6c11856c7fca4471cee294ed9e999aefa59
package com.android.purebilibili.navigation
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal fun shouldAutoEnterPortraitForStandardVideoNavigation(): Boolean = false

internal fun resolveStandardVideoRoute(
    bvid: String,
    cid: Long,
    coverUrl: String,
    startAudio: Boolean = false,
    autoPortrait: Boolean = shouldAutoEnterPortraitForStandardVideoNavigation(),
    fullscreen: Boolean = false,
    resumePositionMs: Long = 0L,
    commentRootRpid: Long = 0L,
    commentTargetRpid: Long = 0L,
    initialVertical: Boolean = false,
    directPortraitEntry: Boolean = false,
): String {
    val encodedCover = URLEncoder.encode(coverUrl, StandardCharsets.UTF_8.toString())
    return VideoRoute.resolveVideoRoutePath(
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

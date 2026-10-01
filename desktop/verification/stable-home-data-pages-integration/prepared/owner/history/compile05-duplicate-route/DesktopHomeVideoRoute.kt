// Original source app/src/main/java/com/android/purebilibili/navigation/AppNavigation.kt; LF SHA256 218267eba2d04714c57d0a67d319d6c11856c7fca4471cee294ed9e999aefa59
package com.android.purebilibili.navigation

object VideoRoute {
    const val base = "video"
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
}

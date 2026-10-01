// Original source app/src/main/java/com/android/purebilibili/feature/video/share/VideoSharePolicy.kt
// LF SHA256 97b76d437936dddfccb4b0f553b14f3b3105ef012fdd520ac293d5863a5fcced
package com.android.purebilibili.feature.video.share
internal data class VideoSharePayload(
    val title: String,
    val bvid: String,
    val coverUrl: String,
    val url: String,
    val text: String,
    val upName: String = "",
    val playCountText: String = "",
)

internal fun buildVideoSharePayload(
    title: String,
    bvid: String,
    coverUrl: String = "",
    upName: String = "",
    playCountText: String = "",
): VideoSharePayload {
    val cleanTitle = title.trim()
    val cleanBvid = bvid.trim()
    val fallbackTitle = cleanTitle.ifBlank { cleanBvid }
    val url = "https://www.bilibili.com/video/$cleanBvid"
    return VideoSharePayload(
        title = fallbackTitle,
        bvid = cleanBvid,
        coverUrl = coverUrl.trim(),
        url = url,
        text = "【$fallbackTitle】\n$url",
        upName = upName.trim(),
        playCountText = playCountText.trim(),
    )
}

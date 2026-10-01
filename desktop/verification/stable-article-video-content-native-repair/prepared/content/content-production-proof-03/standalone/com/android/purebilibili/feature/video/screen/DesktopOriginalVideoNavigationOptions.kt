package com.android.purebilibili.feature.video.screen
internal fun buildDesktopOriginalVideoNavigationOptions(
    base: Pair<Long, String?>? = null,
    targetCid: Long = 0L,
    coverUrl: String? = null
): Pair<Long, String?>? {
    val normalizedCover = coverUrl?.trim().orEmpty()
    if (targetCid <= 0L && normalizedCover.isEmpty()) return base
    return Pair(
        if (targetCid > 0L) targetCid else base?.first ?: 0L,
        if (normalizedCover.isNotEmpty()) normalizedCover else base?.second
    )
}

package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.ViewPoint
import com.android.purebilibili.feature.video.ui.overlay.normalizeViewPointSegments

/** A result of the original player-info request, stamped where that result is admitted.
 * Identity distinguishes two responses even when their chapter payloads are equal. */
internal class DesktopOriginalVideoChapterResult(
    val bvid: String,
    val cid: Long,
    val requestToken: Long,
    points: List<ViewPoint>,
) {
    val points: List<ViewPoint> = points.toList()
}

internal fun desktopOriginalVideoChaptersForOwner(
    result: DesktopOriginalVideoChapterResult?, bvid: String, cid: Long, requestToken: Long,
): DesktopOriginalVideoChapterResult? = result?.takeIf {
    bvid.isNotBlank() && cid > 0L && it.bvid == bvid && it.cid == cid && it.requestToken == requestToken
}

/** A menu/gesture carries the actual response identity. A retained popup cannot seek a
 * replacement part or a newer response; duration and starts use the original segment policy. */
internal fun isDesktopOriginalVideoChapterSeekCurrent(
    expected: DesktopOriginalVideoChapterResult,
    observed: DesktopOriginalVideoChapterResult?,
    bvid: String, cid: Long, requestToken: Long, positionMs: Long, durationMs: Long,
): Boolean = observed === expected &&
    desktopOriginalVideoChaptersForOwner(expected, bvid, cid, requestToken) === expected &&
    normalizeViewPointSegments(expected.points, durationMs).any { it.fromMs == positionMs }

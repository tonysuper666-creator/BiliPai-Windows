package com.bilipai.desktop.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Identity of the accepted native publication the Windows video leaf is showing. */
internal data class DesktopWindowsDanmakuLoadIdentity(
    val bvid: String,
    val cid: Long,
)

/** Arguments for [DesktopOriginalSectionDanmakuPort.loadDanmaku]. Null means do not load. */
internal data class DesktopWindowsDanmakuLoadRequest(
    val cid: Long,
    val aid: Long,
    val bvid: String,
    val durationHintMs: Long,
)

/**
 * Ordinary Windows video does not compose the upstream player section, so this decision
 * is the only gate that asks the section port to fetch danmaku for the visible page.
 * A disabled toggle, a page that is not current, or a detail that does not match the
 * accepted publication produces no request. Missing duration still loads; the overlay
 * treats zero as a metadata fallback.
 */
internal fun desktopWindowsDanmakuLoadRequest(
    enabled: Boolean,
    pageActive: Boolean,
    accepted: DesktopWindowsDanmakuLoadIdentity?,
    detailBvid: String,
    detailCid: Long,
    detailAid: Long,
    pageDurationSeconds: Long,
    playerDurationMs: Long,
): DesktopWindowsDanmakuLoadRequest? {
    if (!enabled || !pageActive) return null
    val source = accepted ?: return null
    if (detailBvid.isBlank() || source.bvid != detailBvid) return null
    if (detailCid <= 0L || source.cid != detailCid) return null
    if (detailAid < 0L) return null
    return DesktopWindowsDanmakuLoadRequest(
        cid = detailCid,
        aid = detailAid,
        bvid = detailBvid,
        durationHintMs = desktopWindowsDanmakuDurationHintMs(pageDurationSeconds, playerDurationMs),
    )
}

internal fun desktopWindowsDanmakuDurationHintMs(pageDurationSeconds: Long, playerDurationMs: Long): Long {
    if (pageDurationSeconds > 0L && pageDurationSeconds <= Long.MAX_VALUE / 1_000L) {
        return pageDurationSeconds * 1_000L
    }
    return playerDurationMs.coerceAtLeast(0L)
}

/**
 * Invokes [load] once the page is still current. A [CancellationException] while this
 * coroutine is still active is an ownership miss from the section port, not disposal
 * of the effect, so it is retried. Disposal cancels this coroutine and is rethrown.
 */
internal suspend fun runDesktopWindowsDanmakuLoad(
    request: DesktopWindowsDanmakuLoadRequest?,
    stillCurrent: () -> Boolean,
    pause: suspend (Long) -> Unit = { delay(it) },
    maxOwnershipRetries: Int = 8,
    load: suspend (DesktopWindowsDanmakuLoadRequest) -> Unit,
) {
    val value = request ?: return
    var attempt = 0
    while (currentCoroutineContext().isActive) {
        if (!stillCurrent()) return
        try {
            load(value)
            return
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            if (++attempt > maxOwnershipRetries || !stillCurrent()) return
            pause(200)
        }
    }
}

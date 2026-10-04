package com.android.purebilibili.feature.video.danmaku

import com.android.purebilibili.danmaku.engine.DanmakuItem

/** Only recent comments are eligible: never reveal a later scene's comments early. */
internal fun selectHotDanmaku(
    items: List<DanmakuItem>,
    positionMs: Long,
    maxItems: Int = 2,
): List<DanmakuItem> = items.asSequence()
    .filter { it.danmakuId > 0L && !it.text.isNullOrBlank() && it.likeCount >= 10L }
    .filter { it.showAtTime in (positionMs.coerceAtLeast(0L) - 15_000L).coerceAtLeast(0L)..positionMs.coerceAtLeast(0L) }
    .sortedWith(compareByDescending<DanmakuItem> { it.likeCount }.thenBy { it.danmakuId })
    .distinctBy { it.danmakuId }
    .distinctBy { it.text }
    .take(maxItems.coerceAtLeast(0))
    .toList()

/** Select short entries that fit in one centered row; never truncate a comment. */
internal fun selectFittingHotDanmaku(
    items: List<DanmakuItem>,
    availableWidthPx: Float,
    spacingPx: Float,
    measureWidthPx: (DanmakuItem) -> Float,
): List<DanmakuItem> {
    val candidates = items.map { it to measureWidthPx(it) }
        .filter { (_, width) -> width.isFinite() && width > 0f && width <= availableWidthPx }
        .sortedBy { (_, width) -> width }
    val selected = mutableListOf<DanmakuItem>()
    var usedWidth = 0f
    for ((item, width) in candidates) {
        val required = width + if (selected.isEmpty()) 0f else spacingPx
        if (usedWidth + required > availableWidthPx) continue
        selected += item
        usedWidth += required
        if (selected.size == 2) break
    }
    return selected
}

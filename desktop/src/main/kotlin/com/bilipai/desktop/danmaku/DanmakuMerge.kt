package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.engine.DanmakuItem
import com.android.purebilibili.feature.video.danmaku.DanmakuMerger
import com.android.purebilibili.feature.video.danmaku.DesktopOriginalDanmakuItemParser
import com.android.purebilibili.feature.video.danmaku.resolveDanmakuRenderLayerType

/** A renderer projection identity retains actual source IDs, including XML items without server IDs. */
private data class OriginalMergeIdentity(
    val id: Long, val time: Long, val text: String?, val userHash: String,
    val layer: Int, val color: Int?, val sizeScale: Float,
)

private fun DanmakuItem.mergeIdentity() = OriginalMergeIdentity(
    danmakuId, showAtTime, mergedOriginalText ?: text, userHash, layerType, textColor, textSizeScale,
)

/** Use the complete fixed-v038 merger on an ephemeral projection of the existing document. */
fun mergeDuplicateDanmaku(
    comments: List<DanmakuComment>, intervalMs: Int = 15_000, countThreshold: Int = 2,
): List<DanmakuComment> {
    if (comments.isEmpty()) return comments
    val originals = mutableMapOf<OriginalMergeIdentity, DanmakuComment>()
    val items = comments.sortedBy { it.timeSeconds }.map { comment ->
        val original = comment.originalLocalItem
            ?: comment.originalElement?.let(DesktopOriginalDanmakuItemParser::createTextDataFromProto)
            ?: if (comment.originalXmlAttributes != null && comment.originalXmlContent != null)
                DesktopOriginalDanmakuItemParser.createTextData(comment.originalXmlAttributes, comment.originalXmlContent)
            else null
        val item = (original?.copy() ?: DanmakuItem()).apply {
            danmakuId = comment.serverId
            userHash = comment.userHash
            text = comment.text
            mergedOriginalText = null
            showAtTime = (comment.timeSeconds * 1_000).toLong()
            layerType = resolveDanmakuRenderLayerType(comment.mode, false)
            textColor = comment.color
            textSizeScale = comment.size / 25f
            weight = comment.weight
            isVipGradualColor = comment.isVipGradualColor
        }
        originals.putIfAbsent(item.mergeIdentity(), comment)
        item
    }
    return DanmakuMerger.merge(items, intervalMs.toLong().coerceIn(1_000, 15_000), countThreshold).first.map { item ->
        val source = checkNotNull(originals[item.mergeIdentity()]) { "Original merger lost source identity" }
        source.copy(text = item.text.orEmpty(), originalRenderItem = item)
    }
}

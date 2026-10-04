package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.engine.DanmakuItem
import com.android.purebilibili.feature.video.danmaku.DesktopOriginalDanmakuItemParser
import com.android.purebilibili.feature.video.danmaku.resolveDanmakuRenderLayerType

/** The ordinary renderer's plugin output and current filters, before merge.
 * Preserve original metadata/like counts, while displaying the accepted plugin rewrite. */
internal fun desktopOriginalHotDanmakuItems(comments:List<DanmakuComment>,settings:DanmakuSettings):List<DanmakuItem> =
    comments.asSequence().filter(settings::allows).mapNotNull { comment ->
        val original=comment.originalLocalItem?.copy()
            ?: comment.originalElement?.let(DesktopOriginalDanmakuItemParser::createTextDataFromProto)
            ?: if(comment.originalXmlAttributes!=null && comment.originalXmlContent!=null)
                DesktopOriginalDanmakuItemParser.createTextData(comment.originalXmlAttributes,comment.originalXmlContent)
            else null
        original?.takeIf {it.danmakuId>0L && it.likeCount>=10L}?.apply {
            text=comment.text
            showAtTime=(comment.timeSeconds*1000.0).toLong()
            layerType=resolveDanmakuRenderLayerType(comment.mode,settings.staticDanmakuToScroll)
            textColor=comment.color
        }
    }.toList()

package com.android.purebilibili.danmaku.engine

import java.awt.image.BufferedImage as Bitmap
// Android Path is outside this selected standard-item class.
import java.awt.Font as Typeface

const val DANMAKU_LAYER_SCROLL = 1001
const val DANMAKU_LAYER_TOP = 1002
const val DANMAKU_LAYER_BOTTOM = 1003
const val DANMAKU_LAYER_REVERSE = 2001

/** Renderer-neutral standard danmaku model used by the app and parser layers. */
open class DanmakuItem {
    var danmakuId: Long = 0L
    var userHash: String = ""
    var text: String? = null
    var showAtTime: Long = 0L
    var layerType: Int = DANMAKU_LAYER_SCROLL
    /** Semantic size relative to the renderer's user-configured base size. */
    var textSizeScale: Float = 1f
    /** Explicit render size in pixels. Prefer [textSizeScale] for server-defined size grades. */
    var textSize: Float? = null
    var textColor: Int? = null
    var typeface: Typeface? = null
    var textStrokeWidth: Float? = null
    var textStrokeColor: Int? = null
    var includeFontPadding: Boolean? = null
    var hasUnderline: Boolean = false
    var weight: Int = 0
    var pool: Int = 0
    var attr: Int = 0
    var likeCount: Long = 0L
    var isVipGradualColor: Boolean = false
    var duplicateCount: Int = 0
    var isSelf: Boolean = false
    var bitmap: Bitmap? = null
    var bitmapWidth: Float = 0f
    var bitmapHeight: Float = 0f

    fun copy(): DanmakuItem = DanmakuItem().also { target ->
        target.danmakuId = danmakuId
        target.userHash = userHash
        target.text = text
        target.showAtTime = showAtTime
        target.layerType = layerType
        target.textSizeScale = textSizeScale
        target.textSize = textSize
        target.textColor = textColor
        target.typeface = typeface
        target.textStrokeWidth = textStrokeWidth
        target.textStrokeColor = textStrokeColor
        target.includeFontPadding = includeFontPadding
        target.hasUnderline = hasUnderline
        target.weight = weight
        target.pool = pool
        target.attr = attr
        target.likeCount = likeCount
        target.isVipGradualColor = isVipGradualColor
        target.duplicateCount = duplicateCount
        target.isSelf = isSelf
        target.bitmap = bitmap
        target.bitmapWidth = bitmapWidth
        target.bitmapHeight = bitmapHeight
    }
}

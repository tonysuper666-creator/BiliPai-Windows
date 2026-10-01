package com.bilipai.desktop.ui

import coil3.size.Size
import coil3.transform.Transformation
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.IRect

/** Alpha scan/crop is the original ReplyComponents algorithm. The only bridge
 * is Android Bitmap pixels/subset -> the actual Coil/Skia Bitmap on Windows. */
internal object DesktopReplyTransparentBoundsCropTransformation : Transformation() {
    override val cacheKey: String = "comment_transparent_bounds_crop_v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (input.alphaType == ColorAlphaType.OPAQUE) return input
        var left = input.width
        var top = input.height
        var right = -1
        var bottom = -1
        for (y in 0 until input.height) {
            for (x in 0 until input.width) {
                if ((input.getColor(x, y) ushr 24) > 4) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        if (right < left || bottom < top) return input
        val cropWidth = right - left + 1
        val cropHeight = bottom - top + 1
        return if (cropWidth == input.width && cropHeight == input.height) input else {
            Bitmap().also { result ->
                check(input.extractSubset(result, IRect.makeXYWH(left, top, cropWidth, cropHeight))) {
                    "评论装扮图片裁剪失败"
                }
            }
        }
    }
}

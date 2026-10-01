// GENERATED from app/src/main/java/com/android/purebilibili/feature/video/ui/components/ReplyCommentImageSaver.kt; do not edit.
// LF-normalized SHA-256: 2c7ab6898c5bfe9b334ba2b71393fe69c0dbe77050b964e41a3892245119f19d
package com.android.purebilibili.feature.video.ui.components
import java.awt.image.BufferedImage
import com.bilipai.desktop.ui.DesktopCommentPaint as Paint
import com.bilipai.desktop.ui.DesktopCommentCanvas as Canvas
import com.bilipai.desktop.ui.DesktopCommentColors as Color
import com.bilipai.desktop.ui.DesktopCommentRect as RectF
import com.bilipai.desktop.ui.createDesktopCommentBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
internal fun renderDesktopReplyCommentImage(spec: ReplyCommentImageSpec): BufferedImage {
    val width = 1080
    val horizontalPadding = 56f
    val topPadding = 56f
    val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(31, 35, 40)
        textSize = 42f
    }
    val messageLines = breakTextIntoLines(
        text = spec.message,
        paint = messagePaint,
        maxWidth = width - horizontalPadding * 2
    ).take(14)
    val messageHeight = messageLines.size * 58f
    val footerHeight = 188f
    val height = (topPadding + 68f + 42f + messageHeight + 44f + footerHeight + 40f)
        .toInt()
        .coerceAtLeast(520)

    val bitmap = createDesktopCommentBitmap(width, height, BufferedImage.TYPE_INT_ARGB)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.rgb(250, 250, 250))

    val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    canvas.drawRoundRect(
        RectF(24f, 24f, width - 24f, height - 24f),
        28f,
        28f,
        cardPaint
    )

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(251, 114, 153)
        textSize = 38f
        typeface = java.awt.Font.BOLD
    }
    val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(111, 119, 128)
        textSize = 30f
    }
    val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(75, 83, 92)
        textSize = 30f
    }
    val tinyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(139, 148, 158)
        textSize = 26f
    }

    var y = topPadding + 28f
    canvas.drawText("@${spec.authorName}", horizontalPadding, y, titlePaint)
    if (spec.metadataText.isNotBlank()) {
        y += 44f
        canvas.drawText(spec.metadataText, horizontalPadding, y, metaPaint)
    }
    y += 64f
    messageLines.forEach { line ->
        canvas.drawText(line, horizontalPadding, y, messagePaint)
        y += 58f
    }

    val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(235, 238, 240) }
    val dividerY = height - footerHeight - 18f
    canvas.drawRect(horizontalPadding, dividerY, width - horizontalPadding, dividerY + 2f, dividerPaint)

    val qrSize = 148
    val qrBitmap = generateQrBitmap(spec.qrUrl, qrSize)
    val qrLeft = width - horizontalPadding - qrSize
    val qrTop = height - footerHeight + 18f
    canvas.drawBitmap(qrBitmap, qrLeft, qrTop, null)

    val footerLeft = horizontalPadding
    val footerBaseline = qrTop + 42f
    canvas.drawText(spec.footerText, footerLeft, footerBaseline, footerPaint)
    canvas.drawText("BiliPai · ${spec.generatedAtText}", footerLeft, footerBaseline + 42f, tinyPaint)
    canvas.drawText(spec.qrUrl, footerLeft, footerBaseline + 84f, tinyPaint)

    canvas.close()
    return bitmap
}

private fun breakTextIntoLines(
    text: String,
    paint: Paint,
    maxWidth: Float
): List<String> {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    return normalized.split('\n').flatMap { paragraph ->
        if (paragraph.isBlank()) {
            listOf("")
        } else {
            buildList {
                var remaining = paragraph
                while (remaining.isNotEmpty()) {
                    val count = paint.breakText(remaining, true, maxWidth, null)
                        .coerceAtLeast(1)
                    add(remaining.take(count))
                    remaining = remaining.drop(count).trimStart()
                }
            }
        }
    }
}

private fun generateQrBitmap(content: String, size: Int): BufferedImage {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val bitmap = createDesktopCommentBitmap(size, size, BufferedImage.TYPE_USHORT_565_RGB)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setRGB(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    return bitmap
}

package com.android.purebilibili.feature.video.share

import com.android.purebilibili.core.util.FormatUtils
import com.bilipai.desktop.ui.DesktopVideoShareBindings
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.font.FontRenderContext
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.geom.RoundRectangle2D
import java.text.AttributedString

/* Original scalar values and complete drawing geometry. Java2D replaces only
 * Android Canvas/TextPaint/StaticLayout and bitmap resource management. */
private const val CARD_WIDTH = 1080
private const val CARD_PADDING = 56
private const val CARD_CORNER_RADIUS = 28f
private const val CARD_TITLE_MAX_LINES = 3
private const val CARD_TITLE_LINE_HEIGHT = 64f
private const val CARD_TITLE_FIRST_BASELINE = 48f
private const val CARD_META_BLOCK_HEIGHT = 52
private const val CARD_GAP_TITLE_META = 18f
private const val CARD_GAP_META_COVER_WITH_META = 28f
private const val CARD_GAP_META_COVER_WITHOUT_META = 36f
private const val CARD_BOTTOM_PADDING = 56f
private const val CARD_QR_SIZE = 136
private const val CARD_QR_CORNER = 18f
private const val CARD_QR_TEXT_GAP = 28f

internal suspend fun prepareVideoShareCardFile(context:DesktopVideoShareBindings,payload:VideoSharePayload):VideoShareCoverFile? {
    if(payload.coverUrl.isBlank())return null
    val coverUrl=FormatUtils.resolveVideoCoverUrl(url=payload.coverUrl,useLowQuality=false)
    if(coverUrl.isBlank())return null
    return withContext(Dispatchers.IO) {
        try {
            val cover=context.files.bitmap(coverUrl) ?: error("Cover bitmap decode failed")
            val card=try { renderVideoShareCardBitmap(payload,cover) }finally {cover.flush()}
            try { context.files.jpeg(resolveVideoShareCardFileName(payload),card) }finally {card.flush()}
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(failure:Exception) {currentCoroutineContext().ensureActive();if(!context.isOwned())throw CancellationException("分享页面已退役");null}
    }
}

internal fun renderVideoShareCardBitmap(payload:VideoSharePayload,coverBitmap:BufferedImage):BufferedImage {
    val contentWidth = CARD_WIDTH - CARD_PADDING * 2
    val coverHeight = (contentWidth * 9f / 16f).toInt()
    val titlePaint = Font("Dialog", Font.BOLD, 52)
    val metaPaint = Font("Dialog", Font.PLAIN, 36)
    val textMaxWidth = (contentWidth - CARD_QR_SIZE - CARD_QR_TEXT_GAP).toInt().coerceAtLeast(1)
    val titleLayout = buildShareCardTitleLayout(payload.title,titlePaint,textMaxWidth)
    val metaLine = resolveVideoShareCardMetaLine(payload)
    val titleLineCount = titleLayout.size
    val titleBlockHeight = (titleLineCount * CARD_TITLE_LINE_HEIGHT).toInt()
    val metaBlockHeight = if (metaLine.isBlank()) 0 else CARD_META_BLOCK_HEIGHT
    val gapTitleMeta = if (metaBlockHeight == 0) 0f else CARD_GAP_TITLE_META
    val gapMetaCover = if (metaBlockHeight == 0) CARD_GAP_META_COVER_WITHOUT_META else CARD_GAP_META_COVER_WITH_META
    val headerTextHeight = titleBlockHeight + gapTitleMeta + metaBlockHeight
    var contentY = CARD_TITLE_FIRST_BASELINE + titleBlockHeight + gapTitleMeta + metaBlockHeight
    contentY += gapMetaCover
    val coverTop = CARD_PADDING + contentY
    val cardHeight = (coverTop + coverHeight + CARD_BOTTOM_PADDING).toInt()
    val bitmap=BufferedImage(CARD_WIDTH,cardHeight,BufferedImage.TYPE_INT_ARGB)
    val canvas=bitmap.createGraphics()
    try {
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
        canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        canvas.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        canvas.color=Color.WHITE;canvas.fillRect(0,0,bitmap.width,bitmap.height)
        var baseline = CARD_PADDING + CARD_TITLE_FIRST_BASELINE
        canvas.font=titlePaint;canvas.color=Color(0x1F1F1F)
        for(index in 0 until titleLineCount) {
            canvas.drawString(titleLayout[index],CARD_PADDING.toFloat(),baseline)
            baseline += CARD_TITLE_LINE_HEIGHT
        }
        if (metaBlockHeight > 0) {
            baseline += gapTitleMeta
            canvas.font=metaPaint;canvas.color=Color(0x7A7A7A)
            canvas.drawString(metaLine,CARD_PADDING.toFloat(),baseline)
            baseline += metaBlockHeight
        }
        drawRoundedBitmap(canvas,coverBitmap,CARD_PADDING.toFloat(),coverTop,contentWidth.toFloat(),coverHeight.toFloat(),CARD_CORNER_RADIUS)
        val textBlockTop = CARD_PADDING.toFloat()
        val textBlockBottom = CARD_PADDING + headerTextHeight
        val qrLeft = (CARD_WIDTH - CARD_PADDING - CARD_QR_SIZE).toFloat()
        val qrTop = textBlockTop + (textBlockBottom - textBlockTop - CARD_QR_SIZE) / 2f
        drawShareCardQrPlate(canvas,payload.url,qrLeft,qrTop.coerceAtLeast(textBlockTop),CARD_QR_SIZE.toFloat())
    } finally {canvas.dispose()}
    return bitmap
}

/** Full Windows text shaping/breaking; original trim/3-line/END/no-pad contract.
 * Platform font shaping differs from Android; pixel-identical glyphs are not claimed. */
internal fun buildShareCardTitleLayout(title:String,paint:Font,maxWidth:Int):List<String> {
    val source=title.trim().ifBlank {" "}
    val frc=FontRenderContext(null,true,true)
    val attributed=AttributedString(source).apply {addAttribute(TextAttribute.FONT,paint)}
    val measurer=LineBreakMeasurer(attributed.iterator,frc)
    val lines=mutableListOf<String>()
    while(measurer.position<source.length && lines.size<CARD_TITLE_MAX_LINES) {
        val begin=measurer.position
        val newline=source.indexOf('\n',begin).let {if(it<0)source.length else it}
        if(newline==begin) {lines.add("");measurer.position=begin+1;continue}
        measurer.nextLayout(maxWidth.coerceAtLeast(1).toFloat(),newline,false)
        val end=measurer.position.coerceAtLeast(begin+Character.charCount(source.codePointAt(begin))).coerceAtMost(newline)
        measurer.position=end
        var line=source.substring(begin,end)
        if(lines.size==CARD_TITLE_MAX_LINES-1 && end<source.length) {
            while(line.isNotEmpty() && paint.getStringBounds(line+"…",frc).width>maxWidth) {
                line=line.substring(0,line.offsetByCodePoints(line.length,-1))
            }
            line+="…"
        }
        lines.add(line)
        if(end==newline && end<source.length)measurer.position=end+1
    }
    return lines
}

private fun drawShareCardQrPlate(canvas:Graphics2D,url:String,left:Float,top:Float,size:Float) {
    canvas.color=Color.WHITE
    canvas.fill(RoundRectangle2D.Float(left,top,size,size,CARD_QR_CORNER*2,CARD_QR_CORNER*2))
    val qrBitmap=createShareCardQrBitmap(url,CARD_QR_SIZE)
    val qrPadding=8f
    try {canvas.drawImage(qrBitmap,(left+qrPadding).toInt(),(top+qrPadding).toInt(),(left+size-qrPadding).toInt(),(top+size-qrPadding).toInt(),0,0,qrBitmap.width,qrBitmap.height,null)}
    finally {qrBitmap.flush()}
}

private fun createShareCardQrBitmap(url:String,size:Int):BufferedImage {
    val matrix = QRCodeWriter().encode(
        url,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(
            com.google.zxing.EncodeHintType.MARGIN to 1,
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to "M",
        )
    )
    return BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB).also { bitmap ->
        for(x in 0 until size)for(y in 0 until size)bitmap.setRGB(x,y,if(matrix[x,y])Color.BLACK.rgb else Color.WHITE.rgb)
    }
}

private fun drawRoundedBitmap(canvas:Graphics2D,bitmap:BufferedImage,left:Float,top:Float,width:Float,height:Float,radius:Float) {
    val clip=canvas.clip
    try {
        canvas.clip(RoundRectangle2D.Float(left,top,width,height,radius*2,radius*2))
        canvas.drawImage(bitmap,left.toInt(),top.toInt(),(left+width).toInt(),(top+height).toInt(),0,0,bitmap.width,bitmap.height,null)
    } finally {canvas.clip=clip}
}

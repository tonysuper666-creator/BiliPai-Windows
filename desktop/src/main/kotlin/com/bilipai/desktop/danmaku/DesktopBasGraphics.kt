package com.bilipai.desktop.danmaku

import org.jetbrains.skia.*
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import kotlin.math.ceil

/** Dedicated primitives for the complete original painter; no Android or second video surface. */
internal class DesktopBasRect(var left:Float=0f,var top:Float=0f,var right:Float=0f,var bottom:Float=0f) {
    fun width()=right-left
    fun height()=bottom-top
}
internal object DesktopBasColor { const val BLACK:Int=-0x1000000 }
internal data class DesktopBasTypeface(val family:String,val weight:Int) {
    companion object {
        const val NORMAL=0;const val BOLD=1
        fun create(family:String,style:Int)=DesktopBasTypeface(
            if(family=="sans-serif")"Segoe UI" else family,if(style==BOLD)700 else 400)
        fun create(base:DesktopBasTypeface,weight:Int,@Suppress("UNUSED_PARAMETER") italic:Boolean)=base.copy(weight=weight)
    }
}
internal open class DesktopBasPaint(@Suppress("UNUSED_PARAMETER") flags:Int=1) {
    companion object { const val ANTI_ALIAS_FLAG=1 }
    enum class Style { FILL,STROKE }
    var style=Style.FILL
    var color:Int=DesktopBasColor.BLACK
    var strokeWidth=1f
    internal fun native():Paint {
        DesktopBasRenderLimits.finite(strokeWidth,128f)
        if(strokeWidth<0f)throw DesktopBasRenderRejected("BAS invalid stroke width")
        return Paint().apply {
            isAntiAlias=true;this.color=this@DesktopBasPaint.color
            mode=if(style==Style.FILL)PaintMode.FILL else PaintMode.STROKE
            this.strokeWidth=this@DesktopBasPaint.strokeWidth
        }
    }
}
internal class DesktopBasTextPaint(flags:Int):DesktopBasPaint(flags),AutoCloseable {
    var textSize=25f
    var typeface=DesktopBasTypeface("Segoe UI",400)
    private var fonts:FontCollection?=null
    internal var shadow:Shadow?=null
    internal data class Shadow(val radius:Float,val dx:Float,val dy:Float,val color:Int)
    fun clearShadowLayer(){shadow=null}
    fun setShadowLayer(radius:Float,dx:Float,dy:Float,color:Int){shadow=Shadow(radius,dx,dy,color)}
    internal fun paragraph(text:String):Paragraph {
        DesktopBasRenderLimits.finite(textSize,DesktopBasRenderLimits.MAX_FONT_SIZE)
        if(textSize<=0f || text.length>DesktopBasRenderLimits.MAX_TEXT_CHARS)
            throw DesktopBasRenderRejected("BAS text exceeds the font budget")
        val collection=fonts ?: FontCollection().setDefaultFontManager(FontMgr.default).also {
            // Retain the one current layout in the original node, never an unbounded animation history.
            it.paragraphCache.setEnabled(false);fonts=it
        }
        return ParagraphStyle().use { paragraphStyle ->
            TextStyle().use { textStyle ->
                textStyle.fontSize=textSize
                textStyle.fontStyle=FontStyle.NORMAL.withWeight(typeface.weight)
                textStyle.setFontFamily(typeface.family)
                paragraphStyle.textStyle=textStyle
                ParagraphBuilder(paragraphStyle,collection).use { builder ->
                    builder.addText(text);builder.build()
                }
            }
        }
    }
    fun measureText(text:String,start:Int,end:Int):Float=paragraph(text.substring(start,end)).use { paragraph ->
        paragraph.layout(DesktopBasRenderLimits.MAX_LAYOUT_WIDTH.toFloat())
        // Reject wrapping caused by an over-wide authored line rather than silently clipping it.
        if(paragraph.lineNumber>1 || !paragraph.maxIntrinsicWidth.isFinite() ||
            paragraph.maxIntrinsicWidth>DesktopBasRenderLimits.MAX_LAYOUT_WIDTH)
            throw DesktopBasRenderRejected("BAS authored line exceeds the layout width budget")
        paragraph.maxIntrinsicWidth
    }
    override fun close(){fonts?.close();fonts=null;shadow=null}
}
internal object DesktopBasLayout { enum class Alignment { ALIGN_NORMAL } }

/** SkParagraph performs real shaping/fallback. Layout survives color, seek and frame changes. */
internal class DesktopBasStaticLayout private constructor(
    private val text:String,private val paint:DesktopBasTextPaint,val width:Int,
    private val paragraph:Paragraph,
):AutoCloseable {
    val height:Int=ceil(paragraph.height.toDouble()).toInt()
    fun draw(canvas:DesktopBasCanvas) {
        val shadow=paint.shadow
        if(shadow!=null) {
            MaskFilter.makeBlur(FilterBlurMode.NORMAL,MaskFilter.convertRadiusToSigma(shadow.radius),true).use { blur ->
                paint.native().use { native ->
                    native.mode=PaintMode.FILL
                    native.color=(shadow.color and 0xFFFFFF) or (paint.color and -0x1000000)
                    native.maskFilter=blur
                    paragraph.updateForegroundPaint(0,text.length,native)
                    paragraph.paint(canvas.native,shadow.dx,shadow.dy)
                }
            }
        }
        paint.native().use { native ->
            paragraph.updateForegroundPaint(0,text.length,native)
            paragraph.paint(canvas.native,0f,0f)
        }
    }
    override fun close()=paragraph.close()
    class Builder private constructor(private val text:String,private val paint:DesktopBasTextPaint,private val width:Int) {
        companion object {fun obtain(text:String,start:Int,end:Int,paint:DesktopBasTextPaint,width:Int)=Builder(text.substring(start,end),paint,width)}
        fun setAlignment(value:DesktopBasLayout.Alignment):Builder {require(value==DesktopBasLayout.Alignment.ALIGN_NORMAL);return this}
        fun setIncludePad(value:Boolean):Builder {require(!value);return this} // SkParagraph has no Android font padding.
        fun build():DesktopBasStaticLayout {
            if(width !in 1..DesktopBasRenderLimits.MAX_LAYOUT_WIDTH)throw DesktopBasRenderRejected("BAS text layout width exceeds the budget")
            val paragraph=paint.paragraph(text)
            try {
                paragraph.layout(width.toFloat())
                if(!paragraph.height.isFinite() || paragraph.height>DesktopBasRenderLimits.MAX_LAYOUT_HEIGHT)
                    throw DesktopBasRenderRejected("BAS text layout height exceeds the budget")
                return DesktopBasStaticLayout(text,paint,width,paragraph)
            }catch(error:Exception){paragraph.close();throw error}
        }
    }
}
internal class DesktopBasPath internal constructor(internal val native:Path):AutoCloseable {
    fun computeBounds(rect:DesktopBasRect,@Suppress("UNUSED_PARAMETER") exact:Boolean) {
        val bounds=native.computeTightBounds();rect.left=bounds.left;rect.top=bounds.top;rect.right=bounds.right;rect.bottom=bounds.bottom
    }
    override fun close()=native.close()
}
internal object DesktopBasPathParser {
    fun createPathFromPathData(data:String):DesktopBasPath? {
        if(data.isBlank())return null
        DesktopBasRenderBudget().validateSvg(data)
        val path=Path.makeFromSVGString(data)
        if(!path.isFinite || path.verbsCount>DesktopBasRenderLimits.MAX_PATH_VERBS || path.pointsCount>DesktopBasRenderLimits.MAX_PATH_VERBS*3) {
            path.close();throw DesktopBasRenderRejected("BAS native path exceeds the budget")
        }
        return DesktopBasPath(path)
    }
}
internal class DesktopBasCanvas(internal val native:Canvas) {
    fun save()=native.save()
    fun restoreToCount(count:Int){native.restoreToCount(count)}
    fun concat(matrix:DesktopBasMatrix){
        val m=matrix.values;m.forEach {DesktopBasRenderLimits.finite(it)}
        native.concat(Matrix33(m[0],m[1],m[2],m[3],m[4],m[5],m[6],m[7],m[8]))
    }
    fun clipRect(left:Float,top:Float,right:Float,bottom:Float){native.clipRect(Rect.makeLTRB(left,top,right,bottom))}
    fun translate(x:Float,y:Float){native.translate(x,y)}
    fun drawPath(path:DesktopBasPath,paint:DesktopBasPaint){paint.native().use {native.drawPath(path.native,it)}}
    fun drawRoundRect(left:Float,top:Float,right:Float,bottom:Float,rx:Float,ry:Float,paint:DesktopBasPaint){
        paint.native().use {native.drawRRect(RRect.makeXYWH(left,top,right-left,bottom-top,rx,ry),it)}
    }
}

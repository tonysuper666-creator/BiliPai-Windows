package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.parser.bas.BasDanmaku
import com.android.purebilibili.danmaku.parser.bas.BasTarget
import com.sun.jna.Pointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteOrder

internal data class DesktopBasRenderStatus(val accepted:Boolean,val activeScenes:Int=0,val reason:String?=null)
internal data class DesktopBasHit(val target:BasTarget,val targetIdentity:Any,val frameRevision:Long)

/** Thread-confined retained leaf, to be owned by the existing Overlay EDT. No window/player/source authority.
 * configure and coordinates are physical viewport pixels; frame uses absolute media milliseconds.
 * Root must still bind source/document/settings/geometry identity at press and final action admission.
 */
internal class DesktopBasRenderer(private val touchSlopPx:Float=8f):AutoCloseable {
    private val budget=DesktopBasRenderBudget()
    private val scenes=DesktopBasRetainedScenes(budget,touchSlopPx)
    private var items:List<BasDanmaku> = emptyList()
    private var viewport:DesktopBasViewport?=null
    private var bitmap:Bitmap?=null
    private var canvas:Canvas?=null
    private var raster:BufferedImage?=null
    private var closed=false
    private var frameReady=false
    private var pressedHit:DesktopBasHit?=null
    private var displayOpacity=1f
    private var displayFontScale=1f
    private var displayFontWeight=5
    var status=DesktopBasRenderStatus(true)
        private set
    var frameRevision=0L
        private set
    var paintedRevision=-1L
        private set
    init {require(touchSlopPx.isFinite() && touchSlopPx>=0f && touchSlopPx<=128f)}

    fun configure(items:List<BasDanmaku>,widthPx:Int,heightPx:Int,opacity:Float=1f,fontScale:Float=1f,fontWeight:Int=5) {
        check(!closed)
        try {
            DesktopBasRenderLimits.pixels(widthPx,heightPx)
            DesktopBasRenderLimits.finite(opacity,1f);DesktopBasRenderLimits.finite(fontScale,8f)
            if(opacity<0f || fontScale<=0f || fontWeight !in 1..9 || items.size>5000)
                throw DesktopBasRenderRejected("BAS invalid display settings")
            if(this.items!==items) {
                var elements=0L;var transitions=0L;var text=0L
                for(item in items) {
                    budget.validateScene(item)
                    elements+=item.program.elements.size;transitions+=item.program.transitions.size
                    text+=maxOf(item.program.textContent.length.toLong(),budget.textCost(item.program))
                    if(elements>4096 || transitions>16384 || text>2*1024*1024)
                        throw DesktopBasRenderRejected("BAS drawing document exceeds the aggregate budget")
                }
            }
            val next=DesktopBasViewport(widthPx,heightPx)
            if(this.items!==items || next!=viewport || displayOpacity!=opacity || displayFontScale!=fontScale || displayFontWeight!=fontWeight)
                cancelPress()
            if(next!=viewport)releaseRaster()
            this.items=items;viewport=next
            displayOpacity=opacity;displayFontScale=fontScale;displayFontWeight=fontWeight
            scenes.configure(items,next,opacity,fontScale,fontWeight)
            frameRevision++;paintedRevision=-1L;frameReady=false;status=DesktopBasRenderStatus(true)
        }catch(rejected:DesktopBasRenderRejected){reject(rejected)}
    }

    fun frame(positionMs:Long) {
        check(!closed)
        if(!status.accepted || viewport==null)return
        try {
            if(positionMs<0L)throw DesktopBasRenderRejected("BAS invalid media clock")
            var activeElements=0
            for(item in items) {
                val relative=positionMs-item.startTimeMs
                if(relative>=0L && relative<item.durationMs)activeElements+=item.program.elements.size
                if(activeElements>DesktopBasRenderLimits.MAX_ACTIVE_ELEMENTS)
                    throw DesktopBasRenderRejected("BAS active drawing elements exceed the budget")
            }
            if(budget.retainedElements+activeElements>DesktopBasRenderLimits.MAX_RETAINED_ELEMENTS)scenes.evictInactive(positionMs)
            budget.beginFrame();scenes.frame(positionMs)
            frameRevision++;paintedRevision=-1L;frameReady=true
            status=DesktopBasRenderStatus(true,scenes.activeCount)
        }catch(rejected:DesktopBasRenderRejected){reject(rejected)}
    }

    /** No per-node full-window image; one Skia raster + one premultiplied Java raster reused by size. */
    private fun drawRaster():BufferedImage? {
        val stage=viewport ?: return null
        if(!status.accepted || !frameReady)return null
        try {
            DesktopBasRenderLimits.pixels(stage.widthPx,stage.heightPx)
            if(bitmap==null) {
                check(ByteOrder.nativeOrder()==ByteOrder.LITTLE_ENDIAN) {"BAS BGRA raster requires little endian"}
                val allocated=Bitmap()
                try {
                    check(allocated.allocPixels(ImageInfo(stage.widthPx,stage.heightPx,ColorType.BGRA_8888,ColorAlphaType.PREMUL)))
                    bitmap=allocated;canvas=Canvas(allocated)
                    raster=BufferedImage(stage.widthPx,stage.heightPx,BufferedImage.TYPE_INT_ARGB_PRE)
                }catch(error:Exception){
                    if(bitmap===allocated)releaseRaster() else allocated.close()
                    throw error
                }
            }
            val drawing=checkNotNull(canvas)
            drawing.clear(0);scenes.draw(DesktopBasCanvas(drawing))
            val output=checkNotNull(raster)
            val pixels=(output.raster.dataBuffer as DataBufferInt).data
            // Borrow only this exact owned bitmap's checked pointer for a bounded copy.
            // BGRA8888 premultiplied on Windows little endian maps to ARGB_PRE Int pixels.
            checkNotNull(checkNotNull(bitmap).peekPixels()).use { view ->
                check(view.info.colorType==ColorType.BGRA_8888 && view.info.colorAlphaType==ColorAlphaType.PREMUL)
                check(view.rowBytes==stage.widthPx*4 && view.addr!=0L)
                Pointer(view.addr).read(0L,pixels,0,pixels.size)
            }
            return output
        }catch(rejected:DesktopBasRenderRejected){reject(rejected);return null}
    }

    fun paint(graphics:Graphics2D):Boolean {
        check(!closed)
        if(!status.accepted || !frameReady || viewport==null)return false
        if(status.activeScenes==0) {
            // The owning overlay clears its panel before every paint. No BAS means
            // no full 4K/5K raster allocation or per-frame transparent pixel copy.
            releaseRaster();paintedRevision=frameRevision;return true
        }
        val output=drawRaster() ?: return false
        if(!graphics.drawImage(output,0,0,null))return false
        paintedRevision=frameRevision;return true
    }

    /** A bounded headless raster copy for pixel tests/export; no native surface/window/GPU is opened. */
    fun rasterSnapshot():BufferedImage? {
        check(!closed)
        val output=drawRaster() ?: return null
        val copy=BufferedImage(output.width,output.height,BufferedImage.TYPE_INT_ARGB_PRE)
        val source=(output.raster.dataBuffer as DataBufferInt).data
        val target=(copy.raster.dataBuffer as DataBufferInt).data
        source.copyInto(target);return copy
    }

    fun hit(xPx:Float,yPx:Float):BasTarget? {
        if(closed || !status.accepted || !frameReady || !xPx.isFinite() || !yPx.isFinite())return null
        return scenes.hit(xPx,yPx)
    }
    /** Revision attests this leaf's completed target Graphics paint, not a physical-screen observation. */
    fun hitReceipt(xPx:Float,yPx:Float):DesktopBasHit? {
        if(paintedRevision!=frameRevision)return null
        return hit(xPx,yPx)?.let {DesktopBasHit(it,it,frameRevision)}
    }
    fun press(xPx:Float,yPx:Float):Boolean {
        if(closed || !status.accepted || !frameReady || !xPx.isFinite() || !yPx.isFinite())return false
        val hit=hitReceipt(xPx,yPx) ?: return false
        scenes.activated=null
        return scenes.onTouchEvent(DesktopBasPointerEvent(DesktopBasPointerEvent.ACTION_DOWN,xPx,yPx)).also {pressedHit=if(it)hit else null}
    }
    fun pressReceipt(xPx:Float,yPx:Float):DesktopBasHit?=if(press(xPx,yPx))pressedHit else null
    fun move(xPx:Float,yPx:Float) {
        if(!xPx.isFinite() || !yPx.isFinite()){cancelPress();return}
        if(!closed && status.accepted && frameReady)scenes.onTouchEvent(DesktopBasPointerEvent(DesktopBasPointerEvent.ACTION_MOVE,xPx,yPx))
    }
    fun release(xPx:Float,yPx:Float):BasTarget? {
        return releaseReceipt(xPx,yPx)?.target
    }
    fun releaseReceipt(xPx:Float,yPx:Float):DesktopBasHit? {
        if(!xPx.isFinite() || !yPx.isFinite()){cancelPress();return null}
        if(closed || !status.accepted || !frameReady)return null
        val down=pressedHit;pressedHit=null
        val up=hitReceipt(xPx,yPx)
        scenes.activated=null
        scenes.onTouchEvent(DesktopBasPointerEvent(DesktopBasPointerEvent.ACTION_UP,xPx,yPx))
        val target=scenes.activated;scenes.activated=null
        return if(target!=null && down!=null && up!=null && down.targetIdentity===up.targetIdentity)up else null
    }
    fun cancelPress(){pressedHit=null;if(!closed)scenes.onTouchEvent(DesktopBasPointerEvent(DesktopBasPointerEvent.ACTION_CANCEL,0f,0f))}

    fun clear() {
        scenes.clear();pressedHit=null;items=emptyList();viewport=null;releaseRaster()
        frameRevision++;paintedRevision=-1L;frameReady=false;status=DesktopBasRenderStatus(true)
    }
    private fun reject(error:DesktopBasRenderRejected){clear();status=DesktopBasRenderStatus(false,reason=error.reason)}
    private fun releaseRaster(){canvas?.close();canvas=null;bitmap?.close();bitmap=null;raster?.flush();raster=null}
    override fun close(){if(!closed){clear();closed=true}}
}

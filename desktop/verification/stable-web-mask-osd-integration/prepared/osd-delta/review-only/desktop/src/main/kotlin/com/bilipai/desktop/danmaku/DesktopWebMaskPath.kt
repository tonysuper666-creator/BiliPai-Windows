package com.bilipai.desktop.danmaku

import java.awt.Graphics2D
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import org.jetbrains.skia.Path as SkiaPath
import org.jetbrains.skia.PathVerb

/** Windows path carrier; Skia owns SVG grammar, Java2D owns the existing native overlay paint. */
class DesktopWebMaskPath internal constructor() {
    private val path = Path2D.Float(Path2D.WIND_NON_ZERO)
    internal var commandCount:Int=0
        private set
    private var valid=true
    private fun recordCommand(){if(++commandCount>MAX_PATH_COMMANDS)throw PathBudgetExceeded()}
    fun addPath(other: DesktopWebMaskPath) {
        if(!valid)return
        if(!other.valid || other.commandCount>MAX_PATH_COMMANDS-commandCount){valid=false;path.reset();commandCount=0;return}
        path.append(other.path, false);commandCount+=other.commandCount
    }
    internal fun scaledArea(width: Int, height: Int, sourceWidth: Int, sourceHeight: Int): Area =
        Area(AffineTransform.getScaleInstance(width.toDouble()/sourceWidth, height.toDouble()/sourceHeight).createTransformedShape(path))
    internal fun transformedArea(transform:AffineTransform):Area=Area(transform.createTransformedShape(path))

    companion object {
        private const val MAX_PATH_COMMANDS=20_000
        private class PathBudgetExceeded:RuntimeException()
        fun createPathFromPathData(data: String): DesktopWebMaskPath? {
            if (data.isBlank()) return null
            return try { SkiaPath.makeFromSVGString(data).use { native ->
                if (!native.isFinite) return@use null
                val result = DesktopWebMaskPath()
                native.iterator().use { iterator ->
                    while (iterator.hasNext()) {
                        val segment = iterator.next() ?: continue
                        val p = result.path
                        result.recordCommand()
                        when (segment.verb) {
                            PathVerb.MOVE -> p.moveTo(segment.p0!!.x, segment.p0!!.y)
                            PathVerb.LINE -> p.lineTo(segment.p1!!.x, segment.p1!!.y)
                            PathVerb.QUAD -> p.quadTo(segment.p1!!.x, segment.p1!!.y, segment.p2!!.x, segment.p2!!.y)
                            PathVerb.CUBIC -> p.curveTo(segment.p1!!.x, segment.p1!!.y, segment.p2!!.x, segment.p2!!.y, segment.p3!!.x, segment.p3!!.y)
                            PathVerb.CONIC -> {
                                // Real Skia verbs retain the full SVG grammar. Java2D has no rational-conic verb.
                                val a=segment.p0!!;val b=segment.p1!!;val c=segment.p2!!;val weight=segment.conicWeight.toDouble()
                                fun point(t:Double):Pair<Double,Double>{
                                    val u=1.0-t;val aa=u*u;val bb=2.0*weight*t*u;val cc=t*t;val denominator=aa+bb+cc
                                    return (aa*a.x+bb*b.x+cc*c.x)/denominator to (aa*a.y+bb*b.y+cc*c.y)/denominator
                                }
                                fun flatten(t0:Double,t1:Double,from:Pair<Double,Double>,to:Pair<Double,Double>,depth:Int){
                                    val middle=(t0+t1)/2.0;val q=point(middle)
                                    val dx=q.first-(from.first+to.first)/2.0;val dy=q.second-(from.second+to.second)/2.0
                                    if(depth<16 && dx*dx+dy*dy>0.0001){flatten(t0,middle,from,q,depth+1);flatten(middle,t1,q,to,depth+1)}
                                    else {result.recordCommand();p.lineTo(to.first,to.second)}
                                }
                                flatten(0.0,1.0,a.x.toDouble() to a.y.toDouble(),c.x.toDouble() to c.y.toDouble(),0)
                            }
                            PathVerb.CLOSE -> p.closePath()
                            PathVerb.DONE -> Unit
                        }
                    }
                }
                result
            }}catch(_:PathBudgetExceeded){null}
        }
    }
}

/** Clip only the standard danmaku Graphics2D child, before advanced/UI/eye tint painting. */
internal fun applyDesktopWebMaskClip(graphics: Graphics2D, width: Int, height: Int, frame: com.android.purebilibili.danmaku.engine.DanmakuMaskFrame?, videoViewport:com.bilipai.desktop.player.PlayerVideoViewport?) {
    if (frame == null || videoViewport == null || width <= 0 || height <= 0) return
    val visible = Area(Rectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble()))
    visible.subtract(frame.path.transformedArea(videoViewport.sourceToPhysicalTransform(width,height,frame.sourceWidth,frame.sourceHeight)))
    graphics.clip(visible)
}

package com.bilipai.desktop.danmaku

import com.android.purebilibili.feature.video.danmaku.resolveDanmakuViewport
import java.awt.DisplayMode
import java.awt.geom.AffineTransform
import java.io.File
import kotlin.math.abs

/** Exact dedicated command expression ABI. Never invoked without an actual Overlay owner. */
internal fun commandViewportReceipt(width:Int,height:Int,density:Float,danmaku:DanmakuOverlay)=
    resolveDanmakuViewport(width,height,density,danmaku.maximumDisplayShortSidePx())

fun main(args:Array<String>) {
    var assertions=0
    fun expect(value:Boolean,label:String) {assertions++;check(value){label}}
    val transform=AffineTransform.getScaleInstance(1.5,1.5)
    val mode=DisplayMode(1920,1080,32,60) // Declared fixture physical mode; not a fabricated production getter.
    val short=minOf(mode.width,mode.height).toFloat()
    val geometry=requireNotNull(DesktopDanmakuPaintGeometry.from(720,480,transform,short))
    expect(geometry.viewport.widthPx==1080,"actual paint physical width")
    expect(geometry.viewport.heightPx==720,"actual paint physical height")
    expect(geometry.viewport.density==1.5f,"actual physical DPI")
    expect(abs(geometry.viewport.scale-2f/3f)<.00001f,"real maximum monitor reference, not360")
    val highDpiMode=DisplayMode(3840,2160,32,60)
    val moved=requireNotNull(DesktopDanmakuPaintGeometry.from(720,480,transform,minOf(highDpiMode.width,highDpiMode.height).toFloat()))
    expect(abs(moved.viewport.scale-1f/3f)<.00001f,"monitor change alters source viewport reference")
    expect(DesktopDanmakuPaintGeometry.from(720,480,transform,0f)==null,"missing reference has no360 fallback")
    expect(DesktopDanmakuPaintGeometry.from(720,480,transform,Float.NaN)==null,"invalid reference denied")
    expect(DesktopDanmakuPaintGeometry.from(0,480,transform,short)==null,"invalid size denied")
    expect(DesktopDanmakuPaintGeometry.from(720,480,AffineTransform.getScaleInstance(0.0,1.0),short)==null,"invalid device transform denied")
    val landscape=resolveDanmakuViewport(1080,720,1.5f,short)
    val portrait=resolveDanmakuViewport(720,1080,1.5f,short)
    expect(landscape?.scale==portrait?.scale,"rotation keeps true physical short-side reference")
    expect(DanmakuOverlay::class.java.declaredMethods.any {it.name.startsWith("maximumDisplayShortSidePx")},"required Overlay shared command getter ABI")
    expect(DesktopWindowsDanmakuRenderPlatform::class.java.declaredMethods.any {it.name=="maximumDisplayShortSidePx"},"real Windows port class ABI")
    val result="{\"status\":\"PASS\",\"assertions\":$assertions,\"fixtureOnlyDisplayModes\":true,\"actualRootMonitorRuntimeAccepted\":false,\"nativeWindowOrHTTP\":false}"
    File(args.single(),"proof-result.json").writeText(result+"\n");println(result)
}

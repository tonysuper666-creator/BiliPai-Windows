package com.bilipai.desktop.danmaku

import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.EventQueue
import java.io.File
import javax.swing.UIManager

fun main(args:Array<String>) {
    check(!GraphicsEnvironment.isHeadless())
    val rows=mutableListOf<String>()
    var assertions=0
    EventQueue.invokeAndWait {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.forEachIndexed {index,device ->
            val window=Frame(device.defaultConfiguration)
            try {
                window.setSize(640,480)
                window.addNotify() // Real private hidden test HWND, not a mounted application Root.
                check(window.isDisplayable && !window.isVisible);assertions++
                val actualDevice=requireNotNull(window.graphicsConfiguration).device
                check(actualDevice===device);assertions++
                val platform=DesktopWindowsDanmakuRenderPlatform {window}
                val mode=actualDevice.displayMode
                val short=minOf(mode.width,mode.height).toFloat()
                check(platform.maximumDisplayShortSidePx()==short && short>0f);assertions++
                val geometry=requireNotNull(DesktopDanmakuPaintGeometry.from(640,480,
                    window.graphicsConfiguration.defaultTransform,platform.maximumDisplayShortSidePx()))
                check(geometry.viewport.widthPx>0 && geometry.viewport.heightPx>0);assertions++
                check(geometry.viewport.scale>0f && geometry.viewport.scale<=1f);assertions++
                val font=platform.resolveTypeface(4)
                check(font.family==(window.font ?: requireNotNull(UIManager.getFont("Label.font"))).family);assertions++
                check(platform.systemChromeInsetPx()>=0);assertions++
                rows += "{\"index\":$index,\"physicalWidth\":${mode.width},\"physicalHeight\":${mode.height},\"shortSide\":$short,\"viewportScale\":${geometry.viewport.scale}}"
            } finally {window.dispose()}
        }
    }
    check(rows.isNotEmpty());assertions++
    val result="{\"status\":\"PASS\",\"assertions\":$assertions,\"actualHiddenAWTWindow\":true,\"actualRootWindowAccepted\":false,\"accountOrSocket\":false,\"monitors\":[${rows.joinToString()}]}"
    File(args.single(),"proof-result.json").writeText(result+"\n")
    println(result)
}

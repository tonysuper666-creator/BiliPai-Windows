package com.bilipai.desktop.ui

import com.bilipai.desktop.plugins.DesktopAnimatedSkinImage
import java.awt.Component
import java.awt.Graphics
import java.awt.image.BufferedImage
import javax.swing.Icon
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Rect

/** A view lease on the existing bounded Skia animated-image decoder. Encoded
 * bytes come only from the same successful Coil request's pinned disk snapshot. */
internal class DesktopInlineAnimatedEmoteIcon(
    private val animation:DesktopAnimatedSkinImage,
    private val size:Int,
    private val padding:Int,
) : Icon,AutoCloseable {
    private val bitmap=Bitmap().apply {check(allocN32Pixels(size,size))}
    private val canvas=Canvas(bitmap)
    private val raster=BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB)
    private val started=System.nanoTime()
    private var closed=false
    override fun getIconWidth()=size+padding*2
    override fun getIconHeight()=size
    override fun paintIcon(component:Component?,graphics:Graphics,x:Int,y:Int) {
        if(closed)return
        canvas.clear(0)
        animation.render(canvas,Rect.makeWH(size.toFloat(),size.toFloat()),(System.nanoTime()-started)/1_000_000_000.0)
        for(row in 0 until size)for(column in 0 until size)raster.setRGB(column,row,bitmap.getColor(column,row))
        graphics.drawImage(raster,x+padding,y,component)
    }
    override fun close() {if(!closed){closed=true;canvas.close();bitmap.close();animation.close();raster.flush()}}
}

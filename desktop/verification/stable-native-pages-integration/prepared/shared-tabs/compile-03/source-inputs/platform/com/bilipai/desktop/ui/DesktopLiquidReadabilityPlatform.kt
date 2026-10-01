package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.*
import kotlin.math.roundToInt

internal data class DesktopLiquidSampleRect(val left:Int,val top:Int,val right:Int,val bottom:Int) {
    fun width()=right-left
    fun height()=bottom-top
}
internal class DesktopLiquidSampleBitmap(val width:Int,val height:Int,private val pixels:IntArray) {
    fun getPixels(target:IntArray,offset:Int,stride:Int,x:Int,y:Int,w:Int,h:Int) {
        for(row in 0 until h)pixels.copyInto(target,offset+row*stride,(y+row)*width+x,(y+row)*width+x+w)
    }
}

/** Windows PixelCopy seam for the existing recorded page background layer.
 * Root supplies that same layer, its actual boundsInWindow, current Compose
 * window size and existing foreground/page owner. No HWND/window screenshot,
 * second backdrop cache/account owner or native sensor is fabricated. */
internal class DesktopLiquidReadabilityEnvironment(
    private val layer:GraphicsLayer,
    private val boundsInWindow:()->Rect?,
    private val windowSize:()->IntSize,
    private val stillOwned:()->Boolean,
) {
    val width:Int get()=windowSize().width
    val height:Int get()=windowSize().height
    val isSupported:Boolean get()=stillOwned()
    suspend fun sampleBitmap(bounds:DesktopLiquidSampleRect,sampleWidth:Int,sampleHeight:Int):DesktopLiquidSampleBitmap? {
        currentCoroutineContext().ensureActive();if(!stillOwned())return null
        val actual=boundsInWindow()?:return null
        if(actual.width<=0f||actual.height<=0f||layer.size.width<=0||layer.size.height<=0)return null
        val source=layer.toImageBitmap()
        currentCoroutineContext().ensureActive();if(!stillOwned())return null
        val left=(bounds.left-actual.left).roundToInt().coerceIn(0,source.width)
        val top=(bounds.top-actual.top).roundToInt().coerceIn(0,source.height)
        val right=(bounds.right-actual.left).roundToInt().coerceIn(0,source.width)
        val bottom=(bounds.bottom-actual.top).roundToInt().coerceIn(0,source.height)
        if(right<=left||bottom<=top)return null
        val sample=ImageBitmap(sampleWidth,sampleHeight)
        Canvas(sample).drawImageRect(source,IntOffset(left,top),IntSize(right-left,bottom-top),IntOffset.Zero,IntSize(sampleWidth,sampleHeight),Paint().apply{filterQuality=FilterQuality.Low})
        val pixels=IntArray(sampleWidth*sampleHeight);sample.readPixels(pixels)
        currentCoroutineContext().ensureActive();if(!stillOwned())return null
        return DesktopLiquidSampleBitmap(sampleWidth,sampleHeight,pixels)
    }
}

/** Absence has the original no-host reset behavior; adaptive Windows proof and
 * integration require a real existing background layer supplied by Root. */
internal val LocalDesktopLiquidReadabilityEnvironment=staticCompositionLocalOf<DesktopLiquidReadabilityEnvironment?> { null }

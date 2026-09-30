package com.android.purebilibili.feature.home.components.cards

import androidx.compose.ui.graphics.Color
import com.bilipai.desktop.palette.DesktopPalette
import org.jetbrains.skia.Bitmap
import kotlinx.coroutines.*
import kotlin.math.ceil
import kotlin.math.sqrt

/** Original AndroidX median-cut quantizer and original swatch selection with real Skia pixels. */
object VideoCardCoverColorStore {
    private val colorCache=object:LinkedHashMap<String,Color>(128,0.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Color>?)=size>128
    }
    fun getCachedColor(cacheKey:String):Color?=if(cacheKey.isBlank())null else synchronized(colorCache){colorCache[cacheKey]}
    fun extractColorAsync(cacheKey:String,bitmap:Bitmap,scope:CoroutineScope,onColorExtracted:(Color)->Unit) {
        if(cacheKey.isBlank())return
        getCachedColor(cacheKey)?.let{onColorExtracted(it);return}
        scope.launch(Dispatchers.Default){
            val color=extractRepresentativeColor(bitmap)?:return@launch
            ensureActive()
            synchronized(colorCache){colorCache[cacheKey]=color}
            withContext(Dispatchers.Main){onColorExtracted(color)}
        }
    }
    internal fun extractRepresentativeColor(bitmap:Bitmap):Color? {
        return runCatching {
        if(bitmap.isClosed || bitmap.width<=0 || bitmap.height<=0)return null
        // Palette.Builder.resizeBitmapArea(48*48): exact area/ceil dimensions, unfiltered scaling.
        val area=bitmap.width.toLong()*bitmap.height
        val ratio=if(area>48*48)sqrt((48.0*48)/area)else 1.0
        val width=ceil(bitmap.width*ratio).toInt();val height=ceil(bitmap.height*ratio).toInt()
        val pixels=IntArray(width*height){i ->
            val x=(((i%width)+0.5)*bitmap.width/width).toInt().coerceAtMost(bitmap.width-1)
            val y=(((i/width)+0.5)*bitmap.height/height).toInt().coerceAtMost(bitmap.height-1)
            bitmap.getColor(x,y)
        }
        resolveRepresentativeSwatch(DesktopPalette.quantize(pixels,16))?.rgb?.let{Color(it)}
        }.getOrNull()
    }
    internal fun resolveRepresentativeSwatch(swatches:List<DesktopPalette.Swatch>):DesktopPalette.Swatch? {
        if(swatches.isEmpty())return null
        val useful=swatches.filterNot{swatch ->
            val rgb=swatch.rgb;val r=(rgb ushr 16)and 255;val g=(rgb ushr 8)and 255;val b=rgb and 255
            val max=maxOf(r,g,b);val min=minOf(r,g,b)
            val saturation=if(max==0)0f else (max-min).toFloat()/max
            val value=max/255f
            saturation<0.08f || value<0.08f || value>0.96f
        }
        return (useful.ifEmpty{swatches}).maxByOrNull{it.population}
    }
    fun trimToSize(maxSize:Int){synchronized(colorCache){while(colorCache.size>maxSize.coerceAtLeast(0))colorCache.remove(colorCache.keys.first())}}
    fun clear(){synchronized(colorCache){colorCache.clear()}}
}

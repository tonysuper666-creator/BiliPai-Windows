package com.android.purebilibili.feature.home.components.cards

import androidx.compose.ui.graphics.Color
import coil3.BitmapImage
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import com.bilipai.desktop.palette.DesktopPalette
import org.jetbrains.skia.Bitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil
import kotlin.math.sqrt

/** Original AndroidX median-cut quantizer and original swatch selection with real Skia pixels. */
object VideoCardCoverColorStore {
    private val extractionMutex = Mutex()
    private val colorCache=object:LinkedHashMap<String,Color>(128,0.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Color>?)=size>128
    }
    fun getCachedColor(cacheKey:String):Color?=if(cacheKey.isBlank())null else synchronized(colorCache){colorCache[cacheKey]}
    /** Original stable 96px private sample and serialized cache recheck. Skia
     * supplies software pixels and closes the owned sample in place of recycle.
     */
    suspend fun extractColor(context: PlatformContext, cacheKey: String, coverUrl: String): Color? =
        withContext(Dispatchers.Default) {
            if (cacheKey.isBlank() || coverUrl.isBlank()) return@withContext null
            extractionMutex.withLock {
                getCachedColor(cacheKey)?.let { return@withLock it }
                val request = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .diskCacheKey(cacheKey)
                    .size(96, 96)
                    .scale(Scale.FIT)
                    .precision(Precision.EXACT)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .build()
                var sample: Bitmap? = null
                try {
                    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
                        ?: return@withLock null
                    val bitmap = (result.image as? BitmapImage)?.bitmap ?: return@withLock null
                    sample = bitmap
                    val color = extractRepresentativeColor(bitmap) ?: return@withLock null
                    ensureActive()
                    synchronized(colorCache) { colorCache[cacheKey] = color }
                    color
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                finally { sample?.close() }
            }
        }
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

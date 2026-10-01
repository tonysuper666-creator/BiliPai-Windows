package com.bilipai.desktop.ui

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.*
import java.io.File
import java.net.URI
import java.nio.file.Path
import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import com.bilipai.desktop.palette.DesktopPalette
import com.bilipai.desktop.palette.DesktopWallpaperPaletteScoring

internal interface DesktopWallpaperPaletteContext {
    fun isOwned():Boolean
    fun commitIfOwned(action:()->Unit):Boolean
    suspend fun systemWallpaper():DesktopSystemWallpaperSource
    suspend fun readOwnedPixels(uri:String):DesktopWallpaperPaletteRaster?
}
internal data class DesktopSystemWallpaperSource(val fileUri:String?,val desktopArgb:Int?)
internal fun interface DesktopSystemWallpaperPort { fun read():DesktopSystemWallpaperSource }

/** Read-only current Windows desktop information. No HWND, persistence or native actor. */
internal class DesktopWindowsSystemWallpaperPort : DesktopSystemWallpaperPort {
    private interface Api:StdCallLibrary {
        fun SystemParametersInfoW(action:Int,size:Int,path:CharArray,flags:Int):Boolean
        fun GetSysColor(index:Int):Int
        fun GetSysColorBrush(index:Int):com.sun.jna.Pointer?
    }
    private val api:Api by lazy {
        check(System.getProperty("os.name").startsWith("Windows"))
        Native.load("user32",Api::class.java,W32APIOptions.UNICODE_OPTIONS)
    }
    override fun read():DesktopSystemWallpaperSource {
        val buffer=CharArray(261)
        check(api.SystemParametersInfoW(0x0073,buffer.size,buffer,0)) {"系统壁纸信息暂不可用"}
        val path=String(buffer).substringBefore('\u0000')
        // COLOR_DESKTOP is unsupported on Windows10+ per Win32; zero alone is not an availability test.
        // This brush belongs to Windows. Never delete or mutate it.
        val brush=api.GetSysColorBrush(1)
        val windowsMajor=System.getProperty("os.version").substringBefore('.').toIntOrNull()
        val argb=if(windowsMajor==null || windowsMajor>=10 || brush==null || com.sun.jna.Pointer.nativeValue(brush)==0L)null else {
            val color=api.GetSysColor(1) // COLORREF is 0x00BBGGRR.
            0xff000000.toInt() or ((color and 0xff) shl 16) or (color and 0xff00) or ((color ushr 16) and 0xff)
        }
        return DesktopSystemWallpaperSource(path.takeIf(String::isNotBlank)?.let {Path.of(it).toUri().toString()},argb)
    }
}

/** Root MUST pass its same SingletonImageLoader and real platform context/admission.
 * The returned Coil Bitmap is borrowed/cache-owned: neither close nor recycle it.
 * Only this owned IntArray snapshot survives the request. */
internal class DesktopOwnedWallpaperPaletteContext(
    private val platformContext:PlatformContext,
    private val imageLoader:ImageLoader,
    private val system:DesktopSystemWallpaperPort,
    private val owned:()->Boolean,
    private val commit:((()->Unit)->Boolean),
) : DesktopWallpaperPaletteContext {
    override fun isOwned()=owned()
    override fun commitIfOwned(action:()->Unit)=commit {if(!owned())throw CancellationException("Wallpaper owner retired");action()}
    private suspend fun checkpoint() {currentCoroutineContext().ensureActive();if(!owned())throw CancellationException("Wallpaper owner retired")}
    override suspend fun systemWallpaper():DesktopSystemWallpaperSource=withContext(Dispatchers.IO) {checkpoint();system.read().also {checkpoint()}}
    override suspend fun readOwnedPixels(uri:String):DesktopWallpaperPaletteRaster? {
        checkpoint()
        val model:Any=when {
            uri.startsWith("file:") ->Path.of(URI.create(uri)).toFile()
            uri.startsWith("/") || Regex("^[A-Za-z]:[\\\\/].*").matches(uri) ->File(uri)
            uri.startsWith("content://") ->return null // Android provider URIs have no Windows service.
            else ->uri
        }
        val request=ImageRequest.Builder(platformContext).data(model).size(256,512).build()
        val result=imageLoader.execute(request) as? SuccessResult ?: return null
        checkpoint()
        val bitmap=(result.image as? coil3.BitmapImage)?.bitmap ?: return null
        if(bitmap.isClosed || bitmap.isEmpty)return null
        val width=bitmap.width;val height=bitmap.height
        require(width>0 && height>0 && width.toLong()*height<=16_000_000L) {"壁纸采样尺寸过大"}
        val pixels=IntArray(width*height)
        for(y in 0 until height) {
            checkpoint()
            for(x in 0 until width)pixels[y*width+x]=bitmap.getColor(x,y)
        }
        checkpoint()
        return DesktopWallpaperPaletteRaster(width,height,pixels)
    }
}

internal class DesktopWallpaperPaletteLru<T>(private val maximum:Int) {
    private val values=object:LinkedHashMap<String,T>(maximum+1,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,T>?)=size>maximum
    }
    fun get(key:String):T?=values[key]
    fun put(key:String,value:T) {values[key]=value}
    fun evictAll() {values.clear()}
    internal val size:Int get()=values.size
}

/** Exact Palette1.0.0 FULL-image area rescale, then mapped region, clearFilters/max8.
 * Nearest-neighbor center mapping replaces Bitmap.createScaledBitmap(filter=false).
 * Existing DesktopPalette is the ONLY color quantizer. */
internal class DesktopWallpaperPaletteRaster(val width:Int,val height:Int,private val argb:IntArray) {
    init {require(width>0 && height>0 && argb.size.toLong()==width.toLong()*height)}
    fun getPixel(x:Int,y:Int)=argb[y*width+x]
    fun paletteForOriginalRegion(top:Int,bottom:Int):DesktopWallpaperPaletteScoring {
        require(top in 0 until height && bottom in top+1..height)
        val area=112*112
        val ratio=if(width.toLong()*height>area)kotlin.math.sqrt(area/(width.toDouble()*height)) else 1.0
        val scaledWidth=kotlin.math.ceil(width*ratio).toInt()
        val scaledHeight=kotlin.math.ceil(height*ratio).toInt()
        val scale=scaledWidth/width.toDouble()
        val regionTop=if(ratio<1)kotlin.math.floor(top*scale).toInt() else top
        val regionBottom=if(ratio<1)minOf(kotlin.math.ceil(bottom*scale).toInt(),scaledHeight) else bottom
        val pixels=IntArray(scaledWidth*(regionBottom-regionTop))
        for(y in regionTop until regionBottom)for(x in 0 until scaledWidth) {
            val sourceX=minOf(((x+.5)*width/scaledWidth).toInt(),width-1)
            val sourceY=minOf(((y+.5)*height/scaledHeight).toInt(),height-1)
            pixels[(y-regionTop)*scaledWidth+x]=getPixel(sourceX,sourceY)
        }
        return DesktopWallpaperPaletteScoring(DesktopPalette.quantize(pixels,8))
    }
}

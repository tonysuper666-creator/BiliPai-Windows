package com.android.purebilibili.feature.home.components.cards

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 全局壁纸调色板提取与缓存管理
 * 负责在壁纸变更时异步提取多段垂直区间的调色板基色，完全解耦 UI 渲染树，杜绝循环采样。
 */
internal class WallpaperPaletteStore : AutoCloseable {
    private val MAX_CACHE_SIZE = 8
    private val requestGate=Any()
    private var generation=0L
    private var activeJob:kotlinx.coroutines.Job?=null
    private var closed=false
    private val paletteCache = com.bilipai.desktop.ui.DesktopWallpaperPaletteLru<WallpaperPalette>(MAX_CACHE_SIZE)
    private val _currentPalette = MutableStateFlow<WallpaperPalette?>(null)
    val currentPalette: StateFlow<WallpaperPalette?> = _currentPalette.asStateFlow()

    fun getCachedPalette(uri: String): WallpaperPalette? {
        if (uri.isBlank()) return null
        return synchronized(paletteCache) { paletteCache.get(uri) }
    }

    fun loadWallpaperPalette(context:com.bilipai.desktop.ui.DesktopWallpaperPaletteContext,uri:String,scope:CoroutineScope) {
        if(!context.isOwned() || scope.coroutineContext[kotlinx.coroutines.Job]?.isActive==false)return
        val version=synchronized(requestGate) {
            if(closed)return
            generation++;activeJob?.cancel();activeJob=null;generation
        }
        fun publish(palette:WallpaperPalette,cache:Boolean) {
            context.commitIfOwned {
                synchronized(requestGate) {
                    if(!closed && generation==version && scope.coroutineContext[kotlinx.coroutines.Job]?.isActive!=false) {
                        if(cache)synchronized(paletteCache) {paletteCache.put(uri,palette)}
                        _currentPalette.value=palette
                    }
                }
            }
        }
        val cached=getCachedPalette(uri)
        if(cached!=null) {publish(cached,false);return}
        val job=scope.launch(Dispatchers.Default,start=CoroutineStart.LAZY) {
            val palette=if(uri.isBlank())extractSystemWallpaperPalette(context) ?: createDefaultThemePalette()
                else extractWallpaperPaletteFromUri(context,uri) ?: extractSystemWallpaperPalette(context) ?: createDefaultThemePalette()
            currentCoroutineContext().ensureActive()
            publish(palette,uri.isNotBlank())
        }
        val stillOwned=context.isOwned()
        synchronized(requestGate) {
            if(closed || generation!=version || !stillOwned)job.cancel()
            else {activeJob=job;job.start()}
        }
    }

    private suspend fun extractSystemWallpaperPalette(context:com.bilipai.desktop.ui.DesktopWallpaperPaletteContext):WallpaperPalette? {
        return try {
            val system=context.systemWallpaper()
            val image=system.fileUri?.let {extractWallpaperPaletteFromUri(context,it)}
            image ?: system.desktopArgb?.let {argb ->Color(argb).let {primary ->WallpaperPalette(primary,primary,primary,listOf(primary,primary,primary))}}
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(failure:Exception) {currentCoroutineContext().ensureActive();if(!context.isOwned())throw CancellationException("Wallpaper owner retired");null}
    }

    private suspend fun extractWallpaperPaletteFromUri(context:com.bilipai.desktop.ui.DesktopWallpaperPaletteContext,uri:String):WallpaperPalette? {
        return try {
            val bitmap=context.readOwnedPixels(uri) ?: return null
            val stops = mutableListOf<Color>()
            val sliceCount = 5
            val sliceHeight = (bitmap.height / sliceCount).coerceAtLeast(1)
            for (i in 0 until sliceCount) {
                currentCoroutineContext().ensureActive()
                if(!context.isOwned())throw CancellationException("Wallpaper owner retired")
                val sliceTop = (i * sliceHeight).coerceIn(0, bitmap.height - 1)
                val sliceBottom = ((i + 1) * sliceHeight).coerceIn(sliceTop + 1, bitmap.height)
                val palette = bitmap.paletteForOriginalRegion(sliceTop,sliceBottom)
                val colorInt = palette.vibrantSwatch?.rgb
                    ?: palette.lightVibrantSwatch?.rgb
                    ?: palette.darkVibrantSwatch?.rgb
                    ?: palette.mutedSwatch?.rgb
                    ?: palette.dominantSwatch?.rgb
                    ?: bitmap.getPixel(bitmap.width / 2, (sliceTop + sliceBottom) / 2)
                stops.add(Color(colorInt))
            }
            WallpaperPalette(
                topColor = stops.first(),
                bottomColor = stops.last(),
                dominantColor = stops[stops.size / 2],
                stops = stops
            )
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(failure:Exception) {currentCoroutineContext().ensureActive();if(!context.isOwned())throw CancellationException("Wallpaper owner retired");null}
    }

    private fun createDefaultThemePalette(): WallpaperPalette {
        return WallpaperPalette(
            topColor = Color(0xFF6750A4),
            bottomColor = Color(0xFF7D5260),
            dominantColor = Color(0xFF6750A4),
            stops = listOf(
                Color(0xFF6750A4),
                Color(0xFF5B4D82),
                Color(0xFF6B4A6A),
                Color(0xFF7D5260),
                Color(0xFF8C4F5A)
            )
        )
    }

    fun clearCache() {
        synchronized(requestGate) { generation++;activeJob?.cancel();activeJob=null }
        synchronized(paletteCache) {
            paletteCache.evictAll()
        }
    }

    override fun close() { synchronized(requestGate) { closed=true };clear() }

    fun clear() {
        clearCache()
        _currentPalette.value = null
    }
}

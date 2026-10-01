package com.android.purebilibili.feature.home.components.cards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.size.Precision
import coil3.size.Scale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 视频封面代表色提取与缓存管理
 * 复用官方 androidx.palette:palette-ktx 能力，纯异步单向流，严防循环采样。
 */
object VideoCardCoverColorStore {
    private const val MAX_CACHE_SIZE = 128
    private val colorCache = LruCache<String, Color>(MAX_CACHE_SIZE)
    private val extractionMutex = Mutex()

    /** 同步获取已缓存的封面代表色。 */
    fun getCachedColor(cacheKey: String): Color? {
        if (cacheKey.isBlank()) return null
        return synchronized(colorCache) {
            colorCache.get(cacheKey)
        }
    }

    /** The caller owns cancellation; serialize small decodes and recheck the color cache. */
    suspend fun extractColor(
        context: Context,
        cacheKey: String,
        coverUrl: String,
    ): Color? = withContext(Dispatchers.Default) {
        if (cacheKey.isBlank() || coverUrl.isBlank()) return@withContext null
        extractionMutex.withLock {
            getCachedColor(cacheKey)?.let { return@withLock it }
            // Reuse the displayed cover's encoded disk entry, never copy its full hardware bitmap.
            // Memory reads/writes are disabled so this software sample is privately owned.
            val request = ImageRequest.Builder(context.applicationContext)
                .data(coverUrl)
                .diskCacheKey(cacheKey)
                .size(96, 96)
                .scale(Scale.FIT)
                .precision(Precision.EXACT)
                .allowHardware(false)
                .allowRgb565(false)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .build()
            var sample: Bitmap? = null
            try {
                val result = context.imageLoader.execute(request) as? SuccessResult
                    ?: return@withLock null
                val bitmap = (result.image as? BitmapImage)?.bitmap ?: return@withLock null
                sample = bitmap
                val color = extractRepresentativeColor(bitmap) ?: return@withLock null
                synchronized(colorCache) { colorCache.put(cacheKey, color) }
                color
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                sample?.recycle()
            }
        }
    }

    /**
     * 对整张封面聚类，按 swatch 面积选择代表色，避免小面积字幕色压过主体画面。
     */
    internal fun extractRepresentativeColor(bitmap: Bitmap): Color? {
        return runCatching {
            if (bitmap.isRecycled) return@runCatching null

            val palette = Palette.from(bitmap)
                .resizeBitmapArea(48 * 48)
                .maximumColorCount(16)
                .clearFilters()
                .generate()

            resolveRepresentativeSwatch(palette.swatches)?.rgb?.let { Color(it) }
        }.getOrNull()
    }

    /** Select the largest useful color; saturated color wins only when it also covers area. */
    internal fun resolveRepresentativeSwatch(
        swatches: List<Palette.Swatch>,
    ): Palette.Swatch? {
        if (swatches.isEmpty()) return null
        val useful = swatches.filterNot { swatch ->
            val hsv = FloatArray(3)
            AndroidColor.colorToHSV(swatch.rgb, hsv)
            hsv[1] < 0.08f || hsv[2] < 0.08f || hsv[2] > 0.96f
        }
        return (useful.ifEmpty { swatches }).maxByOrNull { it.population }
    }

    fun trimToSize(maxSize: Int) {
        synchronized(colorCache) {
            colorCache.trimToSize(maxSize)
        }
    }

    fun clear() {
        synchronized(colorCache) {
            colorCache.evictAll()
        }
    }
}

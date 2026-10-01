package com.android.purebilibili.app
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.disk.directory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.addLastModifiedToFileCacheKey
import coil3.request.maxBitmapSize
import coil3.request.crossfade
@OptIn(coil3.annotation.ExperimentalCoilApi::class)
internal fun newImageLoader(context: coil3.PlatformContext, ownedCallFactory: okhttp3.Call.Factory, cacheDir: java.io.File): ImageLoader {
        val memoryCachePercent = resolveOriginalImageMemoryCachePercent()
        val diskCacheBytes = 100L * 1024 * 1024
        return ImageLoader.Builder(context)
            .components {
                // 共享网络客户端及 DNS 策略，保留 HTTP 缓存头语义。
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { ownedCallFactory },
                        cacheStrategy = { CacheControlCacheStrategy() },
                    )
                )
                // The existing Coil/Skia decoder provides Windows still images.
                // Animated wallpaper/preview callers use the existing owned Skia GIF actor;
                // neither Android decoder is a Windows service.
            }
            //  内存缓存预算（移动/平板主仓）
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, memoryCachePercent)
                    .build()
            }
            //  磁盘缓存预算（移动/平板主仓）
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(diskCacheBytes)
                    .build()
            }
            // 显示用位图必须低于 Android Canvas 的单次绘制上限。长图与雪碧图按比例采样。
            .addLastModifiedToFileCacheKey(true)
            .maxBitmapSize(coil3.size.Size(4608, 4608))
            //  优先使用缓存
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            // RGB_565 is an Android bitmap allocation hint; Skia owns Windows pixels.
            .crossfade(true)
            .build()
            // The one Windows application owner retains this returned exact loader.
    }

internal fun resolveOriginalImageMemoryCachePercent(): Double = 0.10

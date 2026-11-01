package com.bilipai.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.DesktopDrawableTable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.Dimension
import com.bilipai.desktop.appearance.LocalDesktopDarkTheme

/**
 * coil3 models that are upstream `R.drawable` / `R.mipmap` ids (TON-1072), for example `AsyncImage(model = R.mipmap.ic_launcher_3d)`.
 * Android coil maps an Int to the app resource; the desktop has no such mapper, so this fetcher draws the resource from the
 * generated drawable table (the same variants painterResource picks) into a Skia bitmap at the requested size. The
 * application image loader adds it to the upstream loader's components ([withUpstreamResourceModels]).
 * An Int that is not an upstream drawable id fails loudly.
 */
internal class DesktopUpstreamResourceFetcher(private val id: Int, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val night = DesktopDrawableTable.nightForImageRequests
        val densityDpi = 160 * 2 // rasterise at xhdpi: coil scales the bitmap to the request size afterwards
        val variant = DesktopDrawableTable.select(id, night, densityDpi)
        val bitmap = when (variant.kind) {
            "raster" -> org.jetbrains.skia.Bitmap.makeFromImage(org.jetbrains.skia.Image.makeFromEncoded(DesktopDrawableTable.encoded(variant)))
            "vector", "adaptive" -> {
                val fallback = 72 * densityDpi / 160
                val width = (options.size.width as? Dimension.Pixels)?.px ?: fallback
                val height = (options.size.height as? Dimension.Pixels)?.px ?: fallback
                val scene = ImageComposeScene(width, height, Density(densityDpi / 160f)) {
                    CompositionLocalProvider(LocalDesktopDarkTheme provides night) {
                        Image(painterResource(id), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                }
                try { org.jetbrains.skia.Bitmap.makeFromImage(scene.render(0L)) } finally { scene.close() }
            }
            else -> DesktopDrawableTable.unsupported(variant)
        }
        return ImageFetchResult(image = bitmap.asImage(), isSampled = false, dataSource = DataSource.MEMORY)
    }

    class Factory : Fetcher.Factory<Int> {
        override fun create(data: Int, options: Options, imageLoader: coil3.ImageLoader): Fetcher =
            DesktopUpstreamResourceFetcher(data, options)
    }
}

/** The upstream application loader with the desktop fetcher for upstream resource ids added to its own components. */
internal fun coil3.ImageLoader.withUpstreamResourceModels(): coil3.ImageLoader =
    newBuilder().components(components.newBuilder().add(DesktopUpstreamResourceFetcher.Factory()).build()).build()

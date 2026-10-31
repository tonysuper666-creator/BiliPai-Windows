package com.bilipai.desktop.compat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.res.DesktopDrawableTable
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Density
import androidx.paging.PagingSource
import coil3.PlatformContext
import coil3.request.Options
import coil3.size.Size
import com.android.purebilibili.R
import com.android.purebilibili.data.model.response.DynamicItem
import com.android.purebilibili.data.repository.DynamicFeedFetchResult
import com.android.purebilibili.feature.dynamic.dynamicPagingSource
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import com.bilipai.desktop.compat.coil.desktopImageRequestBuilder
import com.bilipai.desktop.ui.DesktopUpstreamResourceFetcher
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image as SkiaImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** TON-1072: upstream paging sources, R.drawable / R.mipmap resources and coil requests built with an Android Context. */
@OptIn(ExperimentalComposeUiApi::class)
class UpstreamSharedResourcesTest {
    private fun <T> inTheme(mode: AppThemeMode, size: Int = 48, read: @Composable () -> T): Pair<T, SkiaImage> {
        var value: T? = null
        val scene = ImageComposeScene(size, size, Density(1f)) {
            DesktopAppearanceTheme(DesktopThemeSettings(themeMode = mode, hapticFeedbackEnabled = false), systemLanguageTags = listOf("zh-CN")) {
                value = read()
            }
        }
        try {
            val image = scene.render(0L)
            @Suppress("UNCHECKED_CAST") return (value as T) to image
        } finally { scene.close() }
    }

    private fun opaquePixels(image: SkiaImage): Int {
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if ((bitmap.getColor(x, y) ushr 24) != 0) count++
        return count
    }

    private fun pixel(image: SkiaImage, x: Int, y: Int): Color = Color(org.jetbrains.skia.Bitmap.makeFromImage(image).getColor(x, y))

    @Test fun upstreamDynamicPagingSourceTurnsToItsSecondPage() = runBlocking {
        val requested = mutableListOf<String>()
        val source = dynamicPagingSource { offset, _ ->
            requested += offset
            when (offset) {
                "" -> DynamicFeedFetchResult(listOf(DynamicItem(id_str = "a")), nextOffset = "page-2", hasMore = true)
                "page-2" -> DynamicFeedFetchResult(listOf(DynamicItem(id_str = "b")), nextOffset = "", hasMore = false)
                else -> error("unexpected offset $offset")
            }
        }
        val first = assertIs<PagingSource.LoadResult.Page<String, *>>(source.load(PagingSource.LoadParams.Refresh(null, 20, false)))
        assertEquals("page-2", first.nextKey)
        val second = assertIs<PagingSource.LoadResult.Page<String, *>>(source.load(PagingSource.LoadParams.Append("page-2", 20, false)))
        assertEquals(listOf("", "page-2"), requested)
        assertEquals(null, second.nextKey)
        assertEquals("b", (second.data.single() as com.android.purebilibili.feature.dynamic.DynamicPageChunk).page.items.single().id_str)
    }

    @Test fun upstreamVectorDrawablePaintsWithTheThemeTint() {
        val (light, image) = inTheme(AppThemeMode.LIGHT) {
            Image(painterResource(R.drawable.bp_nav_home_outline_24), null, Modifier.fillMaxSize())
            ImageVector.vectorResource(R.drawable.bp_nav_home_outline_24)
        }
        assertTrue(opaquePixels(image) > 40, "the home outline icon paints visible strokes")
        assertEquals(Color(0x8A000000), light.tintColor, "?attr/colorControlNormal is Material's light textColorSecondary")
        val (dark, _) = inTheme(AppThemeMode.DARK) { ImageVector.vectorResource(R.drawable.bp_nav_home_outline_24) }
        assertEquals(Color(0xB3FFFFFF), dark.tintColor)
    }

    @Test fun drawableNightResourceWinsUnderTheDarkTheme() {
        val (_, light) = inTheme(AppThemeMode.LIGHT, size = 36) { Image(painterResource(R.drawable.ic_telegram_squircle), null, Modifier.fillMaxSize()) }
        val (_, dark) = inTheme(AppThemeMode.DARK, size = 36) { Image(painterResource(R.drawable.ic_telegram_squircle), null, Modifier.fillMaxSize()) }
        assertEquals("app/drawable-night/ic_telegram_squircle.xml", DesktopDrawableTable.select(R.drawable.ic_telegram_squircle, true, 160).path)
        assertEquals(Color.White, pixel(light, 1, 18), "drawable/ draws the white squircle")
        assertEquals(Color(0xFF2C2C2E), pixel(dark, 1, 18), "drawable-night/ draws the dark grey squircle")
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(light)
        val blue = (0 until 36).sumOf { y -> (0 until 36).count { x -> Color(bitmap.getColor(x, y)).let { it.blue > it.red + 0.2f } } }
        assertTrue(blue > 200, "the blue gradient logo (aapt:attr) paints over the squircle ($blue px)")
    }

    @Test fun bitmapResourcesDecodeToNonEmptyImages() {
        val (badge, _) = inTheme(AppThemeMode.LIGHT) { ImageBitmap.imageResource(R.drawable.lv6) }
        val png = SkiaImage.makeFromEncoded(javaClass.classLoader.getResourceAsStream("upstream-drawables/app/drawable-nodpi/lv6.png")!!.readBytes())
        assertEquals(png.width to png.height, badge.width to badge.height, "nodpi bitmaps keep their pixels")
        assertTrue(badge.toPixelMap().let { map -> (0 until map.width).any { x -> (0 until map.height).any { y -> map[x, y].alpha > 0f } } })
        val (_, avatar) = inTheme(AppThemeMode.LIGHT) { Image(painterResource(R.drawable.avatar_jay3_yy), null, Modifier.fillMaxSize()) }
        assertTrue(opaquePixels(avatar) > 48 * 48 / 2, "the webp contributor avatar paints")
    }

    @Test fun everyUpstreamDrawableAndMipmapLoads() {
        val density = Density(2f)
        var checked = 0
        val types = listOf(R.drawable::class.java, R.mipmap::class.java, com.android.bilipai.brandmotion.R.drawable::class.java,
            com.android.bilipai.brandmotion.R.mipmap::class.java)
        for (type in types) for (field in type.declaredFields) {
            // The Compose compiler adds a synthetic `$stable` int to every class.
            if (field.type != Int::class.javaPrimitiveType || field.name.startsWith("$")) continue
            val id = field.getInt(null)
            for (night in listOf(false, true)) {
                val variant = DesktopDrawableTable.select(id, night, 320)
                when (variant.kind) {
                    "vector" -> {
                        fun paths(group: VectorGroup): Int = group.sumOf { if (it is VectorGroup) paths(it) else if (it is VectorPath) 1 else 0 }
                        assertTrue(paths(DesktopDrawableTable.vector(variant, density).root) > 0, "${variant.name} has paths")
                    }
                    "raster" -> assertTrue(DesktopDrawableTable.bitmap(variant).width > 0, "${variant.name} decodes")
                    "adaptive" -> DesktopDrawableTable.adaptiveLayers(variant).toList().forEach { layer ->
                        if (layer is DesktopDrawableTable.Layer.Resource) DesktopDrawableTable.select(layer.id, night, 320)
                    }
                    // Splash-screen compositions and the one upstream icon whose colour exists only as a -v31 system colour.
                    else -> assertFailsWith<UnsupportedDesktopCapability> { DesktopDrawableTable.unsupported(variant) }
                }
                checked++
            }
        }
        assertTrue(checked > 2 * 500, "all upstream drawable and mipmap ids were checked ($checked)")
    }

    @Test fun coilLoadsUpstreamMipmapIdsAndAndroidContextRequests() = runBlocking {
        val result = DesktopUpstreamResourceFetcher(R.mipmap.ic_launcher_3d, Options(PlatformContext.INSTANCE, size = Size(96, 96))).fetch()
        val image = assertIs<coil3.fetch.ImageFetchResult>(result).image
        assertEquals(96 to 96, image.width to image.height)
        val context = object : android.content.Context(java.io.File("build/tmp/ton1072-context")) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences = error("unused")
        }
        assertSame(PlatformContext.INSTANCE, desktopImageRequestBuilder(context).data("https://i0.hdslb.com/a.jpg").build().context)
        assertFailsWith<IllegalArgumentException> { DesktopDrawableTable.select(0x7f7f7f7f, false, 160) }
        Unit
    }
}

package com.bilipai.desktop.ui

import androidx.compose.ui.res.DesktopDrawableTable
import coil3.BitmapImage
import coil3.PlatformContext
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.size.Size
import com.android.purebilibili.R
import com.android.purebilibili.core.store.AppIconAppearance
import com.android.purebilibili.feature.settings.getIconGroups
import com.android.purebilibili.feature.settings.resolveIconOptionPreviewRes
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.settings.desktopOriginalIconPreviewUri
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DesktopIntegerResourceCoilTest {
    @Test fun applicationOwnerLoadsResourceModelsAndAboutUris(): Unit = runBlocking {
        val directory = Files.createTempDirectory(Path.of("build/tmp").toAbsolutePath(), "integer-coil")
        val owner = DesktopApplicationImageLoader(DesktopRepository(DesktopSessionStore.temporary()), directory) { true }
        val oldNight = DesktopDrawableTable.nightForImageRequests
        try {
            val loader = owner.imageLoader
            val cases = listOf(
                "raster" to R.drawable.lv6,
                "vector" to R.drawable.ic_telegram_squircle,
                "adaptive" to R.mipmap.ic_launcher_bilipai_round,
            )
            val options = Options(PlatformContext.INSTANCE, size = Size(96, 96))
            for ((kind, id) in cases) {
                assertEquals(kind, DesktopDrawableTable.select(id, false, 320).kind)
                val mapped = loader.components.map(id, options)
                assertTrue(mapped is Int && mapped == id, "$kind mapped to ${mapped.javaClass.name}: $mapped")
                val result = loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(id).size(96, 96)
                    .memoryCachePolicy(CachePolicy.DISABLED).build())
                assertTrue(result is SuccessResult, "$kind: $result")
                val bitmap = ((result as SuccessResult).image as BitmapImage).bitmap
                assertTrue(bitmap.width > 0 && bitmap.height > 0)
                assertTrue((0 until bitmap.height).any { y -> (0 until bitmap.width).any { x -> bitmap.getColor(x, y).ushr(24) != 0 } }, "$kind has no visible pixels")
                println("$kind Int -> ${mapped.javaClass.name}: decoded ${bitmap.width}x${bitmap.height}")
            }
            val invalid = loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(0x7f7f7f7f).size(96, 96).build())
            assertTrue(invalid is ErrorResult && invalid.throwable is IllegalArgumentException, "invalid integer: $invalid")
            println("invalid Int: ${(invalid as ErrorResult).throwable}")
            var count = 0
            for (dark in listOf(false, true)) {
                DesktopDrawableTable.nightForImageRequests = dark
                for (option in getIconGroups().flatMap { it.icons }) for (appearance in AppIconAppearance.entries) {
                    val name = resolveIconOptionPreviewRes(option.key, appearance)
                    val uri = desktopOriginalIconPreviewUri(name, dark)
                    val result = loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(uri).size(96, 96)
                        .memoryCachePolicy(CachePolicy.DISABLED).build())
                    assertTrue(result is SuccessResult, "${option.key} $appearance dark=$dark: $result")
                    val image = (result as SuccessResult).image
                    assertTrue(image.width > 0 && image.height > 0)
                    count++
                }
            }
            assertEquals(48, count)
            println("about URI matrix: $count decoded")
            val missing = loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE)
                .data(coil3.Uri(scheme = "file", path = "/definitely-missing-ton1163/icon.png")).size(96, 96).build())
            assertTrue(missing is ErrorResult, "missing URI must fail: $missing")
            println("missing URI: ErrorResult")
        } finally {
            DesktopDrawableTable.nightForImageRequests = oldNight
            owner.close()
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

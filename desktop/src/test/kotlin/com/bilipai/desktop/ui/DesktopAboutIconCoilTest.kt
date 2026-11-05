package com.bilipai.desktop.ui

import androidx.compose.ui.res.DesktopDrawableTable
import coil3.BitmapImage
import coil3.PlatformContext
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.android.purebilibili.core.store.AppIconAppearance
import com.android.purebilibili.feature.settings.getIconGroups
import com.android.purebilibili.feature.settings.resolveIconOptionPreviewRes
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.settings.desktopOriginalIconPreviewUri
import com.bilipai.desktop.settings.resolveDesktopOriginalIconResource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** About's selected icon is converted to a theme-aware URI and loaded by the application owner. */
class DesktopAboutIconCoilTest {
    @Test fun generatedAboutCardPassesSelectionAndCurrentThemeToCoil() {
        val generated = Files.walk(Path.of("build/generated/system-about")).use { paths ->
            paths.filter { it.fileName.toString() == "DesktopOriginalAboutSection.kt" }.findFirst().orElseThrow()
        }
        val source = Files.readString(generated)
        assertTrue(source.contains("private fun AboutProjectOverviewCard("))
        assertTrue(source.contains("private fun AboutContributorItem("))
        val card = source.substringAfter("private fun AboutProjectOverviewCard(")
            .substringBefore("private fun AboutContributorItem(")
        assertTrue(card.contains("model = desktopOriginalIconPreviewUri(appIconRes, isSystemInDarkTheme())"),
            "About must pass the current selected resource and theme to Coil")
    }

    @Test fun selectedAboutIconUsesAppearanceAndRealLoader(): Unit = runBlocking {
        val directory = Files.createTempDirectory(Path.of("build/tmp").toAbsolutePath(), "about-guard")
        val owner = DesktopApplicationImageLoader(DesktopRepository(DesktopSessionStore.temporary()), directory) { true }
        val oldNight = DesktopDrawableTable.nightForImageRequests
        try {
            val icons = getIconGroups().flatMap { it.icons }
            assertEquals(8, icons.size)
            val light = resolveIconOptionPreviewRes("icon_blue_snow_maid", AppIconAppearance.LIGHT)
            val dark = resolveIconOptionPreviewRes("icon_blue_snow_maid", AppIconAppearance.DARK)
            val system = resolveIconOptionPreviewRes("icon_blue_snow_maid", AppIconAppearance.FOLLOW_SYSTEM)
            assertNotEquals(light, dark, "explicit appearances must select different resources")
            assertNotEquals(light, system, "following system must keep its own selection")
            assertNotEquals(resolveIconOptionPreviewRes("icon_bilipai", AppIconAppearance.FOLLOW_SYSTEM), system,
                "changing icon key must change the selected resource")
            assertTrue(resolveDesktopOriginalIconResource(system, false).contains("/day/"))
            assertTrue(resolveDesktopOriginalIconResource(system, true).contains("/night/"))
            var count = 0
            for (night in listOf(false, true)) {
                DesktopDrawableTable.nightForImageRequests = night
                for (option in icons) for (appearance in AppIconAppearance.entries) {
                    val name = resolveIconOptionPreviewRes(option.key, appearance)
                    val uri = desktopOriginalIconPreviewUri(name, night)
                    val result = owner.imageLoader.execute(ImageRequest.Builder(PlatformContext.INSTANCE)
                        .data(uri).size(96, 96).memoryCachePolicy(CachePolicy.DISABLED).build())
                    assertTrue(result is SuccessResult, "${option.key} $appearance night=$night: $result")
                    val bitmap = ((result as SuccessResult).image as BitmapImage).bitmap
                    assertTrue(bitmap.width > 0 && bitmap.height > 0)
                    assertTrue((0 until bitmap.height).any { y -> (0 until bitmap.width).any { x -> bitmap.getColor(x, y).ushr(24) != 0 } }, "transparent ${option.key} $appearance night=$night")
                    println("${option.key} $appearance night=$night model=$uri ${bitmap.width}x${bitmap.height}")
                    count++
                }
            }
            assertEquals(48, count)
            val missing = owner.imageLoader.execute(ImageRequest.Builder(PlatformContext.INSTANCE)
                .data(coil3.Uri(scheme = "file", path = "/definitely-missing-ton1085/about-icon.png")).size(96, 96).build())
            assertTrue(missing is ErrorResult, "missing icon must fail: $missing")
            println("48 selected models decoded with visible pixels; missing model failed")
        } finally {
            DesktopDrawableTable.nightForImageRequests = oldNight
            owner.close()
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

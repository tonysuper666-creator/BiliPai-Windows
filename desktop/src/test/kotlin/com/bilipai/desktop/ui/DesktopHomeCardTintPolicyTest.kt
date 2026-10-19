package com.bilipai.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.android.purebilibili.core.ui.wallpaper.WallpaperLuminanceBand
import com.android.purebilibili.core.ui.wallpaper.WallpaperReadabilityProfile
import com.android.purebilibili.core.ui.wallpaper.resolveWallpaperBodyTintAlpha
import com.android.purebilibili.core.ui.wallpaper.resolveWallpaperProtectionAlpha
import com.android.purebilibili.feature.home.components.cards.LocalHomeCardScrolling
import com.android.purebilibili.feature.home.components.cards.LocalHomeCardTransitionRunning
import com.android.purebilibili.feature.home.components.cards.WallpaperPalette
import com.android.purebilibili.feature.home.components.cards.interpolateWallpaperColor
import com.android.purebilibili.feature.home.components.cards.resolveVideoCardAdaptiveContentColors
import com.android.purebilibili.feature.home.components.cards.resolveVideoCardAmbientDrawSpec
import com.android.purebilibili.feature.home.components.cards.resolveWallpaperGlassAlpha
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure checks of the whole v0.4.6 home card tint policy and wallpaper readability policy: no window, GPU, account
 * or network.
 *
 * Each case fails against the vendored v0.2.5 policy. There resolveWallpaperGlassAlpha, the readability profile and
 * the two parameters of resolveVideoCardAdaptiveContentColors did not exist, the frosted glass alpha was the fixed
 * 0.38 (dark) and 0.34 (light), and a dark dominant wallpaper colour forced white text whatever the card's own
 * region looked like. The protection and body tint cases are the two cases of the upstream
 * WallpaperReadabilityPolicyTest that need no ProgressiveFadeDefaults.
 */
class DesktopHomeCardTintPolicyTest {
    private val onSurface = Color(0xFF202020)
    private val onSurfaceVariant = Color(0xFF606060)

    private fun contentColors(
        palette: WallpaperPalette?,
        isDarkTheme: Boolean = false,
        yFraction: Float = 0.5f,
        glassAlpha: Float = 0f,
    ) = resolveVideoCardAdaptiveContentColors(
        wallpaperPalette = palette,
        coverTint = null,
        wallpaperTintEnabled = true,
        isDarkTheme = isDarkTheme,
        defaultOnSurface = onSurface,
        defaultOnSurfaceVariant = onSurfaceVariant,
        wallpaperYFraction = yFraction,
        wallpaperGlassAlpha = glassAlpha,
    )

    @Test
    fun `glass alpha without a blur layer stays near opaque in both themes`() {
        assertEquals(0.88f, resolveWallpaperGlassAlpha(frostedGlassEnabled = false, isDarkTheme = false))
        assertEquals(0.88f, resolveWallpaperGlassAlpha(frostedGlassEnabled = false, isDarkTheme = true))
        assertEquals(
            0.88f,
            resolveWallpaperGlassAlpha(frostedGlassEnabled = false, isDarkTheme = true, isDataSaverActive = true),
        )
    }

    @Test
    fun `frosted glass alpha keeps the v046 floor above the old fixed alphas`() {
        assertEquals(0.56f, resolveWallpaperGlassAlpha(frostedGlassEnabled = true, isDarkTheme = true))
        assertEquals(0.52f, resolveWallpaperGlassAlpha(frostedGlassEnabled = true, isDarkTheme = false))
        assertEquals(
            0.65f,
            resolveWallpaperGlassAlpha(frostedGlassEnabled = true, isDarkTheme = true, isDataSaverActive = true),
        )
        assertEquals(
            0.70f,
            resolveWallpaperGlassAlpha(frostedGlassEnabled = true, isDarkTheme = false, isDataSaverActive = true),
        )
        assertTrue(resolveWallpaperGlassAlpha(frostedGlassEnabled = true, isDarkTheme = true) > 0.38f)
        assertTrue(resolveWallpaperGlassAlpha(frostedGlassEnabled = true, isDarkTheme = false) > 0.34f)
    }

    @Test
    fun `wallpaper glass surface takes its alpha from resolveWallpaperGlassAlpha`() {
        val palette = WallpaperPalette(topColor = Color.Blue, bottomColor = Color.Red)
        val region = interpolateWallpaperColor(palette, 0.5f)
        val cases = listOf(
            Triple(true, false, false),
            Triple(true, true, false),
            Triple(true, false, true),
            Triple(true, true, true),
            Triple(false, false, false),
        )
        for ((frosted, dark, saver) in cases) {
            val spec = resolveVideoCardAmbientDrawSpec(
                wallpaperPalette = palette,
                yFraction = 0.5f,
                coverTint = null,
                wallpaperTintEnabled = true,
                isDarkTheme = dark,
                defaultContainerColor = Color.White,
                defaultBorderColor = Color.Gray,
                isDataSaverActive = saver,
                frostedGlassEnabled = frosted,
            )
            assertEquals(
                region.copy(alpha = resolveWallpaperGlassAlpha(frosted, dark, saver)),
                spec.containerColor,
            )
        }
    }

    @Test
    fun `neutral glass without dynamic tint keeps the fixed container alphas`() {
        val light = resolveVideoCardAmbientDrawSpec(
            wallpaperPalette = null,
            yFraction = 0.5f,
            coverTint = null,
            wallpaperTintEnabled = true,
            isDarkTheme = false,
            defaultContainerColor = Color.White,
            defaultBorderColor = Color.Gray,
            frostedGlassEnabled = true,
            dynamicTintEnabled = false,
        )
        val dark = resolveVideoCardAmbientDrawSpec(
            wallpaperPalette = null,
            yFraction = 0.5f,
            coverTint = null,
            wallpaperTintEnabled = true,
            isDarkTheme = true,
            defaultContainerColor = Color.White,
            defaultBorderColor = Color.Gray,
            frostedGlassEnabled = true,
            dynamicTintEnabled = false,
        )
        assertEquals(Color.White.copy(alpha = 0.34f), light.containerColor)
        assertEquals(Color.White.copy(alpha = 0.38f), dark.containerColor)
    }

    @Test
    fun `a light wallpaper region under a dark dominant colour keeps the default text colours`() {
        val palette = WallpaperPalette(topColor = Color.White, bottomColor = Color.White, dominantColor = Color.Black)
        val colors = contentColors(palette)

        assertFalse(colors.isDarkSurface)
        assertEquals(onSurface, colors.titleColor)
        assertEquals(onSurfaceVariant, colors.subtitleColor)
    }

    @Test
    fun `a dark wallpaper region under a light dominant colour switches to white text in light theme`() {
        val palette = WallpaperPalette(topColor = Color.Black, bottomColor = Color.Black, dominantColor = Color.White)
        val colors = contentColors(palette)

        assertTrue(colors.isDarkSurface)
        assertEquals(Color.White, colors.titleColor)
        assertEquals(Color.White.copy(alpha = 0.78f), colors.subtitleColor)
    }

    @Test
    fun `the card position along the wallpaper decides which region is judged`() {
        val palette = WallpaperPalette(topColor = Color.Black, bottomColor = Color.White)

        assertTrue(contentColors(palette, yFraction = 0f).isDarkSurface)
        assertFalse(contentColors(palette, yFraction = 1f).isDarkSurface)
    }

    @Test
    fun `a nearly opaque glass keeps a dark region dark and a thin glass lets the light base through`() {
        val palette = WallpaperPalette(topColor = Color.Black, bottomColor = Color.Black)

        assertTrue(contentColors(palette, glassAlpha = 0.9f).isDarkSurface)
        assertFalse(contentColors(palette, glassAlpha = 0.4f).isDarkSurface)
    }

    @Test
    fun `dark theme always counts as a dark surface and keeps the default text colours`() {
        val palette = WallpaperPalette(topColor = Color.White, bottomColor = Color.White)
        val colors = contentColors(palette, isDarkTheme = true)

        assertTrue(colors.isDarkSurface)
        assertEquals(onSurface, colors.titleColor)
        assertEquals(onSurfaceVariant, colors.subtitleColor)
    }

    @Test
    fun `a palette carries an optional readability profile that defaults to none`() {
        val plain = WallpaperPalette(topColor = Color.Blue, bottomColor = Color.Red)
        assertEquals(null, plain.readability)

        val top = WallpaperLuminanceBand(low = 0.1f, mean = 0.2f, high = 0.3f)
        val bottom = WallpaperLuminanceBand(low = 0.6f, mean = 0.7f, high = 0.9f)
        val profile = WallpaperReadabilityProfile(listOf(top, bottom))
        val withProfile = plain.copy(readability = profile)

        assertEquals(profile, withProfile.readability)
        assertEquals(top, profile.top)
        assertEquals(bottom, profile.bottom)
        assertEquals(null, WallpaperReadabilityProfile(emptyList()).top)
    }

    @Test
    fun `dark wallpaper gets local light protection for dark text`() {
        val alpha = resolveWallpaperProtectionAlpha(
            WallpaperLuminanceBand(0f, 0f, 0f), Color.White, Color.Black, false,
        )
        val painted = Color.White.copy(alpha = alpha).compositeOver(Color.Black)
        assertTrue((painted.luminance() + 0.05f) / 0.05f >= 4.49f)

        val alreadyReadable = resolveWallpaperProtectionAlpha(
            WallpaperLuminanceBand(1f, 1f, 1f), Color.White, Color.Black, false,
        )
        assertTrue(alpha > alreadyReadable)
    }

    @Test
    fun `bright highlights still receive protection even when the average is dark`() {
        val mixed = WallpaperLuminanceBand(0f, 0.08f, 1f)
        val alpha = resolveWallpaperProtectionAlpha(mixed, Color.Black, Color.White, true)
        val painted = Color.Black.copy(alpha = alpha).compositeOver(Color.White)
        assertTrue(1.05f / (painted.luminance() + 0.05f) >= 4.49f)

        val dark = resolveWallpaperProtectionAlpha(
            WallpaperLuminanceBand(0f, 0f, 0f), Color.Black, Color.White, true,
        )
        assertTrue(alpha > dark)
        assertTrue(dark <= 0.1f)
    }

    @Test
    fun `body tint keeps more image detail while strong protection stays local`() {
        assertTrue(resolveWallpaperBodyTintAlpha(0.5f) < 0.5f)
        assertTrue(resolveWallpaperBodyTintAlpha(1f) <= 0.28f)
        assertEquals(0f, resolveWallpaperBodyTintAlpha(0f))
    }

    @Test
    fun `the two motion boundary locals are distinct stable objects`() {
        assertTrue(LocalHomeCardScrolling !== LocalHomeCardTransitionRunning)
    }
}

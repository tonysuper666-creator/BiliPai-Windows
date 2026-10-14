package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.HomeCardBadgeEffectMode
import com.android.purebilibili.core.store.HomeCardInfoGlassMode
import com.android.purebilibili.core.store.HomeWallpaperEffectMode
import com.android.purebilibili.feature.home.HomeCoverRequestSpec
import com.android.purebilibili.feature.home.resolveHomeCoverRequestSpec
import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopHomeGridCardPolicyTest {
    @Test fun cardWidthUsesTheOriginalHomeScreenPaddingFormula() {
        assertEquals(153.25f, resolveDesktopHomeGridCardWidthDp(1280f, 8, 6, 6))
        assertEquals(388f, resolveDesktopHomeGridCardWidthDp(400f, 1, 6, 6))
        assertEquals(400f, resolveDesktopHomeGridCardWidthDp(400f, 0, 0, 6))
    }

    @Test fun coverRequestFollowsOriginalTiersAndTheSaverSize() {
        val wide = resolveDesktopHomeGridCoverRequestSpec(1280f, 8, 6, 6, density = 3f, useLowQualityCover = false)
        val width = resolveDesktopHomeGridCardWidthDp(1280f, 8, 6, 6)
        val sampled = ceil(width * 3f * 1.25f).toInt()
        assertTrue(sampled in 481..640)
        assertEquals(resolveHomeCoverRequestSpec(width, 3f, false), wide)
        assertEquals(HomeCoverRequestSpec(640, 400), wide)

        val saver = resolveDesktopHomeGridCoverRequestSpec(1280f, 8, 6, 6, density = 3f, useLowQualityCover = true)
        assertEquals(HomeCoverRequestSpec(240, 150), saver)
    }

    @Test fun dataSaverMatchesTheHomePreferenceBranches() {
        assertFalse(resolveDesktopHomeDataSaverActive(0, cellular = true))
        assertTrue(resolveDesktopHomeDataSaverActive(2, cellular = false))
        assertFalse(resolveDesktopHomeDataSaverActive(1, cellular = null))
        assertFalse(resolveDesktopHomeDataSaverActive(null, cellular = false))
        assertTrue(resolveDesktopHomeDataSaverActive(null, cellular = true))
        assertFalse(resolveDesktopHomeDataSaverActive(9, cellular = null))
        assertTrue(resolveDesktopHomeDataSaverActive(9, cellular = true))
    }

    @Test fun samplingBindsSaverCoverAndKeepsRetiredGlassOff() {
        val settings = snapshot(
            "low_quality_home_cover_in_data_saver" to JsonPrimitive(true),
            "home_wallpaper_effect_mode" to JsonPrimitive(HomeWallpaperEffectMode.STRONG_BLUR.value),
            "home_cover_glass_badges_visible" to JsonPrimitive(true),
            "home_info_glass_badges_visible" to JsonPrimitive(true),
            "home_card_badge_effect_mode" to JsonPrimitive(HomeCardBadgeEffectMode.LIGHT_BLUR.value),
            "home_card_info_glass_mode" to JsonPrimitive(HomeCardInfoGlassMode.REALTIME_LIQUID_GLASS.value),
        )
        val always = resolveDesktopHomeGridSampling(settings, snapshot("mode" to JsonPrimitive(2)), cellular = false)
        assertTrue(always.isDataSaverActive)
        assertTrue(always.preferLowQualityCover)
        assertTrue(always.useLowQualityCover)
        assertEquals(HomeWallpaperEffectMode.STRONG_BLUR, always.wallpaperEffectMode)
        assertFalse(always.wallpaperTintEnabled)
        assertFalse(always.showCoverGlassBadges)
        assertFalse(always.showInfoGlassBadges)
        assertEquals(HomeCardBadgeEffectMode.OFF, always.badgeEffectMode)
        assertEquals(HomeCardInfoGlassMode.OFF, always.infoGlassMode)

        val mobileOnly = resolveDesktopHomeGridSampling(settings, snapshot(), cellular = null)
        assertFalse(mobileOnly.isDataSaverActive)
        assertTrue(mobileOnly.preferLowQualityCover)
        assertFalse(mobileOnly.useLowQualityCover)
        assertEquals(HomeWallpaperEffectMode.SOFT_BLUR, resolveDesktopHomeGridSampling(snapshot(), snapshot(), null).wallpaperEffectMode)
    }

    private fun snapshot(vararg entries: Pair<String, JsonPrimitive>) =
        DesktopPreferenceSnapshot(if (entries.isEmpty()) JsonObject(emptyMap()) else buildJsonObject {
            entries.forEach { (key, value) -> put(key, value) }
        })
}

package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.HomeCardBadgeEffectMode
import com.android.purebilibili.core.store.HomeCardInfoGlassMode
import com.android.purebilibili.core.store.HomeWallpaperEffectMode
import com.android.purebilibili.feature.home.HomeCoverRequestSpec
import com.android.purebilibili.feature.home.HomeFeedCardLayout
import com.android.purebilibili.feature.home.resolveHomeCoverRequestSpec
import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot
import com.bilipai.desktop.plugins.booleanPreferencesKey

internal data class DesktopHomeGridCardFrame(
    val layout: HomeFeedCardLayout,
    val columns: Int,
    val cardWidthDp: Float,
    val coverRequestSpec: HomeCoverRequestSpec,
)

/** Flags the desktop home grid passes into the original ElegantVideoCard. */
internal data class DesktopHomeGridSampling(
    val isDataSaverActive: Boolean,
    val preferLowQualityCover: Boolean,
    val showCoverGlassBadges: Boolean,
    val showInfoGlassBadges: Boolean,
    val badgeEffectMode: HomeCardBadgeEffectMode,
    val infoGlassMode: HomeCardInfoGlassMode,
    val wallpaperTintEnabled: Boolean,
    val wallpaperEffectMode: HomeWallpaperEffectMode,
) {
    val useLowQualityCover: Boolean get() = isDataSaverActive && preferLowQualityCover
}

/** Same width HomeScreen subtracts before resolveHomeCoverRequestSpec. */
internal fun resolveDesktopHomeGridCardWidthDp(
    contentWidthDp: Float,
    columns: Int,
    outerPaddingDp: Int,
    itemSpacingDp: Int,
): Float {
    val count = columns.coerceAtLeast(1)
    return (
        contentWidthDp -
            outerPaddingDp * 2f -
            itemSpacingDp * (count - 1).coerceAtLeast(0)
        ) / count
}

internal fun resolveDesktopHomeGridCoverRequestSpec(
    contentWidthDp: Float,
    columns: Int,
    outerPaddingDp: Int,
    itemSpacingDp: Int,
    density: Float,
    useLowQualityCover: Boolean,
): HomeCoverRequestSpec = resolveHomeCoverRequestSpec(
    cardWidthDp = resolveDesktopHomeGridCardWidthDp(contentWidthDp, columns, outerPaddingDp, itemSpacingDp),
    density = density,
    useLowQualityCover = useLowQualityCover,
)

/**
 * Same branches as DesktopOriginalHomePreferences.isDataSaverActive.
 * Missing mode is the original MOBILE_ONLY default. A null cellular observation
 * is not treated as mobile, so this grid does not invent a Windows network result.
 */
internal fun resolveDesktopHomeDataSaverActive(storedMode: Int?, cellular: Boolean?): Boolean = when (storedMode ?: 1) {
    0 -> false
    2 -> true
    else -> cellular == true
}

internal fun resolveDesktopHomeGridSampling(
    settings: DesktopPreferenceSnapshot,
    dataSaver: DesktopPreferenceSnapshot,
    cellular: Boolean?,
): DesktopHomeGridSampling {
    val preferLowQualityCover = settings[booleanPreferencesKey("low_quality_home_cover_in_data_saver")] ?: false
    val wallpaperEffectMode = HomeWallpaperEffectMode.fromValue(
        settings[favoriteIntKey("home_wallpaper_effect_mode")] ?: HomeWallpaperEffectMode.SOFT_BLUR.value,
    )
    return DesktopHomeGridSampling(
        isDataSaverActive = resolveDesktopHomeDataSaverActive(dataSaver[favoriteIntKey("mode")], cellular),
        preferLowQualityCover = preferLowQualityCover,
        // mapHomeSettingsFromPreferences retires these; stored keys stay ignored.
        showCoverGlassBadges = false,
        showInfoGlassBadges = false,
        badgeEffectMode = HomeCardBadgeEffectMode.OFF,
        infoGlassMode = HomeCardInfoGlassMode.OFF,
        // This grid has no wallpaper backdrop, so tint stays off and the card samples the cover.
        wallpaperTintEnabled = false,
        wallpaperEffectMode = wallpaperEffectMode,
    )
}

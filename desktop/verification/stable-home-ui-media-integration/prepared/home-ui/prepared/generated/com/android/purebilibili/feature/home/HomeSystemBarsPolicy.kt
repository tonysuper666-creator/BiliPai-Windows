// Original source app/src/main/java/com/android/purebilibili/feature/home/HomeSystemBarsPolicy.kt
// LF SHA256 4dc21ae2ccff91c0fd33c871734f91bbf85a4473d112f99b75a2f3a98c848496
package com.android.purebilibili.feature.home



internal fun shouldApplyHomeSystemBars(isTopLevelActive: Boolean): Boolean = isTopLevelActive

internal fun resolveHomeStatusBarDarkIcons(
    hasTopSkinArtwork: Boolean,
    skinColorMode: String?,
    topSkinTintIsLight: Boolean,
    defaultBackgroundIsLight: Boolean,
): Boolean {
    if (!hasTopSkinArtwork) return defaultBackgroundIsLight
    return when (skinColorMode?.trim()?.lowercase()) {
        "dark" -> false
        "light" -> true
        else -> topSkinTintIsLight
    }
}

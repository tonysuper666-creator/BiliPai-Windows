// Replaces upstream app/src/main/java/com/android/purebilibili/core/theme/SystemWallpaperSeed.kt sha256=2387dd4059414584993474b78186be5f09a610cef299391934102b40d07e66d5
// Upstream reads Android 12's system accent palette (android.R.color.system_accent1_500). The desktop has no system
// wallpaper palette, so there is no wallpaper seed, as on Android before 12; callers fall back to their own seed.
package com.android.purebilibili.core.theme

import android.content.Context
import androidx.compose.ui.graphics.Color

@Suppress("UNUSED_PARAMETER")
internal fun readSystemWallpaperSeed(context: Context): Color? = null

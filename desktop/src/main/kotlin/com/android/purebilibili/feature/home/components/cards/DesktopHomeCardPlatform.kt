package com.android.purebilibili.feature.home.components.cards

/** Android RenderEffect/API gating is unavailable on Windows. Original optional wallpaper path
 * stays unsupported here; the shared Compose/Skia card, cache, stat and menu paths remain real. */
internal object DesktopHomeCardPlatform { const val androidRenderEffectApiLevel:Int=0 }

// Original source app/src/main/java/com/android/purebilibili/feature/home/components/liquid/Vibrancy.kt
// OriginalLF_SHA256 f8522497c4281dfd1e9560bb5828082a7b708abe24806ae0a3c31707a7f3616b
package com.android.purebilibili.feature.home.components.liquid

import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.colorControls

fun BackdropEffectScope.vibrancy(saturation: Float = 1.5f) {
    colorControls(
        brightness = 0f,
        contrast = 1f,
        saturation = saturation.coerceIn(0f, 2f),
    )
}

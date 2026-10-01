// GENERATED from app/src/main/java/com/android/purebilibili/feature/anime4k/gl/Anime4KShaderRepository.kt; do not edit.
// LF-normalized SHA-256: 4bda89a5f97a455931ec77c225ea0a7a2f5a725f58ca3655314c2679a9e4f89b
package com.android.purebilibili.feature.anime4k.gl

import com.android.purebilibili.feature.anime4k.Anime4KShaderChain

internal fun resolveAnime4KShaderFiles(chain: Anime4KShaderChain): List<String> {
    return when (chain) {
        Anime4KShaderChain.KAZUMI_EFFICIENCY -> listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_M.glsl",
            "Anime4K_Restore_CNN_S.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl"
        )

        Anime4KShaderChain.KAZUMI_QUALITY -> listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_VL.glsl",
            "Anime4K_Upscale_CNN_x2_VL.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl"
        )
    }
}

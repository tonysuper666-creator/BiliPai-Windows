// GENERATED from app/src/main/java/com/android/purebilibili/feature/anime4k/Anime4KOutputPolicy.kt; do not edit.
// LF-normalized SHA-256: bb02db22bcefb15717915d384c2385d4d462cf443cb543941264ccae576ac0ab
package com.android.purebilibili.feature.anime4k

import com.bilipai.desktop.player.platform.DesktopMedia3ColorTransfers as C

enum class Anime4KBypassReason {
    NONE,
    DISABLED,
    GL_UNAVAILABLE,
    HDR_OR_DOLBY_VISION,
    PICTURE_IN_PICTURE,
    AUDIO_ONLY,
    HOST_NOT_STARTED
}

data class Anime4KOutputDecision(
    val shouldUsePipeline: Boolean,
    val bypassReason: Anime4KBypassReason
)

fun resolveAnime4KOutputDecision(
    pluginEnabled: Boolean,
    glAvailable: Boolean,
    colorTransfer: Int,
    sampleMimeType: String?,
    isInPipMode: Boolean,
    isAudioOnly: Boolean,
    hostLifecycleStarted: Boolean
): Anime4KOutputDecision {
    val bypassReason = when {
        !pluginEnabled -> Anime4KBypassReason.DISABLED
        !glAvailable -> Anime4KBypassReason.GL_UNAVAILABLE
        isAnime4kHdrOrDolbyVision(colorTransfer, sampleMimeType) -> Anime4KBypassReason.HDR_OR_DOLBY_VISION
        isInPipMode -> Anime4KBypassReason.PICTURE_IN_PICTURE
        isAudioOnly -> Anime4KBypassReason.AUDIO_ONLY
        !hostLifecycleStarted -> Anime4KBypassReason.HOST_NOT_STARTED
        else -> Anime4KBypassReason.NONE
    }
    return Anime4KOutputDecision(
        shouldUsePipeline = bypassReason == Anime4KBypassReason.NONE,
        bypassReason = bypassReason
    )
}

fun isAnime4kHdrOrDolbyVision(colorTransfer: Int, sampleMimeType: String?): Boolean {
    return colorTransfer == C.COLOR_TRANSFER_ST2084 ||
        colorTransfer == C.COLOR_TRANSFER_HLG ||
        sampleMimeType.equals("video/dolby-vision", ignoreCase = true)
}

package com.bilipai.desktop.player

import com.android.purebilibili.feature.anime4k.DEFAULT_FSR_SHARPNESS
import com.android.purebilibili.feature.anime4k.normalizeFsrSharpness
import com.android.purebilibili.feature.anime4k.resolveFsr1TargetSize
import com.android.purebilibili.feature.anime4k.resolveFsrRcasSharpnessStops
import com.android.purebilibili.feature.anime4k.shouldApplyFsr1Enhancement
import com.android.purebilibili.feature.anime4k.gl.Fsr1Shaders
import java.util.Collections

/** The math is the original FSR source. These values describe only mpv's output and sampler plumbing. */
internal data class DesktopFsrHookProgram(
    val targetWidth: Int,
    val targetHeight: Int,
    val easu: String,
    val rcas: String,
    val parameters: Map<String, Double>,
    // The original Android EASU FBO is RGBA8; the native bridge must verify the actual chosen format.
    val requiredIntermediateFormat: String = "rgba8",
    val requiredPassDescriptions: Set<String> = setOf(DesktopFsrHookAdapter.EASU_DESCRIPTION, DesktopFsrHookAdapter.RCAS_DESCRIPTION),
)

internal object DesktopFsrHookAdapter {
    const val SHARPNESS_PARAMETER = "bilipai-fsr-rcas/FSR_SHARPNESS_STOPS"
    const val EASU_DESCRIPTION = "BiliPai original AMD FSR 1.0 EASU"
    const val RCAS_DESCRIPTION = "BiliPai original AMD FSR 1.0 RCAS"

    /** outputWidth/Height are the actual video display area, and maxTextureSize is current native GPU evidence. */
    fun prepare(sourceWidth: Int, sourceHeight: Int, outputWidth: Int, outputHeight: Int,
        maxTextureSize: Int, strength: Float = DEFAULT_FSR_SHARPNESS): DesktopFsrHookProgram? {
        require(maxTextureSize > 0) { "The current GPU texture limit has not been reported." }
        if (!shouldApplyFsr1Enhancement(sourceWidth, sourceHeight, outputWidth, outputHeight)) return null
        val (width, height) = resolveFsr1TargetSize(sourceWidth, sourceHeight, outputWidth, outputHeight, maxTextureSize)
        return DesktopFsrHookProgram(width, height,
            easu = hook("EASU", Fsr1Shaders.EASU, """
                //!HOOK MAIN
                //!BIND HOOKED
                //!SAVE FSR_EASU
                //!WIDTH $width
                //!HEIGHT $height
                //!DESC $EASU_DESCRIPTION
                #define uInputSize HOOKED_size
                #define vTexCoord HOOKED_pos
            """.trimIndent(), "HOOKED_tex", 1),
            rcas = hook("RCAS", Fsr1Shaders.RCAS, """
                //!PARAM FSR_SHARPNESS_STOPS
                //!TYPE float
                //!MINIMUM 0
                //!MAXIMUM 2
                ${resolveFsrRcasSharpnessStops(DEFAULT_FSR_SHARPNESS)}
                //!HOOK MAIN
                //!BIND FSR_EASU
                //!WIDTH OUTPUT.w
                //!HEIGHT OUTPUT.h
                //!DESC $RCAS_DESCRIPTION
                #define uTextureSize FSR_EASU_size
                #define vTexCoord FSR_EASU_pos
                #define uSharpnessStops FSR_SHARPNESS_STOPS
            """.trimIndent(), "FSR_EASU_tex", 5),
            parameters = parameters(strength))
    }

    fun parameters(strength: Float): Map<String, Double> = Collections.unmodifiableMap(mapOf(
        SHARPNESS_PARAMETER to resolveFsrRcasSharpnessStops(normalizeFsrSharpness(strength)).toDouble()))

    private fun hook(stage: String, source: String, plumbing: String, sampler: String, samples: Int): String {
        val normalized = source.trimIndent().replace("\r\n", "\n")
        val firstFunction = normalized.indexOf("float fsrLuma(vec3 color)")
        check(firstFunction > 0) { "The original FSR $stage source preamble changed." }
        var body = normalized.substring(firstFunction)
        val sampling = "texture(uTexture,"
        check(body.split(sampling).size - 1 == samples) { "The original FSR $stage sampling contract changed." }
        check(body.split("void main() {").size == 2) { "The original FSR $stage entry point changed." }
        check(body.split("outColor = vec4(clamp(result, 0.0, 1.0), 1.0);").size == 2) { "The original FSR $stage output contract changed." }
        // Do not change taps, luma, bit approximations, clamps, directions, lobes or weights.
        body = body.replace(sampling, "$sampler(")
            .replace("void main() {", "vec4 hook() {")
            .replace("outColor = vec4(clamp(result, 0.0, 1.0), 1.0);", "return vec4(clamp(result, 0.0, 1.0), 1.0);")
        return DesktopFsrShaderLicense.TEXT + "\n\n" + plumbing + "\n\n" + body + "\n"
    }
}

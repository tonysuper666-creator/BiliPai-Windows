package com.bilipai.desktop.player

import com.android.purebilibili.feature.anime4k.*
import com.android.purebilibili.feature.anime4k.gl.Fsr1Shaders
import kotlin.test.*

class DesktopFsrHookAdapterTest {
    @Test fun adaptedShaderBodiesContainTheEntireOriginalAlgorithmsWithoutMathChanges() {
        val program = assertNotNull(DesktopFsrHookAdapter.prepare(320, 180, 720, 405, 4096))
        fun original(source: String) = source.trimIndent().substringAfter("float fsrLuma(vec3 color)").trim()
        fun restored(source: String, sampler: String) = source.substringAfter("float fsrLuma(vec3 color)")
            .replace("$sampler(", "texture(uTexture,")
            .replace("vec4 hook() {", "void main() {")
            .replace("return vec4(clamp(result, 0.0, 1.0), 1.0);", "outColor = vec4(clamp(result, 0.0, 1.0), 1.0);").trim()
        assertEquals(original(Fsr1Shaders.EASU), restored(program.easu, "HOOKED_tex"))
        assertEquals(original(Fsr1Shaders.RCAS), restored(program.rcas, "FSR_EASU_tex"))
        assertTrue("Copyright (c) 2021 Advanced Micro Devices" in program.easu)
        assertTrue("THE SOFTWARE IS PROVIDED \"AS IS\"" in program.rcas)
    }

    @Test fun easuUsesOriginalTwoTimesAreaPolicyAndActualTextureLimitWhileRcasTargetsDisplay() {
        val program = assertNotNull(DesktopFsrHookAdapter.prepare(320, 180, 720, 405, 4096))
        assertEquals(640, program.targetWidth)
        assertEquals(360, program.targetHeight)
        assertEquals("rgba8", program.requiredIntermediateFormat)
        assertTrue("//!WIDTH 640" in program.easu && "//!HEIGHT 360" in program.easu)
        assertTrue("//!WIDTH OUTPUT.w" in program.rcas && "//!HEIGHT OUTPUT.h" in program.rcas)
        val clamped = assertNotNull(DesktopFsrHookAdapter.prepare(1280, 720, 2560, 1440, 1600))
        assertEquals(1600, clamped.targetWidth)
        assertEquals(900, clamped.targetHeight)
    }

    @Test fun unknownGpuLimitCannotGenerateAFakeReadyProgramAndHigherResolutionUsesOriginalBypass() {
        assertFailsWith<IllegalArgumentException> { DesktopFsrHookAdapter.prepare(320, 180, 720, 405, 0) }
        assertNull(DesktopFsrHookAdapter.prepare(1921, 1081, 1600, 900, 4096))
        assertNotNull(DesktopFsrHookAdapter.prepare(1920, 1080, 1600, 900, 4096))
        assertNull(DesktopFsrHookAdapter.prepare(0, 180, 720, 405, 4096))
    }

    @Test fun sharpnessUsesOriginalNormalizationAndRcasStopMappingWithoutRewritingShaders() {
        val weak = assertNotNull(DesktopFsrHookAdapter.prepare(320, 180, 720, 405, 4096, 0.06f))
        val strong = assertNotNull(DesktopFsrHookAdapter.prepare(320, 180, 720, 405, 4096, 1f))
        assertEquals(weak.easu, strong.easu)
        assertEquals(weak.rcas, strong.rcas)
        assertEquals(resolveFsrRcasSharpnessStops(0.1f).toDouble(), weak.parameters[DesktopFsrHookAdapter.SHARPNESS_PARAMETER])
        assertEquals(0.0, strong.parameters[DesktopFsrHookAdapter.SHARPNESS_PARAMETER])
        assertEquals(DesktopFsrHookAdapter.parameters(DEFAULT_FSR_SHARPNESS), DesktopFsrHookAdapter.parameters(Float.NaN))
        assertFailsWith<UnsupportedOperationException> { (strong.parameters as MutableMap<String, Double>).clear() }
    }

    @Test fun pureOriginalOutputDecisionUsesOfficialHdrValuesAndOriginalBypassOrder() {
        fun decision(transfer: Int = 0, mime: String? = null, pip: Boolean = false, audio: Boolean = false, started: Boolean = true,
            enabled: Boolean = true, available: Boolean = true) = resolveAnime4KOutputDecision(enabled, available, transfer, mime, pip, audio, started)
        assertEquals(Anime4KBypassReason.NONE, decision().bypassReason)
        assertEquals(Anime4KBypassReason.HDR_OR_DOLBY_VISION, decision(transfer = 6).bypassReason)
        assertEquals(Anime4KBypassReason.HDR_OR_DOLBY_VISION, decision(transfer = 7).bypassReason)
        assertEquals(Anime4KBypassReason.HDR_OR_DOLBY_VISION, decision(mime = "video/dolby-vision", pip = true).bypassReason)
        assertEquals(Anime4KBypassReason.PICTURE_IN_PICTURE, decision(pip = true).bypassReason)
        assertEquals(Anime4KBypassReason.AUDIO_ONLY, decision(audio = true).bypassReason)
        assertEquals(Anime4KBypassReason.HOST_NOT_STARTED, decision(started = false).bypassReason)
        assertEquals(Anime4KBypassReason.GL_UNAVAILABLE, decision(available = false, transfer = 6).bypassReason)
        assertEquals(Anime4KBypassReason.DISABLED, decision(enabled = false, available = false).bypassReason)
    }
}

package com.bilipai.desktop.appearance

import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.test.Test
import kotlin.test.assertNotNull

/** Compiles the actual retained programs and constructs the same native content filter.
 * This is headless Skia validation; it does not claim GPU rendering or pixel parity.
 */
class DesktopLiquidLensSkiaTest {
    @Test
    fun originalRefractionProgramCompilesAndBindsTheNativeContentFilter() {
        compileAndBind("ROUNDED_RECT_REFRACTION_SHADER", dispersion = false)
    }

    @Test
    fun originalDispersionProgramCompilesAndBindsTheNativeContentFilter() {
        compileAndBind("ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER", dispersion = true)
    }

    private fun compileAndBind(fieldName: String, dispersion: Boolean) {
        // Read the production constants instead of duplicating their shader source in tests.
        val owner = Class.forName("com.android.purebilibili.core.ui.effect.LiquidLensKt")
        val field = owner.getDeclaredField(fieldName).apply { isAccessible = true }
        val program = field.get(null) as String
        RuntimeEffect.makeForShader(program).use { effect ->
            RuntimeShaderBuilder(effect).use { shader ->
                for (downscale in listOf(1f, 2f, 4f)) {
                    shader.uniform("size", 96f / downscale, 32f / downscale)
                    shader.uniform("offset", -8f / downscale, -8f / downscale)
                    shader.uniform("cornerRadii", FloatArray(4) { 16f / downscale })
                    shader.uniform("refractionHeight", 8f / downscale)
                    shader.uniform("refractionAmount", -8f / downscale)
                    shader.uniform("depthEffect", 1f)
                    if (dispersion) {
                        shader.uniform("chromaticAberration", 0.2f)
                        shader.uniform("chromaticEdgeBand", 4f / downscale)
                    }
                    ImageFilter.makeRuntimeShader(shader, "content", null).use { filter ->
                        assertNotNull(filter)
                    }
                }
            }
        }
    }
}

package com.bilipai.desktop.compat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.ui.effect.LiquidGlassBackgroundShader
import com.android.purebilibili.designsystem.R
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The Android stand-ins the migrated design system relies on, against the real upstream constants (TON-997). */
@OptIn(ExperimentalComposeUiApi::class)
class DesignSystemStandInsTest {
    private fun <T> inScene(mode: AppThemeMode, width: Int = 520, height: Int = 400, read: @androidx.compose.runtime.Composable () -> T): T {
        var value: T? = null
        val scene = ImageComposeScene(width, height, Density(1f)) {
            DesktopAppearanceTheme(DesktopThemeSettings(themeMode = mode, hapticFeedbackEnabled = false), systemLanguageTags = listOf("zh-CN")) {
                value = read()
            }
        }
        try { scene.render(0L) } finally { scene.close() }
        @Suppress("UNCHECKED_CAST") return value as T
    }

    @Test fun upstreamLiquidGlassShaderCompilesOnSkiaAndRejectsUnknownUniforms() {
        val shader = android.graphics.RuntimeShader(LiquidGlassBackgroundShader.SHADER)
        shader.setFloatUniform("resolution", 100f, 50f)
        shader.setFloatUniform("refract_intensity", 0.2f)
        shader.setFloatUniform("scroll_offset", 12f)
        shader.setFloatUniform("background_color", 0f, 0f, 0f, 0f)
        shader.snapshot()
        android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "img")
        assertFailsWith<Throwable> { shader.setFloatUniform("no_such_uniform", 1f) }
    }

    @Test fun aShaderSkiaCannotCompileFailsAtCreation() {
        assertFailsWith<Throwable> { android.graphics.RuntimeShader("half4 main(float2 p) { return not valid; }") }
    }

    @Test fun colorResourceFollowsTheUpstreamXmlAndTheEffectiveAppTheme() {
        // design-system/src/main/res/values and values-night/liquid_value_colors.xml
        assertEquals(Color(0xFF34C759), inScene(AppThemeMode.LIGHT) { colorResource(R.color.liquid_switch_active) })
        assertEquals(Color(0xFF30D158), inScene(AppThemeMode.DARK) { colorResource(R.color.liquid_switch_active) })
        assertEquals(Color(0x33787878), inScene(AppThemeMode.LIGHT) { colorResource(R.color.liquid_value_inactive) })
        assertEquals(Color(0x5C787880), inScene(AppThemeMode.DARK) { colorResource(R.color.liquid_value_inactive) })
        // the night file repeats this accent, so dark and light agree
        assertEquals(Color(0xFF0088FF), inScene(AppThemeMode.DARK) { colorResource(R.color.liquid_slider_active) })
    }

    @Test fun anUnknownColorResourceIsAnErrorNotADefaultColor() {
        val failure = runCatching { inScene(AppThemeMode.LIGHT) { colorResource(0x7f0f9999) } }.exceptionOrNull()
        assertEquals(true, generateSequence(failure) { it.cause }.any { it is IllegalArgumentException && "color resource" in it.message.orEmpty() }, failure.toString())
    }

    @Test fun localConfigurationIsTheLiveWindowSizeInDp() {
        val (width, height) = inScene(AppThemeMode.LIGHT, width = 640, height = 360) {
            LocalConfiguration.current.let { it.screenWidthDp to it.screenHeightDp }
        }
        assertEquals(640 to 360, width to height)
    }
}

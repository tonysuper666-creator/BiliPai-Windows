package android.graphics

import androidx.compose.ui.graphics.asComposeRenderEffect
import org.jetbrains.skia.ImageFilter
import top.yukonga.miuix.kmp.shader.asSkikoRuntimeShader

/** Desktop stand-in for android.graphics.RenderEffect: the Skia image filter behind a Compose RenderEffect. */
class RenderEffect private constructor(internal val compose: androidx.compose.ui.graphics.RenderEffect) {
    companion object {
        /** Runs [shader] over the drawn content, which the shader reads through its `uniform shader` named [uniformShaderName]. */
        @JvmStatic fun createRuntimeShaderEffect(shader: RuntimeShader, uniformShaderName: String): RenderEffect =
            RenderEffect(ImageFilter.makeRuntimeShader(shader.delegate.asSkikoRuntimeShader(), uniformShaderName, null).asComposeRenderEffect())

        /** Gaussian blur with Android's radius semantics: Compose's BlurEffect, which is what Compose builds on Android too. */
        @JvmStatic fun createBlurEffect(radiusX: Float, radiusY: Float, edgeTreatment: Shader.TileMode): RenderEffect =
            RenderEffect(androidx.compose.ui.graphics.BlurEffect(radiusX, radiusY, when (edgeTreatment) {
                Shader.TileMode.CLAMP -> androidx.compose.ui.graphics.TileMode.Clamp
                Shader.TileMode.REPEAT -> androidx.compose.ui.graphics.TileMode.Repeated
                Shader.TileMode.MIRROR -> androidx.compose.ui.graphics.TileMode.Mirror
                Shader.TileMode.DECAL -> androidx.compose.ui.graphics.TileMode.Decal
            }))
    }
}

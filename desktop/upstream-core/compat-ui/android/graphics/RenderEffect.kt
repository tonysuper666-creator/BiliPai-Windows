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
    }
}

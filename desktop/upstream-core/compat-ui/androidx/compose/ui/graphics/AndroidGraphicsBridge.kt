package androidx.compose.ui.graphics

import androidx.compose.ui.geometry.Size

/** android.graphics.RenderEffect to Compose, as `androidx.compose.ui.graphics.asComposeRenderEffect` on Android. */
fun android.graphics.RenderEffect.asComposeRenderEffect(): RenderEffect = compose

/**
 * A brush over an android.graphics.RuntimeShader. Android's shader keeps drawing with the uniforms set after it was
 * made; a Skia shader is a snapshot, and ShaderBrush keeps the first one per size. Callers whose uniforms change
 * must therefore make a new brush for each draw (the upstream press glow is patched to do so).
 */
fun ShaderBrush(shader: android.graphics.RuntimeShader): ShaderBrush = object : ShaderBrush() {
    override fun createShader(size: Size): Shader = shader.snapshot()
}

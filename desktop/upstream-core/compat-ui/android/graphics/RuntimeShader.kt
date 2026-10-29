package android.graphics

import top.yukonga.miuix.kmp.shader.RuntimeShader as SkiaRuntimeShader
import top.yukonga.miuix.kmp.shader.asComposeShader

/**
 * Desktop stand-in for android.graphics.RuntimeShader on Skia's runtime effects (the Skia side of the AGSL shaders
 * upstream ships). A shader that Skia cannot compile throws when it is created, and a uniform the shader does not
 * declare throws when it is set, as on Android: there is no fallback to a flat paint and no silently ignored write,
 * so an effect that does not run is a visible failure.
 *
 * Unlike a Skia shader object, an Android RuntimeShader is mutable and keeps drawing with its latest uniforms;
 * [snapshot] gives the Compose side the current state.
 */
class RuntimeShader(sksl: String) {
    internal val delegate: SkiaRuntimeShader = SkiaRuntimeShader(sksl)
    private val declared: Set<String> = DECLARATION.findAll(sksl).map { it.groupValues[1] }.toSet()

    private fun known(name: String): String {
        require(name in declared) { "Uniform $name not found in the shader (declared: ${declared.sorted()})" }
        return name
    }

    fun setFloatUniform(name: String, value: Float) = delegate.setFloatUniform(known(name), value)
    fun setFloatUniform(name: String, value1: Float, value2: Float) = delegate.setFloatUniform(known(name), value1, value2)
    fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float) = delegate.setFloatUniform(known(name), value1, value2, value3)
    fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float, value4: Float) =
        delegate.setFloatUniform(known(name), value1, value2, value3, value4)
    fun setFloatUniform(name: String, values: FloatArray) = delegate.setFloatUniform(known(name), values)
    fun setIntUniform(name: String, value: Int) = delegate.setIntUniform(known(name), value)

    /** A `layout(color)` uniform receives the unpremultiplied sRGB components, as on Android. */
    fun setColorUniform(name: String, color: Int) =
        delegate.setFloatUniform(known(name), Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, Color.alpha(color) / 255f)

    internal fun snapshot(): androidx.compose.ui.graphics.Shader = delegate.asComposeShader()

    private companion object {
        val DECLARATION = Regex("""(?:layout\s*\([^)]*\)\s*)?uniform\s+\w+\s+(\w+)(?:\s*\[\s*\d+\s*])?\s*;""")
    }
}

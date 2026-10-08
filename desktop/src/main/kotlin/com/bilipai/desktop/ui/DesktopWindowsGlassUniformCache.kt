package com.bilipai.desktop.ui

import top.yukonga.miuix.kmp.shader.RuntimeShader
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

// The actual Lens producer is the only writer of these uniform names on its two
// shader keys. Cache numeric payloads per actual builder owner, never layer/image
// identity. Weak keys and the transient delegate retain no retired source owner.
private class DesktopWindowsGlassShaderKey(shader: RuntimeShader, queue: ReferenceQueue<RuntimeShader>? = null) :
    WeakReference<RuntimeShader>(shader, queue) {
    private val identityHash = System.identityHashCode(shader)
    override fun hashCode(): Int = identityHash
    override fun equals(other: Any?): Boolean = this === other ||
        (other is DesktopWindowsGlassShaderKey && get()?.let { it === other.get() } == true)
}
private val desktopGlassShaderQueue = ReferenceQueue<RuntimeShader>()
private val desktopGlassUniforms = mutableMapOf<DesktopWindowsGlassShaderKey, DesktopWindowsGlassUniformWriter>()

internal fun RuntimeShader.withDesktopWindowsGlassUniformCache(block: DesktopWindowsGlassUniformWriter.() -> Unit) {
    synchronized(desktopGlassUniforms) {
        while (true) {
            val retired = desktopGlassShaderQueue.poll() as? DesktopWindowsGlassShaderKey ?: break
            desktopGlassUniforms.remove(retired)
        }
        val lookup = DesktopWindowsGlassShaderKey(this)
        val writer = desktopGlassUniforms[lookup] ?: DesktopWindowsGlassUniformWriter().also {
            desktopGlassUniforms[DesktopWindowsGlassShaderKey(this, desktopGlassShaderQueue)] = it
        }
        writer.apply(this, block)
    }
}

internal class DesktopWindowsGlassUniformWriter {
    private class FloatUniform(val callKind: Int, val values: FloatArray)
    private val uniforms = mutableMapOf<String, FloatUniform>()
    private var activeShader: RuntimeShader? = null

    internal fun apply(shader: RuntimeShader, block: DesktopWindowsGlassUniformWriter.() -> Unit) {
        val previous = activeShader
        activeShader = shader
        try { block() } finally { activeShader = previous }
    }
    private fun matches(name: String, kind: Int, count: Int): FloatArray? =
        uniforms[name]?.takeIf { it.callKind == kind && it.values.size == count }?.values
    private fun same(left: Float, right: Float): Boolean = left.toRawBits() == right.toRawBits()

    fun setFloatUniform(name: String, value: Float) {
        val old = matches(name, 1, 1)
        if (old != null && same(old[0], value)) return
        checkNotNull(activeShader).setFloatUniform(name, value)
        uniforms[name] = FloatUniform(1, floatArrayOf(value))
    }
    fun setFloatUniform(name: String, value1: Float, value2: Float) {
        val old = matches(name, 2, 2)
        if (old != null && same(old[0], value1) && same(old[1], value2)) return
        checkNotNull(activeShader).setFloatUniform(name, value1, value2)
        uniforms[name] = FloatUniform(2, floatArrayOf(value1, value2))
    }
    fun setFloatUniform(name: String, values: FloatArray) {
        val old = matches(name, 3, values.size)
        if (old != null && values.indices.all { same(old[it], values[it]) }) return
        val frozen = values.copyOf()
        checkNotNull(activeShader).setFloatUniform(name, frozen)
        uniforms[name] = FloatUniform(3, frozen)
    }
}

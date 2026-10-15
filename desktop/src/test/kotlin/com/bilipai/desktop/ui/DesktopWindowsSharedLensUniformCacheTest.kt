package com.bilipai.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import top.yukonga.miuix.kmp.shader.RuntimeShader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DesktopWindowsSharedLensUniformCacheTest {
    @Test
    fun unchangedPayloadsAreSkippedOnlyForTheirOwnShaderBuilder() {
        val first = RecordingShader()
        val second = RecordingShader()
        repeat(2) {
            first.withDesktopWindowsGlassUniformCache {
                setFloatUniform("size", 96f, 32f)
                setFloatUniform("refractionHeight", 8f)
            }
        }
        second.withDesktopWindowsGlassUniformCache {
            setFloatUniform("size", 96f, 32f)
            setFloatUniform("refractionHeight", 8f)
        }
        assertEquals(2, first.calls.size)
        assertEquals(first.calls, second.calls)
    }

    @Test
    fun changingChromaticEdgeBandStillReachesTheExistingNativeWriter() {
        val shader = RecordingShader()
        for (band in listOf(2f, 2f, 4f)) {
            shader.withDesktopWindowsGlassUniformCache {
                setFloatUniform("size", 96f, 32f)
                setFloatUniform("chromaticEdgeBand", band)
            }
        }
        assertEquals(listOf("size", "chromaticEdgeBand", "chromaticEdgeBand"), shader.calls.map { it.first })
        assertEquals(listOf(2f, 4f), shader.calls.filter { it.first == "chromaticEdgeBand" }.map { it.second.single() })
    }

    @Test
    fun callerArrayMutationAndSignedZeroDoNotReuseAnIncorrectPayload() {
        val shader = RecordingShader()
        val radii = floatArrayOf(16f, 16f, 16f, 16f)
        shader.withDesktopWindowsGlassUniformCache { setFloatUniform("cornerRadii", radii) }
        radii[0] = 12f
        shader.withDesktopWindowsGlassUniformCache { setFloatUniform("cornerRadii", radii) }
        shader.withDesktopWindowsGlassUniformCache { setFloatUniform("cornerRadii", radii.copyOf()) }
        shader.withDesktopWindowsGlassUniformCache { setFloatUniform("depthEffect", 0f) }
        shader.withDesktopWindowsGlassUniformCache { setFloatUniform("depthEffect", -0f) }
        assertEquals(4, shader.calls.size)
        assertEquals(16f, shader.calls[0].second[0])
        assertEquals(12f, shader.calls[1].second[0])
        assertEquals(0f.toRawBits(), shader.calls[2].second.single().toRawBits())
        assertEquals((-0f).toRawBits(), shader.calls[3].second.single().toRawBits())
    }

    @Test
    fun aFailedUniformBlockDoesNotRetainItsTransientShaderOwner() {
        val writer = DesktopWindowsGlassUniformWriter()
        val shader = RecordingShader()
        assertFailsWith<IllegalStateException> {
            writer.apply(shader) {
                setFloatUniform("depthEffect", 1f)
                error("synthetic failure")
            }
        }
        val field = DesktopWindowsGlassUniformWriter::class.java.getDeclaredField("activeShader").apply { isAccessible = true }
        assertNull(field.get(writer))
        writer.apply(shader) { setFloatUniform("depthEffect", 0f) }
        assertNull(field.get(writer))
        assertEquals(listOf(1f, 0f), shader.calls.map { it.second.single() })
    }

    private class RecordingShader : RuntimeShader {
        val calls = mutableListOf<Pair<String, List<Float>>>()
        override fun setFloatUniform(name: String, value: Float) { calls += name to listOf(value) }
        override fun setFloatUniform(name: String, value1: Float, value2: Float) { calls += name to listOf(value1, value2) }
        override fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float) { calls += name to listOf(value1, value2, value3) }
        override fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float, value4: Float) { calls += name to listOf(value1, value2, value3, value4) }
        override fun setFloatUniform(name: String, values: FloatArray) { calls += name to values.toList() }
        override fun setIntUniform(name: String, value: Int) = error("Unexpected integer uniform")
        override fun setIntUniform(name: String, value1: Int, value2: Int) = error("Unexpected integer uniform")
        override fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int) = error("Unexpected integer uniform")
        override fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int, value4: Int) = error("Unexpected integer uniform")
        override fun setIntUniform(name: String, values: IntArray) = error("Unexpected integer uniform")
        override fun setColorUniform(name: String, color: Color) = error("Unexpected color uniform")
        override fun setInputShader(name: String, shader: Shader) = error("Unexpected input shader")
    }
}

package com.bilipai.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlayerVideoShaderPathTest {
    @Test fun `native shader paths preserve unicode and long absolute drive paths without changing the asset`() {
        val path = "C:\\中文 缓存\\" + "a".repeat(64) + "\\nested\\".repeat(25) + "Anime4K_Restore_CNN_M.glsl"
        val native = nativeVideoShaderPath(path, isWindows = true)
        assertEquals("\\\\?\\" + path, native)
        assertEquals(native, nativeVideoShaderPath(native, isWindows = true))
    }

    @Test fun `network shares use the correct extended UNC path while other platforms keep ordinary paths`() {
        val path = "\\\\server\\share\\中文\\Anime4K.glsl"
        assertEquals("\\\\?\\UNC\\server\\share\\中文\\Anime4K.glsl", nativeVideoShaderPath(path, isWindows = true))
        assertEquals("/home/media/shader.glsl", nativeVideoShaderPath("/home/media/shader.glsl", isWindows = false))
    }

    @Test fun `relative Windows paths cannot become native device namespace paths`() {
        assertFailsWith<IllegalArgumentException> { nativeVideoShaderPath("shader.glsl", isWindows = true) }
        assertFailsWith<IllegalArgumentException> { nativeVideoShaderPath("C:shader.glsl", isWindows = true) }
    }
}

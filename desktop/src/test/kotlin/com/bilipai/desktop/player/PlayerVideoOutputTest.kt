package com.bilipai.desktop.player

import kotlin.test.*

class PlayerVideoOutputTest {
    @Test fun onlyExactPinnedGpuMetadataCanSupplyATextureLimitOrActualFormat() {
        assertEquals(NativeVideoCapability.MaximumTextureDimension(16384), parseNativeVideoCapability("vo/gpu/d3d11", "Maximum Texture2D size: 16384x16384\n"))
        assertEquals(NativeVideoCapability.IntermediateFormat("rgba16hf"), parseNativeVideoCapability("vo/gpu", "Using FBO format rgba16hf.\n"))
        assertEquals(NativeVideoCapability.IntermediateFormat("rgba8"), parseNativeVideoCapability("vo/gpu", "Using FBO format rgba8."))
        assertNull(parseNativeVideoCapability("ffmpeg/http", "Maximum Texture2D size: 16384x16384"))
        assertNull(parseNativeVideoCapability("vo/gpu/d3d11", "Maximum Texture2D size: 16384x8192"))
        assertNull(parseNativeVideoCapability("vo/gpu/d3d11", "Maximum Texture2D size: 2147483648x2147483648"))
        assertNull(parseNativeVideoCapability("vo/gpu", "Using FBO format rgba8. Cookie: fixture-secret"))
        assertNull(parseNativeVideoCapability("vo/gpu/d3d11", "Maximum Texture2D size: 0x0"))
    }

    @Test fun shaderOptionsCannotInjectNativeOptionsAndTheirHandoffIsImmutable() {
        val mutable = linkedMapOf("bilipai-fsr-rcas/FSR_SHARPNESS_STOPS" to 0.2)
        val options = PlayerVideoShaderOptions("rgba8", mutable).frozen()
        mutable.clear()
        assertEquals(0.2, options.parameters.values.single())
        assertFailsWith<UnsupportedOperationException> { (options.parameters as MutableMap<String, Double>).clear() }
        assertFailsWith<IllegalArgumentException> { PlayerVideoShaderOptions("rgba8,glsl-shaders=foreign").frozen() }
        assertFailsWith<IllegalArgumentException> { PlayerVideoShaderOptions(parameters = mapOf("param,vo=null" to 1.0)).frozen() }
        assertFailsWith<IllegalArgumentException> { PlayerVideoShaderOptions(parameters = mapOf("param" to Double.NaN)).frozen() }
        assertEquals("rgba16hf", PlayerVideoShaderOptions("rgba16hf").frozen().intermediateFormat)
    }

    @Test fun aStaleRendererCannotInstallShadersOnANewSourceOrClearANewerConfiguration() {
        val shader = java.nio.file.Files.createTempFile("bilipai-fsr-owner-fixture-", ".glsl")
        try {
            java.nio.file.Files.writeString(shader, "//!HOOK MAIN\n//!BIND HOOKED\n//!DESC Ownership fixture\nvec4 hook(){ return HOOKED_tex(HOOKED_pos); }\n")
            MpvPlayer().use { player ->
                val first = player.loadVersioned(PlaybackSource("file:///C:/first.avi"))
                val firstConfiguration = assertNotNull(player.setVideoShadersIfSourceVersion(first, listOf(shader)))
                val replacement = player.loadVersioned(PlaybackSource("file:///C:/second.avi"))
                assertNull(player.setVideoShadersIfSourceVersion(first, listOf(shader), PlayerVideoShaderOptions("rgba8")))
                assertTrue(player.videoShaderState.value.configurationVersion > firstConfiguration)
                assertTrue(player.videoShaderState.value.requestedFiles.isEmpty(), "A source-scoped enhancement must not leak into a new video before its identity is bound.")
                val latest = assertNotNull(player.setVideoShadersIfSourceVersion(replacement, listOf(shader), PlayerVideoShaderOptions("rgba8")))
                assertTrue(player.recoverSource(replacement, positionSeconds = 3.0, paused = true))
                assertEquals(latest, player.videoShaderState.value.configurationVersion, "Same-owner CDN/codec recovery must keep its current enhancement.")
                assertFalse(player.clearVideoShadersIfConfigurationVersion(firstConfiguration))
                assertEquals(latest, player.videoShaderState.value.configurationVersion)
                assertEquals("rgba8", player.videoShaderState.value.requestedIntermediateFormat)
                assertTrue(player.clearVideoShadersIfConfigurationVersion(latest))
                assertTrue(player.videoShaderState.value.requestedFiles.isEmpty())
            }
        } finally { java.nio.file.Files.deleteIfExists(shader) }
    }

    @Test fun bothFsrStagesMustExecuteAndRequiredNamesMustExistInTheActualAssets() {
        val required = setOf(DesktopFsrHookAdapter.EASU_DESCRIPTION, DesktopFsrHookAdapter.RCAS_DESCRIPTION)
        val configuration = PreparedVideoShaders(emptyList(), required,
            PlayerVideoShaderOptions("rgba8", requiredPassDescriptions = required).frozen())
        assertFalse(configuration.executed(listOf(DesktopFsrHookAdapter.EASU_DESCRIPTION)))
        assertFalse(configuration.executed(listOf(DesktopFsrHookAdapter.RCAS_DESCRIPTION)))
        assertTrue(configuration.executed(required.toList()))
        val asset = java.nio.file.Files.createTempFile("bilipai-fsr-stage-fixture-", ".glsl")
        try {
            java.nio.file.Files.writeString(asset, "//!HOOK MAIN\n//!BIND HOOKED\n//!DESC Only EASU\nvec4 hook(){ return HOOKED_tex(HOOKED_pos); }\n")
            MpvPlayer().use { player ->
                val owner = player.loadVersioned(PlaybackSource("file:///C:/stage.avi"))
                assertFailsWith<IllegalArgumentException> {
                    player.setVideoShadersIfSourceVersion(owner, listOf(asset), configuration.options)
                }
                assertTrue(player.videoShaderState.value.requestedFiles.isEmpty())
            }
        } finally { java.nio.file.Files.deleteIfExists(asset) }
    }

    @Test fun requestedFormatChangesCannotEraseOrInventTheLastActualNativeGpuObservation() {
        val shader = java.nio.file.Files.createTempFile("bilipai-fsr-format-fixture-", ".glsl")
        try {
            java.nio.file.Files.writeString(shader, "//!HOOK MAIN\n//!BIND HOOKED\n//!DESC Format fixture\nvec4 hook(){ return HOOKED_tex(HOOKED_pos); }\n")
            MpvPlayer().use { player ->
                val owner = player.loadVersioned(PlaybackSource("file:///C:/format-fixture.avi"))
                @Suppress("UNCHECKED_CAST")
                val output = MpvPlayer::class.java.getDeclaredField("mutableVideoOutput").apply { isAccessible = true }
                    .get(player) as kotlinx.coroutines.flow.MutableStateFlow<PlayerVideoOutputState>
                // A recorded native event is the fixture input; setter requests never fabricate it.
                output.value = output.value.copy(intermediateFormat = "rgba8")
                val first = assertNotNull(player.setVideoShadersIfSourceVersion(owner, listOf(shader), PlayerVideoShaderOptions("rgba8")))
                assertTrue(player.clearVideoShadersIfConfigurationVersion(first))
                assertNotNull(player.setVideoShadersIfSourceVersion(owner, listOf(shader), PlayerVideoShaderOptions("rgba8")))
                assertEquals("rgba8", player.videoOutput.value.intermediateFormat)
                assertNotNull(player.setVideoShadersIfSourceVersion(owner, listOf(shader), PlayerVideoShaderOptions("rgba16hf")))
                assertEquals("rgba8", player.videoOutput.value.intermediateFormat, "Requesting a different format is not proof that the GPU adopted it.")
                assertFalse(player.videoShaderState.value.active)
                assertNull(player.videoShaderState.value.actualIntermediateFormat)
            }
        } finally { java.nio.file.Files.deleteIfExists(shader) }
    }
}

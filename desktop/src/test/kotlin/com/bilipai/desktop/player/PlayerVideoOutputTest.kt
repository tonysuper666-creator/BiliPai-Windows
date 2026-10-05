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

}

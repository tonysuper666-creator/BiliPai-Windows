// GPL-3.0-or-later. Actual ingress: existing MPV bridge has already decoded
// supported NV12/P010/BGRA range+transfer and encoded full-range sRGB BT.709.
// Bind t0 R8G8B8A8_UNORM (NOT _SRGB), u0 R16G16B16A16_FLOAT.
// No unknown transfer, PQ/HLG, BT.2020 or direct YUV belongs at this interface.
#include "BiliPaiSrgb.hlsli"
cbuffer Dims : register(b0) { uint width; uint height; uint reserved0; uint reserved1; };
Texture2D<float4> encodedColor : register(t0);
RWTexture2D<float4> linearColor : register(u0);
[numthreads(16,16,1)]
void main(uint3 id : SV_DispatchThreadID)
{
    if (id.x >= width || id.y >= height) return;
    float4 c = encodedColor.Load(int3(id.xy,0));
    // Same opaque-black alpha composition as fixed RgbToLinear; actual bridge
    // writes alpha=1, so this is an identity alpha gain in the production path.
    linearColor[id.xy] = float4(BiliPaiSrgbDecode3(c.rgb) * c.a, 1.0);
}

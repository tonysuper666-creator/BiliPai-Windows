// GPL-3.0-or-later. Genuine graphics conversion, NOT a bit-copy swizzle.
// Bind t0 R8G8B8A8_UNORM encoded sRGB and RTV B8G8R8A8_UNORM.
// Set viewport to the exact shared visible extent; input and target must agree.
// The typed RTV stores physical BGRA, while shader logical channels stay RGB.
// No filtering, rescaling, second transfer encode, _SRGB view or BGRA UAV.
Texture2D<float4> encodedColor : register(t0);
float4 vs(uint vertexId : SV_VertexID) : SV_Position
{
    return float4(vertexId == 2 ? 3.0 : -1.0, vertexId == 1 ? 3.0 : -1.0, 0.0, 1.0);
}
float4 ps(float4 pos : SV_Position) : SV_Target
{
    return float4(encodedColor.Load(int3(uint2(pos.xy),0)).rgb, 1.0);
}

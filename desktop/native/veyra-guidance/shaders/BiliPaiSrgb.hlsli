// GPL-3.0-or-later. BiliPai-owned narrow SDR adapters.
// EOTF from fixed Veyra96a RgbToLinear.hlsl; OETF from PresentBlit.hlsl.
// Linear BT.709/scRGB units: 1.0 = 80 cd/m2. No 203/80 gain or exposure pump.
float BiliPaiSrgbDecode(float c)
{
    c = max(c, 0.0); // UNORM input is nonnegative; make the valid pow domain explicit to FXC.
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}
float3 BiliPaiSrgbDecode3(float3 c)
{
    return float3(BiliPaiSrgbDecode(c.r), BiliPaiSrgbDecode(c.g), BiliPaiSrgbDecode(c.b));
}
float3 BiliPaiSrgbEncode3(float3 c)
{
    c = max(c, 0.0);
    return lerp(1.055 * pow(c, 1.0 / 2.4) - 0.055, c * 12.92, step(c, 0.0031308));
}

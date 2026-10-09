// GPL-3.0-or-later. UNWIRED SOURCE: no HDR admission or GPU acceptance.
// Fixed Veyra96 chroma origins and BT2020 NCL/PQ formulas. Own P010 bit
// extraction discards low-six padding before chroma-code interpolation.
// Direct BT2020/PQ -> signed linear709 scRGB80; no SDR/RGB10 intermediate.
#include "../veyra-hdr-video-sr/shaders/BiliPaiHdrColor.hlsli"

cbuffer P010Params:register(b0) {
    uint visibleWidth,visibleHeight,allocationWidth,allocationHeight;
    uint rawRange,rawChromaLocation,reserved0,reserved1;
};
// Both views select ONE same actual decoder array slice (ArraySize=1).
// View formats are R16_UNORM and R16G16_UNORM of DXGI_FORMAT_P010.
// The local shader array index is zero, not the decoder's absolute slice.
Texture2DArray<float> lumaPlane:register(t0);
Texture2DArray<float2> chromaPlane:register(t1);
RWTexture2D<float4> hdrBase:register(u0);

// DXGI does not enforce zero low-six padding bits. Reconstruct the actual
// UNORM 16-bit word per texel, then keep only bits 15..6. Never truncate
// after UV interpolation: interpolation must retain fractional 10-bit codes.
uint P010Code(float normalizedWord) {
    return ((uint)floor(saturate(normalizedWord)*65535.0+0.5))>>6u;
}
uint2 P010Code2(float2 normalizedWord) {
    return uint2(floor(saturate(normalizedWord)*65535.0+0.5))>>6u;
}

float2 Chroma(uint2 pixel) {
    // Exact fixed96 origins in luma pixel-center coordinates. UNKNOWN has
    // no nearest/left/full-range guessed fallback in this narrow entry.
    float2 origin=float2(0.0,0.5);
    if(rawChromaLocation==2u)origin=float2(0.5,0.5);
    else if(rawChromaLocation==3u)origin=float2(0.0,0.0);
    else if(rawChromaLocation==4u)origin=float2(0.5,0.0);
    else if(rawChromaLocation==5u)origin=float2(0.0,1.0);
    else if(rawChromaLocation==6u)origin=float2(0.5,1.0);
    float2 position=(float2(pixel)-origin)*0.5;
    int2 base=int2(floor(position));float2 fraction=frac(position);
    // Clamp against VISIBLE chroma, never allocation padding.
    int2 last=int2((uint2(visibleWidth,visibleHeight)+1u)/2u)-1;
    float2 a=float2(P010Code2(chromaPlane.Load(int4(clamp(base,int2(0,0),last),0,0))));
    float2 b=float2(P010Code2(chromaPlane.Load(int4(clamp(base+int2(1,0),int2(0,0),last),0,0))));
    float2 c=float2(P010Code2(chromaPlane.Load(int4(clamp(base+int2(0,1),int2(0,0),last),0,0))));
    float2 d=float2(P010Code2(chromaPlane.Load(int4(clamp(base+int2(1,1),int2(0,0),last),0,0))));
    return lerp(lerp(a,b,fraction.x),lerp(c,d,fraction.x),fraction.y);
}

[numthreads(16,16,1)]void main(uint3 id:SV_DispatchThreadID) {
    uint yw,yh,yl,ym,cw,ch,cl,cm,ow,oh;
    lumaPlane.GetDimensions(0u,yw,yh,yl,ym);
    chromaPlane.GetDimensions(0u,cw,ch,cl,cm);
    hdrBase.GetDimensions(ow,oh);
    if(id.z!=0u||!visibleWidth||!visibleHeight||
       visibleWidth>allocationWidth||visibleHeight>allocationHeight||
       (allocationWidth&1u)||(allocationHeight&1u)||
       (rawRange!=1u&&rawRange!=2u)||rawChromaLocation<1u||rawChromaLocation>6u||
       reserved0||reserved1||yw!=allocationWidth||yh!=allocationHeight||
       cw!=allocationWidth/2u||ch!=allocationHeight/2u||yl!=1u||cl!=1u||ym!=1u||cm!=1u||
       ow!=visibleWidth||oh!=visibleHeight||id.x>=visibleWidth||id.y>=visibleHeight)return;
    // P010 is TOP ten significant bits of a normalized 16-bit word.
    // Do not reuse 8-bit 16/219 or infer sample depth from encoded metadata.
    float y=float(P010Code(lumaPlane.Load(int4(id.xy,0,0))));
    float2 uv=Chroma(id.xy);
    bool limited=rawRange==2u;
    float yy=saturate(limited?(y-64.0)/876.0:y/1023.0);
    float2 cc=(uv-512.0)/(limited?896.0:1023.0);
    float3 pq2020=float3(yy+1.4746*cc.y,
        yy-0.164553*cc.x-0.571353*cc.y,yy+1.8814*cc.x);
    // Fixed96 clamps encoded PQ before EOTF. HdrDecodePq includes the exact
    // 2020->709 matrix and scRGB80 scale; its signed output is NOT saturated.
    hdrBase[id.xy]=float4(HdrDecodePq(saturate(pq2020)),1.0);
}

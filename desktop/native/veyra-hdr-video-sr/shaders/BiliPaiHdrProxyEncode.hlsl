// GPL-3.0-or-later. INACTIVE fixed-203nit HDR VideoSR proxy.
// Preserve hdrBase separately. Only this encoded proxy is limited to SDR gamut.
#include "BiliPaiHdrColor.hlsli"
Texture2D<float4> hdrBase:register(t0);
RWTexture2D<float4> proxyInput:register(u0);
[numthreads(16,16,1)] void main(uint3 id:SV_DispatchThreadID) {
    uint w,h,sw,sh;proxyInput.GetDimensions(w,h);hdrBase.GetDimensions(sw,sh);
    if(w!=sw||h!=sh||id.x>=w||id.y>=h)return;
    const float3 c=max(HdrProxy(hdrBase.Load(int3(id.xy,0)).rgb),0.0);
    proxyInput[id.xy]=float4(BiliPaiHdrSelect(c<=0.0031308,c*12.92,1.055*pow(c,1.0/2.4)-0.055),1);
}

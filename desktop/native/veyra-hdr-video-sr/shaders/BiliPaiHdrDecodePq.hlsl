// GPL-3.0-or-later. INACTIVE future private HDR ingress.
// Input has already been range-normalized encoded BT2020/PQ RGB, not YUV.
// No exposure, range guessing, native HDR qualification or alpha reconstruction.
#include "BiliPaiHdrColor.hlsli"
Texture2D<float4> sourcePq:register(t0);
RWTexture2D<float4> hdrBase:register(u0);
[numthreads(16,16,1)] void main(uint3 id:SV_DispatchThreadID) {
    uint w,h,sw,sh;hdrBase.GetDimensions(w,h);sourcePq.GetDimensions(sw,sh);
    if(w!=sw||h!=sh||id.x>=w||id.y>=h)return;
    hdrBase[id.xy]=float4(HdrDecodePq(sourcePq.Load(int3(id.xy,0)).rgb),1);
}

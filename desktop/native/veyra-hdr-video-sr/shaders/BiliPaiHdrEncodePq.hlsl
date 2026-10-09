// GPL-3.0-or-later. INACTIVE final BT2020/PQ output boundary.
// This is the sole base gamut clamp; signed linear709 survives until conversion.
// Output dither is explicitly disabled, as no presentation/output policy is wired.
#include "BiliPaiHdrColor.hlsli"
Texture2D<float4> restoredHdr:register(t0);
RWTexture2D<float4> outputPq:register(u0);
[numthreads(16,16,1)] void main(uint3 id:SV_DispatchThreadID) {
    uint w,h,sw,sh;outputPq.GetDimensions(w,h);restoredHdr.GetDimensions(sw,sh);
    if(w!=sw||h!=sh||id.x>=w||id.y>=h)return;
    outputPq[id.xy]=float4(HdrEncodePq(restoredHdr.Load(int3(id.xy,0)).rgb),1);
}

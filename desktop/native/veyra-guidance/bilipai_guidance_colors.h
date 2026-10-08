// GPL-3.0-or-later. Internal source-owned color resources; not an SDK or player ABI.
#pragma once
#include <d3d12.h>
#include <cstdint>

namespace bilipai::guidance {
struct FrameIdentity {
    uint64_t session=0, generation=0, history=0, sequence=0, adapterLuid=0;
    int64_t ptsNumerator=0;
    int32_t ptsDenominator=0;
};
struct ColorConfig {
    uint64_t session=0, generation=0, history=0, adapterLuid=0;
    uint32_t width=0, height=0, targetWidth=0, targetHeight=0, timeoutMs=0;
};
// Every output is borrowed in COMMON. The actual consumer must wait readyFence,
// return every used texture to COMMON before signaling its final-use fence, and
// never read previousEncoded when previousValid is false. GPU state cannot be
// inferred from a pointer; this is the real native caller's required contract.
struct ColorPacket {
    FrameIdentity identity;
    ID3D12Resource* linearColor=nullptr;       // source-size RGBA16F; BT709, 1=80 nits
    ID3D12Resource* previousEncoded=nullptr;  // source-size BGRA8, normalized sRGB
    ID3D12Resource* currentEncoded=nullptr;   // source-size BGRA8, normalized sRGB
    ID3D12Resource* conventionalDepth=nullptr; // target-size R32_FLOAT, actual .5 fill
    ID3D12Fence* readyFence=nullptr;
    uint64_t readyValue=0;
    bool previousValid=false;
};
class ColorProducer {
public:
    ColorProducer();
    ColorProducer(const ColorProducer&)=delete;
    ColorProducer& operator=(const ColorProducer&)=delete;
    ~ColorProducer();
    HRESULT initialize(ID3D12Device*, ID3D12CommandQueue*, const ColorConfig&);
    // The existing MPV ingress already performs real range/matrix/transfer
    // normalization. Only full-range sRGB BT709 RGBA8 in COMMON qualifies.
    HRESULT prepare(ID3D12Resource* normalizedRgba8, ID3D12Fence* actualInputFence,
                    uint64_t actualInputValue, const FrameIdentity&, bool resetHistory,
                    ColorPacket&);
    // The future same-source core caller supplies its actual final-use receipt;
    // invoking this is not a substitute for a real GPU submission/fence signal.
    // A packet cannot be overwritten/reset/retired before this and real completion.
    HRESULT sealFinalUse(const FrameIdentity&, ID3D12Fence*, uint64_t);
    HRESULT resetHistory(uint64_t strictlyNewerGeneration, uint64_t strictlyNewerHistory);
    HRESULT retire();
private:
    struct State;
    State* state_=nullptr; // leaked only on unresolved work, retained by the one source owner
};
}

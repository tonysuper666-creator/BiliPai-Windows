/* GPL-3.0-or-later. UNWIRED SOURCE PROTOTYPE. No HDR admission or SDK calls. */
#ifndef BILIPAI_HDR_D3D11_H
#define BILIPAI_HDR_D3D11_H
#include <windows.h>
#include <d3d11_4.h>
#include <d3dcompiler.h>
#include <stdint.h>

struct bv_hdr11_pipeline;
struct bv_hdr11_frame;
struct bv_hdr11_lease {
    void *value;
    void (*release)(void *);
};
struct bv_hdr11_config {
    ID3D11Device *device;
    /* Actual caller's already selected/loaded compiler; this module loads no DLL. */
    pD3DCompile compile;
    /* Explicit nonzero caller-selected estimated texture payload budget. It
     * includes the four internal textures and two held external texture refs,
     * but NOT decoder/SR/driver allocation overhead or other pipeline memory.
     * It is NOT measured free VRAM. The future owner must reserve actual DXGI
     * local-memory headroom; even accepted dimensions can fail device creation.
     * No fixed 512MiB ceiling or source-resolution downgrade is imposed here. */
    uint64_t texture_payload_budget_bytes;
    void (*context_lock)(void *);
    void (*context_unlock)(void *);
    void *context_lock_opaque;
};

/* Serialized calls by ONE existing bounded native owner, one frame at a time.
 * Lock callbacks exclude other users of the immediate context. If absent,
 * that exclusion must already be held by the caller throughout begin/finish.
 * create compiles the four existing exact C strings as cs_5_0 when INVOKED;
 * this SOURCE prototype has not been compiled or invoked. */
HRESULT bv_hdr11_create(const struct bv_hdr11_config *, struct bv_hdr11_pipeline **);
/* E_PENDING with unchanged pointer while any retained frame remains. */
HRESULT bv_hdr11_destroy(struct bv_hdr11_pipeline **);

/* Consumes a well-formed nonnull source_lease on every return. The lease must
 * retain the REAL decoder/array-slice/storage, not only a COM texture pointer.
 * source_pq must already be normalized encoded BT2020/PQ in a precise
 * single-mip/array/sample R10G10B10A2_UNORM SRV texture on this device.
 * The owner, not this module, proves color/source identity and producer-ready
 * synchronization. Exact texture extents equal source_width/source_height.
 *
 * On pre-submission failure *out=NULL and that lease is released immediately.
 * On ANY failure after submission *out is a FAILED retained frame: do not
 * destroy it or its pipeline. Attach an actual final-use fence and retire.
 * S_OK means commands recorded, never decode completion or enhancement. */
HRESULT bv_hdr11_begin(struct bv_hdr11_pipeline *, ID3D11Texture2D *source_pq,
    uint32_t source_width, uint32_t source_height,
    uint32_t output_width, uint32_t output_height,
    struct bv_hdr11_lease source_lease, struct bv_hdr11_frame **out);

/* Borrowed views only while the frame is unsealed/nonfailed; retain the frame
 * for all external use. Proxy: R8G8B8A8_UNORM at source extent, encoded sRGB,
 * fixed 203-nit shoulder. The owner synchronizes before external SR reads. */
HRESULT bv_hdr11_proxy(struct bv_hdr11_frame *, ID3D11Texture2D **,
    ID3D11ShaderResourceView **);

/* Consumes a well-formed nonnull sr_lease on every return. sr_output is the
 * real externally completed SR-only R8G8B8A8_UNORM result for this proxy/source,
 * exactly output extent and same device. This module does NOT prove that
 * relationship, completion, SR success, SDK identity or color metadata.
 * No NVIDIA call is made. A malformed lease is rejected without transfer or
 * poisoning; the owner may retry with a real well-formed lease. With a valid
 * lease, invalid external input poisons this frame; it still
 * retains all earlier submitted resources/source lease until final-use fence.
 * S_OK means restore/encode commands queued, not displayed or HDR accepted. */
HRESULT bv_hdr11_finish(struct bv_hdr11_frame *, ID3D11Texture2D *sr_output,
    struct bv_hdr11_lease sr_lease);

/* Borrowed pending output: precise R10G10B10A2_UNORM at output extent.
 * Its content is BT2020/PQ only after the actual completion dependency. No
 * renderer, token, metadata mutation, native HDR or public ABI is enabled. */
HRESULT bv_hdr11_output(struct bv_hdr11_frame *, ID3D11Texture2D **,
    ID3D11ShaderResourceView **);
HRESULT bv_hdr11_retain(struct bv_hdr11_frame *);

/* Attach caller's actual same-device ID3D11Fence (QueryInterface checked) BEFORE
 * that owner signals value, after ALL helper/consumer work has been enqueued.
 * value must be nonzero/non-sentinel and greater than actual completed value.
 * The caller must ensure Signal is after decode/proxy, external SR use and
 * restore/encode AND the final presentation/copy use of EVERY exposed resource.
 * This module neither creates/signals a fence nor proves that signal ordering.
 * No later use is permitted after seal. A failed/missing Signal retains the
 * frame/pipeline; the existing owner must bound this quarantine to one frame.
 * Source/input/SR readiness fences or core completion alone are insufficient. */
HRESULT bv_hdr11_seal(struct bv_hdr11_frame *, IUnknown *actual_final_use_fence,
    uint64_t value);
/* No CPU waiting. S_FALSE pending; failed device/sentinel remains retained.
 * S_OK clears ONE caller reference only after actual GetCompletedValue>=value.
 * Each retained reference must separately retire; pipeline frees only last.
 * Never call Release/free on the opaque frame or claim S_OK proves display. */
HRESULT bv_hdr11_retire(struct bv_hdr11_frame **);
#endif

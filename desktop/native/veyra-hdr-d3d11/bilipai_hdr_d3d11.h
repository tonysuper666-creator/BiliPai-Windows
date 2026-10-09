/* GPL-3.0-or-later. UNWIRED SOURCE PROTOTYPE. No HDR admission or SDK calls. */
#ifndef BILIPAI_HDR_D3D11_H
#define BILIPAI_HDR_D3D11_H
#include <windows.h>
#include <d3d11_4.h>
#include <d3dcompiler.h>
#include <stdint.h>
#include "../veyra-hdr-p010-input/bilipai_hdr_p010_bindings.h"

struct bv_hdr11_pipeline;
struct bv_hdr11_frame;
struct bv_hdr11_p010_prepared;
struct bv_hdr11_lease {
    void *value;
    void (*release)(void *);
};
struct bv_hdr11_config {
    ID3D11Device *device;
    /* Actual caller's already selected/loaded compiler; this module loads no DLL. */
    pD3DCompile compile;
    /* Explicit nonzero caller-selected estimated texture payload budget. It
     * includes the four internal textures and held external texture refs. P010
 * additionally counts its entire retained padded decoder array and owned copy,
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

/* PRIVATE SOURCE input shape, not a source/HDR admission certificate. All
 * four actual AVFrame crop values must be zero: this method has no cropped
 * origin contract. constants use the exact reviewed explicit range/chroma
 * mapping; unknown/guessed/retagged values are not permitted by the caller.
 * Range FULL=1/LIMITED=2 here is not the observer raw_range numbering:
 * that observation uses LIMITED=1/FULL=2. It MUST be explicitly mapped.
 * Producer readiness is an actual SAME-device ID3D11Fence/value whose Signal
 * the producer has enqueued after writing this retained decoder slice. The
 * caller must independently keep that slice immutable through final use. */
struct bv_hdr11_p010_input {
    ID3D11Texture2D *texture;
    uint32_t array_slice;
    uint32_t crop_left,crop_top,crop_right,crop_bottom;
    struct bv_hdr_p010_constants constants;
    IUnknown *producer_ready_fence;
    uint64_t producer_ready_value;
};
/* Separate UNWIRED method: actual DXGI_P010 array slice -> same-device owned
 * single-slice P010 copy with explicit plane0/1 SRV1 -> signed FP16 HDR base.
 * This host adapts input02's direct-decoder-slice view contract: the whole
 * actual padded source slice is copied to owned subresource0, then both
 * plane views select owned FirstArraySlice0 with shader-local array index0.
 * Copying proves no original decoder/source/epoch or CURRENT qualification;
 * those remain the future caller's separate obligation for the actual input.
 * Neither RGB10 nor SDR quantization occurs before that base. The existing
 * fixed203 proxy/SR-only restore/PQ encode/final-consumer retirement follows.
 * The caller must positively establish CURRENT PQ/BT2020-NCL/primaries,
 * explicit range/chroma, decoder/storage/epoch identity AT GPU use. Current
 * raw history and epochMatchedAtObserve do not meet that contract; current
 * source provides no active route authorizing this method. No qualification
 * flag, metadata retag, PRIVATE/PUBLIC token or HDR gate is added here.
 *
 * Shared keyed-mutex resources are unsupported by this narrow method: a
 * readiness fence never substitutes for AcquireSync/ReleaseSync ownership.
 * No source SRV bind is required: DECODER-only textures are copied after an
 * actual Context4 GPU Wait, not sampled directly. Wait is ordering only;
 * it neither establishes color/source identity nor waits on the CPU. The
 * caller must arrange a real producer Signal independently of this Wait,
 * avoid an active query scope unless its accounting accepts these commands,
 * and retain REAL decoder/HWctx/slice refs in source_lease through final use.
 * That lease must retain any real producer submission owner needed to issue
 * the readiness Signal. The actual fence COM ref and its value are also held
 * by this frame until final-consumer retirement, not only until Wait returns.
 * One frame remains retained, but a missing producer Signal may stall the
 * shared immediate GPU command stream and ALL later work until real Signal.
 * The caller must prove the real producer Signal was enqueued BEFORE this
 * method. A one-frame resource bound does not isolate shared queue impact.
 * There is no CPU timeout, signal or new quarantine owner inside this module.
 * All resources are created before Wait. A valid source_lease is consumed on
 * every return. Before any queued operation failures release it with *out
 * NULL; once Wait is attempted ANY failure returns a retained FAILED frame.
 * Seal/retire use the final-consumer fence, never producer/SR completion alone.
 * S_OK reports queued commands only, not shader/GPU/HDR or display success. */
HRESULT bv_hdr11_begin_p010(struct bv_hdr11_pipeline *,
    const struct bv_hdr11_p010_input *, uint32_t output_width,uint32_t output_height,
    struct bv_hdr11_lease source_lease,struct bv_hdr11_frame **out);

/* Separate UNWIRED split route; existing begin methods keep their contracts.
 * prepare is called by the one existing legal device/host owner OUTSIDE both
 * decoder dispatch scope and immediate-context exclusion. No cross-thread
 * device access is licensed when creation flags/thread ownership are unknown.
 * D3D11_CREATE_DEVICE_SINGLETHREADED is strictly unsupported for this route.
 * It consumes a well-formed source_lease on every return, owns source/fence refs,
 * compiles/creates all resources, and issues NO context state/GPU commands (only
 * IUnknown interface query). Prepared state is NOT CURRENT/epoch/HDR proof.
 * No frame is exposed while prepared; pipeline destroy/new begin stay pending.
 * All methods are serialized by that SAME host OS thread, not a new registry.
 * Compiler and lease callbacks must not reenter, destroy or mutate this same
 * pipeline; its external owner serialization includes callback execution.
 */
HRESULT bv_hdr11_prepare_p010(struct bv_hdr11_pipeline *,
    const struct bv_hdr11_p010_input *,uint32_t output_width,uint32_t output_height,
    struct bv_hdr11_lease source_lease,struct bv_hdr11_p010_prepared **out);
/* The future independent GPU-use owner must establish actual CURRENT decoder
 * instance/epoch/retained refs/raw PQ2020/range/chroma at this exact submission,
 * and hold the ORIGINAL immediate-context exclusion throughout this method.
 * Existing CPU-only observation callback is NOT authorized to invoke submit.
 * Caller rechecks real mp_image/HWctx/ready getter and supplies exactly the
 * prepared texture/slice/crop/constants/fence pointer/value. The helper rechecks
 * actual owned descriptors, but does not mint an active owner/source permit.
 * No module-explicit compiler/allocation/QI/AddRef/Release/configured lock/
 * lease callback, CPU GPU wait, Flush or NVIDIA call. Swap/binding/driver
 * implicit reference or allocation work is not excluded or validated here.
 * No active asynchronous query unless the
 * real owner accounts for these Dispatch commands; VideoContext is unchanged.
 * Swap/restore is immediate-context-only; old state ref is retained for close.
 * E_UNEXPECTED means no nonnull previous state was returned: restore is UNKNOWN,
 * caller must stop use/recover the shared context; NULL must not fake restore.
 * Any state/queue attempt is conservatively submitted before first Wait.
 * Failure keeps prepared/failed frame for lock-free close; one attempt only.
 * Success means commands queued, not later active epoch or GPU/HDR/display.
 */
HRESULT bv_hdr11_submit_p010(struct bv_hdr11_p010_prepared *,
    const struct bv_hdr11_p010_input *actual_input_at_submit);
/* Called AFTER BOTH owner scope and context exclusion end, on same host thread.
 * Clears prepared handle exactly once and releases temporary COM refs outside
 * locks. With no submitted work it frees the frame/source_lease and *out=NULL.
 * Otherwise it transfers ONE original frame reference even on submit failure;
 * caller must use actual final-consumer seal/retire, never producer completion.
 * There is no retry/free of an attempted partial frame or hidden cleanup fence.
 */
HRESULT bv_hdr11_close_p010_prepared(struct bv_hdr11_p010_prepared **,
    struct bv_hdr11_frame **submitted_frame);

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

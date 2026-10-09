/* GPL-3.0-or-later. Candidate only: native video bridge, no playback owner. */
#ifndef BILIPAI_RTX_MPV_BRIDGE_H
#define BILIPAI_RTX_MPV_BRIDGE_H
#include <windows.h>
#include <d3d11.h>
#include <d3d11_4.h>
#include <stdint.h>
#include "bilipai_veyra_core_v1.h"
#include "video/bilipai_rtx_frame_provenance.h"
struct bv_mpv_bridge;
struct bv_mpv_config {
    ID3D11Device *device;
    const wchar_t *dll_path, *runtime_directory;
    const char *project_id, *engine_version;
    uint64_t session, generation, configuration;
    uint32_t input_width, input_height, output_width, output_height;
    uint32_t effects, quality, peak_nits, timeout_ms;
    void (*context_lock)(void *), (*context_unlock)(void *);
    void *context_lock_opaque;
};
/* SDR only: primaries must be BT709; matrix=0 RGB, 1 BT601, 2 BT709;
   transfer=0 sRGB, 1 BT1886/gamma2.4. Unknown/HDR goes around this bridge. */
/* Private same-MPV call contract only: rgb10_qualified is set exclusively
   by the strict CURRENT X2BGR10 gate and must match the actual DXGI texture.
   This is not the public core ABI or its reserved field. */
/* p016_depth is 12/16 only after actual AVHWFramesContext sw_format
   qualification. Zero for every other texture. No encoded-bitdepth inference. */
struct bv_mpv_color { uint32_t matrix, limited, transfer, chroma, rgb10_qualified, p016_depth; };
/* Closed native-HDR observation only. History, held-reference and actual
   texture observations remain distinct. Epoch at GPU use is never asserted,
   native_hdr_qualified always stays zero, and no frame token is produced. */
struct bv_mpv_pq_p010_observation {
    uint64_t decoder_instance, decoder_epoch, decoder_sequence;
    uint32_t width, height, raw_range;
    uint32_t boundary_pq_p010, reference_unchanged, hw_context_matching;
    uint32_t texture_p010, current_domain_observed;
    uint32_t epoch_matched_at_observe, gpu_epoch_at_use_known, native_hdr_qualified;
    uint32_t refusal_history;
    uint32_t dxgi_format, texture_width, texture_height, texture_array_size;
};
void bv_mpv_observe_pq_p010(ID3D11Device *, ID3D11Texture2D *, uint32_t,
                           struct bv_mpv_pq_p010_observation *);
int bv_mpv_bridge_create(const struct bv_mpv_config *, struct bv_mpv_bridge **,
                         bv_status_v1 *);
/* UNWIRED PRIVATE SR-only proxy route. Not the public core ABI, an HDR
 * admission or a display token. One existing bridge/host OS thread serializes
 * all calls, including lease callbacks; no new owner or playback thread.
 * The bridge must have been created with effects EXACTLY BV_VIDEO_SR. */
struct AVBufferRef;
struct bv_mpv_proxy_sr_loan;
struct bv_mpv_proxy_sr_input {
    ID3D11Texture2D *proxy;
    /* Exact built-in default AVHWDeviceContext owner, never a custom opaque. */
    struct AVBufferRef *device_ref;
    IUnknown *producer_ready_fence;
    uint64_t producer_ready_value;
    uint64_t session, configuration, generation, sequence, adapter_luid;
    int64_t pts_numerator;
    int32_t pts_denominator;
    void *retained_lease;
    void (*release_retained_lease)(void *);
};
struct bv_mpv_proxy_sr_result {
    /* Borrowed until release_proxy_sr_loan retires the whole bridge loan.
     * Exact source-sized R8 sRGB proxy becomes output-sized R8 SR-only.
     * ready_fence/value is a REAL shared D3D11 dependency signaled by the
     * bridge's SAME D3D12 queue after the actual core completion dependency.
     * A Wait on it is already enqueued on the original immediate context.
     * It is not the final-use fence and does not retire the HDR source frame. */
    ID3D11Texture2D *output;
    ID3D11Fence *ready_fence;
    uint64_t ready_value;
    IUnknown *core_completion_fence;
    uint64_t core_completion_value;
    uint64_t session, configuration, generation, sequence, adapter_luid;
    int64_t pts_numerator;
    int32_t pts_denominator;
    uint32_t width, height, effects;
};
/* Called OUTSIDE decoder GPU-use scope AND original context exclusion.
 * Neither existing CPU observer nor current scoped borrow callback authorizes
 * this method. It may CPU-wait/drain and call NVIDIA through the existing core. The
 * default mutex is recursive: successful checked try0 does NOT prove that a
 * caller's outer context lock was absent. The future caller must first finish
 * its independent GPU-use scope and release ALL original exclusion levels.
 * This interface cannot certify that precondition; never call it from a borrow
 * callback or while holding the original context mutex.
 * The actual device_ref must have known built-in video/d3d constructor
 * provenance. The bridge retains that first AVHWDeviceContext owner until
 * full destruction, checks original callback/opaque/device/context identities,
 * and uses its CHECKED zero-wait default mutex functions, never void callbacks.
 * Unknown/custom or busy/abandoned/failed mutex owners safely reject; there is
 * no spinning or GPU/CPU wait under that mutex. NVIDIA/core drain runs outside.
 * Calls from an active asynchronous query must be excluded or accounted for.
 *
 * Input is the actual host-private immutable single-slice/single-mip R8 proxy,
 * with exact input extent and an independently enqueued same-device readiness
 * Signal after the host proxy dispatch. UNKNOWN ready ordering is rejected by
 * the caller. The retained lease must keep the REAL HDR frame, decoder/storage
 * refs and proxy storage alive; the bridge separately retains the exact
 * original AVHWDeviceContext/lock owner. A COM texture
 * reference alone is insufficient. These source/owner obligations and epoch
 * AT the prior HDR submission are not certified by this method.
 *
 * Caller initializes *loan to NULL. A nonnull incoming *loan is rejected and
 * preserved: new-frame retries must not lose the live handle needed to seal or
 * retire. Each attempt supplies a NEW owned retained lease; never resend a
 * lease already consumed by a previous call, even when that call failed.
 * A well-formed retained lease is consumed on EVERY return. Before any state or
 * GPU attempt failure releases it and *loan=NULL. Afterwards *loan remains
 * nonnull even on failure; result remains zero unless the exact core output
 * and real handoff were validated. Core error, failed Signal/Wait, bad result
 * or unknown state restoration permanently retains one loan/whole bridge.
 * Do not silently discard it on seek/VF destroy; the existing owner must keep
 * that bounded custody until process exit. Success only means SR submitted
 * with a real dependency, never CURRENT/native HDR/display/quality success.
 * No token is minted. The old process/reset/destroy refuse an outstanding loan.
 * output cannot alias/reuse the proxy or bridge input. All packet pointers are
 * borrowed and must not be used after sealing or actual retirement. */
int bv_mpv_bridge_process_proxy_sr(struct bv_mpv_bridge *,
    const struct bv_mpv_proxy_sr_input *,struct bv_mpv_proxy_sr_loan **,
    struct bv_mpv_proxy_sr_result *,bv_status_v1 *);
/* Two-stage seal/poll, called OUTSIDE both locks on the SAME host OS thread.
 * First call attaches a real same-device ID3D11Fence/nonzero future value
 * BEFORE the caller actually Signals that value. No later loan use is legal.
 * The caller must have enqueued EVERY use first: proxy copy, core SR dependency,
 * host restore+PQ encode, and copy into an independent MPV output pool. The
 * actual final Signal must be on the SAME immediate queue after all those uses.
 * This interface checks real fence identity/value, NOT caller Signal ordering.
 * SR completion, source-ready or a completion already observed at seal cannot
 * substitute for that final-use contract. It does not signal, wait or Flush.
 *
 * Pass NULL/0 on later polls (or the exact same fence/value). BV_BUSY retains
 * the pointer until all actual producer/core/ready/final values complete;
 * removed-device/sentinel or untracked failure remains retained. BV_OK clears
 * *loan and releases only this bridge-held lease. A host reference remains the
 * caller's responsibility via bv_hdr11_seal/retire at the actual final fence;
 * do NOT implement its release callback as unconditional free. There is no
 * callback invocation or resource free under the original context exclusion.
 * No release here certifies display or future source epoch. */
int bv_mpv_bridge_release_proxy_sr_loan(struct bv_mpv_proxy_sr_loan **,
    IUnknown *actual_final_use_fence,uint64_t actual_final_use_value,
    bv_status_v1 *);

/* process consumes input_lease/output_lease on every path; keep mp_image refs until
   the actual native consumer fence, not just the core completion fence. */
int bv_mpv_bridge_process(struct bv_mpv_bridge *, ID3D11Texture2D *, uint32_t,
                         ID3D11Texture2D *, uint32_t, struct bv_mpv_color,
                         uint64_t generation, uint64_t sequence,
                         int64_t pts_numerator, int32_t pts_denominator,
                         void *input_lease, void *output_lease, void (*release_output_lease)(void *),
                         bv_status_v1 *, struct mp_bilipai_frame_token *);
int bv_mpv_bridge_reset(struct bv_mpv_bridge *, uint64_t, bv_status_v1 *);
/* Returns nonzero if pending core work was quarantined. A quarantined context
   owns DLL/COM handles until a later create retries real core destruction. */
int bv_mpv_bridge_destroy(struct bv_mpv_bridge **, bv_status_v1 *);
#endif
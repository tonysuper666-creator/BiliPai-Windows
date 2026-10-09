/* GPL-3.0-or-later. Private resource owner; native HDR/display policy stays closed. */
#ifndef BILIPAI_HDR_VF_OWNER_H
#define BILIPAI_HDR_VF_OWNER_H
#include "bilipai_hdr_p010_chain.h"
struct bv_mpv_hdr_vf_owner;
struct bv_mpv_hdr_vf_prepare {
    /* The actual application's independently locked/authenticated component
     * selection must supply these paths. This struct is not authentication. */
    struct bv_mpv_config bridge;
    pD3DCompile compile;
    uint64_t texture_payload_budget_bytes;
};
/* One existing MPV main/filter OS thread, no new playback owner. Create is CPU
 * allocation only. It does not prepare a bridge/pool, issue decoder controls,
 * Signal/Wait/Copy/Dispatch, load a DLL, or enable any scope/HDR/VO policy. */
HRESULT bv_mpv_hdr_vf_owner_create(struct bv_mpv_hdr_vf_owner **);
/* Explicit resource preparation only; NO readiness/CPU/GPU controls are issued.
 * Source must retain its actual known constructor AVHWDeviceContext. The owner
 * captures that AVBufferRef, creates a dedicated SR-only bridge+chain and an
 * independent R10 MPV AVHWFrames pool. The exact active epoch is checked on the
 * live borrowed decoder, but this is not a CURRENT_GPU_USE/display proof.
 * Caller does not retain returned chain across reset/detach/another prepare.
 * A future real owner may separately authorize the chain under its reviewed
 * policies. Current VF has no prepare/authorize/submit call, so default stays0.
 * Different owner/epoch/extent/config first drains and destroys the WHOLE old
 * chain and bridge; reset never clears or rebinds the bridge's first HWowner.
 * No allocation of a new generation is allowed while an older generation is
 * unresolved. Process-wide custody is bounded to TWO generations, including
 * orphaned/quarantined ones. Different OS threads cannot poll/destroy them. */
HRESULT bv_mpv_hdr_vf_owner_prepare(struct bv_mpv_hdr_vf_owner *,
    struct mp_decoder_wrapper *live_decoder,const struct mp_image *source,
    const struct bv_mpv_hdr_vf_prepare *,struct bv_mpv_hdr_chain **borrowed_chain);
/* Requires the existing owner's independent ready/CPU/GPU opt-ins already to
 * have been explicitly granted. This module never grants them. Captures the
 * actual source and its own real output-pool frame; chain enforces current
 * scoped source/owner validation and real final-use custody. A failed call can
 * have queued work, and then the whole generation is retained. Never treat
 * failure/bypass as proof that a shared immediate context has recovered. */
HRESULT bv_mpv_hdr_vf_owner_submit_private(struct bv_mpv_hdr_vf_owner *,
    struct mp_decoder_wrapper *live_decoder,const struct mp_image *,
    const struct bv_mpv_hdr_chain_ticket *);
/* Actual borrowed decoder is valid only for this synchronous main-lane call.
 * Poll uses no stored decoder pointer. Completed HDR pool outputs are released
 * internally and NEVER delivered to current PRIVATE2/VO or stamped with tokens.
 * Only an independently reviewed future renderer policy may change that. */
HRESULT bv_mpv_hdr_vf_owner_service(struct bv_mpv_hdr_vf_owner *,
    struct mp_decoder_wrapper *live_decoder);
/* No active resources means no added decoder-control/dispatch query per frame. */
bool bv_mpv_hdr_vf_owner_has_work(const struct bv_mpv_hdr_vf_owner *);
/* Recovery also includes any quarantined whole generation on this SAME actual
 * original live OS lane. It survives old VF detach/new adapter allocation;
 * unknown shared-context recovery cannot disappear with a local bool. NULL
 * adapter still checks same live lane custody after optional CPU allocation
 * failure; an existing adapter used from a foreign lane cannot query resources.
 * Legacy enhancement preparation and private submit must both refuse it. */
bool bv_mpv_hdr_vf_owner_recovery_required(const struct bv_mpv_hdr_vf_owner *);
/* Reset marks the entire generation retiring and polls outside all original
 * locks. On unproved completion/destroy it remains in bounded owned custody.
 * Original OS thread identity is retained with a live thread HANDLE; a recycled
 * numeric thread ID is never treated as the original lane.
 * Serialized detach on another lane only transfers CPU ownership, with no
 * resource operation or callback. It may free only the small CPU adapter; whole generation ownership moves
 * to the process-wide custody table, with no pointer back to the dead VF.
 * An original-lane later adapter service can retire known-complete generations.
 * Unknown failures remain retained until process exit; no imaginary transfer
 * to another lane, periodic worker or synthetic completion is asserted. */
void bv_mpv_hdr_vf_owner_reset(struct bv_mpv_hdr_vf_owner *);
void bv_mpv_hdr_vf_owner_detach(struct bv_mpv_hdr_vf_owner **);
#endif

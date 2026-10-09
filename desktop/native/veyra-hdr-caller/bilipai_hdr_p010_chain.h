/* GPL-3.0-or-later. SOURCE draft: DEFAULT OFF, no production caller/renderer. */
#ifndef BILIPAI_HDR_P010_CHAIN_H
#define BILIPAI_HDR_P010_CHAIN_H
#include <stdbool.h>
#include "../veyra-hdr-d3d11/bilipai_hdr_d3d11.h"
#include "../bilipai_rtx_mpv_bridge.h"
struct mp_image;
struct mp_decoder_wrapper;
struct bv_mpv_hdr_chain;
struct bv_mpv_hdr_chain_config {
    struct AVBufferRef *device_ref;
    /* Already owned, dedicated BV_VIDEO_SR bridge with the exact tuple below.
     * Caller keeps it until chain_destroy succeeds and then destroys it outside
     * all decoder/context locks. This module neither loads nor authenticates it. */
    /* Must have the SAME exact retained AVHWDeviceContext owner. A different
     * owner requires all actual loans retired, then whole bridge destroy/recreate;
     * reset does not clear its original first-owner binding. */
    struct bv_mpv_bridge *sr_bridge;
    pD3DCompile compile;
    uint64_t texture_payload_budget_bytes;
    uint64_t session, configuration, generation, adapter_luid;
    uint32_t output_width, output_height;
};
struct bv_mpv_hdr_chain_ticket {
    uint64_t sequence;
    int64_t pts_numerator;
    int32_t pts_denominator;
};
/* ONE existing host/filter OS thread; serialized calls/callbacks only. No new
 * thread, audio owner or public ABI. Default closed: create does not issue
 * decoder controls, Signal, Wait, Copy, Dispatch or NVIDIA calls. Device resource
 * creation/QI and the caller-selected compiler are possible in create/prepare.
 * Legal device owner thread is a caller prerequisite; SINGLETHREADED rejects.
 * No active asynchronous query unless its real owner accounts for Dispatch.
 * Config/source identities are not authentication; only the app's actual locked
 * verified component plus future explicit renderer/HDR policy may choose this.
 * This draft adds NO production enable call or native-HDR source admission. */
HRESULT bv_mpv_hdr_chain_create(const struct bv_mpv_hdr_chain_config *,
    struct bv_mpv_hdr_chain **);
/* Enables ONLY the new independently authorized GPU-submit scope for one exact
 * current decoder epoch. Original readiness and CPU input opt-ins must already
 * have been issued by their actual owner. Does not enable them automatically.
 * Reset/reinit revoke authorization. Not callable from any scoped callback. */
HRESULT bv_mpv_hdr_chain_authorize(struct bv_mpv_hdr_chain *,
    struct mp_decoder_wrapper *,uint64_t instance,uint64_t epoch,bool enable);
/* Source and independent pool_output are borrowed; actual mp_image refs are
 * captured before preparing resources. No AVBuffer/HWctx/slice lease is replaced
 * with a COM texture ref. pool_output must be an actual single-sample R10 texture
 * in its independent MPV pool, with exact output dimensions and retained buffer.
 * Source params.crop must describe the actual full visible source rectangle;
 * raw AV crop zeros alone are insufficient. Before first submission, budget
 * includes both actual whole source/pool arrays and all retained host textures.
 * No reference can be rewritten/reused until the actual final-use fence completes.
 * Ticket is from the existing filter/source owner, not property/log TOFU or
 * time-pos. This chain passes it to the dedicated bridge and checks the result.
 * The source tuple is requalified DURING independent scoped submit, never via
 * stale atObserve. Scope false/unlock failure can still leave queued work.
 * NVIDIA/core/CPU waits, host prepare/close and all cleanup run OUTSIDE scope
 * and outside original context exclusion. No recursive wrapper controls.
 * Input/restore/final Signal callbacks each requalify the SAME held BVR2
 * receipt and exact current epoch. First attempt sticks BEFORE the first Wait.
 * Final-use is the actual ticket-owned new fence/value1: transport/core-ready
 * cannot retire it. Actual final Signal is queued ONLY after restore/PQcopy,
 * after the same fence seals hostframe, SRloan and generation ticket.
 * prepare_finish builds SRV/refs outside both locks; submit_restore has actual
 * nonnull prior context state; close_finish/lease cleanup are outside both locks.
 * Legacy host finish is not used as qualified HDR playback.
 * Partial/untracked failure retains one whole chain and refuses all later frames.
 * S_OK means final Signal enqueued after restore+PQ COPY, not completion/display.
 */
HRESULT bv_mpv_hdr_chain_submit(struct bv_mpv_hdr_chain *,
    struct mp_decoder_wrapper *,const struct mp_image *source,
    const struct mp_image *pool_output,const struct bv_mpv_hdr_chain_ticket *);
/* Nonwaiting fence poll. S_FALSE pending, failure retains the whole chain.
 * A live direct decoder owner must be held by caller through this call; NULL
 * discards output after safe retirement (e.g. teardown). Epoch mismatch discards
 * a completed old frame without altering playback. Outputs never mint tokens,
 * HDR/current flags or presentation proof. Source/restore completion is NOT a
 * renderer final fence; copying into independent MPV storage ends host use here.
 * pool_output is returned only after actual final-use completed, with explicit
 * generated RGB/PQ2020 metadata and full OUTPUT crop; timing/raw attribute
 * history only. Inferred HDR peaks/ICC/DV/grain/FF side data/EL are not copied;
 * all CURRENT witnesses/readiness/old RGB10 qualification and token are cleared.
 * Source compilation registration does not enable a caller; PRIVATE2 still
 * rejects native-HDR preservation. Do not deliver it to VO before that policy
 * and real renderer transport are independently implemented/reviewed. */
HRESULT bv_mpv_hdr_chain_poll(struct bv_mpv_hdr_chain *,
    struct mp_decoder_wrapper *live_decoder,struct mp_image **pool_output);
/* E_PENDING preserves *chain while any frame/loan is retained or quarantine
 * is set. Do not talloc/free/discard the opaque pointer on seek/VF teardown. */
HRESULT bv_mpv_hdr_chain_destroy(struct bv_mpv_hdr_chain **);
#endif
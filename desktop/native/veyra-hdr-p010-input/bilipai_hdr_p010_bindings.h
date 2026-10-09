/* GPL-3.0-or-later. PRIVATE UNWIRED SOURCE, not a public ABI or HDR permit. */
#ifndef BILIPAI_HDR_P010_BINDINGS_H
#define BILIPAI_HDR_P010_BINDINGS_H
#include <stdint.h>
enum bv_hdr_p010_raw_range { BV_HDR_P010_RANGE_UNKNOWN=0,
    BV_HDR_P010_RANGE_FULL=1, BV_HDR_P010_RANGE_LIMITED=2 };
enum bv_hdr_p010_raw_chroma { BV_HDR_P010_CHROMA_UNKNOWN=0,
    BV_HDR_P010_CHROMA_LEFT=1, BV_HDR_P010_CHROMA_CENTER=2,
    BV_HDR_P010_CHROMA_TOP_LEFT=3, BV_HDR_P010_CHROMA_TOP_CENTER=4,
    BV_HDR_P010_CHROMA_BOTTOM_LEFT=5, BV_HDR_P010_CHROMA_BOTTOM_CENTER=6 };
struct bv_hdr_p010_constants {
    uint32_t visible_width,visible_height,allocation_width,allocation_height;
    uint32_t raw_range,raw_chroma_location,reserved0,reserved1;
};
#ifdef __cplusplus
static_assert(sizeof(bv_hdr_p010_constants)==32,"private P010 constants");
#else
_Static_assert(sizeof(struct bv_hdr_p010_constants)==32,"private P010 constants");
#endif

/* SHAPE/constants validation only: never decoder origin/current-pixel admission.
 * Future caller must independently and atomically capture the real source:
 * - actual owned mp_image/AVHWFramesContext P010 + real same D3D11 texture/device;
 * - positively established CURRENT P010 PQ, BT2020 NCL/primaries and explicit
 *   raw range/chroma, rejecting unknown/guessed/retagged/DV/HLG/other domains;
 * - visible origin 0,0 and exact visible size, actual padded even allocation;
 * - actual planes[1] slice in resource bounds; two view FirstArraySlice equal
 *   that slice, ArraySize=1, mip0 only, matching SAME resource and mip;
 * - planar DXGI_P010 -> R16_UNORM and R16G16_UNORM actual view support;
 *   each Load reconstructs its 16-bit word and discards bits 5..0 BEFORE
 *   10-bit chroma-code interpolation; zero low-six bits are not presumed;
 * - no active async query scope unless the existing owner accepts counting
 *   helper Dispatch; isolated state swapping does not isolate query scopes.
 *
 * Current HDR snapshots are history only and current_encoding is UNKNOWN;
 * this prototype supplies no method to promote them or enable a route.
 * No numeric AV enum cast: explicit caller mapping of raw AV values only.
 * The actual decoder storage/array-slice lease stays retained after ANY queued
 * read until a real fence covering final use, not only SR/core completion.
 * Owner must prove producer synchronization, no SRV/UAV alias, typed FP16
 * UAV store, Dispatch(ceil(visible/16),ceil(visible/16),1), and cleanup/drain.
 * A successful validation/Dispatch alone never means a ready HDR frame. */
static inline int bv_hdr_p010_constants_valid(const struct bv_hdr_p010_constants *p) {
    return p&&p->visible_width&&p->visible_height&&
        p->allocation_width<=16384u&&p->allocation_height<=16384u&&
        p->visible_width<=p->allocation_width&&p->visible_height<=p->allocation_height&&
        !(p->allocation_width&1u)&&!(p->allocation_height&1u)&&
        (p->raw_range==BV_HDR_P010_RANGE_FULL||p->raw_range==BV_HDR_P010_RANGE_LIMITED)&&
        p->raw_chroma_location>=BV_HDR_P010_CHROMA_LEFT&&
        p->raw_chroma_location<=BV_HDR_P010_CHROMA_BOTTOM_CENTER&&
        !p->reserved0&&!p->reserved1;
}
#endif

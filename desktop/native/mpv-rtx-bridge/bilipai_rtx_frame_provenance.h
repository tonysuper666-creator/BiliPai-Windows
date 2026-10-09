/* GPL-3.0-or-later. Private MPV provenance; no public core ABI changes. */
#ifndef BILIPAI_RTX_FRAME_PROVENANCE_H
#define BILIPAI_RTX_FRAME_PROVENANCE_H
#include <stdint.h>
#include <stdbool.h>
#include <limits.h>
enum {
    MP_BILIPAI_TOKEN_V2 = 2,
    MP_BILIPAI_EFFECT_SR = 1, MP_BILIPAI_EFFECT_HDR = 2,
    MP_BILIPAI_SRGB_BGRA8 = 1, MP_BILIPAI_HDR10_RGB10 = 2,
    MP_BILIPAI_SOURCE_SDR_BT709 = 1, MP_BILIPAI_SOURCE_NATIVE_HDR = 2,
    MP_BILIPAI_OUTPUT_SDR_SRGB = 1, MP_BILIPAI_OUTPUT_SDR_TO_HDR = 2,
    MP_BILIPAI_OUTPUT_NATIVE_HDR_PRESERVE = 3, // reserved; no implementation/admission
    MP_BILIPAI_PROOF_UNPROVEN = 0, MP_BILIPAI_PROOF_QUEUE = 1,
    MP_BILIPAI_PROOF_DISPLAY_MATCH = 2, MP_BILIPAI_PROOF_DISJOINT = 3,
    MP_BILIPAI_PROOF_UNSUPPORTED = 4, MP_BILIPAI_PROOF_OVERFLOW = 5,
};
/* Scalar value owned by the mp_image. No borrowed pointers or COM ownership. */
struct mp_bilipai_frame_token {
    uint32_t version, submitted;
    uint64_t session, configuration, stream, sequence, adapter_luid;
    int64_t pts_numerator;
    int32_t pts_denominator;
    uint32_t input_width, input_height, width, height;
    uint32_t effects, transport, hdr_peak_nits;
    uint32_t source_kind, output_intent;
};
static inline bool mp_bilipai_token_valid(const struct mp_bilipai_frame_token *t)
{
    return t && t->version == MP_BILIPAI_TOKEN_V2 && t->submitted == 1 &&
        t->source_kind == MP_BILIPAI_SOURCE_SDR_BT709 &&
        t->session && t->session <= INT64_MAX &&
        t->configuration && t->configuration <= INT64_MAX &&
        t->stream >= t->configuration && t->stream <= INT64_MAX &&
        t->sequence && t->sequence <= INT64_MAX && t->adapter_luid &&
        t->pts_denominator > 0 && t->input_width && t->input_height &&
        t->width && t->height && t->width <= 16384 && t->height <= 16384 &&
        ((t->effects == MP_BILIPAI_EFFECT_SR &&
          t->output_intent == MP_BILIPAI_OUTPUT_SDR_SRGB &&
          t->transport == MP_BILIPAI_SRGB_BGRA8 && !t->hdr_peak_nits) ||
         (t->effects == (MP_BILIPAI_EFFECT_SR | MP_BILIPAI_EFFECT_HDR) &&
          t->output_intent == MP_BILIPAI_OUTPUT_SDR_TO_HDR &&
          t->transport == MP_BILIPAI_HDR10_RGB10 &&
          t->hdr_peak_nits >= 400 && t->hdr_peak_nits <= 2000));
}
static inline bool mp_bilipai_token_equal(const struct mp_bilipai_frame_token *a,
                                         const struct mp_bilipai_frame_token *b)
{
    return mp_bilipai_token_valid(a) && mp_bilipai_token_valid(b) &&
        a->version == b->version && a->source_kind == b->source_kind &&
        a->output_intent == b->output_intent &&
        a->session == b->session && a->configuration == b->configuration &&
        a->stream == b->stream && a->sequence == b->sequence &&
        a->adapter_luid == b->adapter_luid &&
        a->pts_numerator == b->pts_numerator &&
        a->pts_denominator == b->pts_denominator &&
        a->input_width == b->input_width && a->input_height == b->input_height &&
        a->width == b->width && a->height == b->height &&
        a->effects == b->effects && a->transport == b->transport &&
        a->hdr_peak_nits == b->hdr_peak_nits;
}
struct mp_bilipai_render_receipt {
    struct mp_bilipai_frame_token token;
    uint64_t frame_id;
    int32_t target_transfer, target_primaries;
    int32_t framebuffer_transfer, framebuffer_primaries;
    bool fresh;
};
struct mp_bilipai_present_receipt {
    struct mp_bilipai_render_receipt render;
    uint64_t epoch;
    uint32_t present_count, refresh_count;
    int64_t sync_qpc;
    uint32_t dxgi_format, dxgi_color_space;
    bool valid, hdr_output_proved;
};
struct mp_bilipai_presentation {
    uint32_t version, reason;
    uint64_t serial, epoch;
    int32_t present_hresult, statistics_hresult;
    struct mp_bilipai_present_receipt queued, displayed;
};
/* queued.valid is only Present S_OK plus measured last-count.
   displayed.valid additionally requires an exact known ID in actual reliable
   FrameStatistics. Neither field asserts image quality or Tensor utilization. */
#endif

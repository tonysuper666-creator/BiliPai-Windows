/* CPU contract regression; directly includes production provenance without SDK or GPU. */
#include <assert.h>
#include <stdio.h>
#include "../../native/mpv-rtx-bridge/bilipai_rtx_frame_provenance.h"

static struct mp_bilipai_frame_token hdr_only(void)
{
    return (struct mp_bilipai_frame_token) {
        .version = MP_BILIPAI_TOKEN_V2, .submitted = 1,
        .session = 17, .configuration = 23, .stream = 23, .sequence = 9,
        .adapter_luid = 1, .pts_numerator = 123000, .pts_denominator = 1000000,
        .input_width = 640, .input_height = 360, .width = 640, .height = 360,
        .effects = MP_BILIPAI_EFFECT_HDR, .transport = MP_BILIPAI_HDR10_RGB10,
        .hdr_peak_nits = 1000, .source_kind = MP_BILIPAI_SOURCE_SDR_BT709,
        .output_intent = MP_BILIPAI_OUTPUT_SDR_TO_HDR,
    };
}
#define INVALID(field, value) do { \
    struct mp_bilipai_frame_token t = base; t.field = (value); \
    assert(!mp_bilipai_token_valid(&t)); cases++; \
} while (0)

int main(void)
{
    unsigned int cases = 0;
    struct mp_bilipai_frame_token base = hdr_only();
    assert(mp_bilipai_token_valid(&base)); cases++;
    assert(mp_bilipai_token_equal(&base, &base)); cases++;
    assert(!mp_bilipai_token_valid(NULL)); cases++;
    INVALID(effects, 0); INVALID(effects, 4);
    INVALID(width, 1280); INVALID(height, 720);
    INVALID(source_kind, MP_BILIPAI_SOURCE_NATIVE_HDR);
    INVALID(output_intent, MP_BILIPAI_OUTPUT_SDR_SRGB);
    INVALID(output_intent, MP_BILIPAI_OUTPUT_NATIVE_HDR_PRESERVE);
    INVALID(transport, MP_BILIPAI_SRGB_BGRA8);
    INVALID(hdr_peak_nits, 0); INVALID(hdr_peak_nits, 399); INVALID(hdr_peak_nits, 2001);
    INVALID(submitted, 0); INVALID(version, 1);
    INVALID(session, 0); INVALID(configuration, 0); INVALID(stream, 22);
    INVALID(sequence, 0); INVALID(adapter_luid, 0); INVALID(pts_denominator, 0);
    INVALID(input_width, 0); INVALID(input_height, 0); INVALID(width, 0); INVALID(height, 0);
    INVALID(width, 16385); INVALID(height, 16385);
    INVALID(session, UINT64_MAX); INVALID(configuration, UINT64_MAX);
    INVALID(stream, UINT64_MAX); INVALID(sequence, UINT64_MAX);
    {
        struct mp_bilipai_frame_token sr = base;
        sr.effects = MP_BILIPAI_EFFECT_SR; sr.transport = MP_BILIPAI_SRGB_BGRA8;
        sr.output_intent = MP_BILIPAI_OUTPUT_SDR_SRGB; sr.hdr_peak_nits = 0;
        sr.width = 1280; sr.height = 720;
        assert(mp_bilipai_token_valid(&sr)); cases++;
        assert(!mp_bilipai_token_equal(&base, &sr)); cases++;
    }
    {
        struct mp_bilipai_frame_token both = base;
        both.effects = MP_BILIPAI_EFFECT_SR | MP_BILIPAI_EFFECT_HDR;
        both.width = 1280; both.height = 720;
        assert(mp_bilipai_token_valid(&both)); cases++;
        assert(!mp_bilipai_token_equal(&base, &both)); cases++;
        both.width = 640; both.height = 360;
        assert(!mp_bilipai_token_equal(&base, &both)); cases++;
    }
    printf("RTX production token contract: %u cases passed\n", cases);
    return 0;
}

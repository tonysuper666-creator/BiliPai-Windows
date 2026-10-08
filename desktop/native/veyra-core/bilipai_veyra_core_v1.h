/* BiliPai-owned ABI v1. GPL-3.0-or-later; upstream is fixed externally.
   Windows x64, cdecl. All structs use natural 8-byte packing, no STL/bool.
   This seam processes only video: mpv keeps decode/audio/timeline authority. */
#ifndef BILIPAI_VEYRA_CORE_V1_H
#define BILIPAI_VEYRA_CORE_V1_H
#include <stdint.h>
#if defined(BILIVEYRA_EXPORTS)
#define BV_API __declspec(dllexport)
#else
#define BV_API __declspec(dllimport)
#endif
#define BV_CALL __cdecl
#define BV_ABI_V1 0x00010000u
#ifdef __cplusplus
extern "C" {
#endif
#pragma pack(push, 8)
typedef uint64_t bv_handle_v1;
enum bv_code_v1 {
    BV_OK = 0, BV_INVALID = 1, BV_ABI_MISMATCH = 2, BV_BUSY = 3,
    BV_STALE = 4, BV_COLOR_UNSUPPORTED = 5, BV_DEVICE_FAILURE = 6,
    BV_CORE_FAILURE = 7, BV_FEATURE_FAILURE = 8, BV_TIMEOUT = 9,
    BV_RESET_REQUIRED = 10, BV_INTERNAL = 11
};
enum bv_effect_v1 { BV_VIDEO_SR = 1u, BV_VIDEO_HDR = 2u };
enum bv_color_v1 {
    BV_SRGB_BT709_FULL_RGBA8 = 1u,
    BV_SCRGB_BT709_LINEAR_FP16_80NITS = 2u
};
typedef struct bv_status_v1 {
    uint32_t size, abi;
    int32_t code;
    uint32_t native_hresult, upstream_status;
    uint64_t core_init_result;
    char message[256];
} bv_status_v1;
typedef struct bv_config_v1 {
    uint32_t size, abi;
    uint64_t session_id, source_generation, adapter_luid;
    void *d3d12_device, *d3d12_direct_queue;
    const uint16_t *runtime_directory_utf16; /* absolute reviewed runtime root */
    const char *project_id_utf8, *engine_version_utf8; /* application identity */
    uint32_t input_width, input_height, output_width, output_height;
    uint32_t effects, sr_quality; /* exactly SR/HDR; quality 1..4 when SR */
    uint32_t hdr_contrast, hdr_saturation, hdr_middle_gray, hdr_peak_nits;
    uint32_t wait_timeout_ms, reserved;
} bv_config_v1;
typedef struct bv_frame_v1 {
    uint32_t size, abi;
    uint64_t session_id, source_generation, sequence, adapter_luid;
    int64_t pts_numerator;
    int32_t pts_denominator; /* known timestamp in seconds; must be positive */
    uint32_t input_color, output_color, discontinuity_flags;
    void *input_texture, *input_ready_fence, *output_texture;
    uint64_t input_ready_value;
    uint32_t input_state, output_state, output_final_state, reserved;
} bv_frame_v1;
typedef struct bv_result_v1 {
    uint32_t size, abi;
    uint64_t session_id, source_generation, sequence, adapter_luid;
    int64_t pts_numerator;
    int32_t pts_denominator;
    uint32_t output_color, output_width, output_height, output_dxgi_format;
    uint32_t output_state, effects_applied, reserved;
    void *output_texture, *completion_fence;
    uint64_t completion_value;
} bv_result_v1;
#pragma pack(pop)
/* Before every call initialize size/abi on each supplied descriptor/status/result.
   One process host only. Caller serializes stream commands. Only same-device
   D3D12 textures/fences are supported; no raw D3D11/decoder arrays/CPU buffers.
   All texture subresources must really be in the supplied initial state;
   use COMMON, COPY_SOURCE/DEST, UAV, or pixel/nonpixel shader-read states.
   Create/reset enqueue actual feature creation and drain it with a bounded wait.
   Any nonzero handle returned by create is owned even when create fails.
   If create returns TIMEOUT it still returns a valid handle: retry/process after
   its initialization fence, or retry destroy. Never discard that handle.
   Process OK means SDK calls accepted and GPU work SUBMITTED, not displayed.
   Result pointers are borrowed. Caller must wait completion_fence/value before
   reading/reusing either texture; input bytes must remain immutable until then.
   Caller owns output slots and their later presentation/consumer-done leases.
   The adapter keeps COM references while its processing fence is pending.
   Destroy/reset never free pending resources on TIMEOUT; retry after completion.
   Never unload this DLL while a handle exists. No UI/audio/auth/DASH inside. */
BV_API int32_t BV_CALL bv_create_v1(const bv_config_v1*, bv_handle_v1*, bv_status_v1*);
BV_API int32_t BV_CALL bv_process_v1(bv_handle_v1, const bv_frame_v1*, bv_result_v1*, bv_status_v1*);
BV_API int32_t BV_CALL bv_reset_v1(bv_handle_v1, uint64_t session_id, uint64_t newer_generation, bv_status_v1*);
BV_API int32_t BV_CALL bv_destroy_v1(bv_handle_v1, bv_status_v1*);
#ifdef __cplusplus
}
#endif
#endif


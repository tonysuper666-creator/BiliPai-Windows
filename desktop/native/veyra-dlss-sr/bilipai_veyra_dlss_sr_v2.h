/* BiliPai-owned standard DLSS SR ABI v2 candidate. GPL-3.0-or-later.
   Windows x64/cdecl; 8-byte packing, no STL, bool or C++ exception boundary.
   This video-only interface is independent of the phase-one VSR/HDR ABI. */
#ifndef BILIPAI_VEYRA_DLSS_SR_V2_H
#define BILIPAI_VEYRA_DLSS_SR_V2_H
#include <stdint.h>
#if defined(BILIDLSS_EXPORTS)
#define BVD_API __declspec(dllexport)
#else
#define BVD_API __declspec(dllimport)
#endif
#define BVD_CALL __cdecl
#define BVD_ABI_V2 0x00020000u
#ifdef __cplusplus
extern "C" {
#endif
#pragma pack(push, 8)
typedef uint64_t bvd_handle_v2;
enum bvd_code_v2 {
    BVD_OK=0, BVD_INVALID=1, BVD_ABI_MISMATCH=2, BVD_BUSY=3,
    BVD_STALE=4, BVD_COLOR_UNSUPPORTED=5, BVD_DEVICE_FAILURE=6,
    BVD_CORE_FAILURE=7, BVD_FEATURE_FAILURE=8, BVD_TIMEOUT=9,
    BVD_RESET_REQUIRED=10, BVD_INTERNAL=11
};
enum bvd_color_v2 { BVD_SCRGB_BT709_LINEAR_FP16_80NITS=2u };
enum bvd_effect_v2 { BVD_DLSS_SR=4u };
enum bvd_frame_flags_v2 { BVD_HISTORY_RESET=1u };
enum bvd_depth_v2 {
    BVD_DEPTH_DECLARED_ZERO_FALLBACK=1u,
    BVD_DEPTH_NORMAL_NONINVERTED=2u
};
enum bvd_motion_v2 {
    BVD_MV_OUTPUT_PIXELS_CURRENT_TO_PREVIOUS=1u,
    BVD_MV_DECLARED_ZERO=2u
};
typedef struct bvd_status_v2 {
    uint32_t size, abi;
    int32_t code;
    uint32_t native_hresult, upstream_status;
    uint64_t core_init_result;
    char message[256];
} bvd_status_v2;
typedef struct bvd_config_v2 {
    uint32_t size, abi;
    uint64_t session_id, source_generation, adapter_luid, history_epoch;
    void *d3d12_device, *d3d12_direct_queue;
    const uint16_t *runtime_directory_utf16; /* absolute, separately authenticated */
    const char *project_id_utf8; /* caller's own persisted GUID, never an upstream ID */
    const char *engine_version_utf8; /* exactly BiliPai-Veyra-DLSS-SR-2 */
    uint32_t input_width, input_height, output_width, output_height;
    uint32_t perf_quality; /* official enum: 0=MaxPerf, 1=Balanced, 2=MaxQuality */
    uint32_t wait_timeout_ms, flags, reserved; /* flags/reserved must be zero */
} bvd_config_v2;
typedef struct bvd_frame_v2 {
    uint32_t size, abi;
    uint64_t session_id, source_generation, sequence, adapter_luid, history_epoch;
    int64_t pts_numerator;
    int32_t pts_denominator; /* timestamp in seconds, positive denominator */
    uint32_t input_color, output_color, flags;
    void *color_texture, *depth_texture, *motion_texture, *output_texture;
    void *input_ready_fence; /* one producer fence covers all three input textures */
    uint64_t input_ready_value; /* nonzero; same D3D12 device */
    uint32_t color_state, depth_state, motion_state, output_state, output_final_state;
    float jitter_offset_x, jitter_offset_y; /* source sampling offset, input pixels */
    float sharpness; /* exactly zero: current SDK deprecates DLSS sharpening */
    uint32_t depth_contract, motion_contract, reserved[2];
} bvd_frame_v2;
typedef struct bvd_result_v2 {
    uint32_t size, abi;
    uint64_t session_id, source_generation, sequence, adapter_luid, history_epoch;
    int64_t pts_numerator;
    int32_t pts_denominator;
    uint32_t output_color, output_width, output_height, output_dxgi_format;
    uint32_t output_state, effects_submitted, reserved;
    void *output_texture, *completion_fence; /* borrowed until successful destroy */
    uint64_t completion_value;
    uint32_t history_was_reset, guidance_contract;
} bvd_result_v2;
#pragma pack(pop)
/* Initialize size/abi on every status/config/frame/result.
   Both v1 and v2 use the one shared process host/library. One active owner only;
   either ABI returns BUSY while the other owns or quarantines that host.
   All resources must be live genuine same-device COM objects. Color/output:
   non-array single-mip RGBA16F linear BT.709/scRGB (1.0=80 cd/m2), no PQ/YUV.
   Depth: target-size R32_FLOAT, conventional non-inverted [0,1] or real zero.
   Motion: target-size R16G16_FLOAT, current->previous in output pixels, unjittered.
   These guidance extents mirror the fixed Veyra graph; correctness of supplied
   pixel contents is the producer's responsibility, not certified by the ABI.
   First frame after create/reset needs HISTORY_RESET and declared-zero motion.
   A newer history_epoch needs HISTORY_RESET. A seek/source discontinuity needs
   reset with strictly newer source_generation AND history_epoch.
   Texture initial states must be truthful, with no uncoordinated external use.
   The caller owns the output, waits the returned completion value before reading,
   and retains its own consumer lease until downstream use has completed.
   The module retains all inputs/output/fence through GPU completion; timeout or
   failed Signal retains the session. Failed or throwing feature/parameter
   release or shutdown permanently quarantines the entire session through process
   exit; later device removal never clears this latch. Destroy succeeds only
   after accepted feature/parameter release and observed same-device SDK
   Shutdown1 Success with no SEH. Failed initialization also requires one actual
   observed Shutdown1 attempt; NotInitialized is not retirement proof.
   Keep this candidate DLL loaded for process lifetime after destroy succeeds.
   Before NGX the module OS-PINs itself and exactly nvngx_dlss.dll under the
   caller-authenticated canonical directory. PIN failure rejects before NGX.
   Only three known process-lifetime runtime slots and one directory/GUID are
   admitted. Switching that identity requires restart. Native PIN is not auth.
   Caller adapter version remains BiliPai-Veyra-DLSS-SR-2; the actual shared NGX
   host version is fixed BiliPai-Veyra-Core-Shared-1 for both ABI entrypoints.
   OK is real SDK create/evaluate acceptance plus queue submission, not displayed
   pixels. Equal extents are rejected; ordinary player passthrough stays outside.
   On failed create a nonzero handle is still owned and must be destroyed safely.
   No caller strings or pointer descriptors are retained after create/process
   except COM leases; reset/destroy are serialized with all submissions. */
BVD_API int32_t BVD_CALL bvd_create_v2(const bvd_config_v2*,bvd_handle_v2*,bvd_status_v2*);
BVD_API int32_t BVD_CALL bvd_process_v2(bvd_handle_v2,const bvd_frame_v2*,bvd_result_v2*,bvd_status_v2*);
BVD_API int32_t BVD_CALL bvd_reset_v2(bvd_handle_v2,uint64_t session_id,uint64_t source_generation,uint64_t history_epoch,bvd_status_v2*);
BVD_API int32_t BVD_CALL bvd_destroy_v2(bvd_handle_v2,bvd_status_v2*);
#ifdef __cplusplus
}
#endif
#endif

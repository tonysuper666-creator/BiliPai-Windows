/* GPL-3.0-or-later. Candidate only: native video bridge, no playback owner. */
#ifndef BILIPAI_RTX_MPV_BRIDGE_H
#define BILIPAI_RTX_MPV_BRIDGE_H
#include <windows.h>
#include <d3d11.h>
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
int bv_mpv_bridge_create(const struct bv_mpv_config *, struct bv_mpv_bridge **,
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
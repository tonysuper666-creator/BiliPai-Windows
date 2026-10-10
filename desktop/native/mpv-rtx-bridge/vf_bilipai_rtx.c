/*
 * BiliPai mpv native video filter candidate, GPL-3.0-or-later.
 * Input/refqueue/AV hardware pool scaffolding derived from mpv
 * 69e63f425a531f814431fba12750bdb3721357f2 video/filter/vf_d3d11vpp.c
 * (LGPL-2.1-or-later; the original notice is retained below).
 * RTX core follows fixed Veyra 96a7c8de. No Veyra UI/decoder/audio is embedded.
 */
/*
 * This file is part of mpv.
 *
 * mpv is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * mpv is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with mpv.  If not, see <http://www.gnu.org/licenses/>.
 */

#define COBJMACROS
#include <assert.h>
#include <math.h>
#include <limits.h>
#include <inttypes.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>
#include <d3d11.h>
#include <d3dcompiler.h>
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_d3d11va.h>
#include "common/common.h"
#include "filters/filter.h"
#include "filters/filter_internal.h"
#include "filters/user_filters.h"
#include "refqueue.h"
#include "video/hwdec.h"
#include "video/fmt-conversion.h"
#include "video/mp_image.h"
#include "video/mp_image_pool.h"
#include "bilipai_rtx_mpv_bridge.h"
#include "filters/f_decoder_wrapper.h"
#include "veyra-hdr-caller/bilipai_hdr_vf_owner.h"

struct opts {
    char *dll, *runtime, *project;
    int64_t session, generation;
    int64_t native_pq_payload_budget;
    float scale;
    int quality, peak, timeout;
    bool hdr, sr, native_pq_diagnostic, native_pq_output;
};
struct priv {
    struct opts *opts;
    struct mp_refqueue *queue;
    AVBufferRef *av_device_ref, *hw_pool;
    AVD3D11VADeviceContext *d3d;
    struct bv_mpv_bridge *bridge;
    struct bv_mpv_hdr_vf_owner *hdr_owner;
    struct mp_image_params params, out_params;
    uint64_t generation, sequence;
    bool disabled, accepted_logged, decoder_ready_logged;
    bool native_pq_renderer_d3d11; // actual retained hwdec driver, not a caller option
    bool native_pq_route; // default-off diagnostic/output branch; no app admission
    bool context_fatal; // sticky until this entire VO chain is destroyed
    struct bv_mpv_pq_p010_observation pq_p010_observation;
};
static wchar_t *utf16(const char *s)
{
    if (!s) return NULL;
    int n=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,s,-1,NULL,0);
    if (!n) return NULL;
    wchar_t *out=malloc((size_t)n*sizeof(*out));
    if(out&&!MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,s,-1,out,n)){free(out);out=NULL;}
    return out;
}
struct pq_p010_observe_context {
    struct priv *p;
    const struct mp_image *image;
};
static void observe_pq_p010_texture(void *opaque,
                                    const struct mp_bilipai_active_decoder *active)
{
    struct pq_p010_observe_context *ctx = opaque;
    struct bv_mpv_pq_p010_observation *o = &ctx->p->pq_p010_observation;
    o->epoch_matched_at_observe = 1;
    o->refusal_history |= active->stream_refusals & MP_BILIPAI_HDR_HAZARDS_MASK;
    bv_mpv_observe_pq_p010(ctx->p->d3d->device,
        (ID3D11Texture2D *)ctx->image->planes[0],
        (uint32_t)(uintptr_t)ctx->image->planes[1], o);
    // No queue/recursive-control operations, GPU submission/Flush or waits.
}
static void observe_native_pq_p010(struct mp_filter *vf, const struct mp_image *image)
{
    struct priv *p = vf->priv;
    p->pq_p010_observation = (struct bv_mpv_pq_p010_observation){0};
    if (!image || !p->d3d || !p->d3d->device) return;
    const struct mp_image_params *q = &image->params;
    const struct mp_bilipai_decoder_origin *origin = &q->bilipai_decoder_origin;
    const struct mp_bilipai_hdr_snapshot *raw = &origin->frame_snapshot;
    if (origin->version != MP_BILIPAI_DECODER_ORIGIN_VERSION ||
        raw->version != MP_BILIPAI_HDR_SNAPSHOT_VERSION ||
        raw->av_format != AV_PIX_FMT_D3D11 || raw->av_sw_format != AV_PIX_FMT_P010LE ||
        raw->av_matrix != AVCOL_SPC_BT2020_NCL || raw->av_transfer != AVCOL_TRC_SMPTE2084 ||
        raw->av_primaries != AVCOL_PRI_BT2020 ||
        (raw->av_range != AVCOL_RANGE_MPEG && raw->av_range != AVCOL_RANGE_JPEG)) return;
    struct bv_mpv_pq_p010_observation *o = &p->pq_p010_observation;
    o->decoder_instance = origin->instance_id; o->decoder_epoch = origin->epoch_id;
    o->decoder_sequence = origin->frame_sequence; o->boundary_pq_p010 = 1;
    o->raw_range = raw->av_range == AVCOL_RANGE_MPEG ? 1 : 2;
    if (image->imgfmt != IMGFMT_D3D11 || q->imgfmt != IMGFMT_D3D11 ||
        q->hw_subfmt != pixfmt2imgfmt(AV_PIX_FMT_P010LE) ||
        !image->hwctx || !image->hwctx->data || image->hwctx->size < sizeof(AVHWFramesContext) ||
        image->w <= 0 || image->h <= 0 || q->w != image->w || q->h != image->h ||
        raw->width != image->w || raw->height != image->h || !image->planes[0] ||
        (uintptr_t)image->planes[1] > UINT32_MAX) return;
    const AVHWFramesContext *frames = (const void *)image->hwctx->data;
    if (frames->format != AV_PIX_FMT_D3D11 || frames->sw_format != AV_PIX_FMT_P010LE ||
        frames->width < image->w || frames->height < image->h ||
        !frames->device_ref || !frames->device_ref->data ||
        frames->device_ref->size < sizeof(AVHWDeviceContext) ||
        !frames->device_ctx || frames->device_ctx != (const void *)frames->device_ref->data ||
        frames->device_ctx->type != AV_HWDEVICE_TYPE_D3D11VA || !frames->device_ctx->hwctx) return;
    const AVD3D11VADeviceContext *device = frames->device_ctx->hwctx;
    if (device->device != p->d3d->device) return;
    o->hw_context_matching = 1; o->width = image->w; o->height = image->h;
    o->refusal_history = (q->bilipai_hdr_disqualifying_flags_seen |
        origin->stream_refusals | raw->flags) & MP_BILIPAI_HDR_HAZARDS_MASK;
    o->reference_unchanged = q->bilipai_decoder_current_reference == 1 &&
        origin->instance_id && origin->epoch_id && origin->frame_sequence &&
        !(origin->flags & ~MP_BILIPAI_DECODER_EXPORTED_FRAME_ONLY) &&
        !origin->crop_left && !origin->crop_top && !origin->crop_right && !origin->crop_bottom;
    struct mp_stream_info *info = mp_filter_find_stream_info(vf);
    struct pq_p010_observe_context ctx = {p, image};
    if (info && info->bilipai_observe_decoder_frame)
        info->bilipai_observe_decoder_frame(info, image, observe_pq_p010_texture, &ctx);
    const struct mp_bilipai_d3d11_ready *ready = mp_image_bilipai_d3d11_ready(image);
    if (ready && ready->device == p->d3d->device &&
        ready->device_context == p->d3d->device_context && !p->decoder_ready_logged) {
        MP_VERBOSE(vf, "Decoder-ready Signal recorded value=%"PRIu64
                   " instance=%"PRIu64" epoch=%"PRIu64" sequence=%"PRIu64
                   "; CURRENT GPU use and native HDR remain unavailable\n",
                   ready->signal_value, ready->origin.instance_id,
                   ready->origin.epoch_id, ready->origin.frame_sequence);
        p->decoder_ready_logged = true;
    }
    // Unknown/retired owner or epoch mismatch leaves texture/domain flags zero.
    // callback match is ONLY at synchronous observation, never GPU epoch proof.
    // Native HDR admission remains closed. None of these observations changes
    // source_color, out_params, effects, decoder current-HDR marker or tokens.
}
static void service_hdr_owner_borrowed(void *opaque, struct mp_decoder_wrapper *decoder)
{
    struct priv *p = opaque;
    bv_mpv_hdr_vf_owner_service(p->hdr_owner, decoder);
}
static void service_hdr_owner(struct mp_filter *vf)
{
    struct priv *p = vf->priv;
    // Recovery is independent of active/pollable work: failed submit has
    // already moved the whole generation into bounded quarantine. A new VF
    // on this same original live lane must not reuse the shared context.
    if (bv_mpv_hdr_vf_owner_recovery_required(p->hdr_owner)) {
        p->disabled = true;
        return;
    }
    if (!bv_mpv_hdr_vf_owner_has_work(p->hdr_owner)) return;
    struct mp_stream_info *info = mp_filter_find_stream_info(vf);
    bool borrowed = info && info->bilipai_with_decoder_owner &&
        info->bilipai_with_decoder_owner(info, service_hdr_owner_borrowed, p);
    if (!borrowed) bv_mpv_hdr_vf_owner_service(p->hdr_owner, NULL);
    if (bv_mpv_hdr_vf_owner_recovery_required(p->hdr_owner)) {
        // A partial scope/Signal/state failure can affect the shared context.
        // Bypass is not a recovered-context witness; actual owner recovery is
        // still required. No new HDR output or PRIVATE2 token is exported here.
        p->disabled = true;
    }
}
struct native_pq_revoke_context {struct priv *p;HRESULT hr;};
static void revoke_native_pq_borrowed(void *opaque,struct mp_decoder_wrapper *decoder)
{
    struct native_pq_revoke_context *c=opaque;
    c->hr=bv_mpv_hdr_vf_owner_diagnostic_revoke(c->p->hdr_owner,decoder);
}
static void revoke_native_pq(struct mp_filter *vf)
{
    struct priv *p=vf->priv;
    if(!p->opts||!p->opts->native_pq_diagnostic)return;
    struct native_pq_revoke_context c={.p=p,.hr=E_PENDING};
    struct mp_stream_info *info=mp_filter_find_stream_info(vf);
    bool borrowed=info&&info->bilipai_with_decoder_owner&&
        info->bilipai_with_decoder_owner(info,revoke_native_pq_borrowed,&c);
    if(!borrowed)c.hr=bv_mpv_hdr_vf_owner_diagnostic_revoke(p->hdr_owner,NULL);
    if(FAILED(c.hr)||bv_mpv_hdr_vf_owner_recovery_required(p->hdr_owner))p->disabled=true;
}
static bool native_pq_requested(struct priv *p,const struct mp_image *format)
{
    // Format only selects an explicit diagnostic route, never proves current
    // source/ready/authentication or decoder epoch. Real source is checked later.
    return p->opts->native_pq_diagnostic&&p->opts->native_pq_payload_budget>0&&format&&
        format->imgfmt==IMGFMT_D3D11&&format->params.imgfmt==IMGFMT_D3D11&&
        format->params.hw_subfmt==pixfmt2imgfmt(AV_PIX_FMT_P010LE)&&
        format->params.color.transfer==PL_COLOR_TRC_PQ&&
        format->params.color.primaries==PL_COLOR_PRIM_BT_2020;
}
/* Native queued output may use ONLY the actual retained renderer constructor
 * owner obtained at VF create. Same adapter/device-name is not queue ordering. */
static bool native_pq_same_renderer_owner(struct priv *p,const struct mp_image *im) {
    if(!p->native_pq_renderer_d3d11||!p->av_device_ref||!p->d3d||!p->d3d->device||!p->d3d->device_context||
       !im||!im->hwctx||!im->hwctx->data||im->hwctx->size<sizeof(AVHWFramesContext))return false;
    const AVHWFramesContext *f=(const void*)im->hwctx->data;
    if(!f->device_ref||f->device_ref->buffer!=p->av_device_ref->buffer||
       f->device_ref->data!=p->av_device_ref->data||f->device_ref->size!=p->av_device_ref->size||
       f->device_ctx!=(const void*)p->av_device_ref->data||
       f->device_ctx->type!=AV_HWDEVICE_TYPE_D3D11VA||f->device_ctx->hwctx!=p->d3d)return false;
    return true;
}
struct native_pq_step_context {
    struct priv *p;
    const struct mp_image *source;
    struct bv_mpv_hdr_vf_prepare cfg;
    struct bv_mpv_hdr_chain_ticket ticket;
    struct mp_image *output;
    HRESULT hr;
};
static void step_native_pq_borrowed(void *opaque,struct mp_decoder_wrapper *decoder)
{
    struct native_pq_step_context *c=opaque;
    // Real direct decoder is borrowed only for this synchronous main-lane call.
    // Outer callback is OUTSIDE dispatch/context locks. Actual chain submit uses
    // its separate exact qualified scopes; no NVIDIA/CPU wait enters those locks.
    if(c->p->opts->native_pq_output)
        c->hr=bv_mpv_hdr_vf_owner_diagnostic_output_step(c->p->hdr_owner,decoder,
            c->source,&c->cfg,&c->ticket,&c->output);
    else c->hr=bv_mpv_hdr_vf_owner_diagnostic_step(c->p->hdr_owner,decoder,c->source,&c->cfg,&c->ticket);
}
static struct mp_image *step_native_pq_diagnostic(struct mp_filter *vf,const struct mp_image *source)
{
    struct priv *p=vf->priv;
    if(p->opts->native_pq_output&&!native_pq_same_renderer_owner(p,source))return NULL;
    if(!p->native_pq_route||p->disabled)return NULL;
    // External caller must hold actual independently verified LockedComponentBinding
    // files for the native lifetime. These paths/flags do NOT authenticate it;
    // current app rejects native HDR and NEVER emits this internal option.
    if(!source||p->opts->native_pq_payload_budget<=0||p->opts->session<=0||
       p->opts->generation<=0||!p->hdr_owner||
       !isfinite(p->opts->scale)||p->opts->scale<1||p->opts->scale>4||
       source->w<=0||source->h<=0||source->pts==MP_NOPTS_VALUE||!isfinite(source->pts)||
       fabs(source->pts)>=(double)INT64_MAX/1000000.0||p->sequence>=INT64_MAX) {
        p->disabled=true;revoke_native_pq(vf);return NULL;
    }
    double w=source->w*(double)p->opts->scale,h=source->h*(double)p->opts->scale;
    if(w<1||h<1||w>16384||h>16384){p->disabled=true;revoke_native_pq(vf);return NULL;}
    uint32_t output_width=(uint32_t)lrint(w),output_height=(uint32_t)lrint(h);
    if(p->opts->native_pq_output) {
        // Actual D3D11 MPV mapper rounds both extents up to even before Copy.
        // Allocate those real extents; generated metadata preserves source DAR.
        output_width=(output_width+1u)&~1u;output_height=(output_height+1u)&~1u;
    }
    wchar_t *dll=utf16(p->opts->dll),*runtime=utf16(p->opts->runtime);
    struct native_pq_step_context c={.p=p,.source=source,.hr=E_PENDING};
    c.cfg.bridge=(struct bv_mpv_config){.dll_path=dll,.runtime_directory=runtime,
        .project_id=p->opts->project,.engine_version="BiliPai-Veyra-Core-1",
        .session=(uint64_t)p->opts->session,.configuration=(uint64_t)p->opts->generation,
        .generation=p->generation,.input_width=source->w,.input_height=source->h,
        .output_width=output_width,.output_height=output_height,
        .effects=BV_VIDEO_SR,.quality=p->opts->quality,.peak_nits=p->opts->peak,
        .timeout_ms=p->opts->timeout};
    // Native PQ uses fixed HDR-base/proxy/delta restoration, never TrueHDR bit2.
    // Actual owner_prepare overrides device/context fields from source AVHWowner.
    c.cfg.compile=D3DCompile;
    c.cfg.texture_payload_budget_bytes=(uint64_t)p->opts->native_pq_payload_budget;
    c.ticket=(struct bv_mpv_hdr_chain_ticket){.sequence=++p->sequence,
        .pts_numerator=(int64_t)llround(source->pts*1000000.0),.pts_denominator=1000000};
    struct mp_stream_info *info=mp_filter_find_stream_info(vf);
    bool borrowed=dll&&runtime&&info&&info->bilipai_with_decoder_owner&&
        info->bilipai_with_decoder_owner(info,step_native_pq_borrowed,&c);
    free(dll);free(runtime); // actual owner made independent bounded copies in prepare
    if(!borrowed){p->disabled=true;revoke_native_pq(vf);return NULL;}
    bool recovery=bv_mpv_hdr_vf_owner_recovery_required(p->hdr_owner);
    if(!recovery&&(c.hr==S_FALSE||c.hr==E_PENDING))return NULL; // known warm/pending; never mask recovery
    if(FAILED(c.hr)||recovery){p->disabled=true;revoke_native_pq(vf);}
    if(FAILED(c.hr)||recovery){talloc_free(c.output);return NULL;}
    // Only this SAME successful submit can supply a queued pool ref. Its original
    // PTS is preserved. Future service/poll releases chain-owned refs only.
    // No native token/display receipt is asserted by this internal branch.
    return c.output;
}
static bool source_color(const struct mp_image *image,struct bv_mpv_color *out)
{
    if(!image)return false;
    const struct mp_image_params *p=&image->params;
    out->rgb10_qualified=0;out->p016_depth=0;
    /* P012/P016 share DXGI P016. Only the actual retained AVHWFramesContext
       identifies CURRENT stored samples; codec/raw metadata and repr.bits
       are not authority. Fixed FFmpeg uses P012LE shift4 and P016LE shift0. */
    int effective=p->hw_subfmt?p->hw_subfmt:p->imgfmt;
    enum AVPixelFormat sampled=imgfmt2pixfmt(effective);
    if(sampled==AV_PIX_FMT_P012LE||sampled==AV_PIX_FMT_P016LE){
        if(image->imgfmt!=IMGFMT_D3D11||p->imgfmt!=IMGFMT_D3D11||
           !image->hwctx||!image->hwctx->data||
           image->hwctx->size<sizeof(AVHWFramesContext))return false;
        const AVHWFramesContext *frames=(const void*)image->hwctx->data;
        if(frames->format!=AV_PIX_FMT_D3D11||frames->sw_format!=sampled||
           !frames->device_ref||!frames->device_ref->data||
           frames->device_ref->size<sizeof(AVHWDeviceContext)||
           (const void*)frames->device_ref->data!=frames->device_ctx||
           !frames->device_ctx||frames->device_ctx->type!=AV_HWDEVICE_TYPE_D3D11VA||
           pixfmt2imgfmt(frames->sw_format)!=p->hw_subfmt||
           !frames->device_ctx->hwctx||!image->planes[0]||
           image->w<=0||image->h<=0||p->w!=image->w||p->h!=image->h||
           frames->width<image->w||frames->height<image->h)return false;
        const AVD3D11VADeviceContext *device=frames->device_ctx->hwctx;
        if(!device->device)return false;
        ID3D11Device *texture_device=NULL;
        ID3D11Texture2D_GetDevice((ID3D11Texture2D*)image->planes[0],&texture_device);
        bool same_device=texture_device==device->device;
        if(texture_device)ID3D11Device_Release(texture_device);
        if(!same_device)return false;
        out->p016_depth=sampled==AV_PIX_FMT_P012LE?12:16;
    }
    /* No HDR/proxy reconstruction is claimed here; the original HDR picture
       bypasses this first bridge. Only explicit transfer/primary/range qualifies. */
    if(pl_color_space_is_hdr(&p->color)||p->color.primaries!=PL_COLOR_PRIM_BT_709)
        return false;
    /* RGB10 requires CURRENT pixel proof plus the explicit raw import
       tuple and matching resolved SDR metadata. FULL keeps every old raw
       condition and is tightened by the new marker. LIMITED never borrows
       the old FULL flag; UNKNOWN and contradictory metadata bypass. */
    if(effective==IMGFMT_X2BGR10){
        if(p->sys_orig!=PL_COLOR_SYSTEM_RGB||p->repr.sys!=p->sys_orig||
           p->primaries_orig!=PL_COLOR_PRIM_BT_709||p->color.primaries!=p->primaries_orig||
           (p->transfer_orig!=PL_COLOR_TRC_SRGB&&p->transfer_orig!=PL_COLOR_TRC_BT_1886)||
           p->color.transfer!=p->transfer_orig||p->repr.levels!=p->levels_orig)
            return false;
        if(p->bilipai_rgb10_current_range==MP_BILIPAI_RGB10_RANGE_FULL){
            if(!p->bilipai_rgb10_source_explicit||p->levels_orig!=PL_COLOR_LEVELS_FULL)
                return false;
        }else if(p->bilipai_rgb10_current_range==MP_BILIPAI_RGB10_RANGE_LIMITED){
            if(p->bilipai_rgb10_source_explicit||p->levels_orig!=PL_COLOR_LEVELS_LIMITED)
                return false;
        }else return false;
        out->rgb10_qualified=1;
    }
    if(p->color.transfer==PL_COLOR_TRC_SRGB)out->transfer=0;
    else if(p->color.transfer==PL_COLOR_TRC_BT_1886)out->transfer=1;
    else return false;
    if(p->repr.levels==PL_COLOR_LEVELS_FULL)out->limited=0;
    else if(p->repr.levels==PL_COLOR_LEVELS_LIMITED)out->limited=1;
    else return false;
    if(p->repr.sys==PL_COLOR_SYSTEM_RGB)out->matrix=0;
    else if(p->repr.sys==PL_COLOR_SYSTEM_BT_601)out->matrix=1;
    else if(p->repr.sys==PL_COLOR_SYSTEM_BT_709)out->matrix=2;
    else return false;
    out->chroma=0;
    switch(p->chroma_location){
    case PL_CHROMA_UNKNOWN: break;
    case PL_CHROMA_LEFT: out->chroma=1;break;
    case PL_CHROMA_CENTER: out->chroma=2;break;
    case PL_CHROMA_TOP_LEFT: out->chroma=3;break;
    case PL_CHROMA_TOP_CENTER: out->chroma=4;break;
    case PL_CHROMA_BOTTOM_LEFT: out->chroma=5;break;
    case PL_CHROMA_BOTTOM_CENTER: out->chroma=6;break;
    default:return false;
    }
    return true;
}
static void release_output_lease(void *image) { talloc_free(image); }
static void log_failure(struct mp_filter *vf,int code,const char *stage,const char *detail)
{
    struct priv *p=vf->priv;
    MP_WARN(vf,"RTX SDK failure session=%"PRIu64" config-generation=%"PRIu64
        " stream-generation=%"PRIu64" code=%d stage=%s; %s\n",
        (uint64_t)p->opts->session,(uint64_t)p->opts->generation,
        p->generation,code,stage,detail);
}
static void retire_bridge(struct mp_filter *vf)
{
    struct priv *p=vf->priv;
    if(p->bridge){
        bv_status_v1 s;
        int rc=bv_mpv_bridge_destroy(&p->bridge,&s);
        if(rc!=BV_OK)MP_WARN(vf,"RTX resources pending retirement (%d); DLL/frames retained, original path available.\n",rc);
    }
}
static void flush_frames(struct mp_filter *vf)
{
    struct priv *p=vf->priv;
    // Revoke own exact armed tuple BEFORE whole resource retirement.
    revoke_native_pq(vf);
    // Retire whole private owner resources; no first-owner reset/rebind.
    bv_mpv_hdr_vf_owner_reset(p->hdr_owner);
    mp_refqueue_flush(p->queue);
    p->sequence=0;p->accepted_logged=false;p->decoder_ready_logged=false;
    if(p->generation==UINT64_MAX){p->disabled=true;retire_bridge(vf);return;}
    ++p->generation;
    if(p->bridge){
        bv_status_v1 s;int rc=bv_mpv_bridge_reset(p->bridge,p->generation,&s);
        if(rc==BV_OK)p->disabled=false;
        else {p->disabled=true;log_failure(vf,rc,"seek-reset-retired","bypassing original frame");}
    }
}
static struct mp_image *alloc_out(struct mp_filter *vf)
{
    struct priv *p=vf->priv;
    if(!mp_update_av_hw_frames_pool(&p->hw_pool,p->av_device_ref,IMGFMT_D3D11,
          p->out_params.hw_subfmt,p->out_params.w,p->out_params.h,false))return NULL;
    AVFrame *av=av_frame_alloc();if(!av)return NULL;
    if(av_hwframe_get_buffer(p->hw_pool,av,0)<0){av_frame_free(&av);return NULL;}
    struct mp_image *out=mp_image_from_av_frame(av);av_frame_free(&av);return out;
}
static bool prepare_bridge(struct mp_filter *vf,const struct mp_image *format)
{
    struct priv *p=vf->priv;struct bv_mpv_color color;
    if(p->opts->session<=0||p->opts->generation<=0){
        log_failure(vf,BV_INVALID,"bridge-unavailable","invalid configuration identity");return false;
    }
    if(!source_color(format,&color)){
        log_failure(vf,BV_COLOR_UNSUPPORTED,"bridge-unavailable","unsupported frame color metadata");return false;
    }
    if(!isfinite(p->opts->scale)||p->opts->scale<1||p->opts->scale>4||
       p->opts->quality<1||p->opts->quality>4||p->opts->peak<400||p->opts->peak>2000||
       p->opts->timeout<1||p->opts->timeout>5000||
       (!p->opts->sr&&(!p->opts->hdr||p->opts->scale!=1.0f))){
        log_failure(vf,BV_INVALID,"bridge-unavailable","invalid enhancement options");return false;
    }
    double w=p->params.w*(double)p->opts->scale,h=p->params.h*(double)p->opts->scale;
    if(w<1||h<1||w>16384||h>16384){
        log_failure(vf,BV_INVALID,"bridge-unavailable","invalid enhancement dimensions");return false;
    }
    p->out_params=p->params;
    p->out_params.w=(int)lrint(w);p->out_params.h=(int)lrint(h);
    p->out_params.hw_subfmt=p->opts->hdr?IMGFMT_X2BGR10:IMGFMT_BGRA;
    p->out_params.repr.sys=PL_COLOR_SYSTEM_RGB;p->out_params.repr.levels=PL_COLOR_LEVELS_FULL;
    memset(&p->out_params.repr.bits,0,sizeof(p->out_params.repr.bits));
    p->out_params.chroma_location=PL_CHROMA_UNKNOWN;
    if(p->opts->hdr){p->out_params.color=pl_color_space_hdr10;p->out_params.color.hdr.max_luma=p->opts->peak;}
    else p->out_params.color.transfer=PL_COLOR_TRC_SRGB;
    p->out_params.crop.x0=lrintf(p->opts->scale*p->params.crop.x0);
    p->out_params.crop.x1=lrintf(p->opts->scale*p->params.crop.x1);
    p->out_params.crop.y0=lrintf(p->opts->scale*p->params.crop.y0);
    p->out_params.crop.y1=lrintf(p->opts->scale*p->params.crop.y1);
    wchar_t *dll=utf16(p->opts->dll),*runtime=utf16(p->opts->runtime);
    struct bv_mpv_config cfg={0};cfg.device=p->d3d->device;cfg.dll_path=dll;cfg.runtime_directory=runtime;
    cfg.project_id=p->opts->project;cfg.engine_version="BiliPai-Veyra-Core-1";
    cfg.session=(uint64_t)p->opts->session;cfg.generation=p->generation;cfg.configuration=(uint64_t)p->opts->generation;
    cfg.input_width=p->params.w;cfg.input_height=p->params.h;cfg.output_width=p->out_params.w;cfg.output_height=p->out_params.h;
    cfg.effects=(p->opts->sr?BV_VIDEO_SR:0)|(p->opts->hdr?BV_VIDEO_HDR:0);cfg.quality=p->opts->quality;cfg.peak_nits=p->opts->peak;cfg.timeout_ms=p->opts->timeout;
    cfg.context_lock=p->d3d->lock;cfg.context_unlock=p->d3d->unlock;cfg.context_lock_opaque=p->d3d->lock_ctx;
    bv_status_v1 s;int rc=bv_mpv_bridge_create(&cfg,&p->bridge,&s);free(dll);free(runtime);
    if(rc!=BV_OK)log_failure(vf,rc,"bridge-unavailable",s.message);
    return rc==BV_OK;
}
static void process(struct mp_filter *vf)
{
    struct priv *p=vf->priv;
    if(p->context_fatal){mp_filter_internal_mark_failed(vf);return;}
    struct mp_image *format=mp_refqueue_execute_reinit(p->queue);
    if(format){
        revoke_native_pq(vf);
        bv_mpv_hdr_vf_owner_reset(p->hdr_owner);
        retire_bridge(vf);av_buffer_unref(&p->hw_pool);
        p->params=format->params;p->out_params=p->params;p->accepted_logged=false;p->decoder_ready_logged=false;p->sequence=0;
        if(p->generation==UINT64_MAX)p->disabled=true;
        else {
            ++p->generation;p->native_pq_route=native_pq_requested(p,format);
            p->disabled=bv_mpv_hdr_vf_owner_recovery_required(p->hdr_owner)||
                (!p->native_pq_route&&!prepare_bridge(vf,format));
        }
    }
    if(!mp_refqueue_can_output(p->queue))return;
    struct mp_image *in=mp_refqueue_get(p->queue,0),*out=NULL;
    // Actual main-lane real-fence service. Explicit native-only diagnostic may
    // request prepare/submit, but all options/controls remain default0; VO closed.
    service_hdr_owner(vf);
    // Service failure can orphan active pointer while its own armed generation
    // remains in whole custody. Try exact revoke; never infer recovered context.
    if(p->native_pq_route&&p->disabled)revoke_native_pq(vf);
    observe_native_pq_p010(vf, in);
    out=step_native_pq_diagnostic(vf,in);
    if(p->opts->native_pq_output&&
       bv_mpv_hdr_vf_owner_recovery_required(p->hdr_owner)) {
        // Neither a format change nor disabling this filter recovers the shared
        // context. Output-chain must stop this VO, not forward original pixels.
        p->context_fatal=true;
        talloc_free(out);mp_filter_internal_mark_failed(vf);return;
    }
    struct bv_mpv_color color;
    if(!p->native_pq_route&&!p->disabled&&p->bridge&&in&&source_color(in,&color)&&
       in->pts!=MP_NOPTS_VALUE&&isfinite(in->pts)&&
       fabs(in->pts)<(double)INT64_MAX/1000000.0&&p->sequence<UINT64_MAX){
        out=alloc_out(vf);
        if(out){
            mp_image_copy_attributes(out,in);out->params=p->out_params;
            // Cached format params must not replace this frame's raw history.
            // New RTX pixels have no AVFrame-boundary observation or native
            // HDR intake proof. TrueHDR remains the existing SDR conversion.
            mp_image_params_bilipai_hdr_copy_history(&out->params,&in->params);
            struct mp_image *lease=mp_image_new_ref(out),*input_lease=mp_image_new_ref(in);
            if(lease&&input_lease){
                bv_status_v1 s;struct mp_bilipai_frame_token measured={0};
                int rc=bv_mpv_bridge_process(p->bridge,(ID3D11Texture2D*)in->planes[0],(uint32_t)(uintptr_t)in->planes[1],
                   (ID3D11Texture2D*)out->planes[0],(uint32_t)(uintptr_t)out->planes[1],color,p->generation,++p->sequence,
                   (int64_t)llround(in->pts*1000000.0),1000000,input_lease,lease,release_output_lease,&s,&measured);
                /* The bridge consumes both leases even on failure. Actual output
                   ownership survives failed Signal/drain and filter teardown. */
                if(rc!=BV_OK){talloc_free(out);out=NULL;p->disabled=true;
                    log_failure(vf,rc,"process-bypass",s.message);
                }else {out->bilipai_rtx=measured;}
                if(rc==BV_OK&&!p->accepted_logged){
                    MP_INFO(vf,"RTX SDK accepted; GPU frame SUBMITTED session=%"PRIu64
                       " config-generation=%"PRIu64" stream-generation=%"PRIu64
                       " sequence=%"PRIu64" size=%dx%d; display completion not asserted.\n",
                       (uint64_t)p->opts->session,(uint64_t)p->opts->generation,
                       p->generation,p->sequence,out->w,out->h);
                    p->accepted_logged=true;
                }
            }else {talloc_free(lease);talloc_free(input_lease);talloc_free(out);out=NULL;}
        }
    }
    if(!out&&!p->native_pq_route&&!p->disabled&&p->bridge){
        p->disabled=true;
        log_failure(vf,BV_INTERNAL,"process-bypass",
                    "frame preparation bypassed; original frame forwarded");
    }
    if(!out){
        out=mp_image_new_ref(in);
        if(out)out->bilipai_rtx=(struct mp_bilipai_frame_token){0};
    }
    if(!out){mp_filter_internal_mark_failed(vf);return;}
    mp_refqueue_write_out_pin(p->queue,out);
}
static void uninit(struct mp_filter *vf)
{
    struct priv *p=vf->priv;
    revoke_native_pq(vf);
    // Pending whole generations survive this VF; no borrowed decoder pointer
    // or callback into this soon-to-be-destroyed priv is retained.
    bv_mpv_hdr_vf_owner_detach(&p->hdr_owner);
    retire_bridge(vf);
    if(p->queue){mp_refqueue_flush(p->queue);talloc_free(p->queue);}
    av_buffer_unref(&p->hw_pool);av_buffer_unref(&p->av_device_ref);
}
static bool command(struct mp_filter *vf,struct mp_filter_command *cmd)
{
    if(cmd->type!=MP_FILTER_COMMAND_BILIPAI_CONTEXT_FATAL)return false;
    struct priv *p=vf->priv;
    cmd->bilipai_context_fatal=p->context_fatal;
    return true; // CPU-only sticky observation; no GPU/lease/context operation
}
static const struct mp_filter_info filter={
    .name="bilipai-rtx",.process=process,.reset=flush_frames,.destroy=uninit,
    .command=command,.priv_size=sizeof(struct priv),
};
static struct mp_filter *create(struct mp_filter *parent,void *options)
{
    struct mp_filter *f=mp_filter_create(parent,&filter);
    if(!f){talloc_free(options);return NULL;}
    mp_filter_add_pin(f,MP_PIN_IN,"in");mp_filter_add_pin(f,MP_PIN_OUT,"out");
    struct priv *p=f->priv;p->opts=talloc_steal(p,options);p->queue=mp_refqueue_alloc(f);
    if(!p->opts||p->opts->generation<=0)goto fail;
    p->generation=(uint64_t)p->opts->generation;
    // Optional CPU-only owner allocation failure preserves ordinary playback.
    bv_mpv_hdr_vf_owner_create(&p->hdr_owner);
    struct mp_stream_info *info=mp_filter_find_stream_info(f);if(!info||!info->hwdec_devs)goto fail;
    struct hwdec_imgfmt_request request={.imgfmt=IMGFMT_D3D11,.probing=false};
    hwdec_devices_request_for_img_fmt(info->hwdec_devs,&request);
    struct mp_hwdec_ctx *hw=hwdec_devices_get_by_imgfmt_and_type(info->hwdec_devs,IMGFMT_D3D11,AV_HWDEVICE_TYPE_D3D11VA);
    if(!hw||!hw->av_device_ref)goto fail;
    // Fixed D3D11 mapper wraps ra_d3d11_get_device; EGL has a distinct driver.
    // Do not infer queue ordering from any D3D11 decode surface alone.
    p->native_pq_renderer_d3d11=hw->driver_name&&!strcmp(hw->driver_name,"d3d11va");
    p->av_device_ref=av_buffer_ref(hw->av_device_ref);if(!p->av_device_ref)goto fail;
    AVHWDeviceContext *device=(void*)p->av_device_ref->data;p->d3d=device->hwctx;
    if(!p->d3d||!p->d3d->device)goto fail;
    mp_refqueue_add_in_format(p->queue,IMGFMT_D3D11,0);mp_refqueue_set_refs(p->queue,0,0);mp_refqueue_set_mode(p->queue,0);
    return f;
fail:
    talloc_free(f);return NULL;
}
#define OPT_BASE_STRUCT struct opts
static const m_option_t fields[]={
    {"dll",OPT_STRING(dll)},{"runtime",OPT_STRING(runtime)},{"project",OPT_STRING(project)},
    {"session",OPT_INT64(session)},{"generation",OPT_INT64(generation)},
    {"scale",OPT_FLOAT(scale)},{"quality",OPT_INT(quality)},{"hdr",OPT_BOOL(hdr)},{"sr",OPT_BOOL(sr)},
    {"peak",OPT_INT(peak)},{"timeout",OPT_INT(timeout)},
    // Internal explicit diagnostic only; no app/JVM/GUI emitter or renderer gate.
    {"native-pq-diagnostic",OPT_BOOL(native_pq_diagnostic)},
    {"native-pq-payload-budget",OPT_INT64(native_pq_payload_budget)},
    // Internal source-only output handoff; default0, no app/JVM emitter or token.
    {"native-pq-output",OPT_BOOL(native_pq_output)},{0}
};
const struct mp_user_filter_entry vf_bilipai_rtx={
    .desc={.name="bilipai-rtx",.description="BiliPai RTX Video GPU bridge candidate",.priv_size=sizeof(struct opts),
        .priv_defaults=&(const struct opts){.scale=1,.quality=2,.peak=1000,.timeout=1000,.sr=true},.options=fields},.create=create,
};
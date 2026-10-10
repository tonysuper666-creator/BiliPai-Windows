/* GPL-3.0-or-later. Default-off private SOURCE; no production invocation. */
#include "config.h"
#ifndef COBJMACROS
#define COBJMACROS
#endif
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <d3d10.h>
#include <libavutil/buffer.h>
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_d3d11va.h>
#include <libavutil/pixfmt.h>
#include "common/common.h"
#include "filters/f_decoder_wrapper.h"
#include "video/d3d.h"
#include "video/fmt-conversion.h"
#include "video/mp_image.h"
#include "bilipai_hdr_p010_chain.h"
#define DROP(p) do { if(p){IUnknown_Release((IUnknown*)(p));(p)=NULL;} } while(0)
struct bv_mpv_hdr_chain {
    struct bv_mpv_hdr_chain_config cfg;
    DWORD thread;
    struct AVBufferRef *owner;
    ID3D11Device *device;
    ID3D10Multithread *multithread;
    ID3D11DeviceContext4 *context4;
    ID3D11Fence *proxy_ready,*final_use;
    uint64_t proxy_value,final_value,last_sequence,instance,epoch;
    struct bv_hdr11_pipeline *host;
    struct bv_hdr11_p010_prepared *prepared;
    struct bv_hdr11_finish_prepared *finish_prepared;
    AVBufferRef *use_ticket;
    struct bv_hdr11_frame *frame,*proxy_hold;
    struct bv_mpv_proxy_sr_loan *loan;
    struct bv_hdr11_p010_input input;
    struct mp_image *source,*output;
    struct bv_mpv_hdr_chain_ticket ticket;
    /* Incomplete private facts while ONE actual chain submission is held.
     * Version stays0 until exact queued acquire constructs the stack record. */
    struct bv_mpv_hdr_queued_output_record queued_facts;
    HRESULT submit_hr;
    bool busy,quarantine,proxy_released,sr_released,final_signaled,output_loaned;
};
static bool on_thread(const struct bv_mpv_hdr_chain *c) {
    return c&&c->thread==GetCurrentThreadId();
}
static bool same_object(IUnknown *a,IUnknown *b) {
    IUnknown *ia=NULL,*ib=NULL;
    HRESULT ha=a?IUnknown_QueryInterface(a,&IID_IUnknown,(void**)&ia):E_POINTER;
    HRESULT hb=b?IUnknown_QueryInterface(b,&IID_IUnknown,(void**)&ib):E_POINTER;
    bool same=SUCCEEDED(ha)&&SUCCEEDED(hb)&&ia==ib;DROP(ia);DROP(ib);return same;
}
static bool same_texture_device(struct bv_mpv_hdr_chain *c,ID3D11Texture2D *t) {
    ID3D11Device *d=NULL;if(!t)return false;ID3D11Texture2D_GetDevice(t,&d);
    bool same=d&&same_object((IUnknown*)d,(IUnknown*)c->device);DROP(d);return same;
}
static void release_image(void *p) { talloc_free(p); }
/* Callbacks keep the chain opaque alive, never unconditionally free a frame.
 * Actual retirement is polled outside locks after the shared final-use fence. */
static void proxy_release_requested(void *p) {
    ((struct bv_mpv_hdr_chain*)p)->proxy_released=true;
}
static void sr_release_requested(void *p) {
    ((struct bv_mpv_hdr_chain*)p)->sr_released=true;
}
void bv_mpv_hdr_chain_forget_queued_record(struct bv_mpv_hdr_chain *c) {
    if(on_thread(c))c->queued_facts=(struct bv_mpv_hdr_queued_output_record){0};
}
static HRESULT poison(struct bv_mpv_hdr_chain *c,HRESULT hr) {
    bv_mpv_hdr_chain_forget_queued_record(c);
    c->quarantine=true;c->instance=c->epoch=0;return FAILED(hr)?hr:E_FAIL;
}
static bool owner_frames(struct bv_mpv_hdr_chain *c,const struct mp_image *im,
                         enum AVPixelFormat sw) {
    if(!im||im->imgfmt!=IMGFMT_D3D11||im->params.imgfmt!=IMGFMT_D3D11||
       im->params.hw_subfmt!=pixfmt2imgfmt(sw)||!im->bufs[0]||
       !im->hwctx||!im->hwctx->data||im->hwctx->size<sizeof(AVHWFramesContext))return false;
    const AVHWFramesContext *f=(const void*)im->hwctx->data;
    return f->format==AV_PIX_FMT_D3D11&&f->sw_format==sw&&f->device_ref&&
        f->device_ref->data&&f->device_ref->size>=sizeof(AVHWDeviceContext)&&
        f->device_ref->buffer==c->owner->buffer&&f->device_ref->data==c->owner->data&&
        f->device_ref->size==c->owner->size&&f->device_ctx==(const void*)f->device_ref->data&&
        f->device_ctx->type==AV_HWDEVICE_TYPE_D3D11VA;
}
static bool output_shape(struct bv_mpv_hdr_chain *c,const struct mp_image *im,
                         ID3D11Texture2D *source) {
    if(!owner_frames(c,im,AV_PIX_FMT_X2BGR10LE)||!im->planes[0]||
       (uintptr_t)im->planes[1]>UINT32_MAX||im->w!=(int)c->cfg.output_width||
       im->h!=(int)c->cfg.output_height||im->params.w!=im->w||im->params.h!=im->h)
        return false;
    ID3D11Texture2D *t=(void*)im->planes[0];D3D11_TEXTURE2D_DESC d={0};
    if(!same_texture_device(c,t)||same_object((IUnknown*)t,(IUnknown*)source))return false;
    ID3D11Texture2D_GetDesc(t,&d);
    return d.Format==DXGI_FORMAT_R10G10B10A2_UNORM&&d.MipLevels==1&&d.ArraySize&&
        (uintptr_t)im->planes[1]<d.ArraySize&&d.Width==c->cfg.output_width&&
        d.Height==c->cfg.output_height&&d.SampleDesc.Count==1&&!d.SampleDesc.Quality&&
        d.Usage==D3D11_USAGE_DEFAULT&&!d.CPUAccessFlags&&
        !(d.MiscFlags&D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX)&&
        (d.BindFlags&D3D11_BIND_SHADER_RESOURCE);
}
/* Same payload bound as host prepare+finish, including the entire retained
 * decoder and independent pool arrays. Check BEFORE any ticket/Wait/Dispatch.
 * This counts texture payload, not available VRAM or driver allocation. */
static bool payload_add(uint64_t *sum,uint64_t pixels,uint64_t bytes) {
    if(!bytes||pixels>(UINT64_MAX-*sum)/bytes)return false;
    *sum+=pixels*bytes;return true;
}
static bool chain_payload(struct bv_mpv_hdr_chain *c,const struct mp_image *source,
                          const struct mp_image *pool) {
    D3D11_TEXTURE2D_DESC s={0},p={0};
    ID3D11Texture2D_GetDesc((ID3D11Texture2D*)source->planes[0],&s);
    ID3D11Texture2D_GetDesc((ID3D11Texture2D*)pool->planes[0],&p);
    if(!s.Width||!s.Height||!s.ArraySize||!p.ArraySize)return false;
    uint64_t total=0,allocation=UINT64_C(1)*s.Width*s.Height;
    uint64_t visible=UINT64_C(1)*source->w*source->h;
    uint64_t output=UINT64_C(1)*c->cfg.output_width*c->cfg.output_height;
    return payload_add(&total,allocation,3*(UINT64_C(1)+s.ArraySize))&&
        payload_add(&total,visible,12)&&payload_add(&total,output,16)&&
        payload_add(&total,output,4*UINT64_C(1)*p.ArraySize)&&
        total<=c->cfg.texture_payload_budget_bytes;
}
static bool map_input(const struct mp_image *im,
    const struct mp_bilipai_d3d11_ready *r,struct bv_hdr11_p010_input *in) {
    if(!im||!r||!in||r!=mp_image_bilipai_d3d11_ready(im)||r->array_slice>UINT32_MAX)return false;
    const struct mp_bilipai_decoder_origin *o=&im->params.bilipai_decoder_origin;
    const struct mp_bilipai_hdr_snapshot *raw=&o->frame_snapshot;
    if(raw->av_matrix!=AVCOL_SPC_BT2020_NCL||raw->av_transfer!=AVCOL_TRC_SMPTE2084||
       raw->av_primaries!=AVCOL_PRI_BT2020||
        o->crop_left||o->crop_top||o->crop_right||o->crop_bottom||
        im->params.crop.x0||im->params.crop.y0||
        im->params.crop.x1!=im->w||im->params.crop.y1!=im->h)return false;
    *in=(struct bv_hdr11_p010_input){0};in->texture=r->texture;
    in->array_slice=(uint32_t)r->array_slice;
    in->constants.visible_width=(uint32_t)r->width;
    in->constants.visible_height=(uint32_t)r->height;
    in->constants.allocation_width=(uint32_t)r->allocation_width;
    in->constants.allocation_height=(uint32_t)r->allocation_height;
    switch(raw->av_range) {
    case AVCOL_RANGE_MPEG:in->constants.raw_range=BV_HDR_P010_RANGE_LIMITED;break;
    case AVCOL_RANGE_JPEG:in->constants.raw_range=BV_HDR_P010_RANGE_FULL;break;
    default:return false;
    }
    switch(raw->av_chroma) {
    case AVCHROMA_LOC_LEFT:in->constants.raw_chroma_location=BV_HDR_P010_CHROMA_LEFT;break;
    case AVCHROMA_LOC_CENTER:in->constants.raw_chroma_location=BV_HDR_P010_CHROMA_CENTER;break;
    case AVCHROMA_LOC_TOPLEFT:in->constants.raw_chroma_location=BV_HDR_P010_CHROMA_TOP_LEFT;break;
    case AVCHROMA_LOC_TOP:in->constants.raw_chroma_location=BV_HDR_P010_CHROMA_TOP_CENTER;break;
    case AVCHROMA_LOC_BOTTOMLEFT:in->constants.raw_chroma_location=BV_HDR_P010_CHROMA_BOTTOM_LEFT;break;
    case AVCHROMA_LOC_BOTTOM:in->constants.raw_chroma_location=BV_HDR_P010_CHROMA_BOTTOM_CENTER;break;
    default:return false;
    }
    in->producer_ready_fence=r->fence;in->producer_ready_value=r->signal_value;
    return bv_hdr_p010_constants_valid(&in->constants);
}
HRESULT bv_mpv_hdr_chain_create(const struct bv_mpv_hdr_chain_config *cfg,
    struct bv_mpv_hdr_chain **out) {
    if(!out||*out)return E_INVALIDARG;
    if(!cfg||!cfg->sr_bridge||!cfg->compile||!cfg->session||!cfg->configuration||
       !cfg->generation||!cfg->adapter_luid||!cfg->output_width||!cfg->output_height||
       !d3d11_bilipai_default_owner_known(cfg->device_ref))return E_INVALIDARG;
    AVHWDeviceContext *hw=(void*)cfg->device_ref->data;
    AVD3D11VADeviceContext *d=hw->hwctx;
    if(!d||!d->device||!d->device_context||
       (ID3D11Device_GetCreationFlags(d->device)&D3D11_CREATE_DEVICE_SINGLETHREADED))return E_NOTIMPL;
    struct bv_mpv_hdr_chain *c=calloc(1,sizeof(*c));if(!c)return E_OUTOFMEMORY;
    c->cfg=*cfg;c->thread=GetCurrentThreadId();c->owner=av_buffer_ref(cfg->device_ref);
    if(!c->owner){free(c);return E_OUTOFMEMORY;}
    c->device=d->device;ID3D11Device_AddRef(c->device);
    ID3D11Device5 *dev5=NULL;
    /* Same interface used by fixed D3D11 HW mapper to protect this actual
     * renderer device. Keep its strong ref; do not enable protection ourselves
     * or infer exclusion from the FFmpeg constructor mutex alone. */
    HRESULT hr=ID3D11Device_QueryInterface(c->device,&IID_ID3D10Multithread,
        (void**)&c->multithread);
    if(SUCCEEDED(hr)&&!ID3D10Multithread_GetMultithreadProtected(c->multithread))
        hr=E_ACCESSDENIED;
    if(SUCCEEDED(hr))hr=ID3D11Device_QueryInterface(c->device,&IID_ID3D11Device5,(void**)&dev5);
    if(SUCCEEDED(hr))hr=IUnknown_QueryInterface((IUnknown*)d->device_context,
        &IID_ID3D11DeviceContext4,(void**)&c->context4);
    if(SUCCEEDED(hr))hr=ID3D11Device5_CreateFence(dev5,0,D3D11_FENCE_FLAG_NONE,
        &IID_ID3D11Fence,(void**)&c->proxy_ready);
    /* Final-use fence is created per BVR2 consumer ticket, never substituted
     * with a generic transport/producer/core-ready fence. */
    DROP(dev5);
    struct bv_hdr11_config host={.device=c->device,.compile=cfg->compile,
        .texture_payload_budget_bytes=cfg->texture_payload_budget_bytes};
    /* No void context callbacks. This module supplies actual checked exclusion. */
    if(SUCCEEDED(hr))hr=bv_hdr11_create(&host,&c->host);
    if(FAILED(hr)) {DROP(c->proxy_ready);DROP(c->final_use);DROP(c->context4);
        DROP(c->multithread);DROP(c->device);av_buffer_unref(&c->owner);free(c);return hr;}
    *out=c;return S_OK; /* instance/epoch remain0; no Signal/control/NVIDIA */
}
HRESULT bv_mpv_hdr_chain_authorize(struct bv_mpv_hdr_chain *c,
    struct mp_decoder_wrapper *decoder,uint64_t instance,uint64_t epoch,bool enable) {
    if(!on_thread(c)||c->busy||c->quarantine||!decoder)return E_PENDING;
    bv_mpv_hdr_chain_forget_queued_record(c);
    c->instance=c->epoch=0;
    struct mp_bilipai_gpu_submit_request r={instance,epoch,enable};
    int rc=mp_decoder_wrapper_control(decoder,VDCTRL_BILIPAI_SET_GPU_SUBMIT,&r);
    if(rc!=CONTROL_TRUE)return E_ACCESSDENIED;
    if(enable){c->instance=instance;c->epoch=epoch;}return S_OK;
}
static bool submit_prepared(void *opaque,const struct mp_bilipai_gpu_input_view *view) {
    struct bv_mpv_hdr_chain *c=opaque;struct bv_hdr11_p010_input current;
    if(view->image!=c->source||view->active.instance_id!=c->instance||
       view->active.epoch_id!=c->epoch||view->ready->device!=c->device||
       !map_input(view->image,view->ready,&current))return false;
    /* Lock order is decoder dispatch -> constructor HANDLE -> this device
     * critical section. Protected RA calls share this last section without
     * taking the constructor mutex. Preserve the whole state-swap/dispatch/
     * restore sequence; per-API multithread protection alone is insufficient. */
    ID3D10Multithread_Enter(c->multithread);
    if(!ID3D10Multithread_GetMultithreadProtected(c->multithread)) {
        c->submit_hr=E_ACCESSDENIED;ID3D10Multithread_Leave(c->multithread);return false;
    }
    c->submit_hr=bv_hdr11_submit_p010(c->prepared,&current);
    /* No configured callback, compiler, COM cleanup, NVIDIA/core/CPU GPU wait
     * or Flush in this section. Enter itself can wait for another CPU thread's
     * protected API call; it is not a GPU completion operation. */
    if(SUCCEEDED(c->submit_hr))c->submit_hr=ID3D11DeviceContext4_Signal(
        c->context4,c->proxy_ready,c->proxy_value);
    ID3D10Multithread_Leave(c->multithread);
    return SUCCEEDED(c->submit_hr);
}
static bool current_scope(struct bv_mpv_hdr_chain *c,
                          const struct mp_bilipai_gpu_input_view *view) {
    struct bv_hdr11_p010_input actual;
    return view && view->image==c->source &&
        view->active.instance_id==c->instance && view->active.epoch_id==c->epoch &&
        view->ready->device==c->device && map_input(view->image,view->ready,&actual);
}
static bool submit_finish(void *opaque,const struct mp_bilipai_gpu_input_view *view) {
    struct bv_mpv_hdr_chain *c=opaque;
    if(!current_scope(c,view))return false;
    ID3D10Multithread_Enter(c->multithread);
    if(!ID3D10Multithread_GetMultithreadProtected(c->multithread)) {
        c->submit_hr=E_ACCESSDENIED;ID3D10Multithread_Leave(c->multithread);return false;
    }
    c->submit_hr=bv_hdr11_submit_restore(c->finish_prepared);
    ID3D10Multithread_Leave(c->multithread);
    return SUCCEEDED(c->submit_hr);
}
static bool submit_final_signal(void *opaque,const struct mp_bilipai_gpu_input_view *view) {
    struct bv_mpv_hdr_chain *c=opaque;
    if(!current_scope(c,view)||!c->final_use||c->final_value!=1)return false;
    /* Exact dedicated ticket fence: actual Signal AFTER every last-use Copy.
     * No shader preparation/ref/cleanup/NVIDIA/CPU wait in this callback. */
    ID3D10Multithread_Enter(c->multithread);
    if(!ID3D10Multithread_GetMultithreadProtected(c->multithread)) {
        c->submit_hr=E_ACCESSDENIED;ID3D10Multithread_Leave(c->multithread);return false;
    }
    c->submit_hr=ID3D11DeviceContext4_Signal(c->context4,c->final_use,c->final_value);
    ID3D10Multithread_Leave(c->multithread);
    return SUCCEEDED(c->submit_hr);
}
static void clear_unsubmitted(struct bv_mpv_hdr_chain *c) {
    bv_mpv_hdr_chain_forget_queued_record(c);
    talloc_free(c->source);talloc_free(c->output);c->source=c->output=NULL;c->busy=false;
    c->output_loaned=false;
}
static HRESULT cancel_unattempted(struct bv_mpv_hdr_chain *c,HRESULT hr) {
    /* Ticket knows whether it has ever been attempted; no inferred cancellation
     * after first Wait/state attempt. Failure preserves the whole chain. */
    if(c->use_ticket&&!mp_bilipai_d3d11_use_retire(&c->use_ticket))return poison(c,E_PENDING);
    DROP(c->final_use);c->final_value=0;clear_unsubmitted(c);return hr;
}
HRESULT bv_mpv_hdr_chain_submit(struct bv_mpv_hdr_chain *c,
    struct mp_decoder_wrapper *decoder,const struct mp_image *source,
    const struct mp_image *output,const struct bv_mpv_hdr_chain_ticket *ticket) {
    if(!on_thread(c)||!decoder||!ticket||c->busy||c->quarantine||!c->instance||!c->epoch)
        return E_ACCESSDENIED;
    const struct mp_bilipai_d3d11_ready *r=mp_image_bilipai_d3d11_ready(source);
    if(!r||r->origin.instance_id!=c->instance||r->origin.epoch_id!=c->epoch||
       r->device!=c->device||!owner_frames(c,source,AV_PIX_FMT_P010LE)||
       !map_input(source,r,&c->input)||!output_shape(c,output,r->texture)||
       !chain_payload(c,source,output)||
       !ticket->sequence||ticket->sequence<=c->last_sequence||ticket->pts_denominator<=0||
       c->proxy_value>=UINT64_MAX-1)return E_INVALIDARG;
    c->source=mp_image_new_ref((struct mp_image*)source);
    c->output=mp_image_new_ref((struct mp_image*)output);
    struct mp_image *source_lease=c->source?mp_image_new_ref(c->source):NULL;
    if(!c->source||!c->output||!source_lease){talloc_free(source_lease);clear_unsubmitted(c);return E_OUTOFMEMORY;}
    c->busy=true;c->ticket=*ticket;c->proxy_released=c->sr_released=c->final_signaled=false;
    bv_mpv_hdr_chain_forget_queued_record(c);
    c->use_ticket=mp_bilipai_d3d11_use_prepare(c->source->bilipai_d3d11_ready);
    void *own_fence=NULL;uint64_t own_value=0;
    if(!c->use_ticket||!mp_bilipai_d3d11_use_consumer_fence(c->use_ticket,&own_fence,&own_value)||
       !own_fence||own_value!=1) {
        talloc_free(source_lease);return cancel_unattempted(c,E_ACCESSDENIED);
    }
    c->final_use=own_fence;c->final_value=own_value;
    ID3D11Fence_AddRef(c->final_use); // held outside locks through ticket retire
    HRESULT hr=bv_hdr11_prepare_p010(c->host,&c->input,c->cfg.output_width,c->cfg.output_height,
        (struct bv_hdr11_lease){source_lease,release_image},&c->prepared);
    if(FAILED(hr))return cancel_unattempted(c,hr);
    c->proxy_value++;c->submit_hr=E_ACCESSDENIED;
    struct mp_bilipai_gpu_submit_scope scope={.instance_id=c->instance,.epoch_id=c->epoch,
        .image=c->source,.submit=submit_prepared,.opaque=c,.use_ticket=c->use_ticket,
        .stage=MP_BILIPAI_SUBMIT_INPUT};
    bool accepted=mp_decoder_wrapper_bilipai_gpu_submit(decoder,&scope);
    hr=bv_hdr11_close_p010_prepared(&c->prepared,&c->frame); /* BOTH locks ended */
    if(FAILED(hr))return poison(c,hr);
    if(!c->frame) {
        if(scope.ticket_attempted)return poison(c,E_PENDING);
        return cancel_unattempted(c,E_ACCESSDENIED);
    }
    /* False/unlock failure never licenses freeing a partially submitted frame. */
    if(!accepted||!scope.mutex_released||FAILED(c->submit_hr))return poison(c,c->submit_hr);
    ID3D11Texture2D *proxy=NULL;ID3D11ShaderResourceView *proxy_srv=NULL;
    hr=bv_hdr11_proxy(c->frame,&proxy,&proxy_srv);
    if(FAILED(hr))return poison(c,hr);
    hr=bv_hdr11_retain(c->frame);if(FAILED(hr))return poison(c,hr);c->proxy_hold=c->frame;
    struct bv_mpv_proxy_sr_input in={.proxy=proxy,.device_ref=c->owner,
        .producer_ready_fence=(IUnknown*)c->proxy_ready,.producer_ready_value=c->proxy_value,
        .session=c->cfg.session,.configuration=c->cfg.configuration,.generation=c->cfg.generation,
        .sequence=ticket->sequence,.adapter_luid=c->cfg.adapter_luid,
        .pts_numerator=ticket->pts_numerator,.pts_denominator=ticket->pts_denominator,
        .retained_lease=c,.release_retained_lease=proxy_release_requested};
    struct bv_mpv_proxy_sr_result sr={0};bv_status_v1 status={0};
    /* This actual call is OUTSIDE decoder scope AND original context exclusion.
     * Existing bridge obtains checked exclusion only for its own transport;
     * core drain/NVIDIA eval occurs after that exclusion has been released. */
    int rc=bv_mpv_bridge_process_proxy_sr(c->cfg.sr_bridge,&in,&c->loan,&sr,&status);
    if(rc!=BV_OK||!c->loan||sr.session!=c->cfg.session||sr.configuration!=c->cfg.configuration||
       sr.generation!=c->cfg.generation||sr.sequence!=ticket->sequence||
       sr.adapter_luid!=c->cfg.adapter_luid||sr.pts_numerator!=ticket->pts_numerator||
       sr.pts_denominator!=ticket->pts_denominator||sr.effects!=BV_VIDEO_SR||
       sr.width!=c->cfg.output_width||sr.height!=c->cfg.output_height||
       !sr.output||!sr.ready_fence||!sr.ready_value)return poison(c,E_FAIL);
    /* Copy identities from the ACTUAL matched core result, never cfg alone.
     * This is incomplete private state; no record has been issued to a caller. */
    const struct mp_bilipai_decoder_origin *origin=&c->source->params.bilipai_decoder_origin;
    c->queued_facts=(struct bv_mpv_hdr_queued_output_record){
        .decoder_instance=origin->instance_id,.decoder_epoch=origin->epoch_id,
        .decoder_frame_sequence=origin->frame_sequence,
        .session=sr.session,.configuration=sr.configuration,.generation=sr.generation,
        .sequence=sr.sequence,.adapter_luid=sr.adapter_luid,
        .pts_numerator=sr.pts_numerator,.pts_denominator=sr.pts_denominator,
        .input_width=(uint32_t)c->source->w,.input_height=(uint32_t)c->source->h,
        .width=sr.width,.height=sr.height,.sr_effects=sr.effects,
        .output_array_slice=(uint32_t)(uintptr_t)c->output->planes[1],
        .proxy_sr_accepted=true};
    /* Create every SRV/ref/fence/parameter outside BOTH locks. The source
     * frame already has queued work, so all later failures keep whole custody. */
    struct mp_image *pool_lease=mp_image_new_ref(c->output);
    if(!pool_lease)return poison(c,E_OUTOFMEMORY);
    struct bv_hdr11_finish_input finish={.sr_output=sr.output,
        .sr_ready_fence=(IUnknown*)sr.ready_fence,.sr_ready_value=sr.ready_value,
        .pool_output=(void*)c->output->planes[0],
        .pool_array_slice=(uint32_t)(uintptr_t)c->output->planes[1]};
    hr=bv_hdr11_prepare_finish(c->frame,&finish,
        (struct bv_hdr11_lease){c,sr_release_requested},
        (struct bv_hdr11_lease){pool_lease,release_image},&c->finish_prepared);
    if(FAILED(hr))return poison(c,hr);
    scope=(struct mp_bilipai_gpu_submit_scope){.instance_id=c->instance,.epoch_id=c->epoch,
        .image=c->source,.submit=submit_finish,.opaque=c,.use_ticket=c->use_ticket,
        .stage=MP_BILIPAI_SUBMIT_RESTORE};
    c->submit_hr=E_ACCESSDENIED;
    accepted=mp_decoder_wrapper_bilipai_gpu_submit(decoder,&scope);
    hr=bv_hdr11_close_finish(&c->finish_prepared); // BOTH locks ended
    if(FAILED(hr)||!accepted||!scope.mutex_released||FAILED(c->submit_hr))
        return poison(c,FAILED(hr)?hr:c->submit_hr);
    c->queued_facts.restore_copy_enqueued=true; // requested in checked submit_restore
    /* All future uses stop here. Host + SR loan + generation share this EXACT
     * dedicated ticket fence, sealed before the actual final Signal callback. */
    hr=bv_hdr11_seal(c->frame,(IUnknown*)c->final_use,c->final_value);
    if(FAILED(hr))return poison(c,hr);
    rc=bv_mpv_bridge_release_proxy_sr_loan(&c->loan,(IUnknown*)c->final_use,c->final_value,&status);
    if(rc!=BV_BUSY||!c->loan)return poison(c,E_FAIL);
    if(!mp_bilipai_d3d11_use_seal(c->use_ticket,c->final_use,c->final_value))return poison(c,E_PENDING);
    scope=(struct mp_bilipai_gpu_submit_scope){.instance_id=c->instance,.epoch_id=c->epoch,
        .image=c->source,.submit=submit_final_signal,.opaque=c,.use_ticket=c->use_ticket,
        .stage=MP_BILIPAI_SUBMIT_FINAL_SIGNAL};
    c->submit_hr=E_ACCESSDENIED;
    accepted=mp_decoder_wrapper_bilipai_gpu_submit(decoder,&scope);
    if(!accepted||!scope.mutex_released||FAILED(c->submit_hr))return poison(c,c->submit_hr);
    c->final_signaled=true;c->queued_facts.final_use_signal_enqueued=true;
    c->last_sequence=ticket->sequence;return S_OK;
}
/* A generated picture inherits timing and raw ATTRIBUTE HISTORY only.
 * Do not copy decoder crop, inferred HDR peaks, ICC/DV/grain/FF side data or
 * an enhancement-layer picture onto newly restored/encoded RGB pixels. */
/* Preserve ONLY unique, explicitly parsed source mastering-display metadata.
 * It describes the mastering display, not measured enhanced content peaks.
 * Missing/malformed/duplicate values stay unknown; source CLL/FALL are history
 * only because SR may change them. Never copy inferred color.hdr/peak=1000. */
static void generated_mastering_metadata(struct mp_image_params *p,
                                         const struct mp_image *src) {
    const struct mp_bilipai_hdr_snapshot *r=&src->params.bilipai_decoder_origin.frame_snapshot;
    const uint32_t bad=MP_BILIPAI_HDR_DUPLICATE_STATIC|MP_BILIPAI_HDR_MDM_SIZE_UNKNOWN|
        MP_BILIPAI_HDR_MDM_RATIONAL_INVALID|MP_BILIPAI_HDR_MDM_FLAGS_NONBOOLEAN;
    if(r->version!=MP_BILIPAI_HDR_SNAPSHOT_VERSION||r->mdm_count!=1||
       (r->flags&(MP_BILIPAI_HDR_MDM_PRESENT|MP_BILIPAI_HDR_MDM_PARSED))!=
           (MP_BILIPAI_HDR_MDM_PRESENT|MP_BILIPAI_HDR_MDM_PARSED)||(r->flags&bad))return;
    if(r->has_luminance==1&&r->min_luminance.den>0&&r->max_luminance.den>0) {
        double lo=(double)r->min_luminance.num/r->min_luminance.den;
        double hi=(double)r->max_luminance.num/r->max_luminance.den;
        if(lo>=0&&hi>=5&&hi<=10000&&lo<hi) {
            p->color.hdr.min_luma=(float)lo;p->color.hdr.max_luma=(float)hi;
        }
    }
    if(r->has_primaries!=1)return;
    for(unsigned n=0;n<3;n++)for(unsigned xy=0;xy<2;xy++) {
        struct mp_bilipai_hdr_rational q=r->display_primaries[n][xy];
        if(q.den<=0||q.num<0||q.num>q.den)return;
    }
    for(unsigned xy=0;xy<2;xy++) {
        struct mp_bilipai_hdr_rational q=r->white_point[xy];
        if(q.den<=0||q.num<0||q.num>q.den)return;
    }
    p->color.hdr.prim=(struct pl_raw_primaries){
        .red={.x=(float)((double)r->display_primaries[0][0].num/r->display_primaries[0][0].den),
              .y=(float)((double)r->display_primaries[0][1].num/r->display_primaries[0][1].den)},
        .green={.x=(float)((double)r->display_primaries[1][0].num/r->display_primaries[1][0].den),
                .y=(float)((double)r->display_primaries[1][1].num/r->display_primaries[1][1].den)},
        .blue={.x=(float)((double)r->display_primaries[2][0].num/r->display_primaries[2][0].den),
               .y=(float)((double)r->display_primaries[2][1].num/r->display_primaries[2][1].den)},
        .white={.x=(float)((double)r->white_point[0].num/r->white_point[0].den),
                .y=(float)((double)r->white_point[1].num/r->white_point[1].den)}};
}
static void generated_metadata(struct mp_image *dst,const struct mp_image *src) {
    struct mp_image_params p={.imgfmt=dst->params.imgfmt,
        .imgfmt_name=dst->params.imgfmt_name,.hw_subfmt=dst->params.hw_subfmt,
        .w=dst->w,.h=dst->h,
        .vflip=src->params.vflip,.rotate=src->params.rotate,
        .stereo3d=src->params.stereo3d,.no_dovi=true,.no_enhancement_layer=true,
        .crop={0,0,dst->w,dst->h}};
    /* Preserve display aspect even for rounded or nonuniform output extents. */
    int display_w=0,display_h=0;
    mp_image_params_get_dsize(&src->params,&display_w,&display_h);
    mp_image_params_set_dsize(&p,display_w,display_h);
    p.repr.sys=PL_COLOR_SYSTEM_RGB;p.repr.levels=PL_COLOR_LEVELS_FULL;
    p.color.primaries=PL_COLOR_PRIM_BT_2020;p.color.transfer=PL_COLOR_TRC_PQ;
    generated_mastering_metadata(&p,src);
    /* CLL/FALL and chroma/depth/encoded-origin guesses stay zero/unknown.
     * The history helper preserves exact raw snapshots/refusals but invalidates
     * CURRENT and decoder authority. BVP4 export tag/old RGB10 bool stay zero. */
    mp_image_params_bilipai_hdr_copy_history(&p,&src->params);
    dst->params=p;dst->pict_type=0;dst->fields=0;
    dst->pts=src->pts;dst->dts=src->dts;dst->pkt_duration=src->pkt_duration;
    dst->nominal_fps=src->nominal_fps;
    av_buffer_unref(&dst->bilipai_d3d11_ready);
    av_buffer_unref(&dst->icc_profile);av_buffer_unref(&dst->dovi);
    av_buffer_unref(&dst->film_grain);av_buffer_unref(&dst->a53_cc);
    for(int n=0;n<dst->num_ff_side_data;n++)av_buffer_unref(&dst->ff_side_data[n].buf);
    talloc_free(dst->ff_side_data);dst->ff_side_data=NULL;dst->num_ff_side_data=0;
    mp_image_unrefp(&dst->enhancement_layer);
    memset(&dst->bilipai_rtx,0,sizeof(dst->bilipai_rtx));
}
static void current_at_poll(void *opaque,const struct mp_bilipai_active_decoder *active) {
    bool *ok=opaque;*ok=!(active->stream_refusals&MP_BILIPAI_HDR_HAZARDS_MASK);
}
/* Exact SAME submit only. No delayed poll result is substituted for a newer
 * input PTS; AVBuffer/HWctx/plane identity is independent of mutable metadata. */
static bool same_source_reference(const struct mp_image *a,const struct mp_image *b) {
    if(!a||!b||a->imgfmt!=b->imgfmt||a->w!=b->w||a->h!=b->h||
       !a->hwctx||!b->hwctx||a->hwctx->buffer!=b->hwctx->buffer||
       a->hwctx->data!=b->hwctx->data||a->hwctx->size!=b->hwctx->size||
       !mp_image_bilipai_decoder_origin_equal(&a->params.bilipai_decoder_origin,
                                               &b->params.bilipai_decoder_origin)||
       mp_image_bilipai_d3d11_ready(a)!=mp_image_bilipai_d3d11_ready(b))return false;
    for(unsigned n=0;n<MP_MAX_PLANES;n++) {
        if(a->planes[n]!=b->planes[n]||a->stride[n]!=b->stride[n]||
           !!a->bufs[n]!=!!b->bufs[n])return false;
        if(a->bufs[n]&&(a->bufs[n]->buffer!=b->bufs[n]->buffer||
           a->bufs[n]->data!=b->bufs[n]->data||a->bufs[n]->size!=b->bufs[n]->size))return false;
    }
    return true;
}
HRESULT bv_mpv_hdr_chain_acquire_queued_output(struct bv_mpv_hdr_chain *c,
    struct mp_decoder_wrapper *decoder,const struct mp_image *source,
    const struct bv_mpv_hdr_chain_ticket *ticket,struct mp_image **out,
    struct bv_mpv_hdr_queued_output_record *record) {
    if(record)*record=(struct bv_mpv_hdr_queued_output_record){0};
    if(!out||*out||!record||!on_thread(c)||!decoder||!ticket)return E_INVALIDARG;
    if(!c->busy||c->quarantine||!c->final_signaled||c->output_loaned||!c->output||
       !c->final_use||c->final_value!=1||ticket->sequence!=c->ticket.sequence||
       ticket->pts_numerator!=c->ticket.pts_numerator||
       ticket->pts_denominator!=c->ticket.pts_denominator||
       !same_source_reference(source,c->source))return S_FALSE;
    bool current=false;
    if(!mp_decoder_wrapper_bilipai_observe(decoder,c->source,current_at_poll,&current)||
       !current){bv_mpv_hdr_chain_forget_queued_record(c);return S_FALSE;}
    const struct bv_mpv_hdr_queued_output_record *q=&c->queued_facts;
    const struct mp_bilipai_decoder_origin *origin=&c->source->params.bilipai_decoder_origin;
    if(q->version||!q->proxy_sr_accepted||!q->restore_copy_enqueued||
       !q->final_use_signal_enqueued||q->decoder_instance!=c->instance||
       q->decoder_epoch!=c->epoch||q->decoder_frame_sequence!=origin->frame_sequence||
       q->session!=c->cfg.session||q->configuration!=c->cfg.configuration||
       q->generation!=c->cfg.generation||q->sequence!=ticket->sequence||
       q->adapter_luid!=c->cfg.adapter_luid||q->pts_numerator!=ticket->pts_numerator||
       q->pts_denominator!=ticket->pts_denominator||q->sr_effects!=BV_VIDEO_SR||
       q->input_width!=(uint32_t)c->source->w||q->input_height!=(uint32_t)c->source->h||
       q->width!=c->cfg.output_width||q->height!=c->cfg.output_height||
       q->output_array_slice!=(uint32_t)(uintptr_t)c->output->planes[1])
        return poison(c,E_UNEXPECTED);
    if(FAILED(ID3D11Device_GetDeviceRemovedReason(c->device)))return poison(c,E_FAIL);
    // Final COPY and final Signal were enqueued in THIS device's immediate
    // context. The caller's VO MUST use this exact retained renderer HWowner.
    // This is a separate normal frame ref, NOT CPU completion/Present proof.
    // Chain source/output/host/loan/ticket refs remain until its real final fence.
    generated_metadata(c->output,c->source);
    struct mp_image *loan=mp_image_new_ref(c->output);
    if(!loan)return E_OUTOFMEMORY;
    c->output_loaned=true;
    *record=c->queued_facts;record->version=BV_MPV_HDR_QUEUED_OUTPUT_RECORD_V1;
    *out=loan;return S_OK; // no completion, current-HDR, token or display authority
}
HRESULT bv_mpv_hdr_chain_poll(struct bv_mpv_hdr_chain *c,
    struct mp_decoder_wrapper *decoder,struct mp_image **out) {
    if(!out||*out||!on_thread(c))return E_INVALIDARG;
    if(c->quarantine)return E_PENDING;
    if(!c->busy)return S_FALSE;
    if(!c->final_signaled||ID3D11Fence_GetCompletedValue(c->final_use)==UINT64_MAX||
       FAILED(ID3D11Device_GetDeviceRemovedReason(c->device)))return E_PENDING;
    if(ID3D11Fence_GetCompletedValue(c->final_use)<c->final_value)return S_FALSE;
    bv_status_v1 status={0};int rc=c->loan?
        bv_mpv_bridge_release_proxy_sr_loan(&c->loan,NULL,0,&status):BV_OK;
    if(rc!=BV_OK)return rc==BV_BUSY?S_FALSE:poison(c,E_FAIL);
    if(!c->proxy_released)return poison(c,E_FAIL);
    HRESULT hr=bv_hdr11_retire(&c->frame);if(hr!=S_OK)return S_FALSE;
    hr=bv_hdr11_retire(&c->proxy_hold);if(hr!=S_OK)return S_FALSE;
    if(!c->sr_released)return poison(c,E_FAIL);
    if(!mp_bilipai_d3d11_use_retire(&c->use_ticket))return S_FALSE;
    DROP(c->final_use);c->final_value=0;
    bool matched=false;
    if(decoder)matched=mp_decoder_wrapper_bilipai_observe(decoder,c->source,current_at_poll,&matched)&&matched;
    if(matched) {
        /* Generated full-range RGB/PQ2020, not original coded metadata/current.
         * No HDR source qualification or frame/presentation token is minted. */
        generated_metadata(c->output,c->source);
        *out=c->output;c->output=NULL;
    } else c->instance=c->epoch=0;
    clear_unsubmitted(c);return matched?S_OK:S_FALSE;
}
HRESULT bv_mpv_hdr_chain_destroy(struct bv_mpv_hdr_chain **p) {
    if(!p||!*p)return S_OK;struct bv_mpv_hdr_chain *c=*p;
    if(!on_thread(c)||c->busy||c->quarantine||c->prepared||c->finish_prepared||
       c->frame||c->proxy_hold||c->loan||c->use_ticket)return E_PENDING;
    HRESULT hr=bv_hdr11_destroy(&c->host);if(FAILED(hr))return hr;
    DROP(c->proxy_ready);DROP(c->final_use);DROP(c->context4);DROP(c->multithread);DROP(c->device);
    av_buffer_unref(&c->owner);free(c);*p=NULL;return S_OK;
}
/* GPL-3.0-or-later. Default-closed MPV main-lane whole-generation custody. */
#include "config.h"
#ifndef COBJMACROS
#define COBJMACROS
#endif
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include <libavutil/buffer.h>
#include <libavutil/frame.h>
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_d3d11va.h>
#include "common/common.h"
#include "filters/f_decoder_wrapper.h"
#include "video/d3d.h"
#include "video/fmt-conversion.h"
#include "video/mp_image.h"
#include "video/mp_image_pool.h"
#include "bilipai_hdr_vf_owner.h"

struct owner_generation {
    DWORD thread;
    HANDLE thread_ref;
    bool claimed,retiring,quarantined;
    bool diagnostic_armed,diagnostic_in_flight;
    AVBufferRef *diagnostic_owner_ref; // CPU ownership only; held independently by whole custody
    AVBufferRef *device_ref,*pool;
    struct bv_mpv_bridge *bridge;
    struct bv_mpv_hdr_chain *chain;
    struct bv_mpv_hdr_vf_prepare cfg;
    wchar_t *dll,*runtime;
    char *project,*engine;
    uint64_t instance,epoch;
    uint64_t adapter_luid;
};
struct bv_mpv_hdr_vf_owner {
    DWORD thread;
    HANDLE thread_ref;
    bool entered,recovery_required;
    AVBufferRef *diagnostic_owner_ref; // never authentication/current/GPU proof
    struct owner_generation *active;
};
/* The actual whole objects, not counters or weak pointers, survive a dead VF.
 * No worker lane is invented. All resource operations stay on g->thread. */
static SRWLOCK custody_lock=SRWLOCK_INIT;
static struct owner_generation *custody[2];
static bool original_thread(HANDLE ref,DWORD id) {
    // A live held thread HANDLE prevents a recycled numeric ID from being
    // accepted as an original execution lane after that thread has exited.
    return ref&&id==GetCurrentThreadId()&&GetThreadId(ref)==id&&
        WaitForSingleObject(ref,0)==WAIT_TIMEOUT;
}
static bool hold_thread(HANDLE *out) {
    *out=NULL;return DuplicateHandle(GetCurrentProcess(),GetCurrentThread(),
        GetCurrentProcess(),out,0,FALSE,DUPLICATE_SAME_ACCESS)!=0;
}
static bool enter(struct bv_mpv_hdr_vf_owner *o) {
    if(!o||!original_thread(o->thread_ref,o->thread)||o->entered)return false;
    o->entered=true;return true;
}
static void leave(struct bv_mpv_hdr_vf_owner *o) {o->entered=false;}
static wchar_t *copy_wide(const wchar_t *s) {
    if(!s)return NULL;size_t n=wcslen(s);
    if(!n||n>32767)return NULL;
    wchar_t *r=malloc((n+1)*sizeof(*r));if(r)memcpy(r,s,(n+1)*sizeof(*r));return r;
}
static char *copy_utf8(const char *s) {
    if(!s)return NULL;size_t n=strlen(s);
    if(!n||n>4096)return NULL;
    char *r=malloc(n+1);if(r)memcpy(r,s,n+1);return r;
}
static bool reserve(struct owner_generation *g) {
    bool ok=false;AcquireSRWLockExclusive(&custody_lock);
    bool unresolved=false;
    for(unsigned i=0;i<2;i++)
        if(custody[i]&&(custody[i]->retiring||custody[i]->quarantined||
           (custody[i]->instance==g->instance&&custody[i]->epoch==g->epoch)))unresolved=true;
    if(!unresolved)for(unsigned i=0;i<2;i++)if(!custody[i]){
        custody[i]=g;g->claimed=true;ok=true;break;
    }
    ReleaseSRWLockExclusive(&custody_lock);return ok;
}
static struct owner_generation *claim(struct owner_generation *wanted,bool retiring) {
    struct owner_generation *g=NULL;AcquireSRWLockExclusive(&custody_lock);
    for(unsigned i=0;i<2;i++) {
        struct owner_generation *p=custody[i];
        if(p&&original_thread(p->thread_ref,p->thread)&&!p->claimed&&
           (!wanted||p==wanted)&&(!retiring||p->retiring)) {
            p->claimed=true;g=p;break;
        }
    }
    ReleaseSRWLockExclusive(&custody_lock);return g;
}
static void unclaim(struct owner_generation *g) {
    AcquireSRWLockExclusive(&custody_lock);g->claimed=false;
    ReleaseSRWLockExclusive(&custody_lock);
}
static void mark_retiring(struct owner_generation *g,bool quarantine) {
    AcquireSRWLockExclusive(&custody_lock);g->retiring=true;
    g->quarantined|=quarantine;ReleaseSRWLockExclusive(&custody_lock);
}
static bool quarantined_generation(struct owner_generation *g) {
    bool poisoned=false;AcquireSRWLockExclusive(&custody_lock);
    for(unsigned i=0;i<2;i++)if(custody[i]==g)poisoned=g->quarantined;
    ReleaseSRWLockExclusive(&custody_lock);return poisoned;
}
static void drop_empty(struct owner_generation *g) {
    /* Only after actual chain and whole bridge destruction succeeded. */
    AcquireSRWLockExclusive(&custody_lock);
    for(unsigned i=0;i<2;i++)if(custody[i]==g)custody[i]=NULL;
    ReleaseSRWLockExclusive(&custody_lock);
    av_buffer_unref(&g->pool);av_buffer_unref(&g->device_ref);
    av_buffer_unref(&g->diagnostic_owner_ref);
    if(g->thread_ref)CloseHandle(g->thread_ref);
    free(g->dll);free(g->runtime);free(g->project);free(g->engine);free(g);
}
static HRESULT retire_claimed(struct owner_generation *g) {
    // Empty chain does NOT prove that explicitly armed decoder permits were revoked.
    if(g->diagnostic_armed){mark_retiring(g,true);unclaim(g);return E_PENDING;}
    if(g->quarantined){unclaim(g);return E_PENDING;}
    struct mp_image *discard=NULL;
    HRESULT hr=g->chain?bv_mpv_hdr_chain_poll(g->chain,NULL,&discard):S_FALSE;
    talloc_free(discard);
    if(FAILED(hr)){mark_retiring(g,true);unclaim(g);return hr;}
    hr=bv_mpv_hdr_chain_destroy(&g->chain);
    if(FAILED(hr)){unclaim(g);return hr;}
    bv_status_v1 status={0};int rc=bv_mpv_bridge_destroy(&g->bridge,&status);
    if(rc!=BV_OK) {
        /* Legacy bridge may transfer a non-loan failure to its own singleton
         * quarantine and clear the pointer. The AVHWowner/path/pool remain HERE
         * even then; NULL is not a successful destruction witness. */
        mark_retiring(g,true);unclaim(g);return E_PENDING;
    }
    drop_empty(g);return S_OK;
}
static void service_retired(void) {
    /* Each actual slot is attempted at most once; one pending slot cannot
     * starve the other, and foreign-thread/quarantined objects are untouched. */
    for(unsigned i=0;i<2;i++) {
        struct owner_generation *g=NULL;AcquireSRWLockExclusive(&custody_lock);
        struct owner_generation *p=custody[i];
        if(p&&original_thread(p->thread_ref,p->thread)&&p->retiring&&
           !p->claimed&&!p->quarantined){p->claimed=true;g=p;}
        ReleaseSRWLockExclusive(&custody_lock);if(g)retire_claimed(g);
    }
}
static HRESULT retire_active(struct bv_mpv_hdr_vf_owner *o) {
    if(!o->active)return S_OK;
    struct owner_generation *g=o->active;o->active=NULL;
    mark_retiring(g,false);g=claim(g,true);
    HRESULT hr=g?retire_claimed(g):E_PENDING;
    if(FAILED(hr)&&g&&quarantined_generation(g))o->recovery_required=true;
    return hr;
}
static AVBufferRef *actual_owner(const struct mp_image *im) {
    if(!im||im->imgfmt!=IMGFMT_D3D11||im->params.imgfmt!=IMGFMT_D3D11||
       im->params.hw_subfmt!=pixfmt2imgfmt(AV_PIX_FMT_P010LE)||!im->bufs[0]||
       !im->hwctx||!im->hwctx->data||im->hwctx->size<sizeof(AVHWFramesContext)||
       !im->planes[0]||im->w<=0||im->h<=0||
       im->params.w!=im->w||im->params.h!=im->h)return NULL;
    const AVHWFramesContext *f=(const void*)im->hwctx->data;
    if(f->format!=AV_PIX_FMT_D3D11||f->sw_format!=AV_PIX_FMT_P010LE||
       f->width<im->w||f->height<im->h||!f->device_ref||
       f->device_ctx!=(const void*)f->device_ref->data||
       !d3d11_bilipai_default_owner_known(f->device_ref))return NULL;
    return f->device_ref;
}
static bool current_epoch(struct mp_decoder_wrapper *decoder,const struct mp_image *im,
                          struct mp_bilipai_active_decoder *active) {
    memset(active,0,sizeof(*active));
    if(!decoder||mp_decoder_wrapper_control(decoder,VDCTRL_BILIPAI_GET_ACTIVE_DECODER,active)!=CONTROL_TRUE)
        return false;
    const struct mp_bilipai_decoder_origin *o=&im->params.bilipai_decoder_origin;
    const struct mp_bilipai_hdr_snapshot *raw=&o->frame_snapshot;
    return o->version==MP_BILIPAI_DECODER_ORIGIN_VERSION&&
        raw->version==MP_BILIPAI_HDR_SNAPSHOT_VERSION&&
        raw->av_format==AV_PIX_FMT_D3D11&&raw->av_sw_format==AV_PIX_FMT_P010LE&&
        raw->av_matrix==AVCOL_SPC_BT2020_NCL&&raw->av_transfer==AVCOL_TRC_SMPTE2084&&
        raw->av_primaries==AVCOL_PRI_BT2020&&
        (raw->av_range==AVCOL_RANGE_MPEG||raw->av_range==AVCOL_RANGE_JPEG)&&
        raw->av_chroma>=AVCHROMA_LOC_LEFT&&raw->av_chroma<=AVCHROMA_LOC_BOTTOM&&
        raw->width==im->w&&raw->height==im->h&&
        !o->crop_left&&!o->crop_top&&!o->crop_right&&!o->crop_bottom&&
        !im->params.crop.x0&&!im->params.crop.y0&&
        im->params.crop.x1==im->w&&im->params.crop.y1==im->h&&
        !(o->flags&~MP_BILIPAI_DECODER_EXPORTED_FRAME_ONLY)&&
        !((raw->flags|o->stream_refusals|im->params.bilipai_hdr_disqualifying_flags_seen)&MP_BILIPAI_HDR_HAZARDS_MASK)&&
        im->params.bilipai_decoder_current_reference==1&&
        active->instance_id&&active->epoch_id&&o->instance_id==active->instance_id&&
        o->epoch_id==active->epoch_id&&o->frame_sequence&&
        o->frame_sequence<=active->last_frame_sequence&&
        !(active->stream_refusals&MP_BILIPAI_HDR_HAZARDS_MASK);
}
static bool same_owner(const AVBufferRef *a,const AVBufferRef *b) {
    return a&&b&&a->buffer==b->buffer&&a->data==b->data&&a->size==b->size;
}
static bool same_config(const struct owner_generation *g,const struct bv_mpv_hdr_vf_prepare *p) {
    const struct bv_mpv_config *a=&g->cfg.bridge,*b=&p->bridge;
    return a->session==b->session&&a->configuration==b->configuration&&
        a->generation==b->generation&&a->input_width==b->input_width&&
        a->input_height==b->input_height&&a->output_width==b->output_width&&
        a->output_height==b->output_height&&a->effects==b->effects&&
        a->quality==b->quality&&a->peak_nits==b->peak_nits&&a->timeout_ms==b->timeout_ms&&
        g->cfg.compile==p->compile&&g->cfg.texture_payload_budget_bytes==p->texture_payload_budget_bytes&&
        b->dll_path&&b->runtime_directory&&b->project_id&&b->engine_version&&
        !wcscmp(g->dll,b->dll_path)&&!wcscmp(g->runtime,b->runtime_directory)&&
        !strcmp(g->project,b->project_id)&&!strcmp(g->engine,b->engine_version);
}
static bool actual_luid(ID3D11Device *device,uint64_t *out) {
    IDXGIDevice *dx=NULL;IDXGIAdapter *adapter=NULL;DXGI_ADAPTER_DESC desc={0};
    HRESULT hr=ID3D11Device_QueryInterface(device,&IID_IDXGIDevice,(void**)&dx);
    if(SUCCEEDED(hr))hr=IDXGIDevice_GetAdapter(dx,&adapter);
    if(SUCCEEDED(hr))hr=IDXGIAdapter_GetDesc(adapter,&desc);
    if(adapter)IDXGIAdapter_Release(adapter);if(dx)IDXGIDevice_Release(dx);
    if(FAILED(hr))return false;memcpy(out,&desc.AdapterLuid,sizeof(*out));return *out!=0;
}
HRESULT bv_mpv_hdr_vf_owner_create(struct bv_mpv_hdr_vf_owner **out) {
    if(!out||*out)return E_INVALIDARG;
    struct bv_mpv_hdr_vf_owner *o=calloc(1,sizeof(*o));if(!o)return E_OUTOFMEMORY;
    o->thread=GetCurrentThreadId();
    if(!hold_thread(&o->thread_ref)){free(o);return E_FAIL;}
    o->diagnostic_owner_ref=av_buffer_allocz(1);
    if(!o->diagnostic_owner_ref){CloseHandle(o->thread_ref);free(o);return E_OUTOFMEMORY;}
    *out=o;return S_OK;
}
HRESULT bv_mpv_hdr_vf_owner_prepare(struct bv_mpv_hdr_vf_owner *o,
    struct mp_decoder_wrapper *decoder,const struct mp_image *im,
    const struct bv_mpv_hdr_vf_prepare *cfg,struct bv_mpv_hdr_chain **out) {
    if(!out||*out||!cfg||!cfg->compile||!cfg->texture_payload_budget_bytes||!enter(o))return E_INVALIDARG;
    HRESULT hr=E_ACCESSDENIED;AVBufferRef *owner=actual_owner(im);
    struct mp_bilipai_active_decoder active={0};
    const struct bv_mpv_config *b=&cfg->bridge;
    if(bv_mpv_hdr_vf_owner_recovery_required(o)){hr=E_PENDING;goto done;}
    if(!owner||!current_epoch(decoder,im,&active)||
       b->effects!=BV_VIDEO_SR||!b->session||!b->configuration||
       b->configuration>INT64_MAX||b->generation<b->configuration||
       b->quality<1||b->quality>4||b->peak_nits<400||b->peak_nits>2000||
       b->timeout_ms<1||b->timeout_ms>5000||
       b->input_width!=(uint32_t)im->w||b->input_height!=(uint32_t)im->h||
       !b->output_width||!b->output_height||b->output_width>16384||b->output_height>16384)goto done;
    service_retired();
    if(o->active&&same_owner(o->active->device_ref,owner)&&
       o->active->instance==active.instance_id&&o->active->epoch==active.epoch_id&&same_config(o->active,cfg)) {
        *out=o->active->chain;hr=S_OK;goto done;
    }
    hr=retire_active(o);if(FAILED(hr))goto done;
    struct owner_generation *g=calloc(1,sizeof(*g));
    if(!g){hr=E_OUTOFMEMORY;goto done;}
    g->thread=o->thread;g->cfg=*cfg;g->instance=active.instance_id;g->epoch=active.epoch_id;
    if(!hold_thread(&g->thread_ref)){free(g);hr=E_FAIL;goto done;}
    g->diagnostic_owner_ref=av_buffer_ref(o->diagnostic_owner_ref);
    g->device_ref=av_buffer_ref(owner);g->dll=copy_wide(b->dll_path);
    g->runtime=copy_wide(b->runtime_directory);g->project=copy_utf8(b->project_id);g->engine=copy_utf8(b->engine_version);
    if(!g->diagnostic_owner_ref||!g->device_ref||!g->dll||!g->runtime||!g->project||!g->engine) {
        av_buffer_unref(&g->diagnostic_owner_ref);av_buffer_unref(&g->device_ref);CloseHandle(g->thread_ref);free(g->dll);free(g->runtime);free(g->project);free(g->engine);free(g);
        hr=E_OUTOFMEMORY;goto done;
    }
    AVHWDeviceContext *hw=(void*)g->device_ref->data;
    AVD3D11VADeviceContext *d=hw->hwctx;
    if(!d||!d->device||!d->device_context||
       (ID3D11Device_GetCreationFlags(d->device)&D3D11_CREATE_DEVICE_SINGLETHREADED)||
       FAILED(ID3D11Device_GetDeviceRemovedReason(d->device))||
       !actual_luid(d->device,&g->adapter_luid)) {
        av_buffer_unref(&g->diagnostic_owner_ref);av_buffer_unref(&g->device_ref);CloseHandle(g->thread_ref);free(g->dll);free(g->runtime);free(g->project);free(g->engine);free(g);
        hr=E_NOINTERFACE;goto done;
    }
    if(!reserve(g)) {
        av_buffer_unref(&g->diagnostic_owner_ref);av_buffer_unref(&g->device_ref);CloseHandle(g->thread_ref);free(g->dll);free(g->runtime);free(g->project);free(g->engine);free(g);
        hr=E_PENDING;goto done;
    }
    g->cfg.bridge.dll_path=g->dll;g->cfg.bridge.runtime_directory=g->runtime;
    g->cfg.bridge.project_id=g->project;g->cfg.bridge.engine_version=g->engine;
    g->cfg.bridge.device=d->device;g->cfg.bridge.context_lock=d->lock;
    g->cfg.bridge.context_unlock=d->unlock;g->cfg.bridge.context_lock_opaque=d->lock_ctx;
    bv_status_v1 status={0};int rc=bv_mpv_bridge_create(&g->cfg.bridge,&g->bridge,&status);
    if(rc!=BV_OK) {
        /* create may have transferred failed core state into native quarantine.
         * Its cleared output alone cannot authorize releasing the AVHW owner. */
        mark_retiring(g,true);unclaim(g);o->recovery_required=true;hr=E_PENDING;goto done;
    }
    struct bv_mpv_hdr_chain_config chain={.device_ref=g->device_ref,.sr_bridge=g->bridge,
        .compile=cfg->compile,.texture_payload_budget_bytes=cfg->texture_payload_budget_bytes,
        .session=b->session,.configuration=b->configuration,.generation=b->generation,
        .adapter_luid=g->adapter_luid,.output_width=b->output_width,.output_height=b->output_height};
    hr=bv_mpv_hdr_chain_create(&chain,&g->chain);
    if(SUCCEEDED(hr)&&!mp_update_av_hw_frames_pool(&g->pool,g->device_ref,IMGFMT_D3D11,
        IMGFMT_X2BGR10,(int)b->output_width,(int)b->output_height,false))hr=E_OUTOFMEMORY;
    if(FAILED(hr)) {mark_retiring(g,false);HRESULT retired=retire_claimed(g);
        if(FAILED(retired)&&quarantined_generation(g))o->recovery_required=true;goto done;}
    o->active=g;*out=g->chain;unclaim(g);hr=S_OK;
done:leave(o);return hr;
}
static struct mp_image *pool_image(struct owner_generation *g) {
    AVFrame *av=av_frame_alloc();if(!av)return NULL;
    if(av_hwframe_get_buffer(g->pool,av,0)<0){av_frame_free(&av);return NULL;}
    struct mp_image *out=mp_image_from_av_frame(av);av_frame_free(&av);return out;
}
HRESULT bv_mpv_hdr_vf_owner_submit_private(struct bv_mpv_hdr_vf_owner *o,
    struct mp_decoder_wrapper *decoder,const struct mp_image *source,
    const struct bv_mpv_hdr_chain_ticket *ticket) {
    if(!enter(o))return E_INVALIDARG;
    if(bv_mpv_hdr_vf_owner_recovery_required(o)){leave(o);return E_PENDING;}
    struct owner_generation *g=o->active?claim(o->active,false):NULL;HRESULT hr=E_ACCESSDENIED;
    if(g) {
        struct mp_bilipai_active_decoder active={0};
        if(same_owner(g->device_ref,actual_owner(source))&&current_epoch(decoder,source,&active)&&
           g->instance==active.instance_id&&g->epoch==active.epoch_id) {
            struct mp_image *output=pool_image(g);
            hr=output?bv_mpv_hdr_chain_submit(g->chain,decoder,source,output,ticket):E_OUTOFMEMORY;
            talloc_free(output); // chain captured its OWN output ref before any attempt
            if(hr==S_OK)g->diagnostic_in_flight=true; // queued chain, NOT completion
            if(FAILED(hr)) {
                /* No failure code or bool can prove that submit never queued
                 * work. Preserve the entire chain and require owner recovery. */
                mark_retiring(g,true);o->active=NULL;o->recovery_required=true;
            }
        }
        unclaim(g);
    }
    leave(o);return hr;
}
HRESULT bv_mpv_hdr_vf_owner_service(struct bv_mpv_hdr_vf_owner *o,
    struct mp_decoder_wrapper *decoder) {
    if(!enter(o))return E_INVALIDARG;service_retired();HRESULT hr=S_FALSE;
    struct owner_generation *g=o->active?claim(o->active,false):NULL;
    if(g) {
        struct mp_image *completed=NULL;hr=bv_mpv_hdr_chain_poll(g->chain,decoder,&completed);
        /* CURRENT PRIVATE2/VO native HDR admission is closed. A completed
         * private diagnostic pool picture is not a playable proof or token. */
        talloc_free(completed);
        if(hr==S_OK)g->diagnostic_in_flight=false; // actual final-use resources retired by poll
        if(FAILED(hr)){mark_retiring(g,true);o->active=NULL;o->recovery_required=true;}
        unclaim(g);
    }
    leave(o);return hr;
}
// Only this CPU adapter's actual independently retained marker, not a dead-VF
// pointer. Resource/decoder work always follows release of the custody lock.
static struct owner_generation *claim_diagnostic(struct bv_mpv_hdr_vf_owner *o,unsigned slot) {
    struct owner_generation *g=NULL;AcquireSRWLockExclusive(&custody_lock);
    struct owner_generation *p=slot<2?custody[slot]:NULL;
    if(p&&!p->claimed&&original_thread(p->thread_ref,p->thread)&&
       same_owner(p->diagnostic_owner_ref,o->diagnostic_owner_ref)) {
        p->claimed=true;g=p;
    }
    ReleaseSRWLockExclusive(&custody_lock);return g;
}
static HRESULT revoke_diagnostic_claimed(struct owner_generation *g,
    struct mp_decoder_wrapper *decoder) {
    if(!g->diagnostic_armed)return S_OK;
    struct mp_bilipai_active_decoder active={0};
    if(!decoder||mp_decoder_wrapper_control(decoder,VDCTRL_BILIPAI_GET_ACTIVE_DECODER,&active)!=CONTROL_TRUE||
       !active.instance_id||!active.epoch_id)goto unknown;
    // Another decoder instance being alive does NOT prove this old decoder
    // was destroyed/revoked; never alter that new instance or infer retirement.
    if(active.instance_id!=g->instance)goto unknown;
    if(active.epoch_id!=g->epoch) {
        // SAME actual instance: fixed vd_lavc init/reset clears all3 opt-ins
        // BEFORE assigning its new nonreused epoch. Only a TRUE live snapshot
        // of that new epoch counts; query failure remains UNKNOWN.
        g->diagnostic_armed=false;return S_OK;
    }
    struct mp_bilipai_decoder_ready_request request={
        .instance_id=g->instance,.epoch_id=g->epoch,.enable=false};
    // One dispatch-serialized exact validation and all3 revocations. Never
    // GET followed by three independently unlocking permission clears.
    if(mp_decoder_wrapper_control(decoder,VDCTRL_BILIPAI_SET_DECODER_READY,&request)!=CONTROL_TRUE)
        goto unknown;
    g->diagnostic_armed=false;return S_OK;
unknown:
    mark_retiring(g,true);return E_PENDING; // retain armed record AND whole custody
}
HRESULT bv_mpv_hdr_vf_owner_diagnostic_revoke(struct bv_mpv_hdr_vf_owner *o,
    struct mp_decoder_wrapper *decoder) {
    if(!o)return S_OK; // this absent adapter armed nothing; global quarantine is untouched
    if(!enter(o))return E_ACCESSDENIED;
    HRESULT hr=S_OK;
    for(unsigned i=0;i<2;i++) {
        struct owner_generation *g=claim_diagnostic(o,i);
        if(!g)continue;
        HRESULT one=revoke_diagnostic_claimed(g,decoder);
        if(FAILED(one)){o->recovery_required=true;hr=one;}
        unclaim(g);
    }
    // A skipped busy claim must not be mistaken for successful revocation.
    AcquireSRWLockExclusive(&custody_lock);
    for(unsigned i=0;i<2;i++) {
        struct owner_generation *g=custody[i];
        if(g&&same_owner(g->diagnostic_owner_ref,o->diagnostic_owner_ref)&&g->diagnostic_armed) {
            g->retiring=true;g->quarantined=true;o->recovery_required=true;hr=E_PENDING;
        }
    }
    ReleaseSRWLockExclusive(&custody_lock);
    leave(o);return hr;
}
HRESULT bv_mpv_hdr_vf_owner_diagnostic_step(struct bv_mpv_hdr_vf_owner *o,
    struct mp_decoder_wrapper *decoder,const struct mp_image *source,
    const struct bv_mpv_hdr_vf_prepare *cfg,const struct bv_mpv_hdr_chain_ticket *ticket) {
    if(!cfg||!ticket||!source||!enter(o))return E_INVALIDARG;
    struct mp_bilipai_active_decoder active={0};
    AVBufferRef *owner=actual_owner(source);
    if(bv_mpv_hdr_vf_owner_recovery_required(o)){leave(o);return E_PENDING;}
    if(!owner||!current_epoch(decoder,source,&active)){leave(o);return E_ACCESSDENIED;}
    bool replace=o->active&&(!same_owner(o->active->device_ref,owner)||
        o->active->instance!=active.instance_id||o->active->epoch!=active.epoch_id||
        !same_config(o->active,cfg));
    leave(o);
    if(replace) {
        HRESULT revoked=bv_mpv_hdr_vf_owner_diagnostic_revoke(o,decoder);
        if(FAILED(revoked))return revoked;
    }
    // Real resources/whole custody BEFORE granting any opt-in. Compiler/core
    // preparation and resource allocation remain OUTSIDE both original locks.
    struct bv_mpv_hdr_chain *chain=NULL;
    HRESULT hr=bv_mpv_hdr_vf_owner_prepare(o,decoder,source,cfg,&chain);
    if(FAILED(hr))return hr;
    if(!enter(o))return E_ACCESSDENIED;
    struct owner_generation *g=o->active?claim(o->active,false):NULL;
    if(!g){leave(o);return E_PENDING;}
    if(g->diagnostic_in_flight){unclaim(g);leave(o);return S_FALSE;}
    if(!g->diagnostic_armed) {
        // Sticky BEFORE attempting grant. Separate controls are NOT atomic;
        // actual submit will requalify in its independent dispatch+mutex scope.
        g->diagnostic_armed=true;
        struct mp_bilipai_decoder_ready_request ready={
            .instance_id=g->instance,.epoch_id=g->epoch,.enable=true};
        struct mp_bilipai_gpu_input_request input={
            .instance_id=g->instance,.epoch_id=g->epoch,.enable=true};
        if(mp_decoder_wrapper_control(decoder,VDCTRL_BILIPAI_SET_DECODER_READY,&ready)!=CONTROL_TRUE||
           mp_decoder_wrapper_control(decoder,VDCTRL_BILIPAI_SET_GPU_INPUT,&input)!=CONTROL_TRUE) {
            hr=E_ACCESSDENIED;
            if(FAILED(revoke_diagnostic_claimed(g,decoder)))o->recovery_required=true;
            unclaim(g);leave(o);return hr;
        }
    }
    // Already-decoded input NEVER gets a synthetic receipt. Only a subsequent
    // successful decoder receive under the opt-in may issue actual BVR2 ready.
    if(!mp_image_bilipai_d3d11_ready(source)){unclaim(g);leave(o);return S_FALSE;}
    hr=bv_mpv_hdr_chain_authorize(chain,decoder,g->instance,g->epoch,true);
    if(FAILED(hr)) {
        if(FAILED(revoke_diagnostic_claimed(g,decoder)))o->recovery_required=true;
        unclaim(g);leave(o);return hr;
    }
    unclaim(g);leave(o);
    // Existing actual chain rechecks SAME held refs/owner/ready/ticket DURING
    // each scoped submit. Core CPU waits/evaluation and close stay locks OUTSIDE.
    // S_OK is queued final Signal only, never completion/HDR admission/display.
    return bv_mpv_hdr_vf_owner_submit_private(o,decoder,source,ticket);
}
bool bv_mpv_hdr_vf_owner_has_work(const struct bv_mpv_hdr_vf_owner *o) {
    if(!o||!original_thread(o->thread_ref,o->thread))return false;
    bool work=o->active!=NULL;AcquireSRWLockExclusive(&custody_lock);
    for(unsigned i=0;i<2;i++)if(custody[i]&&custody[i]->thread==o->thread&&
        custody[i]->retiring&&!custody[i]->quarantined)work=true;
    ReleaseSRWLockExclusive(&custody_lock);return work;
}
bool bv_mpv_hdr_vf_owner_recovery_required(const struct bv_mpv_hdr_vf_owner *o) {
    // Optional CPU adapter allocation failure cannot erase old lane recovery.
    if(o&&!original_thread(o->thread_ref,o->thread))return false;
    bool recovery=o&&o->recovery_required;AcquireSRWLockExclusive(&custody_lock);
    for(unsigned i=0;i<2;i++)if(custody[i]&&custody[i]->quarantined&&
        original_thread(custody[i]->thread_ref,custody[i]->thread))recovery=true;
    ReleaseSRWLockExclusive(&custody_lock);return recovery;
}
void bv_mpv_hdr_vf_owner_reset(struct bv_mpv_hdr_vf_owner *o) {
    if(!enter(o))return;
    retire_active(o);
    service_retired();leave(o);
}
void bv_mpv_hdr_vf_owner_detach(struct bv_mpv_hdr_vf_owner **out) {
    if(!out||!*out)return;struct bv_mpv_hdr_vf_owner *o=*out;
    // Serialized destruction may arrive on another lane. It only transfers
    // CPU ownership; it must NOT poll/destroy/call any foreign GPU resources.
    if(o->entered)return;
    if(!original_thread(o->thread_ref,o->thread)) {
        if(o->active){mark_retiring(o->active,false);o->active=NULL;}
    } else {
        if(!enter(o))return;
        retire_active(o);service_retired();leave(o);
    }
    /* Every remaining generation is independently owned by custody[2], has
     * no pointer to this adapter/VF, and keeps source/pool/bridge/chain refs. */
    av_buffer_unref(&o->diagnostic_owner_ref);
    if(o->thread_ref)CloseHandle(o->thread_ref);free(o);*out=NULL;
}

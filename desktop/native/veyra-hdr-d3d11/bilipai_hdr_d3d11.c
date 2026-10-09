/* GPL-3.0-or-later. UNWIRED SOURCE PROTOTYPE; no SDK, player or GPU validation. */
#define COBJMACROS
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include "bilipai_hdr_d3d11.h"
#include "../veyra-hdr-video-sr/include/bilipai_hdr_video_sr_sources.h"
#include <stdlib.h>
#include <string.h>
#include <limits.h>

#define RELEASE(x) do { if(x) { IUnknown_Release((IUnknown *)(x)); (x)=NULL; } } while(0)
enum { HDR_BASE, SDR_PROXY, HDR_RESTORED, PQ_OUTPUT, TEXTURES };
struct bv_hdr11_pipeline {
    struct bv_hdr11_config config;
    ID3D11Device *device;
    ID3D11DeviceContext1 *context;
    ID3DDeviceContextState *isolated;
    ID3D11ComputeShader *shader[BV_HDR_STAGE_COUNT];
    struct bv_hdr11_frame *frame;
};
struct bv_hdr11_frame {
    struct bv_hdr11_pipeline *pipeline;
    LONG refs;
    uint32_t source_w,source_h,output_w,output_h;
    ID3D11Texture2D *texture[TEXTURES],*source,*sr;
    ID3D11ShaderResourceView *srv[TEXTURES],*source_srv,*sr_srv;
    ID3D11UnorderedAccessView *uav[TEXTURES];
    struct bv_hdr11_lease source_lease,sr_lease;
    ID3D11Fence *final_use;
    uint64_t final_value;
    int submitted,finished,failed,sealed;
};
static int lease_valid(struct bv_hdr11_lease l) { return l.value&&l.release; }
static void release_lease(struct bv_hdr11_lease *l) {
    void *v=l->value;void (*release)(void*)=l->release;
    l->value=NULL;l->release=NULL;if(v&&release)release(v);
}
static int same_device(ID3D11Device *a,ID3D11Device *b) {
    IUnknown *ia=NULL,*ib=NULL;
    HRESULT ha=ID3D11Device_QueryInterface(a,&IID_IUnknown,(void**)&ia);
    HRESULT hb=ID3D11Device_QueryInterface(b,&IID_IUnknown,(void**)&ib);
    int same=SUCCEEDED(ha)&&SUCCEEDED(hb)&&ia==ib;
    RELEASE(ia);RELEASE(ib);return same;
}
static int bounded_extents(uint32_t sw,uint32_t sh,uint32_t ow,uint32_t oh,uint64_t budget) {
    if(!sw||!sh||!ow||!oh||sw>D3D11_REQ_TEXTURE2D_U_OR_V_DIMENSION||
       sh>D3D11_REQ_TEXTURE2D_U_OR_V_DIMENSION||ow>D3D11_REQ_TEXTURE2D_U_OR_V_DIMENSION||
       oh>D3D11_REQ_TEXTURE2D_U_OR_V_DIMENSION)return 0;
    /* Four internal textures (12 bytes/pixel each source/output pair) plus
     * two exact external 4-byte/pixel textures. Allocation overhead is not
     * represented; the device may still refuse resource creation. */
    uint64_t source_pixels=UINT64_C(1)*sw*sh,output_pixels=UINT64_C(1)*ow*oh;
    /* Guard arithmetic independently of the current tighter SDK limits. */
    if(!budget||output_pixels>UINT64_MAX/16||source_pixels>UINT64_MAX/16-output_pixels)return 0;
    uint64_t payload=16*(source_pixels+output_pixels);
    return payload<=budget&&
        (sw+15)/16<=D3D11_CS_DISPATCH_MAX_THREAD_GROUPS_PER_DIMENSION&&
        (sh+15)/16<=D3D11_CS_DISPATCH_MAX_THREAD_GROUPS_PER_DIMENSION&&
        (ow+15)/16<=D3D11_CS_DISPATCH_MAX_THREAD_GROUPS_PER_DIMENSION&&
        (oh+15)/16<=D3D11_CS_DISPATCH_MAX_THREAD_GROUPS_PER_DIMENSION;
}
static HRESULT support(ID3D11Device *device,DXGI_FORMAT format,int writable) {
    UINT bits=0;HRESULT hr=ID3D11Device_CheckFormatSupport(device,format,&bits);
    UINT required=D3D11_FORMAT_SUPPORT_TEXTURE2D|D3D11_FORMAT_SUPPORT_SHADER_LOAD;
    if(writable)required|=D3D11_FORMAT_SUPPORT_TYPED_UNORDERED_ACCESS_VIEW;
    if(FAILED(hr))return hr;if((bits&required)!=required)return E_NOTIMPL;
    if(writable) {
        D3D11_FEATURE_DATA_FORMAT_SUPPORT2 second={format,0};
        hr=ID3D11Device_CheckFeatureSupport(device,D3D11_FEATURE_FORMAT_SUPPORT2,
            &second,sizeof(second));
        if(FAILED(hr))return hr;
        if(!(second.OutFormatSupport2&D3D11_FORMAT_SUPPORT2_UAV_TYPED_STORE))return E_NOTIMPL;
    }
    return S_OK;
}
static HRESULT external_texture(struct bv_hdr11_pipeline *p,ID3D11Texture2D *t,
    uint32_t w,uint32_t h,DXGI_FORMAT format) {
    if(!t)return E_INVALIDARG;
    ID3D11Device *device=NULL;ID3D11Texture2D_GetDevice(t,&device);
    int owned=device&&same_device(device,p->device);RELEASE(device);
    if(!owned)return E_INVALIDARG;
    D3D11_TEXTURE2D_DESC desc;ID3D11Texture2D_GetDesc(t,&desc);
    if(desc.Width!=w||desc.Height!=h||desc.MipLevels!=1||desc.ArraySize!=1||
       desc.SampleDesc.Count!=1||desc.SampleDesc.Quality||desc.Format!=format||
       desc.Usage!=D3D11_USAGE_DEFAULT||desc.CPUAccessFlags||
       !(desc.BindFlags&D3D11_BIND_SHADER_RESOURCE))return E_INVALIDARG;
    return support(p->device,format,0);
}
static HRESULT texture_create(struct bv_hdr11_frame *f,int index,uint32_t w,
    uint32_t h,DXGI_FORMAT format) {
    ID3D11Device *device=f->pipeline->device;
    D3D11_TEXTURE2D_DESC d={0};d.Width=w;d.Height=h;d.MipLevels=1;d.ArraySize=1;
    d.Format=format;d.SampleDesc.Count=1;d.Usage=D3D11_USAGE_DEFAULT;
    d.BindFlags=D3D11_BIND_SHADER_RESOURCE|D3D11_BIND_UNORDERED_ACCESS;
    HRESULT hr=ID3D11Device_CreateTexture2D(device,&d,NULL,&f->texture[index]);
    if(SUCCEEDED(hr))hr=ID3D11Device_CreateShaderResourceView(device,
        (ID3D11Resource*)f->texture[index],NULL,&f->srv[index]);
    if(SUCCEEDED(hr))hr=ID3D11Device_CreateUnorderedAccessView(device,
        (ID3D11Resource*)f->texture[index],NULL,&f->uav[index]);
    return hr;
}
static void frame_free(struct bv_hdr11_frame *f) {
    struct bv_hdr11_pipeline *p=f->pipeline;
    for(int i=0;i<TEXTURES;i++){RELEASE(f->srv[i]);RELEASE(f->uav[i]);RELEASE(f->texture[i]);}
    RELEASE(f->source_srv);RELEASE(f->sr_srv);RELEASE(f->source);RELEASE(f->sr);RELEASE(f->final_use);
    /* No context/actor gate held: callbacks may release real mp_image refs. */
    release_lease(&f->source_lease);release_lease(&f->sr_lease);
    if(p->frame==f)p->frame=NULL;free(f);
}
static void pipeline_free(struct bv_hdr11_pipeline *p) {
    for(int i=0;i<BV_HDR_STAGE_COUNT;i++)RELEASE(p->shader[i]);
    RELEASE(p->isolated);RELEASE(p->context);RELEASE(p->device);free(p);
}
HRESULT bv_hdr11_create(const struct bv_hdr11_config *c,struct bv_hdr11_pipeline **out) {
    if(!out)return E_POINTER;*out=NULL;
    if(!c||!c->device||!c->compile||!c->texture_payload_budget_bytes||
       (!c->context_lock!=!c->context_unlock))return E_INVALIDARG;
    D3D_FEATURE_LEVEL level=ID3D11Device_GetFeatureLevel(c->device);
    if(level<D3D_FEATURE_LEVEL_11_0)return E_NOTIMPL;
    DXGI_FORMAT formats[]={DXGI_FORMAT_R16G16B16A16_FLOAT,
        DXGI_FORMAT_R8G8B8A8_UNORM,DXGI_FORMAT_R10G10B10A2_UNORM};
    HRESULT hr;for(int i=0;i<3;i++){hr=support(c->device,formats[i],1);if(FAILED(hr))return hr;}
    struct bv_hdr11_pipeline *p=calloc(1,sizeof(*p));if(!p)return E_OUTOFMEMORY;
    p->config=*c;p->device=c->device;ID3D11Device_AddRef(p->device);
    ID3D11DeviceContext *context=NULL;ID3D11Device1 *device1=NULL;
    ID3D11Device_GetImmediateContext(p->device,&context);
    hr=context?ID3D11DeviceContext_QueryInterface(context,&IID_ID3D11DeviceContext1,
        (void**)&p->context):E_NOINTERFACE;RELEASE(context);
    if(SUCCEEDED(hr))hr=ID3D11Device_QueryInterface(p->device,&IID_ID3D11Device1,(void**)&device1);
    D3D_FEATURE_LEVEL chosen;
    if(SUCCEEDED(hr))hr=ID3D11Device1_CreateDeviceContextState(device1,0,&level,1,
        D3D11_SDK_VERSION,&IID_ID3D11Device,&chosen,&p->isolated);
    RELEASE(device1);if(FAILED(hr)){pipeline_free(p);return hr;}
    for(int i=0;i<BV_HDR_STAGE_COUNT;i++) {
        const struct bv_hdr_video_sr_source *source=&bv_hdr_video_sr_sources[i];
        if(strcmp(source->entry,"main")||strcmp(source->target,"cs_5_0")||
           source->srv_count!=(i==BV_HDR_RESTORE?3u:1u)||source->uav_count!=1||source->constant_bytes) {
            pipeline_free(p);return E_INVALIDARG;
        }
        ID3DBlob *code=NULL,*errors=NULL;
        hr=c->compile(source->source,source->source_bytes,"bilipai-hdr-inactive",NULL,NULL,
            source->entry,source->target,D3DCOMPILE_ENABLE_STRICTNESS,0,&code,&errors);
        RELEASE(errors);
        if(SUCCEEDED(hr)&&code)hr=ID3D11Device_CreateComputeShader(p->device,
            ID3D10Blob_GetBufferPointer(code),ID3D10Blob_GetBufferSize(code),NULL,&p->shader[i]);
        else if(SUCCEEDED(hr))hr=E_FAIL;
        RELEASE(code);if(FAILED(hr)){pipeline_free(p);return hr;}
    }
    *out=p;return S_OK;
}
HRESULT bv_hdr11_destroy(struct bv_hdr11_pipeline **handle) {
    if(!handle)return E_POINTER;struct bv_hdr11_pipeline *p=*handle;
    if(!p)return S_OK;if(p->frame)return E_PENDING;
    *handle=NULL;pipeline_free(p);return S_OK;
}
static void context_enter(struct bv_hdr11_pipeline *p,ID3DDeviceContextState **old) {
    if(p->config.context_lock)p->config.context_lock(p->config.context_lock_opaque);
    ID3D11DeviceContext1_SwapDeviceContextState(p->context,p->isolated,old);
    /* Only isolated state is cleared; the caller's state is restored below. */
    ID3D11DeviceContext1_ClearState(p->context);
}
static void context_leave(struct bv_hdr11_pipeline *p,ID3DDeviceContextState **old) {
    ID3D11DeviceContext1_SwapDeviceContextState(p->context,*old,NULL);RELEASE(*old);
    if(p->config.context_unlock)p->config.context_unlock(p->config.context_lock_opaque);
}
static void dispatch(struct bv_hdr11_frame *f,int stage,ID3D11ShaderResourceView **sources,
    UINT count,int target,uint32_t w,uint32_t h) {
    struct bv_hdr11_pipeline *p=f->pipeline;
    ID3D11ShaderResourceView *empty[3]={NULL,NULL,NULL};ID3D11UnorderedAccessView *none=NULL;
    ID3D11DeviceContext1_CSSetShaderResources(p->context,0,3,empty);
    ID3D11DeviceContext1_CSSetUnorderedAccessViews(p->context,0,1,&none,NULL);
    ID3D11DeviceContext1_CSSetShader(p->context,p->shader[stage],NULL,0);
    ID3D11DeviceContext1_CSSetShaderResources(p->context,0,count,sources);
    ID3D11DeviceContext1_CSSetUnorderedAccessViews(p->context,0,1,&f->uav[target],NULL);
    /* Every shader is 16x16x1 and lacks a Z guard: Dispatch Z is EXACTLY 1. */
    f->submitted=1;
    ID3D11DeviceContext1_Dispatch(p->context,(w+15)/16,(h+15)/16,1);
    ID3D11DeviceContext1_CSSetShaderResources(p->context,0,3,empty);
    ID3D11DeviceContext1_CSSetUnorderedAccessViews(p->context,0,1,&none,NULL);
    ID3D11DeviceContext1_CSSetShader(p->context,NULL,NULL,0);
}
HRESULT bv_hdr11_begin(struct bv_hdr11_pipeline *p,ID3D11Texture2D *source,
    uint32_t sw,uint32_t sh,uint32_t ow,uint32_t oh,
    struct bv_hdr11_lease lease,struct bv_hdr11_frame **out) {
    if(!out){if(lease_valid(lease))release_lease(&lease);return E_POINTER;}*out=NULL;
    if(!lease_valid(lease))return E_INVALIDARG;
    if(!p||p->frame||!bounded_extents(sw,sh,ow,oh,p->config.texture_payload_budget_bytes)) {
        release_lease(&lease);return p&&p->frame?E_PENDING:E_INVALIDARG;
    }
    HRESULT hr=external_texture(p,source,sw,sh,DXGI_FORMAT_R10G10B10A2_UNORM);
    if(FAILED(hr)){release_lease(&lease);return hr;}
    struct bv_hdr11_frame *f=calloc(1,sizeof(*f));if(!f){release_lease(&lease);return E_OUTOFMEMORY;}
    f->pipeline=p;f->refs=1;f->source_w=sw;f->source_h=sh;f->output_w=ow;f->output_h=oh;
    f->source_lease=lease;f->source=source;ID3D11Texture2D_AddRef(source);p->frame=f;
    hr=ID3D11Device_CreateShaderResourceView(p->device,(ID3D11Resource*)source,NULL,&f->source_srv);
    if(SUCCEEDED(hr))hr=texture_create(f,HDR_BASE,sw,sh,DXGI_FORMAT_R16G16B16A16_FLOAT);
    if(SUCCEEDED(hr))hr=texture_create(f,SDR_PROXY,sw,sh,DXGI_FORMAT_R8G8B8A8_UNORM);
    if(SUCCEEDED(hr))hr=texture_create(f,HDR_RESTORED,ow,oh,DXGI_FORMAT_R16G16B16A16_FLOAT);
    if(SUCCEEDED(hr))hr=texture_create(f,PQ_OUTPUT,ow,oh,DXGI_FORMAT_R10G10B10A2_UNORM);
    if(FAILED(hr)){frame_free(f);return hr;}
    ID3DDeviceContextState *old=NULL;context_enter(p,&old);
    dispatch(f,BV_HDR_DECODE_PQ,&f->source_srv,1,HDR_BASE,sw,sh);
    dispatch(f,BV_HDR_PROXY_ENCODE,&f->srv[HDR_BASE],1,SDR_PROXY,sw,sh);
    context_leave(p,&old);
    hr=ID3D11Device_GetDeviceRemovedReason(p->device);if(FAILED(hr))f->failed=1;
    /* Submitted work, including failed work, always escapes as retained state. */
    *out=f;return hr;
}
HRESULT bv_hdr11_proxy(struct bv_hdr11_frame *f,ID3D11Texture2D **texture,
    ID3D11ShaderResourceView **srv) {
    if(texture)*texture=NULL;if(srv)*srv=NULL;
    if(!f||!texture||!srv)return E_INVALIDARG;
    if(f->failed||f->sealed||!f->submitted)return E_PENDING;
    *texture=f->texture[SDR_PROXY];*srv=f->srv[SDR_PROXY];return S_OK;
}
HRESULT bv_hdr11_finish(struct bv_hdr11_frame *f,ID3D11Texture2D *sr,
    struct bv_hdr11_lease lease) {
    if(!lease_valid(lease))return E_INVALIDARG;
    if(!f||f->failed||f->sealed||f->finished) { release_lease(&lease);return E_INVALIDARG; }
    struct bv_hdr11_pipeline *p=f->pipeline;
    HRESULT hr=external_texture(p,sr,f->output_w,f->output_h,DXGI_FORMAT_R8G8B8A8_UNORM);
    if(SUCCEEDED(hr)&&(sr==f->source||sr==f->texture[SDR_PROXY]))hr=E_INVALIDARG;
    if(FAILED(hr)){f->failed=1;release_lease(&lease);return hr;}
    f->sr=sr;f->sr_lease=lease;ID3D11Texture2D_AddRef(sr);
    hr=ID3D11Device_CreateShaderResourceView(p->device,(ID3D11Resource*)sr,NULL,&f->sr_srv);
    if(FAILED(hr)){f->failed=1;return hr;}
    ID3DDeviceContextState *old=NULL;context_enter(p,&old);
    ID3D11ShaderResourceView *inputs[3]={f->srv[HDR_BASE],f->srv[SDR_PROXY],f->sr_srv};
    dispatch(f,BV_HDR_RESTORE,inputs,3,HDR_RESTORED,f->output_w,f->output_h);
    dispatch(f,BV_HDR_ENCODE_PQ,&f->srv[HDR_RESTORED],1,PQ_OUTPUT,f->output_w,f->output_h);
    context_leave(p,&old);f->finished=1;
    hr=ID3D11Device_GetDeviceRemovedReason(p->device);if(FAILED(hr))f->failed=1;
    return hr;
}
HRESULT bv_hdr11_output(struct bv_hdr11_frame *f,ID3D11Texture2D **texture,
    ID3D11ShaderResourceView **srv) {
    if(texture)*texture=NULL;if(srv)*srv=NULL;
    if(!f||!texture||!srv)return E_INVALIDARG;
    if(!f->finished||f->failed||f->sealed)return E_PENDING;
    *texture=f->texture[PQ_OUTPUT];*srv=f->srv[PQ_OUTPUT];return S_OK;
}
HRESULT bv_hdr11_retain(struct bv_hdr11_frame *f) {
    if(!f||f->refs==LONG_MAX)return E_INVALIDARG;InterlockedIncrement(&f->refs);return S_OK;
}
HRESULT bv_hdr11_seal(struct bv_hdr11_frame *f,IUnknown *unknown,uint64_t value) {
    if(!f||!unknown||!f->submitted||f->sealed||!value||value==UINT64_MAX)return E_INVALIDARG;
    ID3D11Fence *fence=NULL;HRESULT hr=IUnknown_QueryInterface(unknown,&IID_ID3D11Fence,(void**)&fence);
    if(FAILED(hr))return hr;
    ID3D11Device *device=NULL;ID3D11Fence_GetDevice(fence,&device);
    int same=device&&same_device(device,f->pipeline->device);RELEASE(device);
    uint64_t completed=same?ID3D11Fence_GetCompletedValue(fence):UINT64_MAX;
    if(!same||completed==UINT64_MAX||completed>=value){RELEASE(fence);return E_INVALIDARG;}
    f->final_use=fence;f->final_value=value;f->sealed=1;return S_OK;
}
HRESULT bv_hdr11_retire(struct bv_hdr11_frame **handle) {
    if(!handle)return E_POINTER;struct bv_hdr11_frame *f=*handle;if(!f)return S_OK;
    if(!f->sealed||!f->final_use)return S_FALSE;
    uint64_t actual=ID3D11Fence_GetCompletedValue(f->final_use);
    /* Device removal is not a release proof. Retain this bounded state. */
    if(actual==UINT64_MAX||FAILED(ID3D11Device_GetDeviceRemovedReason(f->pipeline->device)))return S_FALSE;
    if(actual<f->final_value)return S_FALSE;
    *handle=NULL;if(InterlockedDecrement(&f->refs)==0)frame_free(f);return S_OK;
}

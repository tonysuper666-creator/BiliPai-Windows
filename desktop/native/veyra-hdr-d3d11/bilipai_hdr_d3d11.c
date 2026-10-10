/* GPL-3.0-or-later. UNWIRED SOURCE PROTOTYPE; no SDK, player or GPU validation. */
#define COBJMACROS
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include "bilipai_hdr_d3d11.h"
#include "../veyra-hdr-video-sr/include/bilipai_hdr_video_sr_sources.h"
#include "../veyra-hdr-p010-input/bilipai_hdr_p010_sources.h"
#include <stdlib.h>
#include <string.h>
#include <limits.h>

/* Windows SDK D3D11_RESOURCE_MISC_NO_SHADER_ACCESS is 0x400000.
 * Older MinGW headers omit the enum; keep rejecting that ABI bit. */
#define BV_HDR11_RESOURCE_MISC_NO_SHADER_ACCESS 0x00400000u

#define RELEASE(x) do { if(x) { IUnknown_Release((IUnknown *)(x)); (x)=NULL; } } while(0)
enum { HDR_BASE, SDR_PROXY, HDR_RESTORED, PQ_OUTPUT, TEXTURES };
struct bv_hdr11_pipeline {
    struct bv_hdr11_config config;
    ID3D11Device *device;
    ID3D11DeviceContext1 *context;
    ID3DDeviceContextState *isolated;
    ID3D11ComputeShader *shader[BV_HDR_STAGE_COUNT];
    /* Lazily compiled only by the separate inactive P010 begin method. */
    ID3D11ComputeShader *p010_shader;
    struct bv_hdr11_frame *frame;
};
struct bv_hdr11_frame {
    struct bv_hdr11_pipeline *pipeline;
    LONG refs;
    uint32_t source_w,source_h,output_w,output_h;
    ID3D11Texture2D *texture[TEXTURES],*source,*sr;
    ID3D11ShaderResourceView *srv[TEXTURES],*source_srv,*sr_srv;
    ID3D11UnorderedAccessView *uav[TEXTURES];
    ID3D11Texture2D *p010_copy;
    ID3D11ShaderResourceView *p010_plane[2];
    ID3D11Buffer *p010_constants;
    ID3D11Fence *producer_ready;
    uint64_t producer_ready_value;
    struct bv_hdr11_lease source_lease,sr_lease;
    ID3D11Fence *final_use;
    uint64_t final_value;
    ID3D11Texture2D *pool_output;
    struct bv_hdr11_lease pool_lease;
    ID3D11Fence *restore_ready;
    int finish_pending;
    int submitted,finished,failed,sealed;
};
/* Separate CPU-prepared custody. Its opaque frame is not exposed until
 * lock-free close; no prepared pointer is a source/epoch/HDR permit. */
struct bv_hdr11_p010_prepared {
    struct bv_hdr11_frame *frame;
    struct bv_hdr11_p010_input input;
    D3D11_TEXTURE2D_DESC source_desc;
    ID3D11DeviceContext4 *context4;
    ID3D11ShaderResourceView1 *plane1[2];
    IUnknown *ready_identity;
    ID3DDeviceContextState *previous;
    DWORD owner_thread;
    UINT device_flags;
    int attempted;
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
    RELEASE(f->p010_plane[0]);RELEASE(f->p010_plane[1]);RELEASE(f->p010_copy);
    RELEASE(f->p010_constants);RELEASE(f->producer_ready);
    RELEASE(f->pool_output);RELEASE(f->restore_ready);
    /* No context/actor gate held: callbacks may release real mp_image refs. */
    release_lease(&f->source_lease);release_lease(&f->sr_lease);release_lease(&f->pool_lease);
    if(p->frame==f)p->frame=NULL;free(f);
}
static void pipeline_free(struct bv_hdr11_pipeline *p) {
    for(int i=0;i<BV_HDR_STAGE_COUNT;i++)RELEASE(p->shader[i]);
    RELEASE(p->p010_shader);
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
/* Shape/physical representation only. The inactive caller still owes real
 * source color/current-pixel/epoch authorization, never inferred here. */
static HRESULT p010_source(struct bv_hdr11_pipeline *p,
    const struct bv_hdr11_p010_input *in,D3D11_TEXTURE2D_DESC *d) {
    if(!in||!in->texture||!bv_hdr_p010_constants_valid(&in->constants)||
       in->crop_left||in->crop_top||in->crop_right||in->crop_bottom||
       !in->producer_ready_fence||!in->producer_ready_value||
       in->producer_ready_value==UINT64_MAX)return E_INVALIDARG;
    ID3D11Device *device=NULL;ID3D11Texture2D_GetDevice(in->texture,&device);
    int same=device&&same_device(device,p->device);RELEASE(device);
    if(!same)return E_INVALIDARG;
    ID3D11Texture2D_GetDesc(in->texture,d);
    /* Fence readiness is not shared keyed-mutex ownership; no mutex lane
     * exists in this method, so such resources must fail before submission. */
    UINT unsupported=D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX|
        D3D11_RESOURCE_MISC_RESTRICTED_CONTENT|
        D3D11_RESOURCE_MISC_RESTRICT_SHARED_RESOURCE|
        D3D11_RESOURCE_MISC_RESTRICT_SHARED_RESOURCE_DRIVER|
        D3D11_RESOURCE_MISC_GUARDED|D3D11_RESOURCE_MISC_TILE_POOL|
        D3D11_RESOURCE_MISC_TILED|D3D11_RESOURCE_MISC_HW_PROTECTED|
        BV_HDR11_RESOURCE_MISC_NO_SHADER_ACCESS;
    if(d->Format!=DXGI_FORMAT_P010||d->Width!=in->constants.allocation_width||
       d->Height!=in->constants.allocation_height||d->MipLevels!=1||
       !d->ArraySize||d->ArraySize>D3D11_REQ_TEXTURE2D_ARRAY_AXIS_DIMENSION||
       in->array_slice>=d->ArraySize||d->SampleDesc.Count!=1||d->SampleDesc.Quality||
       d->Usage!=D3D11_USAGE_DEFAULT||d->CPUAccessFlags||(d->MiscFlags&unsupported))
        return E_INVALIDARG;
    /* DECODER bind alone is accepted: source is COPY input, not an SRV. */
    UINT bits=0;HRESULT hr=ID3D11Device_CheckFormatSupport(p->device,DXGI_FORMAT_P010,&bits);
    if(FAILED(hr))return hr;
    if(!(bits&D3D11_FORMAT_SUPPORT_TEXTURE2D))return E_NOTIMPL;
    hr=support(p->device,DXGI_FORMAT_R16_UNORM,0);
    if(SUCCEEDED(hr))hr=support(p->device,DXGI_FORMAT_R16G16_UNORM,0);
    return hr;
}
static int payload_add(uint64_t *sum,uint64_t pixels,uint64_t bytes) {
    if(!bytes||pixels>(UINT64_MAX-*sum)/bytes)return 0;
    *sum+=pixels*bytes;return 1;
}
static int p010_payload(struct bv_hdr11_pipeline *p,const D3D11_TEXTURE2D_DESC *d,
    const struct bv_hdr_p010_constants *c,uint32_t ow,uint32_t oh) {
    if(!bounded_extents(c->visible_width,c->visible_height,ow,oh,UINT64_MAX))return 0;
    uint64_t total=0,visible=UINT64_C(1)*c->visible_width*c->visible_height;
    uint64_t allocation=UINT64_C(1)*d->Width*d->Height,output=UINT64_C(1)*ow*oh;
    /* Even P010 is 2 bytes/luma + 4 bytes/2x2 UV = 3 bytes/luma pixel.
     * Retaining ONE slice still pins the real entire decoder array resource.
     * Include that full array, owned single-slice copy, visible FP16+proxy,
     * output FP16+PQ plus the subsequently retained external SR texture.
     * This is texture payload, not decoder/driver allocation/VRAM headroom. */
    return payload_add(&total,allocation,3*(UINT64_C(1)+d->ArraySize))&&
        payload_add(&total,visible,12)&&payload_add(&total,output,16)&&
        total<=p->config.texture_payload_budget_bytes;
}
static HRESULT p010_shader(struct bv_hdr11_pipeline *p) {
    if(p->p010_shader)return S_OK;
    if(strcmp(BV_HDR_P010_ENTRY,"main")||strcmp(BV_HDR_P010_TARGET,"cs_5_0")||
       BV_HDR_P010_SRV_COUNT!=2||BV_HDR_P010_UAV_COUNT!=1||
       BV_HDR_P010_CONSTANT_BYTES!=sizeof(struct bv_hdr_p010_constants))return E_INVALIDARG;
    ID3DBlob *code=NULL,*errors=NULL;
    HRESULT hr=p->config.compile(bv_hdr_p010_to_base_hlsl,sizeof(bv_hdr_p010_to_base_hlsl)-1,
        "bilipai-hdr-p010-inactive",NULL,NULL,BV_HDR_P010_ENTRY,BV_HDR_P010_TARGET,
        D3DCOMPILE_ENABLE_STRICTNESS,0,&code,&errors);
    RELEASE(errors);
    if(SUCCEEDED(hr)&&code)hr=ID3D11Device_CreateComputeShader(p->device,
        ID3D10Blob_GetBufferPointer(code),ID3D10Blob_GetBufferSize(code),NULL,&p->p010_shader);
    else if(SUCCEEDED(hr))hr=E_FAIL;
    RELEASE(code);return hr;
}
static HRESULT p010_copy_create(struct bv_hdr11_frame *f,ID3D11Device3 *device3,
    const D3D11_TEXTURE2D_DESC *source,const struct bv_hdr_p010_constants *c) {
    D3D11_TEXTURE2D_DESC d={0};d.Width=source->Width;d.Height=source->Height;
    d.MipLevels=1;d.ArraySize=1;d.Format=DXGI_FORMAT_P010;d.SampleDesc.Count=1;
    d.Usage=D3D11_USAGE_DEFAULT;d.BindFlags=D3D11_BIND_SHADER_RESOURCE;
    HRESULT hr=ID3D11Device_CreateTexture2D(f->pipeline->device,&d,NULL,&f->p010_copy);
    for(UINT plane=0;SUCCEEDED(hr)&&plane<2;plane++) {
        D3D11_SHADER_RESOURCE_VIEW_DESC1 view={0};
        view.Format=plane?DXGI_FORMAT_R16G16_UNORM:DXGI_FORMAT_R16_UNORM;
        view.ViewDimension=D3D11_SRV_DIMENSION_TEXTURE2DARRAY;
        view.Texture2DArray.MostDetailedMip=0;view.Texture2DArray.MipLevels=1;
        view.Texture2DArray.FirstArraySlice=0;view.Texture2DArray.ArraySize=1;
        view.Texture2DArray.PlaneSlice=plane;
        ID3D11ShaderResourceView1 *actual=NULL;
        hr=ID3D11Device3_CreateShaderResourceView1(device3,(ID3D11Resource*)f->p010_copy,
            &view,&actual);
        if(SUCCEEDED(hr)&&actual)hr=ID3D11ShaderResourceView1_QueryInterface(actual,
            &IID_ID3D11ShaderResourceView,(void**)&f->p010_plane[plane]);
        else if(SUCCEEDED(hr))hr=E_FAIL;
        RELEASE(actual);
    }
    D3D11_BUFFER_DESC b={0};b.ByteWidth=sizeof(*c);b.Usage=D3D11_USAGE_IMMUTABLE;
    b.BindFlags=D3D11_BIND_CONSTANT_BUFFER;
    D3D11_SUBRESOURCE_DATA data={0};data.pSysMem=c;
    if(SUCCEEDED(hr))hr=ID3D11Device_CreateBuffer(f->pipeline->device,&b,&data,&f->p010_constants);
    return hr;
}
static void p010_dispatch(struct bv_hdr11_frame *f) {
    struct bv_hdr11_pipeline *p=f->pipeline;
    ID3D11ShaderResourceView *empty[3]={NULL,NULL,NULL};
    ID3D11UnorderedAccessView *none=NULL;ID3D11Buffer *no_constant=NULL;
    ID3D11DeviceContext1_CSSetShaderResources(p->context,0,3,empty);
    ID3D11DeviceContext1_CSSetUnorderedAccessViews(p->context,0,1,&none,NULL);
    ID3D11DeviceContext1_CSSetConstantBuffers(p->context,0,1,&f->p010_constants);
    ID3D11DeviceContext1_CSSetShader(p->context,p->p010_shader,NULL,0);
    ID3D11DeviceContext1_CSSetShaderResources(p->context,0,2,f->p010_plane);
    ID3D11DeviceContext1_CSSetUnorderedAccessViews(p->context,0,1,&f->uav[HDR_BASE],NULL);
    ID3D11DeviceContext1_Dispatch(p->context,(f->source_w+15)/16,(f->source_h+15)/16,1);
    ID3D11DeviceContext1_CSSetShaderResources(p->context,0,3,empty);
    ID3D11DeviceContext1_CSSetUnorderedAccessViews(p->context,0,1,&none,NULL);
    ID3D11DeviceContext1_CSSetConstantBuffers(p->context,0,1,&no_constant);
    ID3D11DeviceContext1_CSSetShader(p->context,NULL,NULL,0);
}
HRESULT bv_hdr11_begin_p010(struct bv_hdr11_pipeline *p,
    const struct bv_hdr11_p010_input *input,uint32_t ow,uint32_t oh,
    struct bv_hdr11_lease lease,struct bv_hdr11_frame **out) {
    if(!out){if(lease_valid(lease))release_lease(&lease);return E_POINTER;}*out=NULL;
    if(!lease_valid(lease))return E_INVALIDARG;
    if(!p||p->frame||!input){release_lease(&lease);return p&&p->frame?E_PENDING:E_INVALIDARG;}
    /* Explicit caller-owned values copied once; no cache/firstframe metadata. */
    const struct bv_hdr11_p010_input in=*input;
    D3D11_TEXTURE2D_DESC d={0};HRESULT hr=p010_source(p,&in,&d);
    if(SUCCEEDED(hr)&&!p010_payload(p,&d,&in.constants,ow,oh))hr=E_INVALIDARG;
    if(FAILED(hr)){release_lease(&lease);return hr;}
    ID3D11Device3 *device3=NULL;ID3D11DeviceContext4 *context4=NULL;ID3D11Fence *ready=NULL;
    hr=ID3D11Device_QueryInterface(p->device,&IID_ID3D11Device3,(void**)&device3);
    if(SUCCEEDED(hr))hr=ID3D11DeviceContext1_QueryInterface(p->context,&IID_ID3D11DeviceContext4,
        (void**)&context4);
    if(SUCCEEDED(hr))hr=IUnknown_QueryInterface(in.producer_ready_fence,&IID_ID3D11Fence,
        (void**)&ready);
    if(SUCCEEDED(hr)) {
        ID3D11Device *device=NULL;ID3D11Fence_GetDevice(ready,&device);
        int same=device&&same_device(device,p->device);RELEASE(device);
        if(!same||ID3D11Fence_GetCompletedValue(ready)==UINT64_MAX)hr=E_INVALIDARG;
    }
    if(SUCCEEDED(hr))hr=ID3D11Device_GetDeviceRemovedReason(p->device);
    if(SUCCEEDED(hr))hr=p010_shader(p);
    if(FAILED(hr)){RELEASE(ready);RELEASE(context4);RELEASE(device3);release_lease(&lease);return hr;}
    struct bv_hdr11_frame *f=calloc(1,sizeof(*f));
    if(!f){RELEASE(ready);RELEASE(context4);RELEASE(device3);release_lease(&lease);return E_OUTOFMEMORY;}
    f->pipeline=p;f->refs=1;f->source_w=in.constants.visible_width;
    f->source_h=in.constants.visible_height;f->output_w=ow;f->output_h=oh;
    f->source_lease=lease;f->source=in.texture;ID3D11Texture2D_AddRef(f->source);
    f->producer_ready=ready;ready=NULL;f->producer_ready_value=in.producer_ready_value;p->frame=f;
    hr=p010_copy_create(f,device3,&d,&in.constants);RELEASE(device3);
    if(SUCCEEDED(hr))hr=texture_create(f,HDR_BASE,f->source_w,f->source_h,DXGI_FORMAT_R16G16B16A16_FLOAT);
    if(SUCCEEDED(hr))hr=texture_create(f,SDR_PROXY,f->source_w,f->source_h,DXGI_FORMAT_R8G8B8A8_UNORM);
    if(SUCCEEDED(hr))hr=texture_create(f,HDR_RESTORED,ow,oh,DXGI_FORMAT_R16G16B16A16_FLOAT);
    if(SUCCEEDED(hr))hr=texture_create(f,PQ_OUTPUT,ow,oh,DXGI_FORMAT_R10G10B10A2_UNORM);
    if(FAILED(hr)){RELEASE(context4);frame_free(f);return hr;}
    ID3DDeviceContextState *old=NULL;context_enter(p,&old);
    /* Conservatively retain once the first queue operation is attempted,
     * even if Wait fails; never free a possibly submitted partial frame. */
    f->submitted=1;
    hr=ID3D11DeviceContext4_Wait(context4,f->producer_ready,f->producer_ready_value);
    if(SUCCEEDED(hr)) {
        /* Whole padded slice preserves both P010 planes. With mipcount1,
         * mip0 + array_slice*mipcount is exactly array_slice (SDK helper is
         * C++ only). Destination has one slice, subresource0, same extents.
         * There is no source SRV, visible-box copy or partial-plane copy. */
        ID3D11DeviceContext1_CopySubresourceRegion(p->context,(ID3D11Resource*)f->p010_copy,
            0,0,0,0,(ID3D11Resource*)f->source,in.array_slice,NULL);
        hr=ID3D11Device_GetDeviceRemovedReason(p->device);
    }
    if(SUCCEEDED(hr)) {
        p010_dispatch(f);
        dispatch(f,BV_HDR_PROXY_ENCODE,&f->srv[HDR_BASE],1,SDR_PROXY,f->source_w,f->source_h);
        hr=ID3D11Device_GetDeviceRemovedReason(p->device);
    }
    context_leave(p,&old);RELEASE(context4);
    if(FAILED(hr))f->failed=1;
    *out=f;return hr;
}
/* Exact integer fields; do not memcmp SDK structs with padding. */
static int p010_desc_equal(const D3D11_TEXTURE2D_DESC *a,const D3D11_TEXTURE2D_DESC *b) {
    return a->Width==b->Width&&a->Height==b->Height&&a->MipLevels==b->MipLevels&&
        a->ArraySize==b->ArraySize&&a->Format==b->Format&&
        a->SampleDesc.Count==b->SampleDesc.Count&&a->SampleDesc.Quality==b->SampleDesc.Quality&&
        a->Usage==b->Usage&&a->BindFlags==b->BindFlags&&
        a->CPUAccessFlags==b->CPUAccessFlags&&a->MiscFlags==b->MiscFlags;
}
static int p010_input_equal(const struct bv_hdr11_p010_input *a,
    const struct bv_hdr11_p010_input *b) {
    return a->texture==b->texture&&a->array_slice==b->array_slice&&
        a->crop_left==b->crop_left&&a->crop_top==b->crop_top&&
        a->crop_right==b->crop_right&&a->crop_bottom==b->crop_bottom&&
        a->producer_ready_fence==b->producer_ready_fence&&
        a->producer_ready_value==b->producer_ready_value&&
        a->constants.visible_width==b->constants.visible_width&&
        a->constants.visible_height==b->constants.visible_height&&
        a->constants.allocation_width==b->constants.allocation_width&&
        a->constants.allocation_height==b->constants.allocation_height&&
        a->constants.raw_range==b->constants.raw_range&&
        a->constants.raw_chroma_location==b->constants.raw_chroma_location&&
        a->constants.reserved0==b->constants.reserved0&&a->constants.reserved1==b->constants.reserved1;
}
/* No immediate-context state read/write, lock callback or GPU command. The
 * existing legal host owner invokes prepare/close outside decoder/context locks.
 * A SINGLETHREADED device is rejected; checking its flags does not license an
 * otherwise unknown foreign thread to access that device in the first place. */
HRESULT bv_hdr11_prepare_p010(struct bv_hdr11_pipeline *p,
    const struct bv_hdr11_p010_input *input,uint32_t ow,uint32_t oh,
    struct bv_hdr11_lease lease,struct bv_hdr11_p010_prepared **out) {
    if(!out){if(lease_valid(lease))release_lease(&lease);return E_POINTER;}*out=NULL;
    if(!lease_valid(lease))return E_INVALIDARG;
    if(!p||p->frame||!input){release_lease(&lease);return p&&p->frame?E_PENDING:E_INVALIDARG;}
    UINT flags=ID3D11Device_GetCreationFlags(p->device);
    if(flags&D3D11_CREATE_DEVICE_SINGLETHREADED){release_lease(&lease);return E_NOTIMPL;}
    const struct bv_hdr11_p010_input in=*input;
    D3D11_TEXTURE2D_DESC d={0};HRESULT hr=p010_source(p,&in,&d);
    if(SUCCEEDED(hr)&&!p010_payload(p,&d,&in.constants,ow,oh))hr=E_INVALIDARG;
    if(FAILED(hr)){release_lease(&lease);return hr;}
    ID3D11Device3 *device3=NULL;ID3D11DeviceContext4 *context4=NULL;ID3D11Fence *ready=NULL;
    hr=ID3D11Device_QueryInterface(p->device,&IID_ID3D11Device3,(void**)&device3);
    /* IUnknown QI only: no immediate-context command/state inspection. */
    if(SUCCEEDED(hr))hr=IUnknown_QueryInterface((IUnknown*)p->context,
        &IID_ID3D11DeviceContext4,(void**)&context4);
    if(SUCCEEDED(hr))hr=IUnknown_QueryInterface(in.producer_ready_fence,&IID_ID3D11Fence,(void**)&ready);
    if(SUCCEEDED(hr)) {
        ID3D11Device *device=NULL;ID3D11Fence_GetDevice(ready,&device);
        int same=device&&same_device(device,p->device);RELEASE(device);
        if(!same||ID3D11Fence_GetCompletedValue(ready)==UINT64_MAX)hr=E_INVALIDARG;
    }
    if(SUCCEEDED(hr))hr=ID3D11Device_GetDeviceRemovedReason(p->device);
    if(SUCCEEDED(hr))hr=p010_shader(p);
    if(FAILED(hr)){RELEASE(ready);RELEASE(context4);RELEASE(device3);release_lease(&lease);return hr;}
    struct bv_hdr11_p010_prepared *s=calloc(1,sizeof(*s));
    struct bv_hdr11_frame *f=s?calloc(1,sizeof(*f)):NULL;
    if(!f){free(s);RELEASE(ready);RELEASE(context4);RELEASE(device3);release_lease(&lease);return E_OUTOFMEMORY;}
    s->frame=f;s->input=in;s->source_desc=d;s->context4=context4;context4=NULL;
    s->owner_thread=GetCurrentThreadId();s->device_flags=flags;
    s->ready_identity=in.producer_ready_fence;IUnknown_AddRef(s->ready_identity);
    f->pipeline=p;f->refs=1;f->source_w=in.constants.visible_width;
    f->source_h=in.constants.visible_height;f->output_w=ow;f->output_h=oh;
    f->source_lease=lease;f->source=in.texture;ID3D11Texture2D_AddRef(f->source);
    f->producer_ready=ready;ready=NULL;f->producer_ready_value=in.producer_ready_value;p->frame=f;
    hr=p010_copy_create(f,device3,&d,&in.constants);RELEASE(device3);
    for(int i=0;SUCCEEDED(hr)&&i<2;i++)
        hr=ID3D11ShaderResourceView_QueryInterface(f->p010_plane[i],
            &IID_ID3D11ShaderResourceView1,(void**)&s->plane1[i]);
    if(SUCCEEDED(hr))hr=texture_create(f,HDR_BASE,f->source_w,f->source_h,DXGI_FORMAT_R16G16B16A16_FLOAT);
    if(SUCCEEDED(hr))hr=texture_create(f,SDR_PROXY,f->source_w,f->source_h,DXGI_FORMAT_R8G8B8A8_UNORM);
    if(SUCCEEDED(hr))hr=texture_create(f,HDR_RESTORED,ow,oh,DXGI_FORMAT_R16G16B16A16_FLOAT);
    if(SUCCEEDED(hr))hr=texture_create(f,PQ_OUTPUT,ow,oh,DXGI_FORMAT_R10G10B10A2_UNORM);
    if(FAILED(hr)) {
        RELEASE(s->plane1[0]);RELEASE(s->plane1[1]);RELEASE(s->context4);RELEASE(s->ready_identity);
        free(s);frame_free(f);return hr;
    }
    *out=s;return S_OK;
}
/* Resource descriptors are immutable, but re-read the actual held objects.
 * This helper makes no explicit QI/AddRef/Release, allocation, compiler or
 * caller callback. Driver/COM implicit allocation/ref work is not guaranteed. */
static int p010_prepared_resources(struct bv_hdr11_p010_prepared *s) {
    struct bv_hdr11_frame *f=s->frame;struct bv_hdr11_pipeline *p=f->pipeline;
    if(p->frame!=f||f->failed||f->submitted||f->finished||f->sealed||
        !lease_valid(f->source_lease)||!s->context4||!s->ready_identity||!p->isolated||
        !p->p010_shader||!p->shader[BV_HDR_PROXY_ENCODE]||
        f->source!=s->input.texture||!f->producer_ready||
        f->producer_ready_value!=s->input.producer_ready_value||
        !f->p010_copy||!f->p010_constants)return 0;
    D3D11_TEXTURE2D_DESC d={0};ID3D11Texture2D_GetDesc(f->source,&d);
    if(!p010_desc_equal(&d,&s->source_desc))return 0;
    ID3D11Texture2D_GetDesc(f->p010_copy,&d);
    if(d.Width!=s->input.constants.allocation_width||d.Height!=s->input.constants.allocation_height||
        d.MipLevels!=1||d.ArraySize!=1||d.Format!=DXGI_FORMAT_P010||
        d.SampleDesc.Count!=1||d.SampleDesc.Quality||d.Usage!=D3D11_USAGE_DEFAULT||
        d.BindFlags!=D3D11_BIND_SHADER_RESOURCE||d.CPUAccessFlags||d.MiscFlags)return 0;
    for(UINT i=0;i<2;i++) {
        if(!f->p010_plane[i]||!s->plane1[i])return 0;
        D3D11_SHADER_RESOURCE_VIEW_DESC1 v={0};ID3D11ShaderResourceView1_GetDesc1(s->plane1[i],&v);
        if(v.Format!=(i?DXGI_FORMAT_R16G16_UNORM:DXGI_FORMAT_R16_UNORM)||
            v.ViewDimension!=D3D11_SRV_DIMENSION_TEXTURE2DARRAY||
            v.Texture2DArray.MostDetailedMip||v.Texture2DArray.MipLevels!=1||
            v.Texture2DArray.FirstArraySlice||v.Texture2DArray.ArraySize!=1||
            v.Texture2DArray.PlaneSlice!=i)return 0;
    }
    const DXGI_FORMAT formats[TEXTURES]={DXGI_FORMAT_R16G16B16A16_FLOAT,
        DXGI_FORMAT_R8G8B8A8_UNORM,DXGI_FORMAT_R16G16B16A16_FLOAT,DXGI_FORMAT_R10G10B10A2_UNORM};
    for(int i=0;i<TEXTURES;i++) {
        if(!f->texture[i]||!f->srv[i]||!f->uav[i])return 0;
        ID3D11Texture2D_GetDesc(f->texture[i],&d);
        if(d.Width!=(i<HDR_RESTORED?f->source_w:f->output_w)||
            d.Height!=(i<HDR_RESTORED?f->source_h:f->output_h)||d.MipLevels!=1||d.ArraySize!=1||
            d.Format!=formats[i]||d.SampleDesc.Count!=1||d.SampleDesc.Quality||
            d.Usage!=D3D11_USAGE_DEFAULT||d.BindFlags!=(D3D11_BIND_SHADER_RESOURCE|D3D11_BIND_UNORDERED_ACCESS)||
            d.CPUAccessFlags||d.MiscFlags)return 0;
    }
    D3D11_BUFFER_DESC b={0};ID3D11Buffer_GetDesc(f->p010_constants,&b);
    return b.ByteWidth==sizeof(struct bv_hdr_p010_constants)&&b.Usage==D3D11_USAGE_IMMUTABLE&&
        b.BindFlags==D3D11_BIND_CONSTANT_BUFFER&&!b.CPUAccessFlags&&!b.MiscFlags&&!b.StructureByteStride;
}
/* Requires the future independent decoder/GPU owner scope AND original context
 * exclusion, on the same legal host thread. Never invokes configured locks or
 * release callbacks. Exact tuple match is storage shape, NOT an active permit. */
HRESULT bv_hdr11_submit_p010(struct bv_hdr11_p010_prepared *s,
    const struct bv_hdr11_p010_input *input) {
    if(!s||!input||s->owner_thread!=GetCurrentThreadId()||s->attempted)return E_INVALIDARG;
    struct bv_hdr11_frame *f=s->frame;struct bv_hdr11_pipeline *p=f->pipeline;
    const struct bv_hdr11_p010_input in=*input;s->attempted=1;
    if(!p010_input_equal(&in,&s->input)||!p010_prepared_resources(s)||
        ID3D11Device_GetCreationFlags(p->device)!=s->device_flags||
        (s->device_flags&D3D11_CREATE_DEVICE_SINGLETHREADED)||
        ID3D11DeviceContext1_GetType(p->context)!=D3D11_DEVICE_CONTEXT_IMMEDIATE||
        ID3D11Fence_GetCompletedValue(f->producer_ready)==UINT64_MAX) {
        f->failed=1;return E_INVALIDARG;
    }
    HRESULT hr=ID3D11Device_GetDeviceRemovedReason(p->device);
    if(FAILED(hr)){f->failed=1;return hr;}
    /* Conservatively retain even the state-swap attempt; certainly before Wait.
     * Previous state's returned COM ref is held until lock-free close. */
    f->submitted=1;
    ID3D11DeviceContext1_SwapDeviceContextState(p->context,p->isolated,&s->previous);
    if(!s->previous){f->failed=1;return E_UNEXPECTED;}
    ID3D11DeviceContext1_ClearState(p->context);
    hr=ID3D11DeviceContext4_Wait(s->context4,f->producer_ready,f->producer_ready_value);
    if(SUCCEEDED(hr)) {
        ID3D11DeviceContext1_CopySubresourceRegion(p->context,(ID3D11Resource*)f->p010_copy,
            0,0,0,0,(ID3D11Resource*)f->source,in.array_slice,NULL);
        hr=ID3D11Device_GetDeviceRemovedReason(p->device);
    }
    if(SUCCEEDED(hr)) {
        p010_dispatch(f);
        dispatch(f,BV_HDR_PROXY_ENCODE,&f->srv[HDR_BASE],1,SDR_PROXY,f->source_w,f->source_h);
        hr=ID3D11Device_GetDeviceRemovedReason(p->device);
    }
    ID3D11DeviceContext1_SwapDeviceContextState(p->context,s->previous,NULL);
    if(FAILED(hr))f->failed=1;
    return hr; /* queued only; no epoch-after-unlock, GPU/HDR/display proof */
}
/* Scope and context exclusion have ended. Unsubmitted frames may be freed;
 * any attempted queue/state use transfers the unchanged retained frame. */
HRESULT bv_hdr11_close_p010_prepared(struct bv_hdr11_p010_prepared **handle,
    struct bv_hdr11_frame **out) {
    if(!handle||!out)return E_POINTER;*out=NULL;
    struct bv_hdr11_p010_prepared *s=*handle;if(!s)return S_OK;
    if(s->owner_thread!=GetCurrentThreadId())return E_INVALIDARG;
    struct bv_hdr11_frame *f=s->frame;int submitted=f->submitted;
    *handle=NULL;
    RELEASE(s->previous);RELEASE(s->plane1[0]);RELEASE(s->plane1[1]);
    RELEASE(s->context4);RELEASE(s->ready_identity);free(s);
    if(submitted)*out=f;else frame_free(f);
    return S_OK;
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
/* Separate three-stage restore path. CPU preparation and cleanup never run
 * under decoder dispatch or original immediate-context exclusion. */
struct bv_hdr11_finish_prepared {
    struct bv_hdr11_frame *frame;
    ID3D11Texture2D *sr,*pool;
    ID3D11ShaderResourceView *sr_srv;
    ID3D11DeviceContext4 *context4;
    ID3D11Fence *sr_ready;
    struct bv_hdr11_lease sr_lease,pool_lease;
    ID3DDeviceContextState *previous;
    uint64_t ready_value;
    uint32_t pool_slice;
    DWORD owner_thread;
    int attempted;
};
static int finish_same_resource(ID3D11Texture2D *a,ID3D11Texture2D *b) {
    if(!a||!b)return 0;
    IUnknown *ia=NULL,*ib=NULL;
    HRESULT ha=IUnknown_QueryInterface((IUnknown*)a,&IID_IUnknown,(void**)&ia);
    HRESULT hb=IUnknown_QueryInterface((IUnknown*)b,&IID_IUnknown,(void**)&ib);
    int same=SUCCEEDED(ha)&&SUCCEEDED(hb)&&ia==ib;RELEASE(ia);RELEASE(ib);return same;
}
static void finish_prepared_free(struct bv_hdr11_finish_prepared *s) {
    RELEASE(s->previous);RELEASE(s->sr_srv);RELEASE(s->sr);RELEASE(s->pool);
    RELEASE(s->context4);RELEASE(s->sr_ready);
    release_lease(&s->sr_lease);release_lease(&s->pool_lease);free(s);
}
static int finish_payload(struct bv_hdr11_frame *f,const D3D11_TEXTURE2D_DESC *pool) {
    uint64_t total=0,source=UINT64_C(1)*f->source_w*f->source_h;
    uint64_t output=UINT64_C(1)*f->output_w*f->output_h;
    D3D11_TEXTURE2D_DESC d={0};ID3D11Texture2D_GetDesc(f->source,&d);
    if(f->p010_copy) {
        uint64_t allocation=UINT64_C(1)*d.Width*d.Height;
        if(!payload_add(&total,allocation,3*(UINT64_C(1)+d.ArraySize))||
           !payload_add(&total,source,12))return 0;
    } else if(!payload_add(&total,source,16))return 0;
    /* Output base/PQ + external SR are16 bytes/pixel; holding one pool slice
     * pins its actual WHOLE resource. Include that extra array payload too.
     * This bound is estimated texture bytes, NOT current free VRAM. */
    return payload_add(&total,output,16)&&
        payload_add(&total,output,4*UINT64_C(1)*pool->ArraySize)&&
        total<=f->pipeline->config.texture_payload_budget_bytes;
}
HRESULT bv_hdr11_prepare_finish(struct bv_hdr11_frame *f,
    const struct bv_hdr11_finish_input *in,struct bv_hdr11_lease sr_lease,
    struct bv_hdr11_lease pool_lease,struct bv_hdr11_finish_prepared **out) {
    /* Malformed lease is not consumed. Each well-formed lease is consumed on
     * every return; no caller may destroy its source/loan owner in callbacks. */
    if(!lease_valid(sr_lease)||!lease_valid(pool_lease)) {
        if(lease_valid(sr_lease))release_lease(&sr_lease);
        if(lease_valid(pool_lease))release_lease(&pool_lease);
        return E_INVALIDARG;
    }
    if(!out||*out||!f||f->failed||f->sealed||!f->submitted||f->finished||
       f->finish_pending||!in||!in->sr_output||!in->pool_output||
       !in->sr_ready_fence||!in->sr_ready_value||in->sr_ready_value==UINT64_MAX) {
        release_lease(&sr_lease);release_lease(&pool_lease);return E_INVALIDARG;
    }
    struct bv_hdr11_pipeline *p=f->pipeline;
    if(p->config.context_lock||p->config.context_unlock||
       (ID3D11Device_GetCreationFlags(p->device)&D3D11_CREATE_DEVICE_SINGLETHREADED)) {
        release_lease(&sr_lease);release_lease(&pool_lease);return E_NOTIMPL;
    }
    HRESULT hr=external_texture(p,in->sr_output,f->output_w,f->output_h,
                               DXGI_FORMAT_R8G8B8A8_UNORM);
    ID3D11Device *pool_device=NULL;D3D11_TEXTURE2D_DESC d={0};
    ID3D11Texture2D_GetDevice(in->pool_output,&pool_device);
    int same=pool_device&&same_device(pool_device,p->device);RELEASE(pool_device);
    ID3D11Texture2D_GetDesc(in->pool_output,&d);
    if(!same||d.Format!=DXGI_FORMAT_R10G10B10A2_UNORM||d.MipLevels!=1||
       !d.ArraySize||in->pool_array_slice>=d.ArraySize||d.Width!=f->output_w||
       d.Height!=f->output_h||d.SampleDesc.Count!=1||d.SampleDesc.Quality||
       d.Usage!=D3D11_USAGE_DEFAULT||d.CPUAccessFlags||
       !(d.BindFlags&D3D11_BIND_SHADER_RESOURCE)||
       (d.MiscFlags&D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX))hr=E_INVALIDARG;
    if(finish_same_resource(in->sr_output,in->pool_output)||
       finish_same_resource(in->sr_output,f->source)||
       finish_same_resource(in->sr_output,f->p010_copy)||
       finish_same_resource(in->pool_output,f->source)||
       finish_same_resource(in->pool_output,f->p010_copy))hr=E_INVALIDARG;
    for(int n=0;n<TEXTURES;n++)
        if(finish_same_resource(in->sr_output,f->texture[n])||
           finish_same_resource(in->pool_output,f->texture[n]))hr=E_INVALIDARG;
    if(SUCCEEDED(hr)&&!finish_payload(f,&d))hr=E_OUTOFMEMORY;
    if(FAILED(hr)){release_lease(&sr_lease);release_lease(&pool_lease);return hr;}
    struct bv_hdr11_finish_prepared *s=calloc(1,sizeof(*s));
    if(!s){release_lease(&sr_lease);release_lease(&pool_lease);return E_OUTOFMEMORY;}
    s->frame=f;s->owner_thread=GetCurrentThreadId();s->ready_value=in->sr_ready_value;
    s->pool_slice=in->pool_array_slice;s->sr_lease=sr_lease;s->pool_lease=pool_lease;
    s->sr=in->sr_output;s->pool=in->pool_output;
    ID3D11Texture2D_AddRef(s->sr);ID3D11Texture2D_AddRef(s->pool);
    hr=IUnknown_QueryInterface((IUnknown*)p->context,&IID_ID3D11DeviceContext4,
                              (void**)&s->context4);
    if(SUCCEEDED(hr))hr=IUnknown_QueryInterface(in->sr_ready_fence,&IID_ID3D11Fence,
                                              (void**)&s->sr_ready);
    if(SUCCEEDED(hr)) {
        ID3D11Device *device=NULL;ID3D11Fence_GetDevice(s->sr_ready,&device);
        same=device&&same_device(device,p->device);RELEASE(device);
        if(!same||ID3D11Fence_GetCompletedValue(s->sr_ready)==UINT64_MAX)hr=E_INVALIDARG;
    }
    if(SUCCEEDED(hr))hr=ID3D11Device_CreateShaderResourceView(p->device,
        (ID3D11Resource*)s->sr,NULL,&s->sr_srv);
    if(SUCCEEDED(hr))hr=ID3D11Device_GetDeviceRemovedReason(p->device);
    if(FAILED(hr)){finish_prepared_free(s);return hr;}
    hr=bv_hdr11_retain(f);
    if(FAILED(hr)){finish_prepared_free(s);return hr;}
    f->finish_pending=1;*out=s;return S_OK; // NO context command/GPU use
}
HRESULT bv_hdr11_submit_restore(struct bv_hdr11_finish_prepared *s) {
    if(!s||s->owner_thread!=GetCurrentThreadId()||s->attempted)return E_INVALIDARG;
    struct bv_hdr11_frame *f=s->frame;struct bv_hdr11_pipeline *p=f->pipeline;
    if(!f->finish_pending||f->failed||f->sealed||f->finished||!s->sr_srv||
       !s->sr_ready||!s->context4||!s->pool)return E_INVALIDARG;
    s->attempted=1;f->submitted=1; // sticky BEFORE even state/queue attempt
    ID3D11DeviceContext1_SwapDeviceContextState(p->context,p->isolated,&s->previous);
    if(!s->previous){f->failed=1;return E_UNEXPECTED;} // never restore NULL
    ID3D11DeviceContext1_ClearState(p->context);
    HRESULT hr=ID3D11DeviceContext4_Wait(s->context4,s->sr_ready,s->ready_value);
    if(SUCCEEDED(hr)) {
        ID3D11ShaderResourceView *inputs[3]={f->srv[HDR_BASE],f->srv[SDR_PROXY],s->sr_srv};
        dispatch(f,BV_HDR_RESTORE,inputs,3,HDR_RESTORED,f->output_w,f->output_h);
        dispatch(f,BV_HDR_ENCODE_PQ,&f->srv[HDR_RESTORED],1,PQ_OUTPUT,f->output_w,f->output_h);
        ID3D11DeviceContext4_CopySubresourceRegion(s->context4,(ID3D11Resource*)s->pool,
            s->pool_slice,0,0,0,(ID3D11Resource*)f->texture[PQ_OUTPUT],0,NULL);
        hr=ID3D11Device_GetDeviceRemovedReason(p->device);
    }
    ID3D11DeviceContext1_SwapDeviceContextState(p->context,s->previous,NULL);
    if(SUCCEEDED(hr))hr=ID3D11Device_GetDeviceRemovedReason(p->device);
    if(FAILED(hr))f->failed=1;else f->finished=1;
    return hr; // queue ordering only; no CPU wait/core/ref/alloc/cleanup
}
HRESULT bv_hdr11_close_finish(struct bv_hdr11_finish_prepared **handle) {
    if(!handle)return E_POINTER;struct bv_hdr11_finish_prepared *s=*handle;
    if(!s)return S_OK;if(s->owner_thread!=GetCurrentThreadId())return E_INVALIDARG;
    struct bv_hdr11_frame *f=s->frame;
    /* Retain all actually consumed SR/pool refs in the already-submitted frame,
     * including Swap failure. Close runs after BOTH exclusions have ended. */
    if(s->attempted) {
        f->sr=s->sr;s->sr=NULL;f->sr_srv=s->sr_srv;s->sr_srv=NULL;
        f->sr_lease=s->sr_lease;s->sr_lease=(struct bv_hdr11_lease){0};
        f->pool_output=s->pool;s->pool=NULL;
        f->pool_lease=s->pool_lease;s->pool_lease=(struct bv_hdr11_lease){0};
        f->restore_ready=s->sr_ready;s->sr_ready=NULL;
    }
    f->finish_pending=0;*handle=NULL;finish_prepared_free(s);
    /* Original frame reference remains with caller. If it had independently
     * retired after real final completion, this prepared ref is the last one. */
    if(InterlockedDecrement(&f->refs)==0)frame_free(f);
    return S_OK;
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

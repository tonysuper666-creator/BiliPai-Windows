/* GPL-3.0-or-later.
 * Uses Veyra 96a7c8de's explicit shared-texture/fence and color contracts.
 * This is an mpv input/output adapter, not Veyra's decoder or presenter.
 * No CPU pixel readback, audio, window, timing actor or account API here.
 */
#define COBJMACROS
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include "bilipai_rtx_mpv_bridge.h"
#include <d3d11_4.h>
#include <d3d12.h>
#include <dxgi1_4.h>
#include <d3dcompiler.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>
#include <libavutil/buffer.h>
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_d3d11va.h>
#include "video/d3d.h"

_Static_assert(sizeof(void *)==8, "bridge ABI is Windows x64 only");
_Static_assert(sizeof(bv_config_v1)==120, "core config ABI layout");
_Static_assert(sizeof(bv_frame_v1)==112, "core frame ABI layout");
_Static_assert(sizeof(bv_result_v1)==104, "core result ABI layout");

#define RELEASE(x) do { if (x) { IUnknown_Release((IUnknown *)(x)); (x)=NULL; } } while(0)
typedef int32_t (__cdecl *create_fn)(const bv_config_v1*,bv_handle_v1*,bv_status_v1*);
typedef int32_t (__cdecl *process_fn)(bv_handle_v1,const bv_frame_v1*,bv_result_v1*,bv_status_v1*);
typedef int32_t (__cdecl *reset_fn)(bv_handle_v1,uint64_t,uint64_t,bv_status_v1*);
typedef int32_t (__cdecl *destroy_fn)(bv_handle_v1,bv_status_v1*);
struct bv_mpv_bridge {
    struct bv_mpv_config config;
    HMODULE module;
    create_fn create; process_fn process; reset_fn reset; destroy_fn destroy;
    bv_handle_v1 core;
    ID3D11Device *device;
    ID3D11DeviceContext *context;
    ID3D11DeviceContext4 *context4;
    ID3DDeviceContextState *isolated_state;
    ID3D12Device *device12;
    ID3D12CommandQueue *queue12;
    ID3D11Fence *producer11, *consumer11;
    ID3D12Fence *producer12, *consumer12;
    ID3D11Texture2D *input11, *output11, *present11, *source11;
    ID3D12Resource *input12, *output12;
    ID3D11RenderTargetView *input_rtv, *present_rtv;
    ID3D11ShaderResourceView *output_srv, *source_srv[2];
    ID3D11VertexShader *vs;
    ID3D11PixelShader *input_ps, *sr_ps, *hdr_ps;
    ID3D11Buffer *constants;
    ID3D11RasterizerState *raster;
    uint64_t luid, producer_value, consumer_value, consumer_done, sequence;
    uint32_t source_width, source_height;
    DXGI_FORMAT source_format;
    void *held_input_lease, *held_output_lease;
    void (*release_output_lease)(void *);
    int failed;
    struct bv_mpv_proxy_sr_loan *proxy_sr_loan;
    AVBufferRef *proxy_owner_ref; /* one exact known owner until full bridge retirement */
};
struct bv_mpv_proxy_sr_loan {
    struct bv_mpv_bridge *bridge;
    AVBufferRef *device_ref;
    DWORD host_thread;
    ID3D11Fence *source_ready, *final_use;
    ID3D12Fence *core_done;
    uint64_t producer_value, core_value, ready_value, final_value;
    void *retained_lease;
    void (*release_retained_lease)(void *);
    int attempted, untracked;
};
/* At most the existing singleton NGX host can be quarantined. This is solely
 * deferred COM/DLL retirement, never a playable source or second frame cache. */
static SRWLOCK retire_lock=SRWLOCK_INIT;
static struct bv_mpv_bridge *quarantined;
static int fail(bv_status_v1 *s,int code,HRESULT hr,const char *msg) {
    memset(s,0,sizeof(*s));s->size=sizeof(*s);s->abi=BV_ABI_V1;
    s->code=code;s->native_hresult=(uint32_t)hr;
    strncpy_s(s->message,sizeof(s->message),msg,_TRUNCATE);return code;
}
static void status_init(bv_status_v1 *s) { memset(s,0,sizeof(*s));s->size=sizeof(*s);s->abi=BV_ABI_V1; }
static int absolute_path(const wchar_t *p) {
    return p&&((p[0]&&p[1]==L':'&&(p[2]==L'\\'||p[2]==L'/'))||(p[0]==L'\\'&&p[1]==L'\\'));
}
static void release_context(struct bv_mpv_bridge *p) {
    RELEASE(p->source_srv[0]);RELEASE(p->source_srv[1]);RELEASE(p->source11);
    RELEASE(p->input_rtv);RELEASE(p->present_rtv);RELEASE(p->output_srv);
    RELEASE(p->vs);RELEASE(p->input_ps);RELEASE(p->sr_ps);RELEASE(p->hdr_ps);
    RELEASE(p->constants);RELEASE(p->raster);
    RELEASE(p->input12);RELEASE(p->output12);RELEASE(p->input11);RELEASE(p->output11);RELEASE(p->present11);
    RELEASE(p->producer12);RELEASE(p->consumer12);RELEASE(p->producer11);RELEASE(p->consumer11);
    RELEASE(p->isolated_state);RELEASE(p->context4);RELEASE(p->context);RELEASE(p->queue12);RELEASE(p->device12);RELEASE(p->device);
    if(p->held_input_lease)p->release_output_lease(p->held_input_lease);
    if(p->held_output_lease)p->release_output_lease(p->held_output_lease);
    av_buffer_unref(&p->proxy_owner_ref);
    /* PIN survives this withdrawal of our own LoadLibrary reference. No core
     * call is allowed unless that exact module was successfully process-pinned. */
    if(p->module)FreeLibrary(p->module);free(p);
}
static int wait_native(struct bv_mpv_bridge *p,ID3D11Fence *f,uint64_t value,bv_status_v1 *s) {
    if(!value)return BV_OK;
    UINT64 done=ID3D11Fence_GetCompletedValue(f);
    if(done==UINT64_MAX) {
        HRESULT removed=ID3D11Device_GetDeviceRemovedReason(p->device);
        return FAILED(removed)?BV_OK:fail(s,BV_DEVICE_FAILURE,E_FAIL,"invalid D3D11 completion sentinel");
    }
    if(done>=value)return BV_OK;
    HANDLE event=CreateEventW(NULL,FALSE,FALSE,NULL);if(!event)return fail(s,BV_DEVICE_FAILURE,HRESULT_FROM_WIN32(GetLastError()),"native retirement event failed");
    HRESULT hr=ID3D11Fence_SetEventOnCompletion(f,value,event);
    DWORD wait=SUCCEEDED(hr)?WaitForSingleObject(event,p->config.timeout_ms):WAIT_FAILED;
    CloseHandle(event);
    if(FAILED(hr))return fail(s,BV_DEVICE_FAILURE,hr,"native retirement event arm failed");
    if(wait==WAIT_TIMEOUT)return fail(s,BV_TIMEOUT,E_PENDING,"D3D11 input/output lease still in flight; retained");
    if(wait!=WAIT_OBJECT_0)return fail(s,BV_DEVICE_FAILURE,E_FAIL,"native retirement wait failed");
    done=ID3D11Fence_GetCompletedValue(f);
    if(done==UINT64_MAX&&FAILED(ID3D11Device_GetDeviceRemovedReason(p->device)))return BV_OK;
    if(done==UINT64_MAX||done<value)return fail(s,BV_TIMEOUT,E_PENDING,"native event did not prove lease completion");
    return BV_OK;
}
static int destroy_core(struct bv_mpv_bridge *p,bv_status_v1 *s) {
    if(p->proxy_sr_loan)return fail(s,BV_BUSY,E_PENDING,"outstanding SR proxy loan retains whole bridge");
    if(p->core) { int rc=p->destroy(p->core,s);if(rc!=BV_OK)return rc;p->core=0; }
    int rc=wait_native(p,p->producer11,p->producer_value,s);
    if(rc==BV_OK)rc=wait_native(p,p->consumer11,p->consumer_done,s);
    return rc;
}
static void enter_context(struct bv_mpv_bridge *p,ID3DDeviceContextState **previous) {
    if(p->config.context_lock)p->config.context_lock(p->config.context_lock_opaque);
    ID3D11DeviceContext1_SwapDeviceContextState((ID3D11DeviceContext1*)p->context4,p->isolated_state,previous);
}
static void leave_context(struct bv_mpv_bridge *p,ID3DDeviceContextState **previous) {
    ID3D11DeviceContext1_SwapDeviceContextState((ID3D11DeviceContext1*)p->context4,*previous,NULL);
    RELEASE(*previous);
    if(p->config.context_unlock)p->config.context_unlock(p->config.context_lock_opaque);
}
static HRESULT shared_texture(struct bv_mpv_bridge *p,uint32_t w,uint32_t h,
                             DXGI_FORMAT fmt,UINT binds,ID3D11Texture2D **t11,ID3D12Resource **t12) {
    D3D11_TEXTURE2D_DESC d={0};d.Width=w;d.Height=h;d.MipLevels=1;d.ArraySize=1;
    d.Format=fmt;d.SampleDesc.Count=1;d.Usage=D3D11_USAGE_DEFAULT;d.BindFlags=binds;
    d.MiscFlags=D3D11_RESOURCE_MISC_SHARED|D3D11_RESOURCE_MISC_SHARED_NTHANDLE;
    HRESULT hr=ID3D11Device_CreateTexture2D(p->device,&d,NULL,t11);
    IDXGIResource1 *r=NULL;HANDLE handle=NULL;
    if(SUCCEEDED(hr))hr=ID3D11Texture2D_QueryInterface(*t11,&IID_IDXGIResource1,(void**)&r);
    if(SUCCEEDED(hr))hr=IDXGIResource1_CreateSharedHandle(r,NULL,DXGI_SHARED_RESOURCE_READ|DXGI_SHARED_RESOURCE_WRITE,NULL,&handle);
    if(SUCCEEDED(hr))hr=ID3D12Device_OpenSharedHandle(p->device12,handle,&IID_ID3D12Resource,(void**)t12);
    if(handle)CloseHandle(handle);RELEASE(r);return hr;
}
static HRESULT shared_fence(struct bv_mpv_bridge *p,ID3D11Fence **f11,ID3D12Fence **f12) {
    ID3D11Device5 *d5=NULL;HANDLE handle=NULL;
    HRESULT hr=ID3D11Device_QueryInterface(p->device,&IID_ID3D11Device5,(void**)&d5);
    if(SUCCEEDED(hr))hr=ID3D11Device5_CreateFence(d5,0,D3D11_FENCE_FLAG_SHARED,&IID_ID3D11Fence,(void**)f11);
    if(SUCCEEDED(hr))hr=ID3D11Fence_CreateSharedHandle(*f11,NULL,GENERIC_ALL,NULL,&handle);
    if(SUCCEEDED(hr))hr=ID3D12Device_OpenSharedHandle(p->device12,handle,&IID_ID3D12Fence,(void**)f12);
    if(handle)CloseHandle(handle);RELEASE(d5);return hr;
}
/* Exact SDR domain: NV12/P010 and qualified P012/P016 code values ->
 * BT601/709 RGB -> source transfer decode -> sRGB encode. P012 words are
 * left-shifted 4 bits; P016 words are not shifted. Depth is current-frame
 * AVHWFramesContext qualification, never guessed from their shared DXGI P016.
 * HDR output remains scRGB(1=80nit) -> BT2020/PQ. */
static const char shader[]=
"Texture2D<float4> src:register(t0);Texture2D<float2> uv:register(t1);"
"cbuffer K:register(b0){uint mode;uint limited;uint matrixId;uint gamma24;uint width;uint height;uint chroma;uint sampleDepth;}"
"float4 vs(uint i:SV_VertexID):SV_Position{return float4(i==2?3:-1,i==1?3:-1,0,1);}"
"float3 srgb(float3 c){c=max(c,0);return float3(c.r<=0.0031308?12.92*c.r:1.055*pow(c.r,1/2.4)-.055,c.g<=0.0031308?12.92*c.g:1.055*pow(c.g,1/2.4)-.055,c.b<=0.0031308?12.92*c.b:1.055*pow(c.b,1/2.4)-.055);}"
"float3 decodeTransfer(float3 c){c=max(c,0);if(gamma24)return pow(c,2.4);return float3(c.r<=.04045?c.r/12.92:pow((c.r+.055)/1.055,2.4),c.g<=.04045?c.g/12.92:pow((c.g+.055)/1.055,2.4),c.b<=.04045?c.b/12.92:pow((c.b+.055)/1.055,2.4));}"
"float2 chromaSample(uint2 q){float2 result=uv.Load(int3(q/2,0));if(chroma){float2 origin=float2(0,.5);if(chroma==2)origin=float2(.5,.5);else if(chroma==3)origin=float2(0,0);else if(chroma==4)origin=float2(.5,0);else if(chroma==5)origin=float2(0,1);else if(chroma==6)origin=float2(.5,1);float2 t=(float2(q)-origin)/2;int2 a=int2(floor(t));float2 fraction=frac(t);int2 end=int2((width+1)/2-1,(height+1)/2-1);float2 c00=uv.Load(int3(clamp(a,0,end),0));float2 c10=uv.Load(int3(clamp(a+int2(1,0),0,end),0));float2 c01=uv.Load(int3(clamp(a+int2(0,1),0,end),0));float2 c11=uv.Load(int3(clamp(a+int2(1,1),0,end),0));result=lerp(lerp(c00,c10,fraction.x),lerp(c01,c11,fraction.x),fraction.y);}return result;}"
"float4 inputPS(float4 pos:SV_Position):SV_Target{uint2 q=uint2(pos.xy);float3 rgb=src.Load(int3(q,0)).rgb;"
"if(mode){float y=rgb.r;float2 c=chromaSample(q);float scale=mode==2?65535.0/64:mode==3?(sampleDepth==12?65535.0/16:65535.0):255;float mx=mode==2?1023:mode==3?(sampleDepth==12?4095:65535):255;float black=mode==2?64:mode==3?(sampleDepth==12?256:4096):16;float white=mode==2?940:mode==3?(sampleDepth==12?3760:60160):235;float mid=mode==2?512:mode==3?(sampleDepth==12?2048:32768):128;float span=mode==2?896:mode==3?(sampleDepth==12?3584:57344):224;"
"float yy=limited?(y*scale-black)/(white-black):y*scale/mx;float2 cc=(c*scale-mid)/(limited?span:mx);rgb=matrixId==1?float3(yy+1.402*cc.y,yy-.344136*cc.x-.714136*cc.y,yy+1.772*cc.x):float3(yy+1.5748*cc.y,yy-.187324*cc.x-.468124*cc.y,yy+1.8556*cc.x);"
"}else if(limited)rgb=sampleDepth==10?(rgb*1023.0-64.0)/876.0:(rgb*255-16)/219;return float4(saturate(srgb(decodeTransfer(saturate(rgb)))),1);}"
"float4 srPS(float4 p:SV_Position):SV_Target{return src.Load(int3(uint2(p.xy),0));}"
"float3 pq(float3 n){float3 v=pow(saturate(n/10000),2610.0/16384);return pow((3424.0/4096+(2413.0/128)*v)/(1+(2392.0/128)*v),2523.0/32);}"
"float4 hdrPS(float4 p:SV_Position):SV_Target{float3 x=max(src.Load(int3(uint2(p.xy),0)).rgb,0)*80;float3 y=float3(dot(x,float3(.627404,.329283,.043313)),dot(x,float3(.069097,.919540,.011362)),dot(x,float3(.016391,.088013,.895595)));return float4(pq(y),1);}";
static HRESULT shader_create(struct bv_mpv_bridge *p) {
    ID3DBlob *b=NULL,*errors=NULL;HRESULT hr;
#define BUILD(entry,target) hr=D3DCompile(shader,sizeof(shader)-1,"bilipai-rtx-candidate",NULL,NULL,entry,target,D3DCOMPILE_ENABLE_STRICTNESS,0,&b,&errors);RELEASE(errors);if(FAILED(hr)){RELEASE(b);return hr;}
    BUILD("vs","vs_5_0");hr=ID3D11Device_CreateVertexShader(p->device,ID3D10Blob_GetBufferPointer(b),ID3D10Blob_GetBufferSize(b),NULL,&p->vs);RELEASE(b);if(FAILED(hr))return hr;
    BUILD("inputPS","ps_5_0");hr=ID3D11Device_CreatePixelShader(p->device,ID3D10Blob_GetBufferPointer(b),ID3D10Blob_GetBufferSize(b),NULL,&p->input_ps);RELEASE(b);if(FAILED(hr))return hr;
    BUILD("srPS","ps_5_0");hr=ID3D11Device_CreatePixelShader(p->device,ID3D10Blob_GetBufferPointer(b),ID3D10Blob_GetBufferSize(b),NULL,&p->sr_ps);RELEASE(b);if(FAILED(hr))return hr;
    BUILD("hdrPS","ps_5_0");hr=ID3D11Device_CreatePixelShader(p->device,ID3D10Blob_GetBufferPointer(b),ID3D10Blob_GetBufferSize(b),NULL,&p->hdr_ps);RELEASE(b);if(FAILED(hr))return hr;
#undef BUILD
    D3D11_BUFFER_DESC cb={0};cb.ByteWidth=32;cb.Usage=D3D11_USAGE_DEFAULT;cb.BindFlags=D3D11_BIND_CONSTANT_BUFFER;
    hr=ID3D11Device_CreateBuffer(p->device,&cb,NULL,&p->constants);if(FAILED(hr))return hr;
    D3D11_RASTERIZER_DESC rs={0};rs.FillMode=D3D11_FILL_SOLID;rs.CullMode=D3D11_CULL_NONE;rs.DepthClipEnable=TRUE;
    return ID3D11Device_CreateRasterizerState(p->device,&rs,&p->raster);
}
static void draw(struct bv_mpv_bridge *p,ID3D11RenderTargetView *target,ID3D11PixelShader *ps,
                 uint32_t w,uint32_t h,ID3D11ShaderResourceView *a,ID3D11ShaderResourceView *b) {
    D3D11_VIEWPORT vp={0,0,(float)w,(float)h,0,1};ID3D11ShaderResourceView *views[2]={a,b};
    ID3D11DeviceContext_RSSetViewports(p->context,1,&vp);ID3D11DeviceContext_RSSetState(p->context,p->raster);
    ID3D11DeviceContext_IASetInputLayout(p->context,NULL);ID3D11DeviceContext_IASetPrimitiveTopology(p->context,D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    ID3D11DeviceContext_VSSetShader(p->context,p->vs,NULL,0);ID3D11DeviceContext_GSSetShader(p->context,NULL,NULL,0);
    ID3D11DeviceContext_PSSetShader(p->context,ps,NULL,0);ID3D11DeviceContext_PSSetConstantBuffers(p->context,0,1,&p->constants);
    ID3D11DeviceContext_PSSetShaderResources(p->context,0,2,views);ID3D11DeviceContext_OMSetRenderTargets(p->context,1,&target,NULL);
    ID3D11DeviceContext_OMSetBlendState(p->context,NULL,NULL,UINT_MAX);ID3D11DeviceContext_OMSetDepthStencilState(p->context,NULL,0);
    ID3D11DeviceContext_Draw(p->context,3,0);views[0]=views[1]=NULL;
    ID3D11DeviceContext_PSSetShaderResources(p->context,0,2,views);ID3D11DeviceContext_OMSetRenderTargets(p->context,0,NULL,NULL);
}
static HRESULT source_views(struct bv_mpv_bridge *p,const D3D11_TEXTURE2D_DESC *src) {
    if(p->source11&&p->source_width==src->Width&&p->source_height==src->Height&&p->source_format==src->Format)return S_OK;
    if(p->source11)return E_INVALIDARG; /* shape/format retirement requires real drain */
    D3D11_TEXTURE2D_DESC d=*src;d.ArraySize=1;d.MipLevels=1;d.BindFlags=D3D11_BIND_SHADER_RESOURCE;d.MiscFlags=0;d.CPUAccessFlags=0;d.Usage=D3D11_USAGE_DEFAULT;
    HRESULT hr=ID3D11Device_CreateTexture2D(p->device,&d,NULL,&p->source11);if(FAILED(hr))return hr;
    D3D11_SHADER_RESOURCE_VIEW_DESC v={0};v.ViewDimension=D3D11_SRV_DIMENSION_TEXTURE2D;v.Texture2D.MipLevels=1;
    v.Format=src->Format==DXGI_FORMAT_NV12?DXGI_FORMAT_R8_UNORM:(src->Format==DXGI_FORMAT_P010||src->Format==DXGI_FORMAT_P016)?DXGI_FORMAT_R16_UNORM:src->Format;
    hr=ID3D11Device_CreateShaderResourceView(p->device,(ID3D11Resource*)p->source11,&v,&p->source_srv[0]);
    if(SUCCEEDED(hr)&&(src->Format==DXGI_FORMAT_NV12||src->Format==DXGI_FORMAT_P010||src->Format==DXGI_FORMAT_P016)){
        v.Format=src->Format==DXGI_FORMAT_NV12?DXGI_FORMAT_R8G8_UNORM:DXGI_FORMAT_R16G16_UNORM;
        hr=ID3D11Device_CreateShaderResourceView(p->device,(ID3D11Resource*)p->source11,&v,&p->source_srv[1]);
    }
    if(SUCCEEDED(hr)){p->source_width=src->Width;p->source_height=src->Height;p->source_format=src->Format;}return hr;
}
void bv_mpv_observe_pq_p010(ID3D11Device *device, ID3D11Texture2D *texture,
                           uint32_t slice, struct bv_mpv_pq_p010_observation *o)
{
    if (!o) return;
    o->texture_p010 = o->current_domain_observed = 0;
    o->gpu_epoch_at_use_known = o->native_hdr_qualified = 0;
    o->dxgi_format = o->texture_width = o->texture_height = o->texture_array_size = 0;
    if (!device || !texture || !o->width || !o->height ||
        o->width > 16384 || o->height > 16384) return;
    ID3D11Device *actual = NULL;
    ID3D11Texture2D_GetDevice(texture, &actual);
    bool same = actual == device;
    if (actual) ID3D11Device_Release(actual);
    D3D11_TEXTURE2D_DESC d = {0};
    ID3D11Texture2D_GetDesc(texture, &d);
    o->dxgi_format = d.Format;
    o->texture_width = d.Width; o->texture_height = d.Height;
    o->texture_array_size = d.ArraySize;
    o->texture_p010 = same && d.Format == DXGI_FORMAT_P010 &&
        d.MipLevels == 1 && d.SampleDesc.Count == 1 && slice < d.ArraySize &&
        d.Width >= o->width && d.Height >= o->height;
    o->current_domain_observed = o->boundary_pq_p010 && o->reference_unchanged &&
        o->hw_context_matching && o->texture_p010 && o->epoch_matched_at_observe &&
        !o->refusal_history && (o->raw_range == 1 || o->raw_range == 2);
    // Scoped caller holds the decoder dispatch lock throughout this lightweight
    // observation. It is released before return to normal filtering; future GPU
    // use/output epoch is UNKNOWN. No processing/token/admission is performed.
}
int bv_mpv_bridge_create(const struct bv_mpv_config *c,struct bv_mpv_bridge **out,bv_status_v1 *s) {
    *out=NULL;status_init(s);
    if(!c||!c->device||!c->session||!c->configuration||c->configuration>INT64_MAX||c->generation<c->configuration||!absolute_path(c->dll_path)||!absolute_path(c->runtime_directory)||!c->project_id||!c->engine_version||!c->input_width||!c->input_height||!c->output_width||!c->output_height||(!c->context_lock!=!c->context_unlock))return fail(s,BV_INVALID,E_INVALIDARG,"invalid explicit bridge configuration");
    AcquireSRWLockExclusive(&retire_lock);
    if(quarantined&&destroy_core(quarantined,s)==BV_OK){release_context(quarantined);quarantined=NULL;}
    int blocked=quarantined!=NULL;ReleaseSRWLockExclusive(&retire_lock);
    if(blocked)return fail(s,BV_BUSY,E_PENDING,"earlier core still owns GPU work; original picture remains available");
    struct bv_mpv_bridge *p=calloc(1,sizeof(*p));if(!p)return fail(s,BV_INTERNAL,E_OUTOFMEMORY,"bridge allocation failed");
    p->config=*c;p->device=c->device;ID3D11Device_AddRef(p->device);
    IDXGIDevice *dx=NULL;IDXGIAdapter *adapter=NULL;DXGI_ADAPTER_DESC desc;HRESULT hr;
    hr=ID3D11Device_QueryInterface(p->device,&IID_IDXGIDevice,(void**)&dx);
    if(SUCCEEDED(hr))hr=IDXGIDevice_GetAdapter(dx,&adapter);
    if(SUCCEEDED(hr))hr=IDXGIAdapter_GetDesc(adapter,&desc);
    if(SUCCEEDED(hr)&&desc.VendorId!=0x10de)hr=E_INVALIDARG;
    if(SUCCEEDED(hr))p->luid=((uint64_t)(uint32_t)desc.AdapterLuid.HighPart<<32)|desc.AdapterLuid.LowPart;
    if(SUCCEEDED(hr))hr=D3D12CreateDevice((IUnknown*)adapter,D3D_FEATURE_LEVEL_12_0,&IID_ID3D12Device,(void**)&p->device12);
    RELEASE(adapter);RELEASE(dx);if(FAILED(hr))goto native_fail;
    D3D12_COMMAND_QUEUE_DESC q={0};q.Type=D3D12_COMMAND_LIST_TYPE_DIRECT;
    hr=ID3D12Device_CreateCommandQueue(p->device12,&q,&IID_ID3D12CommandQueue,(void**)&p->queue12);if(FAILED(hr))goto native_fail;
    ID3D11Device_GetImmediateContext(p->device,&p->context);
    hr=ID3D11DeviceContext_QueryInterface(p->context,&IID_ID3D11DeviceContext4,(void**)&p->context4);if(FAILED(hr))goto native_fail;
    ID3D11Device1 *device1=NULL;D3D_FEATURE_LEVEL level=ID3D11Device_GetFeatureLevel(p->device),chosen;
    hr=ID3D11Device_QueryInterface(p->device,&IID_ID3D11Device1,(void**)&device1);
    if(SUCCEEDED(hr))hr=ID3D11Device1_CreateDeviceContextState(device1,0,&level,1,D3D11_SDK_VERSION,&IID_ID3D11Device,&chosen,&p->isolated_state);
    RELEASE(device1);if(FAILED(hr))goto native_fail;
    hr=shared_fence(p,&p->producer11,&p->producer12);if(FAILED(hr))goto native_fail;
    hr=shared_fence(p,&p->consumer11,&p->consumer12);if(FAILED(hr))goto native_fail;
    hr=shared_texture(p,c->input_width,c->input_height,DXGI_FORMAT_R8G8B8A8_UNORM,D3D11_BIND_SHADER_RESOURCE|D3D11_BIND_RENDER_TARGET,&p->input11,&p->input12);if(FAILED(hr))goto native_fail;
    DXGI_FORMAT output=(c->effects&BV_VIDEO_HDR)?DXGI_FORMAT_R16G16B16A16_FLOAT:DXGI_FORMAT_R8G8B8A8_UNORM;
    hr=shared_texture(p,c->output_width,c->output_height,output,D3D11_BIND_SHADER_RESOURCE|D3D11_BIND_UNORDERED_ACCESS,&p->output11,&p->output12);if(FAILED(hr))goto native_fail;
    hr=ID3D11Device_CreateRenderTargetView(p->device,(ID3D11Resource*)p->input11,NULL,&p->input_rtv);if(FAILED(hr))goto native_fail;
    hr=ID3D11Device_CreateShaderResourceView(p->device,(ID3D11Resource*)p->output11,NULL,&p->output_srv);if(FAILED(hr))goto native_fail;
    D3D11_TEXTURE2D_DESC d={0};d.Width=c->output_width;d.Height=c->output_height;d.ArraySize=d.MipLevels=d.SampleDesc.Count=1;
    d.Format=(c->effects&BV_VIDEO_HDR)?DXGI_FORMAT_R10G10B10A2_UNORM:DXGI_FORMAT_B8G8R8A8_UNORM;d.BindFlags=D3D11_BIND_RENDER_TARGET;
    hr=ID3D11Device_CreateTexture2D(p->device,&d,NULL,&p->present11);if(FAILED(hr))goto native_fail;
    hr=ID3D11Device_CreateRenderTargetView(p->device,(ID3D11Resource*)p->present11,NULL,&p->present_rtv);if(FAILED(hr))goto native_fail;
    hr=shader_create(p);if(FAILED(hr))goto native_fail;
    p->module=LoadLibraryExW(c->dll_path,NULL,LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
    if(!p->module){hr=HRESULT_FROM_WIN32(GetLastError());goto native_fail;}
    HMODULE pinned=NULL;
    if(!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_PIN|GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS,
            (LPCWSTR)p->module,&pinned)){hr=HRESULT_FROM_WIN32(GetLastError());goto native_fail;}
    if(pinned!=p->module){hr=HRESULT_FROM_WIN32(ERROR_INVALID_HANDLE);goto native_fail;}
    p->create=(create_fn)GetProcAddress(p->module,"bv_create_v1");p->process=(process_fn)GetProcAddress(p->module,"bv_process_v1");
    p->reset=(reset_fn)GetProcAddress(p->module,"bv_reset_v1");p->destroy=(destroy_fn)GetProcAddress(p->module,"bv_destroy_v1");
    if(!p->create||!p->process||!p->reset||!p->destroy){hr=E_NOINTERFACE;goto native_fail;}
    bv_config_v1 cfg={0};cfg.size=sizeof(cfg);cfg.abi=BV_ABI_V1;cfg.session_id=c->session;cfg.source_generation=c->generation;cfg.adapter_luid=p->luid;
    cfg.d3d12_device=p->device12;cfg.d3d12_direct_queue=p->queue12;cfg.runtime_directory_utf16=(const uint16_t*)c->runtime_directory;
    cfg.project_id_utf8=c->project_id;cfg.engine_version_utf8=c->engine_version;cfg.input_width=c->input_width;cfg.input_height=c->input_height;cfg.output_width=c->output_width;cfg.output_height=c->output_height;
    cfg.effects=c->effects;cfg.sr_quality=c->quality;cfg.hdr_contrast=125;cfg.hdr_saturation=75;cfg.hdr_middle_gray=44;cfg.hdr_peak_nits=c->peak_nits;cfg.wait_timeout_ms=c->timeout_ms;
    int rc=p->create(&cfg,&p->core,s);if(rc!=BV_OK){bv_mpv_bridge_destroy(&p,s);return rc;}*out=p;return BV_OK;
native_fail:
    fail(s,BV_DEVICE_FAILURE,hr,"native bridge setup failed; enhancement must bypass");release_context(p);return BV_DEVICE_FAILURE;
}
int bv_mpv_bridge_process(struct bv_mpv_bridge *p,ID3D11Texture2D *in,uint32_t slice,ID3D11Texture2D *out,uint32_t out_slice,struct bv_mpv_color color,uint64_t generation,uint64_t sequence,int64_t pts,int32_t base,void *input_lease,void *output_lease,void (*release_output_lease)(void *),bv_status_v1 *s,struct mp_bilipai_frame_token *receipt) {
    status_init(s);if(receipt)memset(receipt,0,sizeof(*receipt));
#define RETURN(value) do { int result=(value);if(input_lease&&release_output_lease)release_output_lease(input_lease);if(output_lease&&release_output_lease)release_output_lease(output_lease);return result; } while(0)
    if(!receipt||!input_lease||!output_lease||!release_output_lease)RETURN(fail(s,BV_INVALID,E_INVALIDARG,"output image lease required"));
    if(p&&p->proxy_sr_loan)RETURN(fail(s,BV_BUSY,E_PENDING,"SR proxy output still borrowed; old frame cannot reuse bridge"));
    if(!p||p->failed||!in||!out||generation!=p->config.generation||!sequence||sequence<=p->sequence||base<=0)RETURN(fail(s,BV_STALE,E_INVALIDARG,"stale/failed source or invalid timestamp"));
    if(p->held_input_lease||p->held_output_lease){
        int old=wait_native(p,p->producer11,p->producer_value,s);
        if(old==BV_OK)old=wait_native(p,p->consumer11,p->consumer_done,s);
        if(old!=BV_OK){p->failed=1;RETURN(old);}
        if(p->held_input_lease)p->release_output_lease(p->held_input_lease);p->held_input_lease=NULL;
        if(p->held_output_lease)p->release_output_lease(p->held_output_lease);p->held_output_lease=NULL;
    }
    ID3D11Device *input_device=NULL,*output_device=NULL;
    ID3D11Texture2D_GetDevice(in,&input_device);ID3D11Texture2D_GetDevice(out,&output_device);
    int same_device=input_device==p->device&&output_device==p->device;
    RELEASE(input_device);RELEASE(output_device);
    if(!same_device)RETURN(fail(s,BV_STALE,E_INVALIDARG,"frame texture belongs to another D3D11 device"));
    D3D11_TEXTURE2D_DESC d,od;ID3D11Texture2D_GetDesc(in,&d);ID3D11Texture2D_GetDesc(out,&od);
    DXGI_FORMAT expected=(p->config.effects&BV_VIDEO_HDR)?DXGI_FORMAT_R10G10B10A2_UNORM:DXGI_FORMAT_B8G8R8A8_UNORM;
    if(d.MipLevels!=1||d.SampleDesc.Count!=1||slice>=d.ArraySize||d.Width<p->config.input_width||d.Height<p->config.input_height||od.MipLevels!=1||od.SampleDesc.Count!=1||out_slice>=od.ArraySize||od.Format!=expected||od.Width<p->config.output_width||od.Height<p->config.output_height||color.matrix>2||color.transfer>1||color.limited>1||color.chroma>6||color.rgb10_qualified>1||(color.p016_depth!=0&&color.p016_depth!=12&&color.p016_depth!=16))RETURN(fail(s,BV_COLOR_UNSUPPORTED,E_INVALIDARG,"unsupported texture/color contract"));
    if(d.Format!=DXGI_FORMAT_NV12&&d.Format!=DXGI_FORMAT_P010&&d.Format!=DXGI_FORMAT_P016&&d.Format!=DXGI_FORMAT_B8G8R8A8_UNORM&&d.Format!=DXGI_FORMAT_R8G8B8A8_UNORM&&d.Format!=DXGI_FORMAT_R10G10B10A2_UNORM)RETURN(fail(s,BV_COLOR_UNSUPPORTED,E_INVALIDARG,"unsupported source pixel format"));
    /* Typed RGB10A2 sampling is normalized by 1023. Admission came from
       the strict MPV CURRENT pixel gate and must match this real texture.
       Other RGB/YUV inputs cannot claim its private qualification. */
    if((d.Format==DXGI_FORMAT_R10G10B10A2_UNORM)!=(color.rgb10_qualified==1))RETURN(fail(s,BV_COLOR_UNSUPPORTED,E_INVALIDARG,"RGB10 texture and current-pixel qualification mismatch"));
    if((d.Format==DXGI_FORMAT_P016)!=(color.p016_depth==12||color.p016_depth==16))RETURN(fail(s,BV_COLOR_UNSUPPORTED,E_INVALIDARG,"P016 texture and current-sampling depth mismatch"));
    int yuv=d.Format==DXGI_FORMAT_NV12||d.Format==DXGI_FORMAT_P010||d.Format==DXGI_FORMAT_P016;
    if((yuv&&color.matrix==0)||(!yuv&&color.matrix!=0))RETURN(fail(s,BV_COLOR_UNSUPPORTED,E_INVALIDARG,"texture matrix metadata mismatch"));
    HRESULT hr=source_views(p,&d);if(FAILED(hr)){p->failed=1;RETURN(fail(s,BV_DEVICE_FAILURE,hr,"source plane view unavailable"));}
    p->held_input_lease=input_lease;p->release_output_lease=release_output_lease;input_lease=NULL;
    ID3DDeviceContextState *previous=NULL;enter_context(p,&previous);
    ID3D11DeviceContext_CopySubresourceRegion(p->context,(ID3D11Resource*)p->source11,0,0,0,0,(ID3D11Resource*)in,slice,NULL);
    uint32_t constants[8]={d.Format==DXGI_FORMAT_NV12?1:d.Format==DXGI_FORMAT_P010?2:d.Format==DXGI_FORMAT_P016?3:0,color.limited,color.matrix,color.transfer,p->config.input_width,p->config.input_height,color.chroma,d.Format==DXGI_FORMAT_P016?color.p016_depth:(d.Format==DXGI_FORMAT_R10G10B10A2_UNORM||d.Format==DXGI_FORMAT_P010)?10:8};
    ID3D11DeviceContext_UpdateSubresource(p->context,(ID3D11Resource*)p->constants,0,NULL,constants,0,0);
    draw(p,p->input_rtv,p->input_ps,p->config.input_width,p->config.input_height,p->source_srv[0],p->source_srv[1]);
    hr=ID3D11DeviceContext4_Signal(p->context4,p->producer11,++p->producer_value);ID3D11DeviceContext_Flush(p->context);leave_context(p,&previous);
    if(FAILED(hr)){p->failed=1;RETURN(fail(s,BV_DEVICE_FAILURE,hr,"producer fence signal failed"));}
    bv_frame_v1 f={0};f.size=sizeof(f);f.abi=BV_ABI_V1;f.session_id=p->config.session;f.source_generation=generation;f.sequence=sequence;f.adapter_luid=p->luid;
    f.pts_numerator=pts;f.pts_denominator=base;f.input_color=BV_SRGB_BT709_FULL_RGBA8;f.output_color=(p->config.effects&BV_VIDEO_HDR)?BV_SCRGB_BT709_LINEAR_FP16_80NITS:BV_SRGB_BT709_FULL_RGBA8;
    f.input_texture=p->input12;f.input_ready_fence=p->producer12;f.input_ready_value=p->producer_value;f.output_texture=p->output12;
    f.input_state=f.output_state=f.output_final_state=D3D12_RESOURCE_STATE_COMMON;
    bv_result_v1 r={0};r.size=sizeof(r);r.abi=BV_ABI_V1;
    int rc=p->process(p->core,&f,&r,s);
    if(rc!=BV_OK){p->failed=1;RETURN(rc);}
    if(r.size!=sizeof(r)||r.abi!=BV_ABI_V1||r.session_id!=f.session_id||r.source_generation!=generation||r.sequence!=sequence||r.adapter_luid!=p->luid||r.pts_numerator!=pts||r.pts_denominator!=base||r.output_texture!=p->output12||r.output_state!=D3D12_RESOURCE_STATE_COMMON||r.output_color!=f.output_color||r.output_dxgi_format!=(uint32_t)((p->config.effects&BV_VIDEO_HDR)?DXGI_FORMAT_R16G16B16A16_FLOAT:DXGI_FORMAT_R8G8B8A8_UNORM)||r.effects_applied!=p->config.effects||r.output_width!=p->config.output_width||r.output_height!=p->config.output_height||!r.completion_fence||!r.completion_value){p->failed=1;RETURN(fail(s,BV_STALE,E_FAIL,"core output receipt mismatch"));}
    hr=ID3D12CommandQueue_Signal(p->queue12,p->consumer12,++p->consumer_value);
    enter_context(p,&previous);
    if(SUCCEEDED(hr))hr=ID3D11DeviceContext4_Wait(p->context4,p->consumer11,p->consumer_value);
    if(FAILED(hr)){p->failed=1;fail(s,BV_DEVICE_FAILURE,hr,"consumer fence handoff failed");leave_context(p,&previous);RETURN(BV_DEVICE_FAILURE);}
    p->held_output_lease=output_lease;p->release_output_lease=release_output_lease;output_lease=NULL;
    draw(p,p->present_rtv,(p->config.effects&BV_VIDEO_HDR)?p->hdr_ps:p->sr_ps,p->config.output_width,p->config.output_height,p->output_srv,NULL);
    ID3D11DeviceContext_CopySubresourceRegion(p->context,(ID3D11Resource*)out,out_slice,0,0,0,(ID3D11Resource*)p->present11,0,NULL);
    p->consumer_done=++p->consumer_value;
    hr=ID3D11DeviceContext4_Signal(p->context4,p->consumer11,p->consumer_done);
    ID3D11DeviceContext_Flush(p->context);leave_context(p,&previous);
    if(FAILED(hr)){p->failed=1;return fail(s,BV_DEVICE_FAILURE,hr,"D3D11 consumer completion signal failed; lease retained");}
    /* These reads/copy and every following input write are on the SAME D3D11
       immediate queue. The consumer fence covers the real D3D12 submission;
       unlike Veyra's decoder ring, safety does not depend on fixed slot counts. */
    *receipt=(struct mp_bilipai_frame_token){
        .version=MP_BILIPAI_TOKEN_V2,.submitted=1,
        .source_kind=MP_BILIPAI_SOURCE_SDR_BT709,
        .output_intent=(r.effects_applied&BV_VIDEO_HDR)?MP_BILIPAI_OUTPUT_SDR_TO_HDR:MP_BILIPAI_OUTPUT_SDR_SRGB,
        .session=r.session_id,.configuration=p->config.configuration,
        .stream=r.source_generation,.sequence=r.sequence,.adapter_luid=r.adapter_luid,
        .pts_numerator=r.pts_numerator,.pts_denominator=r.pts_denominator,
        .input_width=p->config.input_width,.input_height=p->config.input_height,
        .width=r.output_width,.height=r.output_height,.effects=r.effects_applied,
        .transport=(r.effects_applied&BV_VIDEO_HDR)?MP_BILIPAI_HDR10_RGB10:MP_BILIPAI_SRGB_BGRA8,
        .hdr_peak_nits=(r.effects_applied&BV_VIDEO_HDR)?p->config.peak_nits:0,
    };
    if(!mp_bilipai_token_valid(receipt)){memset(receipt,0,sizeof(*receipt));p->failed=1;
        RETURN(fail(s,BV_STALE,E_FAIL,"measured processing token rejected; leases retained"));}
    p->sequence=sequence;RETURN(BV_OK);
#undef RETURN
}
/* UNWIRED: an immutable host proxy uses the already owned shared R8 route.
 * This code never calls the scoped decoder borrow API or mints a frame token. */
static int proxy_same_object(IUnknown *a,IUnknown *b) {
    IUnknown *ca=NULL,*cb=NULL;
    HRESULT ha=a?IUnknown_QueryInterface(a,&IID_IUnknown,(void**)&ca):E_POINTER;
    HRESULT hb=b?IUnknown_QueryInterface(b,&IID_IUnknown,(void**)&cb):E_POINTER;
    int same=SUCCEEDED(ha)&&SUCCEEDED(hb)&&ca==cb;
    RELEASE(ca);RELEASE(cb);return same;
}
static int proxy_same_device11(struct bv_mpv_bridge *p,ID3D11DeviceChild *child) {
    ID3D11Device *device=NULL;
    ID3D11DeviceChild_GetDevice(child,&device);
    int same=proxy_same_object((IUnknown*)device,(IUnknown*)p->device);
    RELEASE(device);return same;
}
static int proxy_completed(ID3D11Fence *f,uint64_t value) {
    if(!f||!value||value==UINT64_MAX)return 0;
    uint64_t done=ID3D11Fence_GetCompletedValue(f);
    return done!=UINT64_MAX&&done>=value;
}
static void proxy_drop_loan(struct bv_mpv_proxy_sr_loan **lp) {
    struct bv_mpv_proxy_sr_loan *l=*lp;
    struct bv_mpv_bridge *p=l->bridge;
    p->proxy_sr_loan=NULL;*lp=NULL;
    RELEASE(l->source_ready);RELEASE(l->core_done);RELEASE(l->final_use);
    av_buffer_unref(&l->device_ref);
    void *lease=l->retained_lease;void (*release)(void*)=l->release_retained_lease;
    free(l);release(lease);
}
static HRESULT proxy_enter(struct bv_mpv_bridge *p,
    struct bv_mpv_proxy_sr_loan *l,ID3DDeviceContextState **previous) {
    /* This is the REAL constructor-owned default recursive mutex, checked
     * with timeout zero. Unknown/busy/poisoned/abandoned never enter. */
    if(!d3d11_bilipai_default_try_lock(l->device_ref))return E_PENDING;
    AVHWDeviceContext *ctx=(void*)l->device_ref->data;
    AVD3D11VADeviceContext *d=ctx->hwctx;
    /* Every immediate/video context call or query is within that mutex. */
    ID3D11Device *actual=NULL;
    ID3D11DeviceContext_GetDevice(p->context,&actual);
    int same=proxy_same_object((IUnknown*)actual,(IUnknown*)p->device)&&
        proxy_same_object((IUnknown*)d->device,(IUnknown*)p->device)&&
        proxy_same_object((IUnknown*)d->device_context,(IUnknown*)p->context)&&
        proxy_same_object((IUnknown*)d->video_context,(IUnknown*)p->context)&&
        ID3D11DeviceContext_GetType(p->context)==D3D11_DEVICE_CONTEXT_IMMEDIATE&&
        p->config.context_lock==d->lock&&p->config.context_unlock==d->unlock&&
        p->config.context_lock_opaque==d->lock_ctx;
    RELEASE(actual);
    if(!same){
        if(!d3d11_bilipai_default_unlock(l->device_ref)){
            l->untracked=1;p->failed=1;return E_UNEXPECTED;
        }
        return E_INVALIDARG;
    }
    /* Even a state attempt is conservatively retained on failure. */
    l->attempted=1;
    ID3D11DeviceContext1_SwapDeviceContextState((ID3D11DeviceContext1*)p->context4,
        p->isolated_state,previous);
    if(!*previous){l->untracked=1;p->failed=1;
        d3d11_bilipai_default_unlock(l->device_ref);return E_UNEXPECTED;}
    return S_OK;
}
static int proxy_leave(struct bv_mpv_bridge *p,struct bv_mpv_proxy_sr_loan *l,
    ID3DDeviceContextState **previous) {
    ID3D11DeviceContext1_SwapDeviceContextState((ID3D11DeviceContext1*)p->context4,
        *previous,NULL);
    RELEASE(*previous);
    int released=d3d11_bilipai_default_unlock(l->device_ref);
    if(!released){l->untracked=1;p->failed=1;}
    return released;
}
int bv_mpv_bridge_process_proxy_sr(struct bv_mpv_bridge *p,
    const struct bv_mpv_proxy_sr_input *in,struct bv_mpv_proxy_sr_loan **loan,
    struct bv_mpv_proxy_sr_result *result,bv_status_v1 *s) {
    if(result)memset(result,0,sizeof(*result));
    if(s)status_init(s);
    int valid_lease=in&&in->retained_lease&&in->release_retained_lease;
#define PROXY_RETURN(code) do { int rc_=(code);if(valid_lease)in->release_retained_lease(in->retained_lease);return rc_; } while(0)
    if(!s)PROXY_RETURN(BV_INVALID);
    if(!loan||!result)PROXY_RETURN(fail(s,BV_INVALID,E_INVALIDARG,"loan/result output required"));
    /* Never overwrite a live opaque handle on a new-frame retry. */
    if(*loan)PROXY_RETURN(fail(s,BV_BUSY,E_PENDING,"caller must retain and retire its existing SR loan"));
    if(!valid_lease)PROXY_RETURN(fail(s,BV_INVALID,E_INVALIDARG,"real retained host proxy lease required"));
    if(!p||p->failed||!p->core||p->proxy_sr_loan||p->held_input_lease||p->held_output_lease)
        PROXY_RETURN(fail(s,BV_BUSY,E_PENDING,"dedicated idle SR bridge required; outstanding owner retained"));
    if(p->config.effects!=BV_VIDEO_SR||!d3d11_bilipai_default_owner_known(in->device_ref)||
       (p->proxy_owner_ref&&(p->proxy_owner_ref->buffer!=in->device_ref->buffer||
        p->proxy_owner_ref->data!=in->device_ref->data||p->proxy_owner_ref->size!=in->device_ref->size))||
       (ID3D11Device_GetCreationFlags(p->device)&D3D11_CREATE_DEVICE_SINGLETHREADED))
        PROXY_RETURN(fail(s,BV_INVALID,E_INVALIDARG,"SR-only original context owner required"));
    if(!in->proxy||!in->producer_ready_fence||!in->producer_ready_value||in->producer_ready_value==UINT64_MAX||
       in->session!=p->config.session||in->configuration!=p->config.configuration||
       in->generation!=p->config.generation||in->adapter_luid!=p->luid||
       !in->sequence||in->sequence<=p->sequence||in->pts_denominator<=0||
       p->producer_value>=UINT64_MAX-1||p->consumer_value>=UINT64_MAX-1)
        PROXY_RETURN(fail(s,BV_STALE,E_INVALIDARG,"exact proxy session/configuration/source packet required"));
    D3D11_TEXTURE2D_DESC d={0};ID3D11Texture2D_GetDesc(in->proxy,&d);
    if(d.Format!=DXGI_FORMAT_R8G8B8A8_UNORM||d.Width!=p->config.input_width||d.Height!=p->config.input_height||
       d.MipLevels!=1||d.ArraySize!=1||d.SampleDesc.Count!=1||d.Usage!=D3D11_USAGE_DEFAULT||
       d.CPUAccessFlags||(d.MiscFlags&D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX)||
       !(d.BindFlags&D3D11_BIND_SHADER_RESOURCE)||
       !proxy_same_device11(p,(ID3D11DeviceChild*)in->proxy)||
       proxy_same_object((IUnknown*)in->proxy,(IUnknown*)p->input11)||
       proxy_same_object((IUnknown*)in->proxy,(IUnknown*)p->output11))
        PROXY_RETURN(fail(s,BV_COLOR_UNSUPPORTED,E_INVALIDARG,"exact same-device immutable R8 host proxy required"));
    ID3D11Fence *source_ready=NULL;
    HRESULT hr=IUnknown_QueryInterface(in->producer_ready_fence,&IID_ID3D11Fence,(void**)&source_ready);
    if(FAILED(hr)||!source_ready||!proxy_same_device11(p,(ID3D11DeviceChild*)source_ready)||
       proxy_same_object((IUnknown*)source_ready,(IUnknown*)p->producer11)||
       proxy_same_object((IUnknown*)source_ready,(IUnknown*)p->consumer11)||
       ID3D11Fence_GetCompletedValue(source_ready)==UINT64_MAX){RELEASE(source_ready);
        PROXY_RETURN(fail(s,BV_INVALID,E_INVALIDARG,"actual same-device proxy readiness fence required"));}
    struct bv_mpv_proxy_sr_loan *l=calloc(1,sizeof(*l));
    if(!l){RELEASE(source_ready);PROXY_RETURN(fail(s,BV_INTERNAL,E_OUTOFMEMORY,"SR loan allocation failed"));}
    l->bridge=p;l->host_thread=GetCurrentThreadId();l->source_ready=source_ready;
    l->device_ref=av_buffer_ref(in->device_ref);
    if(!l->device_ref){RELEASE(l->source_ready);free(l);
        PROXY_RETURN(fail(s,BV_INTERNAL,E_OUTOFMEMORY,"actual default HW owner reference failed"));}
    if(!p->proxy_owner_ref)p->proxy_owner_ref=av_buffer_ref(in->device_ref);
    if(!p->proxy_owner_ref){av_buffer_unref(&l->device_ref);RELEASE(l->source_ready);free(l);
        PROXY_RETURN(fail(s,BV_INTERNAL,E_OUTOFMEMORY,"bounded bridge HW owner reference failed"));}
    l->retained_lease=in->retained_lease;l->release_retained_lease=in->release_retained_lease;
    p->proxy_sr_loan=l;*loan=l;valid_lease=0;
    ID3DDeviceContextState *previous=NULL;
    hr=proxy_enter(p,l,&previous);
    if(FAILED(hr)){
        if(!l->attempted&&!l->untracked)proxy_drop_loan(loan);
        return fail(s,BV_DEVICE_FAILURE,hr,"original proxy context exclusion/state rejected");
    }
    hr=ID3D11DeviceContext4_Wait(p->context4,l->source_ready,in->producer_ready_value);
    if(SUCCEEDED(hr)){
        ID3D11DeviceContext_CopyResource(p->context,(ID3D11Resource*)p->input11,(ID3D11Resource*)in->proxy);
        l->producer_value=++p->producer_value;
        hr=ID3D11DeviceContext4_Signal(p->context4,p->producer11,l->producer_value);
        ID3D11DeviceContext_Flush(p->context);
    }
    int released=proxy_leave(p,l,&previous);
    if(FAILED(hr)||!released){l->untracked=1;p->failed=1;return fail(s,BV_DEVICE_FAILURE,
        FAILED(hr)?hr:E_UNEXPECTED,"proxy producer handoff/unlock failed; whole loan retained");}
    /* CALLER PRECONDITION: decoder scope and original context exclusion must
     * already have ended. The default Win32 mutex is recursive; checked try0
     * above cannot detect that the caller held an outer lock. This comment is
     * not a runtime proof. No current production caller authorizes this route. */
    bv_frame_v1 f={0};f.size=sizeof(f);f.abi=BV_ABI_V1;
    f.session_id=in->session;f.source_generation=in->generation;f.sequence=in->sequence;f.adapter_luid=in->adapter_luid;
    f.pts_numerator=in->pts_numerator;f.pts_denominator=in->pts_denominator;
    f.input_color=f.output_color=BV_SRGB_BT709_FULL_RGBA8;
    f.input_texture=p->input12;f.output_texture=p->output12;
    f.input_ready_fence=p->producer12;f.input_ready_value=l->producer_value;
    f.input_state=f.output_state=f.output_final_state=D3D12_RESOURCE_STATE_COMMON;
    bv_result_v1 r={0};r.size=sizeof(r);r.abi=BV_ABI_V1;
    int rc=p->process(p->core,&f,&r,s);
    if(rc!=BV_OK){l->untracked=1;p->failed=1;return rc;}
    if(r.size!=sizeof(r)||r.abi!=BV_ABI_V1||r.session_id!=f.session_id||r.source_generation!=f.source_generation||
       r.sequence!=f.sequence||r.adapter_luid!=f.adapter_luid||r.pts_numerator!=f.pts_numerator||r.pts_denominator!=f.pts_denominator||
       r.output_texture!=p->output12||r.output_state!=D3D12_RESOURCE_STATE_COMMON||r.output_color!=BV_SRGB_BT709_FULL_RGBA8||
       r.output_dxgi_format!=(uint32_t)DXGI_FORMAT_R8G8B8A8_UNORM||r.effects_applied!=BV_VIDEO_SR||
       r.output_width!=p->config.output_width||r.output_height!=p->config.output_height||
       !r.completion_fence||!r.completion_value||r.completion_value==UINT64_MAX){
        l->untracked=1;p->failed=1;return fail(s,BV_STALE,E_FAIL,"exact SR-only core receipt mismatch; loan retained");}
    hr=IUnknown_QueryInterface((IUnknown*)r.completion_fence,&IID_ID3D12Fence,(void**)&l->core_done);
    ID3D12Device *fence_device=NULL;
    if(SUCCEEDED(hr))hr=ID3D12Fence_GetDevice(l->core_done,&IID_ID3D12Device,(void**)&fence_device);
    int same=SUCCEEDED(hr)&&proxy_same_object((IUnknown*)fence_device,(IUnknown*)p->device12);
    RELEASE(fence_device);
    if(!same||ID3D12Fence_GetCompletedValue(l->core_done)==UINT64_MAX){
        l->untracked=1;p->failed=1;return fail(s,BV_STALE,E_FAIL,"core completion fence identity rejected; loan retained");}
    l->core_value=r.completion_value;
    hr=ID3D12CommandQueue_Wait(p->queue12,l->core_done,l->core_value);
    if(SUCCEEDED(hr)){
        l->ready_value=++p->consumer_value;
        hr=ID3D12CommandQueue_Signal(p->queue12,p->consumer12,l->ready_value);
    }
    if(FAILED(hr)){l->untracked=1;p->failed=1;return fail(s,BV_DEVICE_FAILURE,hr,"SR completion sharing failed; loan retained");}
    hr=proxy_enter(p,l,&previous);
    if(FAILED(hr)){l->untracked=1;p->failed=1;return fail(s,BV_DEVICE_FAILURE,hr,"SR consumer context rejected; loan retained");}
    hr=ID3D11DeviceContext4_Wait(p->context4,p->consumer11,l->ready_value);
    ID3D11DeviceContext_Flush(p->context);released=proxy_leave(p,l,&previous);
    if(FAILED(hr)||!released){l->untracked=1;p->failed=1;return fail(s,BV_DEVICE_FAILURE,
        FAILED(hr)?hr:E_UNEXPECTED,"SR consumer readiness Wait/unlock rejected; loan retained");}
    *result=(struct bv_mpv_proxy_sr_result){
        .output=p->output11,.ready_fence=p->consumer11,.ready_value=l->ready_value,
        .core_completion_fence=(IUnknown*)l->core_done,.core_completion_value=l->core_value,
        .session=r.session_id,.configuration=in->configuration,.generation=r.source_generation,
        .sequence=r.sequence,.adapter_luid=r.adapter_luid,.pts_numerator=r.pts_numerator,.pts_denominator=r.pts_denominator,
        .width=r.output_width,.height=r.output_height,.effects=r.effects_applied};
    p->sequence=in->sequence;return BV_OK;
#undef PROXY_RETURN
}
int bv_mpv_bridge_release_proxy_sr_loan(struct bv_mpv_proxy_sr_loan **lp,
    IUnknown *final_fence,uint64_t final_value,bv_status_v1 *s) {
    if(!s)return BV_INVALID;status_init(s);
    if(!lp||!*lp)return fail(s,BV_INVALID,E_INVALIDARG,"live SR loan required");
    struct bv_mpv_proxy_sr_loan *l=*lp;struct bv_mpv_bridge *p=l->bridge;
    if(!p||p->proxy_sr_loan!=l||l->host_thread!=GetCurrentThreadId())
        return fail(s,BV_STALE,E_INVALIDARG,"original serialized host thread/loan owner required");
    if(l->untracked)return fail(s,BV_RESET_REQUIRED,E_PENDING,"untracked proxy/SR work retained until process exit");
    if(!l->final_use){
        ID3D11Fence *f=NULL;
        HRESULT hr=final_fence?IUnknown_QueryInterface(final_fence,&IID_ID3D11Fence,(void**)&f):E_INVALIDARG;
        if(FAILED(hr)||!f||!final_value||final_value==UINT64_MAX||
           !proxy_same_device11(p,(ID3D11DeviceChild*)f)||
           proxy_same_object((IUnknown*)f,(IUnknown*)l->source_ready)||
           proxy_same_object((IUnknown*)f,(IUnknown*)p->producer11)||
           proxy_same_object((IUnknown*)f,(IUnknown*)p->consumer11)){RELEASE(f);
            return fail(s,BV_INVALID,E_INVALIDARG,"independent same-device final-consumer fence required");}
        uint64_t completed=ID3D11Fence_GetCompletedValue(f);
        if(completed==UINT64_MAX||completed>=final_value){RELEASE(f);
            return fail(s,BV_INVALID,E_INVALIDARG,"seal before the actual future final-use Signal");}
        l->final_use=f;l->final_value=final_value;
        return fail(s,BV_BUSY,E_PENDING,"SR loan sealed; actual final-consumer Signal/completion still required");
    }
    if((final_fence||final_value)&&(!final_fence||final_value!=l->final_value||
       !proxy_same_object(final_fence,(IUnknown*)l->final_use)))
        return fail(s,BV_INVALID,E_INVALIDARG,"sealed final-use fence/value cannot change");
    uint64_t core_done=ID3D12Fence_GetCompletedValue(l->core_done);
    if(core_done==UINT64_MAX||core_done<l->core_value||
       !proxy_completed(p->producer11,l->producer_value)||
       !proxy_completed(p->consumer11,l->ready_value)||!proxy_completed(l->final_use,l->final_value))
        return fail(s,BV_BUSY,E_PENDING,"actual producer/core/ready/final completion not proved; loan retained");
    proxy_drop_loan(lp);return BV_OK;
}

int bv_mpv_bridge_reset(struct bv_mpv_bridge *p,uint64_t generation,bv_status_v1 *s) {
    status_init(s);if(!p||generation<=p->config.generation)return fail(s,BV_STALE,E_INVALIDARG,"reset generation is not newer");
    if(p->proxy_sr_loan)return fail(s,BV_BUSY,E_PENDING,"reset waits for actual final-consumer loan retirement");
    int rc=wait_native(p,p->producer11,p->producer_value,s);
    if(rc==BV_OK)rc=wait_native(p,p->consumer11,p->consumer_done,s);
    if(rc==BV_OK)rc=p->reset(p->core,p->config.session,generation,s);
    if(rc==BV_OK){p->config.generation=generation;p->sequence=0;p->failed=0;}else p->failed=1;return rc;
}
int bv_mpv_bridge_destroy(struct bv_mpv_bridge **pp,bv_status_v1 *s) {
    status_init(s);if(!pp||!*pp)return BV_OK;
    if((*pp)->proxy_sr_loan)return fail(s,BV_BUSY,E_PENDING,"destroy retains bridge pointer while SR loan is outstanding");
    struct bv_mpv_bridge *p=*pp;*pp=NULL;
    int rc=destroy_core(p,s);
    if(rc==BV_OK){release_context(p);return BV_OK;}
    AcquireSRWLockExclusive(&retire_lock);
    /* A second pending context is impossible with the core ABI's one host.
       Never overwrite/quarantine-release a pending owner on a failed drain. */
    if(!quarantined)quarantined=p;
    ReleaseSRWLockExclusive(&retire_lock);return rc;
}
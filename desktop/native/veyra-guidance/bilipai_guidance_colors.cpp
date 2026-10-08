// GPL-3.0-or-later. No NGX/NVOF invocation; honest color stage of the future producer.
#include "bilipai_guidance_colors.h"
#include "bilipai_color_shader_bytecode.h"
#include <windows.h>
#include <dxgi1_6.h>
#include <wrl/client.h>
#include <cstring>
#include <initializer_list>
#include <climits>
#include <new>

namespace bilipai::guidance {
using Microsoft::WRL::ComPtr;
namespace {
bool sameObject(IUnknown* a,IUnknown* b) {
    ComPtr<IUnknown> x,y;
    return a&&b&&SUCCEEDED(a->QueryInterface(IID_PPV_ARGS(&x)))&&
        SUCCEEDED(b->QueryInterface(IID_PPV_ARGS(&y)))&&x.Get()==y.Get();
}
bool sameDevice(ID3D12DeviceChild* r,ID3D12Device* d) {
    ComPtr<ID3D12Device> actual;
    return r&&SUCCEEDED(r->GetDevice(IID_PPV_ARGS(&actual)))&&sameObject(actual.Get(),d);
}
uint64_t luid(ID3D12Device* d) {
    const auto v=d->GetAdapterLuid();
    return uint64_t(uint32_t(v.LowPart))|(uint64_t(uint32_t(v.HighPart))<<32);
}
bool exactIdentity(const FrameIdentity& a,const FrameIdentity& b) {
    return a.session==b.session&&a.generation==b.generation&&a.history==b.history&&
        a.sequence==b.sequence&&a.adapterLuid==b.adapterLuid&&
        a.ptsNumerator==b.ptsNumerator&&a.ptsDenominator==b.ptsDenominator;
}
void barrier(ID3D12GraphicsCommandList* l,ID3D12Resource* r,
             D3D12_RESOURCE_STATES before,D3D12_RESOURCE_STATES after) {
    if(before==after)return;
    D3D12_RESOURCE_BARRIER b{};b.Type=D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    b.Transition={r,D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES,before,after};
    l->ResourceBarrier(1,&b);
}
void uav(ID3D12GraphicsCommandList* l,ID3D12Resource* r) {
    D3D12_RESOURCE_BARRIER b{};b.Type=D3D12_RESOURCE_BARRIER_TYPE_UAV;b.UAV.pResource=r;
    l->ResourceBarrier(1,&b);
}
bool texture(ID3D12Resource* r,ID3D12Device* d,uint32_t w,uint32_t h,DXGI_FORMAT format) {
    if(!sameDevice(r,d))return false;
    const auto x=r->GetDesc();
    return x.Dimension==D3D12_RESOURCE_DIMENSION_TEXTURE2D&&x.Width==w&&x.Height==h&&
        x.DepthOrArraySize==1&&x.MipLevels==1&&x.SampleDesc.Count==1&&x.SampleDesc.Quality==0&&
        x.Format==format&&!(x.Flags&D3D12_RESOURCE_FLAG_DENY_SHADER_RESOURCE);
}
HRESULT makeTexture(ID3D12Device* d,uint32_t w,uint32_t h,DXGI_FORMAT f,
                    D3D12_RESOURCE_FLAGS flags,ComPtr<ID3D12Resource>& result) {
    D3D12_HEAP_PROPERTIES hp{};hp.Type=D3D12_HEAP_TYPE_DEFAULT;
    D3D12_RESOURCE_DESC x{};x.Dimension=D3D12_RESOURCE_DIMENSION_TEXTURE2D;
    x.Width=w;x.Height=h;x.DepthOrArraySize=1;x.MipLevels=1;x.Format=f;
    x.SampleDesc.Count=1;x.Flags=flags;
    return d->CreateCommittedResource(&hp,D3D12_HEAP_FLAG_NONE,&x,
        D3D12_RESOURCE_STATE_COMMON,nullptr,IID_PPV_ARGS(&result));
}
HRESULT root(ID3D12Device* d,bool compute,ComPtr<ID3D12RootSignature>& result) {
    D3D12_DESCRIPTOR_RANGE ranges[2]{};
    ranges[0]={D3D12_DESCRIPTOR_RANGE_TYPE_SRV,1,0,0,0};
    ranges[1]={D3D12_DESCRIPTOR_RANGE_TYPE_UAV,1,0,0,0};
    D3D12_ROOT_PARAMETER p[3]{};
    p[0].ParameterType=D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE;
    p[0].DescriptorTable={1,&ranges[0]};
    p[0].ShaderVisibility=compute?D3D12_SHADER_VISIBILITY_ALL:D3D12_SHADER_VISIBILITY_PIXEL;
    p[1].ParameterType=D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE;
    p[1].DescriptorTable={1,&ranges[1]};p[1].ShaderVisibility=D3D12_SHADER_VISIBILITY_ALL;
    p[2].ParameterType=D3D12_ROOT_PARAMETER_TYPE_32BIT_CONSTANTS;
    p[2].Constants={0,0,4};p[2].ShaderVisibility=D3D12_SHADER_VISIBILITY_ALL;
    D3D12_ROOT_SIGNATURE_DESC desc{};desc.NumParameters=compute?3u:1u;
    desc.pParameters=p;desc.Flags=D3D12_ROOT_SIGNATURE_FLAG_NONE;
    ComPtr<ID3DBlob> bytes,error;
    const auto hr=D3D12SerializeRootSignature(&desc,D3D_ROOT_SIGNATURE_VERSION_1,&bytes,&error);
    if(FAILED(hr))return hr;
    return d->CreateRootSignature(0,bytes->GetBufferPointer(),bytes->GetBufferSize(),IID_PPV_ARGS(&result));
}
}
struct ColorProducer::State {
    ColorConfig config;
    ComPtr<ID3D12Device> device;
    ComPtr<ID3D12CommandQueue> queue;
    ComPtr<ID3D12CommandAllocator> allocator;
    ComPtr<ID3D12GraphicsCommandList> list;
    ComPtr<ID3D12RootSignature> computeRoot,graphicsRoot;
    ComPtr<ID3D12PipelineState> linearPass,bgraPass;
    ComPtr<ID3D12DescriptorHeap> views,staging,rtv;
    ComPtr<ID3D12Resource> linear,previous,current,depth,heldInput;
    ComPtr<ID3D12Fence> fence,heldInputFence,finalUse;
    HANDLE event=nullptr;
    UINT increment=0;
    uint64_t nextValue=1,lastValue=0,finalValue=0,lastSequence=0;
    FrameIdentity currentIdentity;
    bool outstanding=false,untracked=false,failed=false,hasPrevious=false;
    ~State(){if(event)CloseHandle(event);}
    D3D12_CPU_DESCRIPTOR_HANDLE cpu(UINT i)const {
        auto h=views->GetCPUDescriptorHandleForHeapStart();h.ptr+=SIZE_T(i)*increment;return h;
    }
    D3D12_GPU_DESCRIPTOR_HANDLE gpu(UINT i)const {
        auto h=views->GetGPUDescriptorHandleForHeapStart();h.ptr+=UINT64(i)*increment;return h;
    }
    D3D12_CPU_DESCRIPTOR_HANDLE staged(UINT i)const {
        auto h=staging->GetCPUDescriptorHandleForHeapStart();h.ptr+=SIZE_T(i)*increment;return h;
    }
    HRESULT wait(ID3D12Fence* f,uint64_t value) {
        if(!value)return S_OK;
        const auto deadline=GetTickCount64()+config.timeoutMs;
        for(;;){
            const auto removed=device->GetDeviceRemovedReason();if(FAILED(removed))return removed;
            const auto done=f->GetCompletedValue();
            if(done==UINT64_MAX)return DXGI_ERROR_DEVICE_REMOVED;
            if(done>=value)return S_OK;
            auto now=GetTickCount64();if(now>=deadline)return HRESULT_FROM_WIN32(WAIT_TIMEOUT);
            if(!ResetEvent(event))return HRESULT_FROM_WIN32(GetLastError());
            auto hr=f->SetEventOnCompletion(value,event);if(FAILED(hr))return hr;
            const auto after=f->GetCompletedValue();
            if(after==UINT64_MAX)return DXGI_ERROR_DEVICE_REMOVED;
            if(after>=value)continue;
            now=GetTickCount64();if(now>=deadline)continue;
            const auto waited=WaitForSingleObject(event,DWORD(deadline-now));
            if(waited==WAIT_FAILED)return HRESULT_FROM_WIN32(GetLastError());
            if(waited!=WAIT_OBJECT_0&&waited!=WAIT_TIMEOUT)return E_FAIL;
            // Old/stale wakeups never authorize reuse; always reread the fence.
        }
    }
    HRESULT drain() {
        if(untracked)return E_FAIL;
        auto hr=wait(fence.Get(),lastValue);if(FAILED(hr))return hr;
        if(outstanding){
            if(!finalUse||!finalValue)return E_PENDING;
            hr=wait(finalUse.Get(),finalValue);if(FAILED(hr))return hr;
        }
        outstanding=false;heldInput.Reset();heldInputFence.Reset();finalUse.Reset();finalValue=0;
        return S_OK;
    }
};
ColorProducer::ColorProducer()=default;
ColorProducer::~ColorProducer(){
    // The owning native source is bounded to one live/quarantined instance.
    // Do not release command allocator/textures behind an unqualified timeout.
    if(state_){const auto hr=state_->drain();if(SUCCEEDED(hr))delete state_;state_=nullptr;}
}
HRESULT ColorProducer::initialize(ID3D12Device* d,ID3D12CommandQueue* q,const ColorConfig& c) {
    if(state_||!d||!q||!sameDevice(q,d)||q->GetDesc().Type!=D3D12_COMMAND_LIST_TYPE_DIRECT||
        !c.session||!c.generation||!c.history||!c.adapterLuid||c.adapterLuid!=luid(d)||
        !c.width||!c.height||!c.targetWidth||!c.targetHeight||c.width>16384||c.height>16384||
        c.targetWidth>16384||c.targetHeight>16384||c.timeoutMs<1||c.timeoutMs>10000)return E_INVALIDARG;
    State* p=new(std::nothrow) State;if(!p)return E_OUTOFMEMORY;
    p->config=c;p->device=d;p->queue=q;
    auto fail=[&](HRESULT hr){delete p;return hr;};
    for(const auto format:{DXGI_FORMAT_R8G8B8A8_UNORM,DXGI_FORMAT_R16G16B16A16_FLOAT,DXGI_FORMAT_B8G8R8A8_UNORM,DXGI_FORMAT_R32_FLOAT}){
        D3D12_FEATURE_DATA_FORMAT_SUPPORT s{format};auto hr=d->CheckFeatureSupport(D3D12_FEATURE_FORMAT_SUPPORT,&s,sizeof(s));
        if(FAILED(hr))return fail(hr);
        auto required=D3D12_FORMAT_SUPPORT1_TEXTURE2D|D3D12_FORMAT_SUPPORT1_SHADER_LOAD;
        if(format==DXGI_FORMAT_B8G8R8A8_UNORM)required|=D3D12_FORMAT_SUPPORT1_RENDER_TARGET;
        if((s.Support1&required)!=required||((format==DXGI_FORMAT_R16G16B16A16_FLOAT||format==DXGI_FORMAT_R32_FLOAT)&&
           !(s.Support2&D3D12_FORMAT_SUPPORT2_UAV_TYPED_STORE)))return fail(DXGI_ERROR_UNSUPPORTED);
    }
    auto hr=makeTexture(d,c.width,c.height,DXGI_FORMAT_R16G16B16A16_FLOAT,D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS,p->linear);if(FAILED(hr))return fail(hr);
    hr=makeTexture(d,c.width,c.height,DXGI_FORMAT_B8G8R8A8_UNORM,D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET,p->current);if(FAILED(hr))return fail(hr);
    hr=makeTexture(d,c.width,c.height,DXGI_FORMAT_B8G8R8A8_UNORM,D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET,p->previous);if(FAILED(hr))return fail(hr);
    hr=makeTexture(d,c.targetWidth,c.targetHeight,DXGI_FORMAT_R32_FLOAT,D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS,p->depth);if(FAILED(hr))return fail(hr);
    hr=root(d,true,p->computeRoot);if(FAILED(hr))return fail(hr);hr=root(d,false,p->graphicsRoot);if(FAILED(hr))return fail(hr);
    D3D12_COMPUTE_PIPELINE_STATE_DESC cp{};cp.pRootSignature=p->computeRoot.Get();
    cp.CS={shader::srgbToLinear,sizeof(shader::srgbToLinear)};
    hr=d->CreateComputePipelineState(&cp,IID_PPV_ARGS(&p->linearPass));if(FAILED(hr))return fail(hr);
    D3D12_GRAPHICS_PIPELINE_STATE_DESC gp{};gp.pRootSignature=p->graphicsRoot.Get();
    gp.VS={shader::bgraVs,sizeof(shader::bgraVs)};gp.PS={shader::bgraPs,sizeof(shader::bgraPs)};
    for(auto& rt:gp.BlendState.RenderTarget){
        rt.SrcBlend=D3D12_BLEND_ONE;rt.DestBlend=D3D12_BLEND_ZERO;rt.BlendOp=D3D12_BLEND_OP_ADD;
        rt.SrcBlendAlpha=D3D12_BLEND_ONE;rt.DestBlendAlpha=D3D12_BLEND_ZERO;rt.BlendOpAlpha=D3D12_BLEND_OP_ADD;
        rt.LogicOp=D3D12_LOGIC_OP_NOOP;rt.RenderTargetWriteMask=D3D12_COLOR_WRITE_ENABLE_ALL;
    }
    gp.RasterizerState.FillMode=D3D12_FILL_MODE_SOLID;gp.RasterizerState.CullMode=D3D12_CULL_MODE_NONE;
    gp.RasterizerState.DepthClipEnable=TRUE;gp.SampleMask=UINT_MAX;
    gp.DepthStencilState.DepthEnable=FALSE;gp.DepthStencilState.DepthFunc=D3D12_COMPARISON_FUNC_ALWAYS;
    gp.DepthStencilState.StencilEnable=FALSE;gp.DepthStencilState.StencilReadMask=0xff;gp.DepthStencilState.StencilWriteMask=0xff;
    gp.DepthStencilState.FrontFace={D3D12_STENCIL_OP_KEEP,D3D12_STENCIL_OP_KEEP,D3D12_STENCIL_OP_KEEP,D3D12_COMPARISON_FUNC_ALWAYS};
    gp.DepthStencilState.BackFace=gp.DepthStencilState.FrontFace;
    gp.PrimitiveTopologyType=D3D12_PRIMITIVE_TOPOLOGY_TYPE_TRIANGLE;gp.NumRenderTargets=1;
    gp.RTVFormats[0]=DXGI_FORMAT_B8G8R8A8_UNORM;gp.SampleDesc.Count=1;
    hr=d->CreateGraphicsPipelineState(&gp,IID_PPV_ARGS(&p->bgraPass));if(FAILED(hr))return fail(hr);
    D3D12_DESCRIPTOR_HEAP_DESC vh{};vh.Type=D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV;vh.NumDescriptors=3;vh.Flags=D3D12_DESCRIPTOR_HEAP_FLAG_SHADER_VISIBLE;
    hr=d->CreateDescriptorHeap(&vh,IID_PPV_ARGS(&p->views));if(FAILED(hr))return fail(hr);
    vh.Flags=D3D12_DESCRIPTOR_HEAP_FLAG_NONE;
    hr=d->CreateDescriptorHeap(&vh,IID_PPV_ARGS(&p->staging));if(FAILED(hr))return fail(hr);
    vh.Type=D3D12_DESCRIPTOR_HEAP_TYPE_RTV;vh.NumDescriptors=1;
    hr=d->CreateDescriptorHeap(&vh,IID_PPV_ARGS(&p->rtv));if(FAILED(hr))return fail(hr);
    p->increment=d->GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);
    D3D12_UNORDERED_ACCESS_VIEW_DESC v{};v.ViewDimension=D3D12_UAV_DIMENSION_TEXTURE2D;v.Format=DXGI_FORMAT_R16G16B16A16_FLOAT;
    // Preserve fixed GpuPassUtils' staging-heap driver workaround: do not
    // create resource views directly in a shader-visible heap.
    d->CreateUnorderedAccessView(p->linear.Get(),nullptr,&v,p->staged(1));v.Format=DXGI_FORMAT_R32_FLOAT;
    d->CreateUnorderedAccessView(p->depth.Get(),nullptr,&v,p->staged(2));
    d->CopyDescriptorsSimple(1,p->cpu(1),p->staged(1),D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);
    d->CopyDescriptorsSimple(1,p->cpu(2),p->staged(2),D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);
    D3D12_RENDER_TARGET_VIEW_DESC rt{};rt.Format=DXGI_FORMAT_B8G8R8A8_UNORM;rt.ViewDimension=D3D12_RTV_DIMENSION_TEXTURE2D;
    d->CreateRenderTargetView(p->current.Get(),&rt,p->rtv->GetCPUDescriptorHandleForHeapStart());
    hr=d->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT,IID_PPV_ARGS(&p->allocator));if(FAILED(hr))return fail(hr);
    hr=d->CreateCommandList(0,D3D12_COMMAND_LIST_TYPE_DIRECT,p->allocator.Get(),nullptr,IID_PPV_ARGS(&p->list));if(FAILED(hr))return fail(hr);
    hr=p->list->Close();if(FAILED(hr))return fail(hr);
    hr=d->CreateFence(0,D3D12_FENCE_FLAG_NONE,IID_PPV_ARGS(&p->fence));if(FAILED(hr))return fail(hr);
    p->event=CreateEventW(nullptr,FALSE,FALSE,nullptr);if(!p->event)return fail(HRESULT_FROM_WIN32(GetLastError()));
    state_=p;return S_OK;
}
HRESULT ColorProducer::prepare(ID3D12Resource* input,ID3D12Fence* ready,uint64_t value,
 const FrameIdentity& f,bool reset,ColorPacket& result){
    result={};auto* p=state_;
    if(!p||p->failed||!texture(input,p->device.Get(),p->config.width,p->config.height,DXGI_FORMAT_R8G8B8A8_UNORM)||
       !sameDevice(ready,p->device.Get())||!value||value==UINT64_MAX||f.session!=p->config.session||
       f.generation!=p->config.generation||f.history!=p->config.history||f.adapterLuid!=p->config.adapterLuid||
       !f.sequence||f.sequence<=p->lastSequence||f.ptsDenominator<=0||p->nextValue==UINT64_MAX)return E_INVALIDARG;
    auto hr=p->drain();if(FAILED(hr))return hr;
    hr=p->allocator->Reset();if(SUCCEEDED(hr))hr=p->list->Reset(p->allocator.Get(),nullptr);if(FAILED(hr)){p->failed=true;return hr;}
    auto* l=p->list.Get();const bool previousValid=p->hasPrevious&&!reset;
    if(previousValid){
        barrier(l,p->current.Get(),D3D12_RESOURCE_STATE_COMMON,D3D12_RESOURCE_STATE_COPY_SOURCE);
        barrier(l,p->previous.Get(),D3D12_RESOURCE_STATE_COMMON,D3D12_RESOURCE_STATE_COPY_DEST);
        l->CopyResource(p->previous.Get(),p->current.Get());
        barrier(l,p->current.Get(),D3D12_RESOURCE_STATE_COPY_SOURCE,D3D12_RESOURCE_STATE_COMMON);
        barrier(l,p->previous.Get(),D3D12_RESOURCE_STATE_COPY_DEST,D3D12_RESOURCE_STATE_COMMON);
    }
    D3D12_SHADER_RESOURCE_VIEW_DESC srv{};srv.Format=DXGI_FORMAT_R8G8B8A8_UNORM;
    srv.ViewDimension=D3D12_SRV_DIMENSION_TEXTURE2D;srv.Texture2D.MipLevels=1;srv.Shader4ComponentMapping=D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING;
    p->device->CreateShaderResourceView(input,&srv,p->staged(0));
    p->device->CopyDescriptorsSimple(1,p->cpu(0),p->staged(0),D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);
    ID3D12DescriptorHeap* heaps[]={p->views.Get()};l->SetDescriptorHeaps(1,heaps);
    barrier(l,input,D3D12_RESOURCE_STATE_COMMON,D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE|D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE);
    barrier(l,p->linear.Get(),D3D12_RESOURCE_STATE_COMMON,D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
    l->SetComputeRootSignature(p->computeRoot.Get());l->SetPipelineState(p->linearPass.Get());
    l->SetComputeRootDescriptorTable(0,p->gpu(0));l->SetComputeRootDescriptorTable(1,p->gpu(1));
    const uint32_t dims[4]={p->config.width,p->config.height,0,0};l->SetComputeRoot32BitConstants(2,4,dims,0);
    l->Dispatch((p->config.width+15)/16,(p->config.height+15)/16,1);uav(l,p->linear.Get());
    barrier(l,p->linear.Get(),D3D12_RESOURCE_STATE_UNORDERED_ACCESS,D3D12_RESOURCE_STATE_COMMON);
    barrier(l,p->current.Get(),D3D12_RESOURCE_STATE_COMMON,D3D12_RESOURCE_STATE_RENDER_TARGET);
    const D3D12_VIEWPORT vp{0,0,float(p->config.width),float(p->config.height),0,1};
    const D3D12_RECT sc{0,0,LONG(p->config.width),LONG(p->config.height)};
    l->RSSetViewports(1,&vp);l->RSSetScissorRects(1,&sc);l->SetGraphicsRootSignature(p->graphicsRoot.Get());
    l->SetPipelineState(p->bgraPass.Get());l->SetGraphicsRootDescriptorTable(0,p->gpu(0));
    auto rtv=p->rtv->GetCPUDescriptorHandleForHeapStart();l->OMSetRenderTargets(1,&rtv,FALSE,nullptr);
    l->IASetPrimitiveTopology(D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST);l->DrawInstanced(3,1,0,0);
    barrier(l,p->current.Get(),D3D12_RESOURCE_STATE_RENDER_TARGET,D3D12_RESOURCE_STATE_COMMON);
    barrier(l,input,D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE|D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE,D3D12_RESOURCE_STATE_COMMON);
    barrier(l,p->depth.Get(),D3D12_RESOURCE_STATE_COMMON,D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
    const float conventional[4]={.5f,.5f,.5f,.5f};
    l->ClearUnorderedAccessViewFloat(p->gpu(2),p->staged(2),p->depth.Get(),conventional,0,nullptr);
    uav(l,p->depth.Get());barrier(l,p->depth.Get(),D3D12_RESOURCE_STATE_UNORDERED_ACCESS,D3D12_RESOURCE_STATE_COMMON);
    hr=l->Close();if(FAILED(hr)){p->failed=true;return hr;}
    p->heldInput=input;p->heldInputFence=ready;
    hr=p->queue->Wait(ready,value);if(FAILED(hr)){p->heldInput.Reset();p->heldInputFence.Reset();p->failed=true;return hr;}
    p->outstanding=true;p->untracked=true;p->currentIdentity=f;
    ID3D12CommandList* lists[]={l};p->queue->ExecuteCommandLists(1,lists);
    const auto signaled=p->nextValue++;hr=p->queue->Signal(p->fence.Get(),signaled);
    if(FAILED(hr)){p->failed=true;return hr;} // retain all inputs/allocator/textures forever
    p->untracked=false;p->lastValue=signaled;p->lastSequence=f.sequence;p->hasPrevious=true;
    result.identity=f;result.linearColor=p->linear.Get();result.previousEncoded=p->previous.Get();
    result.currentEncoded=p->current.Get();result.conventionalDepth=p->depth.Get();
    result.readyFence=p->fence.Get();result.readyValue=signaled;result.previousValid=previousValid;
    return S_OK;
}
HRESULT ColorProducer::sealFinalUse(const FrameIdentity& identity,ID3D12Fence* fence,uint64_t value){
    auto* p=state_;
    if(!p||p->untracked||!p->outstanding||p->finalUse||!exactIdentity(identity,p->currentIdentity)||
       !sameDevice(fence,p->device.Get())||!value||value==UINT64_MAX)return E_INVALIDARG;
    // Caller is the actual native producer, not an external callback or a new job.
    p->finalUse=fence;p->finalValue=value;return S_OK;
}
HRESULT ColorProducer::resetHistory(uint64_t generation,uint64_t history){
    auto* p=state_;if(!p||p->failed||generation<=p->config.generation||history<=p->config.history)return E_INVALIDARG;
    const auto hr=p->drain();if(FAILED(hr))return hr;
    p->config.generation=generation;p->config.history=history;p->lastSequence=0;p->hasPrevious=false;return S_OK;
}
HRESULT ColorProducer::retire(){
    if(!state_)return S_OK;const auto hr=state_->drain();if(FAILED(hr))return hr;
    delete state_;state_=nullptr;return S_OK;
}
}

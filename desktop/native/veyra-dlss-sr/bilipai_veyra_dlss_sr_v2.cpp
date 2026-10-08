// BiliPai-owned independent standard DLSS SR candidate. GPL-3.0-or-later.
#define BILIDLSS_EXPORTS
#include "bilipai_veyra_dlss_sr_v2.h"
#include "bilipai_ngx_shared_host.h"
#include "veyra/ngx/DlssSrBackend.h"
#include <d3d12.h>
#include <dxgi1_6.h>
#include <wrl/client.h>
#include <windows.h>
#include <algorithm>
#include <iterator>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <memory>
#include <mutex>
#include <string>
using Microsoft::WRL::ComPtr;
namespace {
std::mutex& g_mutex=bilipai::ngx::mutex();
uint64_t g_token=0;
struct Session {
    bvd_config_v2 config{};
    std::wstring runtime;
    std::string project,engine;
    ComPtr<ID3D12Device> device;
    ComPtr<ID3D12CommandQueue> queue;
    ComPtr<ID3D12CommandAllocator> allocator;
    ComPtr<ID3D12GraphicsCommandList> list;
    ComPtr<ID3D12Fence> fence,retainedReady;
    ComPtr<ID3D12Resource> retainedColor,retainedDepth,retainedMotion,retainedOutput;
    HANDLE event=nullptr;
    uint64_t nextFence=1,lastFence=0,lastSequence=0,historyEpoch=0;
    bool featuresCreated=false,failed=false,untrackedSubmission=false,needsReset=true,releaseQuarantine=false;
    bilipai::ngx::HostLease core{this,bilipai::ngx::Kind::Dlss};
    veyra::ngx::DlssSrBackend sr;
    NVSDK_NGX_Parameter* params=nullptr;
    ~Session(){
        // Published sessions arrive only after checked feature/parameter release
        // and actual same-device SDK shutdown Success/no-SEH. Member destructors
        // then have no remaining handle or initialized host to retry.
        if(event)CloseHandle(event);
    }
};
// No static owner destructor: uncertain GPU leases survive until safe destroy.
Session* g_session=nullptr;
uint64_t luidBits(LUID l) { return (uint64_t(uint32_t(l.HighPart)) << 32) | l.LowPart; }
bool validStatus(const bvd_status_v2* s) { return s && s->size == sizeof(*s) && s->abi == BVD_ABI_V2; }
int32_t report(bvd_status_v2* s, int32_t code, const char* message, HRESULT hr = S_OK, uint32_t upstream = 0, uint64_t coreResult = 0) {
    if (validStatus(s)) {
        s->code = code; s->native_hresult = uint32_t(hr); s->upstream_status = upstream;
        s->core_init_result = coreResult;
        std::memset(s->message, 0, sizeof(s->message));
        std::memcpy(s->message, message, (std::min)(std::strlen(message), sizeof(s->message)-1));
    }
    return code;
}

int32_t releaseFeature(Session& s,bvd_status_v2* st){
    if(s.releaseQuarantine)return report(st,BVD_FEATURE_FAILURE,"prior SDK release failed; retain entire session through process exit");
    s.releaseQuarantine=true; // before the fixed backend: throwing/logging paths may leave an uncertain handle
    if(!s.sr.release()){
        s.failed=true;s.core.quarantine();
        return report(st,BVD_FEATURE_FAILURE,"actual DLSS release rejected; permanently retain session and module");
    }
    s.featuresCreated=false;s.releaseQuarantine=false;
    return BVD_OK;
}
int32_t releaseParameters(Session& s,bvd_status_v2* st){
    if(s.releaseQuarantine)return report(st,BVD_CORE_FAILURE,"prior SDK release failed; retain entire session through process exit");
    if(!s.params)return BVD_OK;
    s.releaseQuarantine=true; // void upstream API reports external failure via core health
    s.core.destroyParameters(s.params);
    if(!s.core.healthy()){
        s.failed=true;s.core.quarantine();
        return report(st,BVD_CORE_FAILURE,"parameter release not certified; permanently retain entire session");
    }
    s.params=nullptr;s.releaseQuarantine=false;
    return BVD_OK;
}

int32_t checkedShutdown(Session& s,bvd_status_v2* st){
    if(s.releaseQuarantine)return report(st,BVD_CORE_FAILURE,"prior SDK retirement failed; retain entire session through process exit");
    s.releaseQuarantine=true;s.failed=true;
    bilipai::ngx::Issue issue;
    if(!s.core.retire(issue))
        return report(st,BVD_CORE_FAILURE,issue.message,issue.hr,static_cast<uint32_t>(issue.sdk));
    s.releaseQuarantine=false;return BVD_OK;
}
bool sameObject(IUnknown* a, IUnknown* b) {
    if (!a || !b) return false;
    ComPtr<IUnknown> x, y;
    return SUCCEEDED(a->QueryInterface(IID_PPV_ARGS(&x))) &&
        SUCCEEDED(b->QueryInterface(IID_PPV_ARGS(&y))) && x.Get() == y.Get();
}
bool sameDevice(ID3D12DeviceChild* resource, ID3D12Device* device) {
    ComPtr<ID3D12Device> owner;
    return resource && SUCCEEDED(resource->GetDevice(IID_PPV_ARGS(&owner))) && sameObject(owner.Get(), device);
}
void transition(ID3D12GraphicsCommandList* list, ID3D12Resource* r, D3D12_RESOURCE_STATES from, D3D12_RESOURCE_STATES to) {
    if (from == to) return;
    D3D12_RESOURCE_BARRIER b{}; b.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    b.Transition.pResource = r; b.Transition.StateBefore = from; b.Transition.StateAfter = to;
    b.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
    list->ResourceBarrier(1, &b);
}
void uav(ID3D12GraphicsCommandList* list, ID3D12Resource* r) {
    D3D12_RESOURCE_BARRIER b{}; b.Type = D3D12_RESOURCE_BARRIER_TYPE_UAV; b.UAV.pResource = r;
    list->ResourceBarrier(1, &b);
}
bool supportedState(uint32_t value) {
    const uint32_t shaderReads = D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE | D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE;
    return value == D3D12_RESOURCE_STATE_COMMON || value == D3D12_RESOURCE_STATE_COPY_SOURCE ||
        value == D3D12_RESOURCE_STATE_COPY_DEST || value == D3D12_RESOURCE_STATE_UNORDERED_ACCESS ||
        (value && !(value & ~shaderReads));
}bool texture(ID3D12Resource* r, ID3D12Device* device, uint32_t w, uint32_t h, DXGI_FORMAT format, bool output) {
    if (!sameDevice(r, device)) return false;
    const auto d = r->GetDesc();
    return d.Dimension == D3D12_RESOURCE_DIMENSION_TEXTURE2D && d.Width == w && d.Height == h &&
        d.DepthOrArraySize == 1 && d.MipLevels == 1 && d.SampleDesc.Count == 1 && d.SampleDesc.Quality == 0 &&
        d.Format == format && !(d.Flags & D3D12_RESOURCE_FLAG_DENY_SHADER_RESOURCE) &&
        (!output || (d.Flags & D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS));
}
int32_t drain(Session& s, bvd_status_v2* st,bool releaseCompletedFrames=true) {
    // Absolute deadline and completion-value recheck: an old notification
    // remaining after a timeout must never become a false device failure.
    const auto deadline = GetTickCount64() + s.config.wait_timeout_ms;
    for (;;) {
        const auto removed = s.device->GetDeviceRemovedReason();
        if (FAILED(removed)) return report(st, BVD_DEVICE_FAILURE, "D3D12 device removed; recreate the video seam", removed);
        if (s.untrackedSubmission) return report(st, BVD_DEVICE_FAILURE, "queue signal failed; outstanding GPU work cannot be released safely");
        const auto completed = s.fence->GetCompletedValue();
        if (completed == UINT64_MAX) return report(st, BVD_DEVICE_FAILURE, "completion fence reports removed device");
        if (!s.lastFence || completed >= s.lastFence) {
            if(releaseCompletedFrames){
                s.retainedColor.Reset();s.retainedDepth.Reset();s.retainedMotion.Reset();
                s.retainedOutput.Reset();s.retainedReady.Reset();
            }
            // reset/destroy retain even completed frame leases until actual
            // feature/parameter/shutdown acceptance qualifies retirement.
            return BVD_OK;
        }
        auto now = GetTickCount64();
        if (now >= deadline) return report(st, BVD_TIMEOUT, "GPU completion pending; retain handle/resources and retry");
        if (!ResetEvent(s.event)) return report(st, BVD_DEVICE_FAILURE, "completion event reset failed", HRESULT_FROM_WIN32(GetLastError()));
        const auto hr = s.fence->SetEventOnCompletion(s.lastFence, s.event);
        if (FAILED(hr)) return report(st, BVD_DEVICE_FAILURE, "completion event registration failed", hr);
        // A fence may complete between its first check and registration.
        const auto afterRegistration = s.fence->GetCompletedValue();
        if (afterRegistration == UINT64_MAX) return report(st, BVD_DEVICE_FAILURE, "completion fence reports removed device");
        if (afterRegistration >= s.lastFence) continue;
        now = GetTickCount64();
        if (now >= deadline) continue; // recheck completion/device before timeout
        const auto wait = WaitForSingleObject(s.event, static_cast<DWORD>(deadline - now));
        if (wait == WAIT_FAILED) return report(st, BVD_DEVICE_FAILURE, "GPU completion wait failed", HRESULT_FROM_WIN32(GetLastError()));
        if (wait != WAIT_OBJECT_0 && wait != WAIT_TIMEOUT) return report(st, BVD_DEVICE_FAILURE, "unexpected completion event wait result", E_FAIL);
        // Both timeout and wakeup loop through actual completion/device state.
        // A stale wakeup is harmless; pending resources remain referenced.
    }
}
int32_t begin(Session& s, bvd_status_v2* st) {
    auto hr = s.allocator->Reset();
    if (SUCCEEDED(hr)) hr = s.list->Reset(s.allocator.Get(), nullptr);
    if (FAILED(hr)) { s.failed = true; return report(st, BVD_DEVICE_FAILURE, "command allocator/list reset failed", hr); }
    return BVD_OK;
}

int32_t submit(Session& s,bvd_status_v2* st,ID3D12Fence* ready=nullptr,uint64_t readyValue=0){
    auto hr=s.list->Close();
    if(FAILED(hr)){s.failed=true;return report(st,BVD_DEVICE_FAILURE,"command list close failed",hr);}
    // Check the timeline before enqueueing any GPU operation.
    if(s.nextFence==UINT64_MAX){s.failed=true;return report(st,BVD_DEVICE_FAILURE,"fence timeline exhausted");}
    if(ready&&readyValue){
        hr=s.queue->Wait(ready,readyValue);
        if(FAILED(hr)){s.failed=true;return report(st,BVD_DEVICE_FAILURE,"producer queue wait rejected",hr);}
    }
    ID3D12CommandList* lists[]={s.list.Get()};
    s.queue->ExecuteCommandLists(1,lists);
    const auto value=s.nextFence++;
    hr=s.queue->Signal(s.fence.Get(),value);
    if(FAILED(hr)){s.untrackedSubmission=true;s.failed=true;return report(st,BVD_DEVICE_FAILURE,"queue signal failed; retain every GPU lease",hr);}
    s.lastFence=value;
    return BVD_OK;
}
int32_t createFeature(Session& s,bvd_status_v2* st){
    const auto started=begin(s,st);if(started!=BVD_OK)return started;
    veyra::Status upstream=veyra::Status::Ok;
    const veyra::ngx::DlssSrBackend::CreateDesc desc{
        s.config.input_width,s.config.input_height,s.config.output_width,s.config.output_height,
        static_cast<int>(s.config.perf_quality),false};
    if(!s.sr.create(s.core.host(),s.list.Get(),s.params,desc,upstream)||!s.sr.created()){
        s.list->Close();s.failed=true;
        return report(st,BVD_FEATURE_FAILURE,"actual standard DLSS SR create rejected",S_OK,uint32_t(upstream),s.core.initResult());
    }
    s.featuresCreated=true;s.needsReset=true;
    const auto submitted=submit(s,st);if(submitted!=BVD_OK)return submitted;
    return drain(s,st);
}
bool current(bvd_handle_v2 token){return g_session&&token&&token==g_token;}
bool ownGuidFormat(const char* p){
    if(!p||std::strlen(p)!=36)return false;
    for(size_t i=0;i<36;++i){
        if(i==8||i==13||i==18||i==23){if(p[i]!='-')return false;}
        else if(!((p[i]>='0'&&p[i]<='9')||(p[i]>='a'&&p[i]<='f')))return false;
    }
    return true;
}
}
extern "C" int32_t BVD_CALL bvd_create_v2(const bvd_config_v2* c,bvd_handle_v2* out,bvd_status_v2* st){
    if(out)*out=0;
    if(!validStatus(st)||!c||!out||c->size!=sizeof(*c)||c->abi!=BVD_ABI_V2)
        return report(st,BVD_ABI_MISMATCH,"DLSS SR ABI v2 size/version required");
    std::lock_guard lock(g_mutex);
    if(g_session||!bilipai::ngx::available())
        return report(st,BVD_BUSY,"one ABI already owns or quarantines the shared process NGX host");
    try{
        if(!c->session_id||!c->source_generation||!c->history_epoch||!c->adapter_luid||
            !c->d3d12_device||!c->d3d12_direct_queue||!c->runtime_directory_utf16||
            !ownGuidFormat(c->project_id_utf8)||!c->engine_version_utf8||
            std::strcmp(c->engine_version_utf8,"BiliPai-Veyra-DLSS-SR-2")||
            !c->input_width||!c->input_height||c->output_width<c->input_width||c->output_height<c->input_height||
            (c->input_width==c->output_width&&c->input_height==c->output_height)||
            c->output_width>16384||c->output_height>16384||c->perf_quality>2||
            !c->wait_timeout_ms||c->wait_timeout_ms>5000||c->flags||c->reserved||g_token==UINT64_MAX)
            return report(st,BVD_INVALID,"own identity, strict upscale, supported quality and bounded timeout required");
        static_assert(sizeof(wchar_t)==sizeof(uint16_t));
        std::wstring runtime(reinterpret_cast<const wchar_t*>(c->runtime_directory_utf16));
        if(!std::filesystem::path(runtime).is_absolute())return report(st,BVD_INVALID,"runtime directory must be absolute");
        bilipai::ngx::Issue issue;
        const auto pinned=bilipai::ngx::pinRuntime(runtime,c->project_id_utf8,c->engine_version_utf8,
            bilipai::ngx::Kind::Dlss,bilipai::ngx::DlssSr,issue);
        if(pinned!=BVD_OK)return report(st,pinned,issue.message,issue.hr);
        auto p=std::make_unique<Session>();p->config=*c;p->runtime=runtime;
        p->project=c->project_id_utf8;p->engine=c->engine_version_utf8;p->historyEpoch=c->history_epoch;
        p->config.runtime_directory_utf16=nullptr;p->config.project_id_utf8=nullptr;p->config.engine_version_utf8=nullptr;
        p->device=static_cast<ID3D12Device*>(c->d3d12_device);
        p->queue=static_cast<ID3D12CommandQueue*>(c->d3d12_direct_queue);
        if(luidBits(p->device->GetAdapterLuid())!=c->adapter_luid||p->device->GetNodeCount()!=1||
            p->queue->GetDesc().Type!=D3D12_COMMAND_LIST_TYPE_DIRECT||!sameDevice(p->queue.Get(),p->device.Get()))
            return report(st,BVD_INVALID,"same-device direct queue and single-node adapter LUID required");
        auto hr=p->device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT,IID_PPV_ARGS(&p->allocator));
        if(SUCCEEDED(hr))hr=p->device->CreateCommandList(0,D3D12_COMMAND_LIST_TYPE_DIRECT,p->allocator.Get(),nullptr,IID_PPV_ARGS(&p->list));
        if(SUCCEEDED(hr))hr=p->list->Close();
        if(SUCCEEDED(hr))hr=p->device->CreateFence(0,D3D12_FENCE_FLAG_NONE,IID_PPV_ARGS(&p->fence));
        if(FAILED(hr))return report(st,BVD_DEVICE_FAILURE,"D3D12 command/fence allocation failed",hr);
        p->event=CreateEventW(nullptr,FALSE,FALSE,nullptr);
        if(!p->event)return report(st,BVD_DEVICE_FAILURE,"completion event creation failed",HRESULT_FROM_WIN32(GetLastError()));
        // Publish before NGX: even a failed init may return an owned handle.
        g_session=p.release();*out=++g_token;
        veyra::Status upstream=veyra::Status::Ok;

        if(!g_session->core.initialize(g_session->device.Get(),g_session->runtime,g_session->project.c_str(),g_session->engine.c_str(),upstream)){
            g_session->failed=true;
            return report(st,BVD_CORE_FAILURE,"actual NGX own-project initialization rejected",S_OK,uint32_t(upstream),g_session->core.initResult());
        }
        g_session->params=g_session->core.allocateParameters(upstream);
        if(!g_session->params){
            g_session->failed=true;
            return report(st,BVD_CORE_FAILURE,"actual NGX parameter allocation rejected",S_OK,uint32_t(upstream),g_session->core.initResult());
        }
        const auto created=createFeature(*g_session,st);
        return created==BVD_OK?report(st,BVD_OK,"actual standard DLSS SR feature initialized",S_OK,0,g_session->core.initResult()):created;
    }catch(...){if(g_session)g_session->failed=true;return report(st,BVD_INTERNAL,"native create exception; retain any owned handle");}
}
extern "C" int32_t BVD_CALL bvd_process_v2(bvd_handle_v2 token,const bvd_frame_v2* f,bvd_result_v2* r,bvd_status_v2* st){
    if(!validStatus(st)||!f||!r||f->size!=sizeof(*f)||f->abi!=BVD_ABI_V2||r->size!=sizeof(*r)||r->abi!=BVD_ABI_V2)
        return report(st,BVD_ABI_MISMATCH,"DLSS SR ABI v2 size/version required");
    const auto size=r->size,abi=r->abi;*r={};r->size=size;r->abi=abi;
    std::lock_guard lock(g_mutex);
    if(!current(token))return report(st,BVD_STALE,"retired or unknown handle");
    auto& s=*g_session;
    try{
        if(f->session_id!=s.config.session_id||f->source_generation!=s.config.source_generation||
            !f->sequence||f->sequence<=s.lastSequence||!f->history_epoch||f->history_epoch<s.historyEpoch)
            return report(st,BVD_STALE,"retired source or non-monotonic frame/history identity");
        const bool reset=(f->flags&BVD_HISTORY_RESET)!=0;
        if((s.needsReset||f->history_epoch>s.historyEpoch)&&!reset)
            return report(st,BVD_RESET_REQUIRED,"first frame/new history requires explicit reset");
        if(reset&&!(f->motion_contract&BVD_MV_DECLARED_ZERO))
            return report(st,BVD_RESET_REQUIRED,"reset requires a real declared-zero motion texture");
        if(f->input_color!=BVD_SCRGB_BT709_LINEAR_FP16_80NITS||f->output_color!=BVD_SCRGB_BT709_LINEAR_FP16_80NITS)
            return report(st,BVD_COLOR_UNSUPPORTED,"only declared linear BT.709/scRGB RGBA16F is supported");
        auto* color=static_cast<ID3D12Resource*>(f->color_texture);
        auto* depth=static_cast<ID3D12Resource*>(f->depth_texture);
        auto* motion=static_cast<ID3D12Resource*>(f->motion_texture);
        auto* output=static_cast<ID3D12Resource*>(f->output_texture);
        auto* ready=static_cast<ID3D12Fence*>(f->input_ready_fence);
        if(f->adapter_luid!=s.config.adapter_luid||f->pts_denominator<=0||f->reserved[0]||f->reserved[1]||
            (f->flags&~BVD_HISTORY_RESET)||!std::isfinite(f->jitter_offset_x)||!std::isfinite(f->jitter_offset_y)||
            std::abs(f->jitter_offset_x)>1.0f||std::abs(f->jitter_offset_y)>1.0f||f->sharpness!=0.0f||
            (f->depth_contract!=BVD_DEPTH_DECLARED_ZERO_FALLBACK&&f->depth_contract!=BVD_DEPTH_NORMAL_NONINVERTED)||
            !(f->motion_contract&BVD_MV_OUTPUT_PIXELS_CURRENT_TO_PREVIOUS)||
            (f->motion_contract&~(BVD_MV_OUTPUT_PIXELS_CURRENT_TO_PREVIOUS|BVD_MV_DECLARED_ZERO))||
            !supportedState(f->color_state)||!supportedState(f->depth_state)||!supportedState(f->motion_state)||
            !supportedState(f->output_state)||!supportedState(f->output_final_state)||sameObject(color,output)||
            !texture(color,s.device.Get(),s.config.input_width,s.config.input_height,DXGI_FORMAT_R16G16B16A16_FLOAT,false)||
            !texture(depth,s.device.Get(),s.config.output_width,s.config.output_height,DXGI_FORMAT_R32_FLOAT,false)||
            !texture(motion,s.device.Get(),s.config.output_width,s.config.output_height,DXGI_FORMAT_R16G16_FLOAT,false)||
            !texture(output,s.device.Get(),s.config.output_width,s.config.output_height,DXGI_FORMAT_R16G16B16A16_FLOAT,true)||
            !ready||!f->input_ready_value||!sameDevice(ready,s.device.Get()))
            return report(st,BVD_INVALID,"invalid linear/guidance/device/texture/fence/state/timestamp contract");
        if(ready->GetCompletedValue()==UINT64_MAX)return report(st,BVD_DEVICE_FAILURE,"producer fence reports removed device");
        if(s.failed||!s.featuresCreated||!s.sr.created()||!s.params||!s.core.healthy())
            return report(st,BVD_FEATURE_FAILURE,"failed or uninitialized standard DLSS feature");
        const auto drained=drain(s,st);if(drained!=BVD_OK)return drained;
        const auto started=begin(s,st);if(started!=BVD_OK)return started;
        s.retainedColor=color;s.retainedDepth=depth;s.retainedMotion=motion;s.retainedOutput=output;s.retainedReady=ready;
        const auto cs=static_cast<D3D12_RESOURCE_STATES>(f->color_state);
        const auto ds=static_cast<D3D12_RESOURCE_STATES>(f->depth_state);
        const auto ms=static_cast<D3D12_RESOURCE_STATES>(f->motion_state);
        const auto os=static_cast<D3D12_RESOURCE_STATES>(f->output_state);
        const auto fs=static_cast<D3D12_RESOURCE_STATES>(f->output_final_state);
        transition(s.list.Get(),color,cs,D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
        transition(s.list.Get(),depth,ds,D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
        transition(s.list.Get(),motion,ms,D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
        transition(s.list.Get(),output,os,D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
        const veyra::ngx::DlssSrBackend::EvalDesc desc{color,output,depth,motion,reset,f->jitter_offset_x,f->jitter_offset_y,0.0f};
        veyra::Status upstream=veyra::Status::Ok;
        if(!s.sr.evaluate(s.list.Get(),s.params,desc,upstream)){
            s.list->Close();s.failed=true;
            return report(st,BVD_FEATURE_FAILURE,"actual standard DLSS evaluate rejected; no commands submitted",S_OK,uint32_t(upstream),s.core.initResult());
        }
        uav(s.list.Get(),output);
        transition(s.list.Get(),color,D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE,cs);
        transition(s.list.Get(),depth,D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE,ds);
        transition(s.list.Get(),motion,D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE,ms);
        transition(s.list.Get(),output,D3D12_RESOURCE_STATE_UNORDERED_ACCESS,fs);
        const auto submitted=submit(s,st,ready,f->input_ready_value);if(submitted!=BVD_OK)return submitted;
        s.lastSequence=f->sequence;s.historyEpoch=f->history_epoch;s.needsReset=false;
        r->session_id=f->session_id;r->source_generation=f->source_generation;r->sequence=f->sequence;
        r->adapter_luid=f->adapter_luid;r->history_epoch=f->history_epoch;
        r->pts_numerator=f->pts_numerator;r->pts_denominator=f->pts_denominator;
        r->output_color=BVD_SCRGB_BT709_LINEAR_FP16_80NITS;
        r->output_width=s.config.output_width;r->output_height=s.config.output_height;
        r->output_dxgi_format=DXGI_FORMAT_R16G16B16A16_FLOAT;r->output_state=f->output_final_state;
        r->effects_submitted=BVD_DLSS_SR;r->output_texture=output;r->completion_fence=s.fence.Get();r->completion_value=s.lastFence;
        r->history_was_reset=reset?1u:0u;r->guidance_contract=f->motion_contract;
        return report(st,BVD_OK,"actual DLSS SDK accepted; GPU submitted; output completion still required",S_OK,0,s.core.initResult());
    }catch(...){s.failed=true;return report(st,BVD_INTERNAL,"native processing exception; retain and drain before teardown");}
}
extern "C" int32_t BVD_CALL bvd_reset_v2(bvd_handle_v2 token,uint64_t session,uint64_t generation,uint64_t history,bvd_status_v2* st){
    if(!validStatus(st))return BVD_ABI_MISMATCH;
    std::lock_guard lock(g_mutex);if(!current(token))return report(st,BVD_STALE,"retired or unknown handle");auto& s=*g_session;
    if(s.releaseQuarantine)return report(st,BVD_FEATURE_FAILURE,"prior SDK release failed; process restart required");
    if(session!=s.config.session_id||generation<=s.config.source_generation||history<=s.historyEpoch)
        return report(st,BVD_STALE,"seek reset requires strictly newer source generation and history epoch");
    try{
        const auto code=drain(s,st,false);if(code!=BVD_OK)return code;
        const auto released=releaseFeature(s,st);if(released!=BVD_OK)return released;
        const auto releasedParams=releaseParameters(s,st);if(releasedParams!=BVD_OK)return releasedParams;
        if(!s.core.healthy()){s.failed=true;return report(st,BVD_CORE_FAILURE,"unhealthy NGX core requires process restart");}
        veyra::Status upstream=veyra::Status::Ok;s.params=s.core.allocateParameters(upstream);
        if(!s.params){s.failed=true;return report(st,BVD_CORE_FAILURE,"reset parameter allocation rejected",S_OK,uint32_t(upstream),s.core.initResult());}
        s.failed=false;s.config.source_generation=generation;s.config.history_epoch=history;
        s.historyEpoch=history;s.lastSequence=0;s.needsReset=true;
        const auto created=createFeature(s,st);
        return created==BVD_OK?report(st,BVD_OK,"actual DLSS history recreated; next frame must reset"):created;
    }catch(...){s.failed=true;if(s.releaseQuarantine)s.core.quarantine();return report(st,BVD_INTERNAL,"native reset exception; any release latch remains permanent");}
}

extern "C" int32_t BVD_CALL bvd_destroy_v2(bvd_handle_v2 token,bvd_status_v2* st){
    if(!validStatus(st))return BVD_ABI_MISMATCH;
    std::lock_guard lock(g_mutex);if(!current(token))return report(st,BVD_STALE,"retired or unknown handle");
    // Never let a later device removal erase a failed/throwing SDK release:
    // fixed backend handles may already be lost, and destructors would retry.
    if(g_session->releaseQuarantine)return report(st,BVD_FEATURE_FAILURE,"prior SDK release failed; retain entire session through process exit");
    try{
        const auto code=drain(*g_session,st,false);
        const bool removed=FAILED(g_session->device->GetDeviceRemovedReason());
        if(code!=BVD_OK&&!removed)return code;
        const auto released=releaseFeature(*g_session,st);if(released!=BVD_OK)return released;
        const auto releasedParams=releaseParameters(*g_session,st);if(releasedParams!=BVD_OK)return releasedParams;
        const auto shutdown=checkedShutdown(*g_session,st);if(shutdown!=BVD_OK)return shutdown;
        // Actual release/shutdown success qualified retirement; PIN remains a
        // separate module lifetime rule and grants no GPU completion authority.
        delete g_session;g_session=nullptr;
        return report(st,BVD_OK,"features/parameters released and actual device shutdown confirmed; modules remain pinned");
    }catch(...){
        if(g_session){g_session->failed=true;g_session->releaseQuarantine=true;g_session->core.quarantine();}
        return report(st,BVD_INTERNAL,"native teardown exception; retain entire session through process exit");
    }
}


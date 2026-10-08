// BiliPai core seam, candidate only. GPL-3.0-or-later.
#define BILIVEYRA_EXPORTS
#include "bilipai_veyra_core_v1.h"
#include "bilipai_ngx_shared_host.h"
#include "veyra/ngx/VideoSrBackend.h"
#include "veyra/ngx/TrueHdrBackend.h"
#include <d3d12.h>
#include <dxgi1_6.h>
#include <wrl/client.h>
#include <windows.h>
#include <algorithm>
#include <cstring>
#include <filesystem>
#include <memory>
#include <mutex>
#include <string>
using Microsoft::WRL::ComPtr;
namespace {
std::mutex& g_mutex=bilipai::ngx::mutex();
uint64_t g_token = 0;
struct Session {
    bv_config_v1 config{};
    std::wstring runtime;
    std::string project, engine;
    ComPtr<ID3D12Device> device;
    ComPtr<ID3D12CommandQueue> queue;
    ComPtr<ID3D12CommandAllocator> allocator;
    ComPtr<ID3D12GraphicsCommandList> list;
    ComPtr<ID3D12Fence> fence;
    ComPtr<ID3D12Resource> intermediate, retainedInput, retainedOutput;
    ComPtr<ID3D12Fence> retainedReady;
    HANDLE event = nullptr;
    uint64_t nextFence = 1, lastFence = 0, lastSequence = 0;
    bool featuresCreated = false, failed = false, untrackedSubmission = false;
    bool releaseQuarantine = false;
    bilipai::ngx::HostLease core{this,bilipai::ngx::Kind::Video};
    veyra::ngx::VideoSrBackend sr;
    veyra::ngx::TrueHdrBackend hdr;
    ~Session() {
        // Published sessions reach this only after checked feature release.
        // Upstream member destructors then see no remaining feature handles.
        if (event) CloseHandle(event);
    }
};
// Deliberately no static Session destructor. Failed release keeps the complete
// Session for process life; module PINs survive even successful Session destroy.
Session* g_session = nullptr;
uint64_t luidBits(LUID l) { return (uint64_t(uint32_t(l.HighPart)) << 32) | l.LowPart; }
bool validStatus(const bv_status_v1* s) { return s && s->size == sizeof(*s) && s->abi == BV_ABI_V1; }
int32_t report(bv_status_v1* s, int32_t code, const char* message, HRESULT hr = S_OK, uint32_t upstream = 0, uint64_t coreResult = 0) {
    if (validStatus(s)) {
        s->code = code; s->native_hresult = uint32_t(hr); s->upstream_status = upstream;
        s->core_init_result = coreResult;
        std::memset(s->message, 0, sizeof(s->message));
        std::memcpy(s->message, message, (std::min)(std::strlen(message), sizeof(s->message)-1));
    }
    return code;
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
        d.DepthOrArraySize == 1 && d.MipLevels == 1 && d.SampleDesc.Count == 1 &&
        d.Format == format && !(d.Flags & D3D12_RESOURCE_FLAG_DENY_SHADER_RESOURCE) &&
        (!output || (d.Flags & D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS));
}
int32_t drain(Session& s, bv_status_v1* st, bool releaseCompletedFrames = true) {
    // Absolute deadline and completion-value recheck: an old notification
    // remaining after a timeout must never become a false device failure.
    const auto deadline = GetTickCount64() + s.config.wait_timeout_ms;
    for (;;) {
        const auto removed = s.device->GetDeviceRemovedReason();
        if (FAILED(removed)) return report(st, BV_DEVICE_FAILURE, "D3D12 device removed; recreate the video seam", removed);
        if (s.untrackedSubmission) return report(st, BV_DEVICE_FAILURE, "queue signal failed; outstanding GPU work cannot be released safely");
        const auto completed = s.fence->GetCompletedValue();
        if (completed == UINT64_MAX) return report(st, BV_DEVICE_FAILURE, "completion fence reports removed device");
        if (!s.lastFence || completed >= s.lastFence) {
            if (releaseCompletedFrames) {
                s.retainedInput.Reset(); s.retainedOutput.Reset(); s.retainedReady.Reset();
            }
            return BV_OK;
        }
        auto now = GetTickCount64();
        if (now >= deadline) return report(st, BV_TIMEOUT, "GPU completion pending; retain handle/resources and retry");
        if (!ResetEvent(s.event)) return report(st, BV_DEVICE_FAILURE, "completion event reset failed", HRESULT_FROM_WIN32(GetLastError()));
        const auto hr = s.fence->SetEventOnCompletion(s.lastFence, s.event);
        if (FAILED(hr)) return report(st, BV_DEVICE_FAILURE, "completion event registration failed", hr);
        // A fence may complete between its first check and registration.
        const auto afterRegistration = s.fence->GetCompletedValue();
        if (afterRegistration == UINT64_MAX) return report(st, BV_DEVICE_FAILURE, "completion fence reports removed device");
        if (afterRegistration >= s.lastFence) continue;
        now = GetTickCount64();
        if (now >= deadline) continue; // recheck completion/device before timeout
        const auto wait = WaitForSingleObject(s.event, static_cast<DWORD>(deadline - now));
        if (wait == WAIT_FAILED) return report(st, BV_DEVICE_FAILURE, "GPU completion wait failed", HRESULT_FROM_WIN32(GetLastError()));
        if (wait != WAIT_OBJECT_0 && wait != WAIT_TIMEOUT) return report(st, BV_DEVICE_FAILURE, "unexpected completion event wait result", E_FAIL);
        // Both timeout and wakeup loop through actual completion/device state.
        // A stale wakeup is harmless; pending resources remain referenced.
    }
}
int32_t begin(Session& s, bv_status_v1* st) {
    auto hr = s.allocator->Reset();
    if (SUCCEEDED(hr)) hr = s.list->Reset(s.allocator.Get(), nullptr);
    if (FAILED(hr)) { s.failed = true; return report(st, BV_DEVICE_FAILURE, "command allocator/list reset failed", hr); }
    return BV_OK;
}
int32_t submit(Session& s, bv_status_v1* st) {
    auto hr = s.list->Close();
    if (FAILED(hr)) { s.failed = true; return report(st, BV_DEVICE_FAILURE, "command list close failed", hr); }
    ID3D12CommandList* lists[] = {s.list.Get()}; s.queue->ExecuteCommandLists(1, lists);
    if (s.nextFence == UINT64_MAX) { s.untrackedSubmission = true; return report(st, BV_DEVICE_FAILURE, "fence timeline exhausted"); }
    const auto value = s.nextFence++;
    hr = s.queue->Signal(s.fence.Get(), value);
    if (FAILED(hr)) { s.untrackedSubmission = true; return report(st, BV_DEVICE_FAILURE, "queue signal failed; retain session", hr); }
    s.lastFence = value;
    return BV_OK;
}
int32_t createFeatures(Session& s, bv_status_v1* st) {
    const auto started = begin(s, st); if (started != BV_OK) return started;
    bool ok = true;
    if (s.config.effects & BV_VIDEO_SR) ok = s.sr.create(s.core.host(), s.list.Get());
    if (ok && (s.config.effects & BV_VIDEO_HDR)) ok = s.hdr.create(s.core.host(), s.list.Get(), s.runtime);
    if (!ok) { s.list->Close(); s.failed = true; return report(st, BV_FEATURE_FAILURE, "Veyra feature creation rejected; inspect upstream diagnostic log"); }
    s.featuresCreated = true;
    const auto submitted = submit(s, st); if (submitted != BV_OK) return submitted;
    return drain(s, st);
}
int32_t releaseFeatures(Session& s, bv_status_v1* st) {
    if (s.releaseQuarantine) return report(st, BV_FEATURE_FAILURE, "feature release failed earlier; session retained until process exit");
    // The fixed backends clear their handles even when release fails. Latch
    // before the first call, and never retry those cleared handles as success.
    s.releaseQuarantine = true; s.failed = true; s.featuresCreated = false;
    const bool initialized = s.core.initialized();
    if (!s.hdr.release() || !s.sr.release() ||
        (initialized && (!s.core.healthy() || s.core.liveParameterBlockCount() != 0))){
        s.core.quarantine();
        return report(st, BV_FEATURE_FAILURE, "actual feature/parameter release rejected; restart required, session retained");
    }
    s.releaseQuarantine = false;
    return BV_OK;
}
int32_t checkedShutdown(Session& s,bv_status_v1* st){
    s.releaseQuarantine=true;s.failed=true;
    bilipai::ngx::Issue issue;
    if(!s.core.retire(issue))
        return report(st,BV_CORE_FAILURE,issue.message,issue.hr,static_cast<uint32_t>(issue.sdk));
    s.releaseQuarantine=false;return BV_OK;
}bool current(bv_handle_v1 token) { return g_session && token && token == g_token; }
}
extern "C" int32_t BV_CALL bv_create_v1(const bv_config_v1* c, bv_handle_v1* out, bv_status_v1* st) {
    if (out) *out = 0;
    if (!validStatus(st) || !c || !out || c->size != sizeof(*c) || c->abi != BV_ABI_V1) return report(st, BV_ABI_MISMATCH, "ABI v1 size/version required");
    std::lock_guard lock(g_mutex);
    if(g_session||!bilipai::ngx::available())
        return report(st,BV_BUSY,"one ABI already owns or quarantines the shared process NGX host");
    try {
        veyra::engine::VideoHdrSettings settings{true,c->hdr_contrast,c->hdr_saturation,c->hdr_middle_gray,c->hdr_peak_nits};
        if (!c->session_id || !c->source_generation || !c->adapter_luid || !c->d3d12_device || !c->d3d12_direct_queue ||
            !c->runtime_directory_utf16 || !c->project_id_utf8 || !c->engine_version_utf8 || !*c->project_id_utf8 || !*c->engine_version_utf8 ||
            !c->input_width || !c->input_height || c->output_width < c->input_width || c->output_height < c->input_height ||
            c->output_width > 16384 || c->output_height > 16384 || !c->effects || (c->effects & ~(BV_VIDEO_SR|BV_VIDEO_HDR)) ||
            ((c->effects & BV_VIDEO_SR) && (c->sr_quality < 1 || c->sr_quality > 4)) ||
            (!(c->effects & BV_VIDEO_SR) && (c->output_width != c->input_width || c->output_height != c->input_height)) ||
            ((c->effects & BV_VIDEO_HDR) && !settings.valid()) || !c->wait_timeout_ms || c->wait_timeout_ms > 5000 || c->reserved || g_token == UINT64_MAX)
            return report(st, BV_INVALID, "invalid identity/dimensions/effect/settings/runtime contract");
        static_assert(sizeof(wchar_t) == sizeof(uint16_t));
        std::wstring runtime(reinterpret_cast<const wchar_t*>(c->runtime_directory_utf16));
        if (!std::filesystem::path(runtime).is_absolute()) return report(st, BV_INVALID, "runtime directory must be absolute");
        // Preserve the v1 public-header contract: both original modules PIN
        // before NGX, under the one shared authenticated process identity.
        bilipai::ngx::Issue issue;
        const auto pinned=bilipai::ngx::pinRuntime(runtime,c->project_id_utf8,c->engine_version_utf8,
            bilipai::ngx::Kind::Video,bilipai::ngx::VideoSr|bilipai::ngx::TrueHdr,issue);
        if(pinned!=BV_OK)return report(st,pinned,issue.message,issue.hr);
        auto p = std::make_unique<Session>(); p->config = *c; p->runtime = runtime; p->project = c->project_id_utf8; p->engine = c->engine_version_utf8;
        p->device = static_cast<ID3D12Device*>(c->d3d12_device); p->queue = static_cast<ID3D12CommandQueue*>(c->d3d12_direct_queue);
        if (luidBits(p->device->GetAdapterLuid()) != c->adapter_luid || p->device->GetNodeCount() != 1 ||
            p->queue->GetDesc().Type != D3D12_COMMAND_LIST_TYPE_DIRECT || !sameDevice(p->queue.Get(), p->device.Get()))
            return report(st, BV_INVALID, "same-device direct queue and single-node adapter LUID required");
        auto hr = p->device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT, IID_PPV_ARGS(&p->allocator));
        if (SUCCEEDED(hr)) hr = p->device->CreateCommandList(0,D3D12_COMMAND_LIST_TYPE_DIRECT,p->allocator.Get(),nullptr,IID_PPV_ARGS(&p->list));
        if (SUCCEEDED(hr)) hr = p->list->Close();
        if (SUCCEEDED(hr)) hr = p->device->CreateFence(0,D3D12_FENCE_FLAG_NONE,IID_PPV_ARGS(&p->fence));
        if (FAILED(hr)) return report(st, BV_DEVICE_FAILURE, "D3D12 command/fence allocation failed", hr);
        p->event = CreateEventW(nullptr,FALSE,FALSE,nullptr);
        if (!p->event) return report(st, BV_DEVICE_FAILURE, "completion event creation failed", HRESULT_FROM_WIN32(GetLastError()));
        if (c->effects == (BV_VIDEO_SR|BV_VIDEO_HDR)) {
            D3D12_HEAP_PROPERTIES heap{}; heap.Type = D3D12_HEAP_TYPE_DEFAULT;
            D3D12_RESOURCE_DESC d{}; d.Dimension=D3D12_RESOURCE_DIMENSION_TEXTURE2D; d.Width=c->output_width; d.Height=c->output_height;
            d.DepthOrArraySize=1; d.MipLevels=1; d.Format=DXGI_FORMAT_R8G8B8A8_UNORM; d.SampleDesc.Count=1; d.Flags=D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS;
            hr=p->device->CreateCommittedResource(&heap,D3D12_HEAP_FLAG_NONE,&d,D3D12_RESOURCE_STATE_COMMON,nullptr,IID_PPV_ARGS(&p->intermediate));
            if (FAILED(hr)) return report(st,BV_DEVICE_FAILURE,"SR/HDR intermediate allocation failed",hr);
        }
        // Ownership published before NGX: any nonzero returned handle must be
        // destroyed, even after init failure/exception/timeout. No unsafe free.
        g_session=p.release(); *out=++g_token;
        veyra::Status upstream=veyra::Status::Ok;

        if (!g_session->core.initialize(g_session->device.Get(),g_session->runtime,g_session->project.c_str(),g_session->engine.c_str(),upstream)) {
            g_session->failed=true;
            return report(st,BV_CORE_FAILURE,"Veyra NGX core initialization rejected",S_OK,uint32_t(upstream),g_session->core.initResult());
        }
        const auto code=createFeatures(*g_session,st);
        return code == BV_OK ? report(st,BV_OK,"actual Veyra SR/HDR features initialized",S_OK,0,g_session->core.initResult()) : code;
    } catch (...) { if(g_session)g_session->failed=true; return report(st,BV_INTERNAL,"native exception; retain any nonzero handle and destroy safely"); }
}
extern "C" int32_t BV_CALL bv_process_v1(bv_handle_v1 token,const bv_frame_v1* f,bv_result_v1* r,bv_status_v1* st) {
    if(!validStatus(st)||!f||!r||f->size!=sizeof(*f)||f->abi!=BV_ABI_V1||r->size!=sizeof(*r)||r->abi!=BV_ABI_V1) return report(st,BV_ABI_MISMATCH,"ABI v1 size/version required");
    const auto size=r->size,abi=r->abi; *r={}; r->size=size; r->abi=abi;
    std::lock_guard lock(g_mutex);
    if(!current(token))return report(st,BV_STALE,"retired/unknown handle");
    auto& s=*g_session;
    if(s.releaseQuarantine)return report(st,BV_FEATURE_FAILURE,"feature release failed earlier; restart required");
    try {
        if(f->session_id!=s.config.session_id||f->source_generation!=s.config.source_generation||!f->sequence||f->sequence<=s.lastSequence)
            return report(st,BV_STALE,"retired session/generation or non-monotonic source sequence");
        if(f->discontinuity_flags)return report(st,BV_RESET_REQUIRED,"reset with a newer source generation before this frame");
        const auto outputColor=(s.config.effects&BV_VIDEO_HDR)?BV_SCRGB_BT709_LINEAR_FP16_80NITS:BV_SRGB_BT709_FULL_RGBA8;
        const auto outputFormat=(s.config.effects&BV_VIDEO_HDR)?DXGI_FORMAT_R16G16B16A16_FLOAT:DXGI_FORMAT_R8G8B8A8_UNORM;
        if(f->input_color!=BV_SRGB_BT709_FULL_RGBA8||f->output_color!=uint32_t(outputColor))return report(st,BV_COLOR_UNSUPPORTED,"only full-range sRGB BT.709 RGBA8 input and declared SR/scRGB output supported");
        auto* input=static_cast<ID3D12Resource*>(f->input_texture); auto* output=static_cast<ID3D12Resource*>(f->output_texture); auto* ready=static_cast<ID3D12Fence*>(f->input_ready_fence);
        if(f->adapter_luid!=s.config.adapter_luid||f->pts_denominator<=0||f->reserved||!supportedState(f->input_state)||!supportedState(f->output_state)||!supportedState(f->output_final_state)||sameObject(input,output)||
            !texture(input,s.device.Get(),s.config.input_width,s.config.input_height,DXGI_FORMAT_R8G8B8A8_UNORM,false)||
            !texture(output,s.device.Get(),s.config.output_width,s.config.output_height,outputFormat,true)||
            (f->input_ready_value && !ready)||(ready && !sameDevice(ready,s.device.Get()))) return report(st,BV_INVALID,"invalid texture/fence/device/extent/timestamp contract");
        if(s.failed||!s.featuresCreated||!s.core.healthy())return report(st,BV_FEATURE_FAILURE,"failed/uninitialized feature; reset or destroy before retry");
        const auto drained=drain(s,st); if(drained!=BV_OK)return drained;
        const auto started=begin(s,st); if(started!=BV_OK)return started;
        if(ready&&f->input_ready_value){const auto hr=s.queue->Wait(ready,f->input_ready_value);if(FAILED(hr)){s.list->Close();return report(st,BV_DEVICE_FAILURE,"input producer fence wait rejected",hr);}}
        s.retainedInput=input; s.retainedOutput=output; s.retainedReady=ready;
        const auto inState=static_cast<D3D12_RESOURCE_STATES>(f->input_state),outState=static_cast<D3D12_RESOURCE_STATES>(f->output_state),finalState=static_cast<D3D12_RESOURCE_STATES>(f->output_final_state);
        ID3D12Resource* hdrInput=input;
        bool ok=true;
        if(s.config.effects&BV_VIDEO_SR){
            auto* srOutput=s.intermediate?s.intermediate.Get():output;
            transition(s.list.Get(),input,inState,D3D12_RESOURCE_STATE_COMMON);
            if(!s.intermediate)transition(s.list.Get(),output,outState,D3D12_RESOURCE_STATE_COMMON);
            ok=s.sr.evaluate(s.list.Get(),input,srOutput,s.config.sr_quality);
            uav(s.list.Get(),srOutput);
            transition(s.list.Get(),input,D3D12_RESOURCE_STATE_COMMON,inState);
            hdrInput=srOutput;
        }
        if(ok&&(s.config.effects&BV_VIDEO_HDR)){
            const auto hdrInputState=s.intermediate?D3D12_RESOURCE_STATE_COMMON:inState;
            transition(s.list.Get(),hdrInput,hdrInputState,D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
            transition(s.list.Get(),output,outState,D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
            const veyra::engine::VideoHdrSettings settings{true,s.config.hdr_contrast,s.config.hdr_saturation,s.config.hdr_middle_gray,s.config.hdr_peak_nits};
            ok=s.hdr.evaluate(s.list.Get(),hdrInput,output,settings); uav(s.list.Get(),output);
            transition(s.list.Get(),hdrInput,D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE,hdrInputState);
            transition(s.list.Get(),output,D3D12_RESOURCE_STATE_UNORDERED_ACCESS,finalState);
        }else if(ok)transition(s.list.Get(),output,D3D12_RESOURCE_STATE_COMMON,finalState);
        if(!ok){s.list->Close();s.failed=true;s.retainedInput.Reset();s.retainedOutput.Reset();s.retainedReady.Reset();return report(st,BV_FEATURE_FAILURE,"actual Veyra evaluate rejected; output was not submitted");}
        const auto submitted=submit(s,st);if(submitted!=BV_OK)return submitted;
        s.lastSequence=f->sequence;
        r->session_id=f->session_id;r->source_generation=f->source_generation;r->sequence=f->sequence;r->adapter_luid=f->adapter_luid;
        r->pts_numerator=f->pts_numerator;r->pts_denominator=f->pts_denominator;r->output_color=uint32_t(outputColor);
        r->output_width=s.config.output_width;r->output_height=s.config.output_height;r->output_dxgi_format=uint32_t(outputFormat);
        r->output_state=f->output_final_state;r->effects_applied=s.config.effects;r->output_texture=output;r->completion_fence=s.fence.Get();r->completion_value=s.lastFence;
        return report(st,BV_OK,"SDK evaluations accepted; GPU submitted; wait completion before use",S_OK,0,s.core.initResult());
    }catch(...){s.failed=true;return report(st,BV_INTERNAL,"native processing exception; reset/destroy only after GPU drain");}
}
extern "C" int32_t BV_CALL bv_reset_v1(bv_handle_v1 token,uint64_t session,uint64_t generation,bv_status_v1* st){
    if(!validStatus(st))return BV_ABI_MISMATCH;
    std::lock_guard lock(g_mutex);if(!current(token))return report(st,BV_STALE,"retired/unknown handle");auto& s=*g_session;
    if(s.releaseQuarantine)return report(st,BV_FEATURE_FAILURE,"feature release failed earlier; restart required");
    if(session!=s.config.session_id||generation<=s.config.source_generation)return report(st,BV_STALE,"reset requires current session and a strictly newer generation");
    try{
        const auto code=drain(s,st,false);if(code!=BV_OK)return code;
        const auto released=releaseFeatures(s,st);if(released!=BV_OK)return released;
        s.failed=false;
        if(!s.core.healthy())return report(st,BV_CORE_FAILURE,"unhealthy core requires destroy/recreate");
        s.config.source_generation=generation;s.lastSequence=0;
        const auto created=createFeatures(s,st);
        return created==BV_OK?report(st,BV_OK,"feature history recreated for the new source generation"):created;
    }catch(...){s.failed=true;s.releaseQuarantine=true;s.core.quarantine();return report(st,BV_INTERNAL,"native reset exception; session retained until process exit");}
}
extern "C" int32_t BV_CALL bv_destroy_v1(bv_handle_v1 token,bv_status_v1* st){
    if(!validStatus(st))return BV_ABI_MISMATCH;
    std::lock_guard lock(g_mutex);if(!current(token))return report(st,BV_STALE,"retired/unknown handle");
    auto& s=*g_session;
    if(s.releaseQuarantine)return report(st,BV_FEATURE_FAILURE,"feature release failed earlier; session retained until process exit");
    try{
        const auto code=drain(s,st,false);
        if(code!=BV_OK&&SUCCEEDED(s.device->GetDeviceRemovedReason()))return code;
        const auto released=releaseFeatures(s,st);if(released!=BV_OK)return released;
        const auto shutdown=checkedShutdown(s,st);if(shutdown!=BV_OK)return shutdown;
        delete g_session;g_session=nullptr;
        return report(st,BV_OK,"GPU drained, features released, actual device shutdown confirmed; modules remain pinned");
    }catch(...){s.failed=true;s.releaseQuarantine=true;s.core.quarantine();return report(st,BV_INTERNAL,"native teardown exception; session retained until process exit");}
}



// BiliPai-owned common NGX host and loader custody. GPL-3.0-or-later.
#include "bilipai_ngx_shared_host.h"
#include <windows.h>
#include <wrl/client.h>
#include <filesystem>
#include <cstring>
#include <iterator>
#include <stdexcept>
using Microsoft::WRL::ComPtr;
namespace {
std::mutex g_mutex;
constexpr const char* kHostEngine="BiliPai-Veyra-Core-Shared-1";
struct Context {
    veyra::ngx::NgxCoreHost core; // the only process-owned fixed host instance
    void* owner=nullptr;
    bilipai::ngx::Kind kind=bilipai::ngx::Kind::Video;
    ComPtr<ID3D12Device> device;
    bool quarantine=false,initAttempted=false,shutdownArmed=false,shutdownAttempted=false;
    ID3D12Device* shutdownDevice=nullptr;
    NVSDK_NGX_Result shutdownResult=NVSDK_NGX_Result_Fail;
    uint32_t shutdownSeh=0;
};
// Deliberately no static owning destructor: whole failed SDK custody stays live.
Context* g_context=nullptr;
bool g_selfPinned=false,g_pinFailed=false;
std::wstring g_runtimeRoot;
std::string g_projectGuid;
HMODULE g_runtimeModules[3]{}; // known finite SR/HDR/DLSS slots for process life
bool samePath(const std::wstring& a,const std::wstring& b){
    return CompareStringOrdinal(a.c_str(),-1,b.c_str(),-1,TRUE)==CSTR_EQUAL;
}
bool guid(const char* p){
    if(!p||std::strlen(p)!=36)return false;
    for(size_t i=0;i<36;++i){
        if(i==8||i==13||i==18||i==23){if(p[i]!='-')return false;}
        else if(!((p[i]>='0'&&p[i]<='9')||(p[i]>='a'&&p[i]<='f')))return false;
    }
    return true;
}
int reject(bilipai::ngx::Issue& i,int code,const char* text,HRESULT hr=S_OK){
    i.code=code;i.message=text;i.hr=hr;return code;
}
int pinOne(size_t index,const wchar_t* name,bilipai::ngx::Issue& issue){
    if(g_runtimeModules[index])return 0;
    std::error_code ec;
    const auto file=std::filesystem::canonical(std::filesystem::path(g_runtimeRoot)/name,ec);
    if(ec||!std::filesystem::is_regular_file(file,ec)||ec||!samePath(file.parent_path().wstring(),g_runtimeRoot))
        return reject(issue,1,"exact original runtime must belong to authenticated canonical root");
    // One bounded retained loader reference, including partial PIN/path failure.
    HMODULE module=LoadLibraryExW(file.c_str(),nullptr,LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|LOAD_LIBRARY_SEARCH_SYSTEM32);
    if(!module)return reject(issue,7,"original runtime load rejected before NGX",HRESULT_FROM_WIN32(GetLastError()));
    g_runtimeModules[index]=module;
    wchar_t path[32768]{};
    const auto length=GetModuleFileNameW(module,path,static_cast<DWORD>(std::size(path)));
    if(!length||length>=std::size(path))return reject(issue,7,"loaded runtime path unavailable");
    const auto actual=std::filesystem::canonical(path,ec);
    if(ec||!samePath(actual.wstring(),file.wstring()))return reject(issue,7,"loaded runtime differs from authenticated file");
    HMODULE pinned=nullptr;
    if(!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_PIN|GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS,
        reinterpret_cast<LPCWSTR>(module),&pinned)||pinned!=module)
        return reject(issue,7,"original runtime OS PIN rejected before NGX",HRESULT_FROM_WIN32(GetLastError()));
    return 0;
}
// No C++ unwinding object lives in this primitive external-call frame.
__declspec(noinline) NVSDK_NGX_Result actualShutdown(ID3D12Device* device,uint32_t& seh){
    seh=0;
    NVSDK_NGX_Result result=NVSDK_NGX_Result_Fail;
    __try{result=NVSDK_NGX_D3D12_Shutdown1(device);}
    __except(EXCEPTION_EXECUTE_HANDLER){seh=static_cast<uint32_t>(GetExceptionCode());result=NVSDK_NGX_Result_FAIL_PlatformError;}
    return result;
}
}
extern "C" NVSDK_NGX_Result NVSDK_CONV bp_ngx_checked_shutdown_shared(ID3D12Device* device){
    // Called only by the owner while the common mutex is held. Never relock.
    if(!g_context||!g_context->owner||device!=g_context->device.Get())
        return NVSDK_NGX_Result_FAIL_InvalidParameter;
    auto& c=*g_context;
    if(c.shutdownAttempted)return c.shutdownDevice==device?c.shutdownResult:NVSDK_NGX_Result_FAIL_InvalidParameter;
    if(!c.shutdownArmed)return NVSDK_NGX_Result_FAIL_InvalidParameter;
    c.shutdownAttempted=true;c.shutdownDevice=device; // latch before SDK/SEH
    c.shutdownResult=actualShutdown(device,c.shutdownSeh);
    return c.shutdownResult;
}
namespace bilipai::ngx {
std::mutex& mutex(){return g_mutex;}
bool available(){return !g_context||(!g_context->owner&&!g_context->quarantine);}
int pinRuntime(std::wstring& runtime,const char* ownGuid,const char* adapterEngine,Kind kind,unsigned mask,Issue& issue){
    if(!available())return reject(issue,3,"another ABI owns or quarantines the single process NGX host");
    const char* expected=kind==Kind::Video?"BiliPai-Veyra-Core-1":"BiliPai-Veyra-DLSS-SR-2";
    if(!guid(ownGuid)||!adapterEngine||std::strcmp(adapterEngine,expected)||
        !mask||(mask&~7u)||(kind==Kind::Video&&(mask&~3u))||(kind==Kind::Dlss&&mask!=DlssSr))
        return reject(issue,1,"own GUID and exact per-ABI adapter engine/runtime mask required");
    if(g_pinFailed)return reject(issue,7,"previous partial runtime PIN failed; process restart required");
    std::error_code ec;
    const auto root=std::filesystem::canonical(runtime,ec);
    if(ec||!std::filesystem::is_directory(root,ec)||ec)return reject(issue,1,"authenticated runtime directory unavailable");
    if((!g_runtimeRoot.empty()&&!samePath(g_runtimeRoot,root.wstring()))||
       (!g_projectGuid.empty()&&g_projectGuid!=ownGuid))
        return reject(issue,1,"runtime root and own GUID fixed for process lifetime; restart before switching");
    if(g_runtimeRoot.empty())g_runtimeRoot=root.wstring();
    if(g_projectGuid.empty())g_projectGuid=ownGuid;
    g_pinFailed=true; // any exception or partial load cannot clear the custody latch
    if(!g_selfPinned){
        HMODULE module=nullptr;
        if(!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_PIN|GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS,
            reinterpret_cast<LPCWSTR>(&actualShutdown),&module)||!module)
            return reject(issue,7,"shared core module OS PIN rejected before NGX",HRESULT_FROM_WIN32(GetLastError()));
        g_selfPinned=true;
    }
    if((mask&VideoSr)&&pinOne(0,L"nvngx_vsr.dll",issue))return issue.code;
    if((mask&TrueHdr)&&pinOne(1,L"nvngx_truehdr.dll",issue))return issue.code;
    if((mask&DlssSr)&&pinOne(2,L"nvngx_dlss.dll",issue))return issue.code;
    g_pinFailed=false;runtime=g_runtimeRoot;return 0;
}
bool HostLease::owned()const{return g_context&&g_context->owner==owner_&&g_context->kind==kind_;}
veyra::ngx::NgxCoreHost& HostLease::host(){
    if(!owned())throw std::logic_error("foreign or retired shared NGX lease");
    return g_context->core;
}
bool HostLease::initialize(ID3D12Device* device,const std::wstring& runtime,
    const char* project,const char* adapterEngine,veyra::Status& status){
    const char* expected=kind_==Kind::Video?"BiliPai-Veyra-Core-1":"BiliPai-Veyra-DLSS-SR-2";
    if(!owner_||!device||!available()||g_pinFailed||!g_selfPinned||
        !samePath(runtime,g_runtimeRoot)||!project||g_projectGuid!=project||
        !adapterEngine||std::strcmp(adapterEngine,expected)){
        status=veyra::Status::InvalidArgument;return false;
    }
    if(!g_context)g_context=new Context; // at most one bounded host context
    auto& c=*g_context;
    c.owner=owner_;c.kind=kind_;c.device=device;attached_=true;
    c.initAttempted=false;c.shutdownArmed=false;c.shutdownAttempted=false;
    c.shutdownDevice=nullptr;c.shutdownResult=NVSDK_NGX_Result_Fail;c.shutdownSeh=0;
    c.initAttempted=true; // failed or throwing init still owns the actual device
    return c.core.initialize(device,g_runtimeRoot,g_projectGuid.c_str(),kHostEngine,status);
}
bool HostLease::initialized()const{return owned()&&g_context->core.initialized();}
bool HostLease::healthy()const{return owned()&&!g_context->quarantine&&g_context->core.healthy();}
uint64_t HostLease::initResult()const{return owned()?g_context->core.initResult():0;}
uint32_t HostLease::liveParameterBlockCount()const{return owned()?g_context->core.liveParameterBlockCount():0;}
NVSDK_NGX_Parameter* HostLease::allocateParameters(veyra::Status& status){return host().allocateParameters(status);}
void HostLease::destroyParameters(NVSDK_NGX_Parameter* p){host().destroyParameters(p);}
void HostLease::quarantine()noexcept{if(owned())g_context->quarantine=true;}
bool HostLease::retire(Issue& issue){
    if(!attached_)return true; // allocation failed before any SDK/lease entry
    if(!owned()){reject(issue,7,"foreign shared host retirement rejected");return false;}
    auto& c=*g_context;
    if(c.quarantine){reject(issue,7,"prior SDK retirement failed; entire shared owner retained");return false;}
    c.quarantine=true;c.shutdownArmed=true;
    try{
        if(c.initAttempted){
            if(c.core.initialized())c.core.shutdown();
            else (void)bp_ngx_checked_shutdown_shared(c.device.Get());
            c.shutdownArmed=false;
            if(!c.shutdownAttempted||c.shutdownDevice!=c.device.Get()||c.shutdownSeh||
                c.shutdownResult!=NVSDK_NGX_Result_Success){
                issue.sdk=c.shutdownResult;reject(issue,7,"actual same-device SDK shutdown unqualified; retain whole owner");return false;
            }
        }
    }catch(...){reject(issue,7,"throwing SDK/upstream shutdown; retain whole shared owner through exit");return false;}
    // Only accepted retirement allows a different ABI/device to take the lease.
    // No fixed core destruction/retry and no runtime/module unload occur here.
    c.shutdownArmed=false;c.owner=nullptr;c.device.Reset();c.quarantine=false;
    attached_=false;return true;
}
}

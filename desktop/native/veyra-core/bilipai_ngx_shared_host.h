// BiliPai internal shared NGX lease, not a public ABI. GPL-3.0-or-later.
#pragma once
#include "veyra/ngx/NgxCoreHost.h"
#include <mutex>
#include <string>
namespace bilipai::ngx {
enum class Kind { Video=1, Dlss=2 };
enum RuntimeMask : unsigned { VideoSr=1, TrueHdr=2, DlssSr=4 };
struct Issue {
    int code=7;
    HRESULT hr=S_OK;
    NVSDK_NGX_Result sdk=NVSDK_NGX_Result_Fail;
    const char* message="shared NGX host rejected";
};
// All ABI calls use this one mutex, including callbacks and SDK entrypoints.
std::mutex& mutex();
bool available();
int pinRuntime(std::wstring& runtime,const char* ownGuid,const char* adapterEngine,
               Kind kind,unsigned mask,Issue& issue);
class HostLease {
public:
    HostLease(void* owner,Kind kind):owner_(owner),kind_(kind){}
    HostLease(const HostLease&)=delete;
    HostLease& operator=(const HostLease&)=delete;
    // No destructor cleanup: failed/throwing SDK custody lasts through exit.
    bool initialize(ID3D12Device*,const std::wstring&,const char*,const char*,veyra::Status&);
    bool owned()const;
    bool initialized()const;
    bool healthy()const;
    uint64_t initResult()const;
    uint32_t liveParameterBlockCount()const;
    veyra::ngx::NgxCoreHost& host();
    NVSDK_NGX_Parameter* allocateParameters(veyra::Status&);
    void destroyParameters(NVSDK_NGX_Parameter*);
    void quarantine()noexcept;
    // Feature/parameter/frame custody must already be qualified by the owner.
    bool retire(Issue&);
private:
    void* owner_;
    Kind kind_;
    bool attached_=false;
};
}

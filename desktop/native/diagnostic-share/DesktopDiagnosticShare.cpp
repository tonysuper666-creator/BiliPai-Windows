// Windows-only projection boundary; no application logging, upload or account access.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#define WINRT_NO_SOURCE_LOCATION
#include <windows.h>
#include <roapi.h>
#include <shobjidl_core.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Storage.h>
#include <winrt/Windows.Storage.FileProperties.h>
#include <winrt/Windows.ApplicationModel.DataTransfer.h>
#include <atomic>
#include <map>
#include <memory>
#include <mutex>
#include <string>

using namespace winrt;
using namespace winrt::Windows::ApplicationModel::DataTransfer;
using namespace winrt::Windows::Storage;
using namespace winrt::Windows::Foundation;
using namespace winrt::Windows::Foundation::Collections;

namespace bilipai_native_diagnostic_share_detail {
struct apartment final {
    HRESULT hr;
    explicit apartment(RO_INIT_TYPE type) : hr(RoInitialize(type)) { check_hresult(hr); }
    ~apartment() { RoUninitialize(); }
};
// No callback ever captures a raw Session pointer. Removed tokens may still have
// in-flight callbacks; weak ownership plus `closed` prevents stale publication.
struct Session final {
    std::recursive_mutex callbackGate; // safe if COM raises a terminal event during a source callback
    StorageFile file{nullptr};
    DataTransferManager manager{nullptr};
    DataPackage package{nullptr};
    com_ptr<IDataTransferManagerInterop> interop;
    event_token requested{}, completed{}, canceled{};
    bool hasRequested=false, hasCompleted=false, hasCanceled=false;
    DWORD uiThread=0;
    HWND uiWindow=nullptr;
    bool holdsApartment=false;
    std::atomic<bool> closed{false};
    std::atomic<int> state{0}; // 0 prepared, 1 pane requested, 2 data provided, 3 complete, 4 canceled, 5 failure
    std::atomic<bool> dataSupplied{false};
    HRESULT revokePackage() noexcept {
        HRESULT failure=S_OK;
        if(package) {
            try { if(hasCompleted) package.ShareCompleted(completed); }
            catch(...) { failure=to_hresult(); }
            try { if(hasCanceled) package.ShareCanceled(canceled); }
            catch(...) { if(SUCCEEDED(failure)) failure=to_hresult(); }
        }
        hasCompleted=false; hasCanceled=false; package=nullptr;
        return failure;
    }
    HRESULT closeOnOwnerThread() noexcept {
        closed=true;
        std::lock_guard callbackDrain(callbackGate);
        HRESULT failure=S_OK;
        try { if(manager && hasRequested) manager.DataRequested(requested); }
        catch(...) { failure=to_hresult(); }
        hasRequested=false;
        auto packageFailure=revokePackage();
        if(SUCCEEDED(failure)) failure=packageFailure;
        manager=nullptr; interop=nullptr; file=nullptr;
        if(holdsApartment) { holdsApartment=false; RoUninitialize(); }
        return failure;
    }
};
std::mutex registryGate;
std::map<uint64_t,std::shared_ptr<Session>> sessions;
std::atomic<uint64_t> nextToken{1};
std::shared_ptr<Session> find(uint64_t token) {
    std::lock_guard lock(registryGate);
    auto it=sessions.find(token);
    if(it==sessions.end()) throw hresult_error(E_HANDLE);
    return it->second;
}
constexpr wchar_t failureText[]=L"本地崩溃日志无法分享，原快照已保留。";
}
using namespace bilipai_native_diagnostic_share_detail;

extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiShareProbe() noexcept {
    try {
        apartment init(RO_INIT_MULTITHREADED);
        auto factory=get_activation_factory<DataTransferManager,IDataTransferManagerInterop>();
        auto storage=get_activation_factory<StorageFile>();
        return factory && storage ? S_OK : E_NOINTERFACE;
    } catch(...) { return to_hresult(); }
}

// Called on a pool thread. No HWND, share pane, receiver or user interaction here.
extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiSharePrepare(wchar_t const* path,uint64_t* result) noexcept {
    if(!path||!result) return E_POINTER;
    *result=0;
    try {
        auto length=wcsnlen_s(path,32761);
        if(length==0||length>=32761) return E_INVALIDARG;
        apartment init(RO_INIT_MULTITHREADED);
        auto attributes=GetFileAttributesW(path);
        if(attributes==INVALID_FILE_ATTRIBUTES || (attributes&(FILE_ATTRIBUTE_DIRECTORY|FILE_ATTRIBUTE_REPARSE_POINT))) return E_INVALIDARG;
        auto value=std::make_shared<Session>();
        value->file=StorageFile::GetFileFromPathAsync(path).get();
        auto size=value->file.GetBasicPropertiesAsync().get().Size();
        if(size==0||size>512*1024 || value->file.FileType()!=L".txt") return E_INVALIDARG;
        auto token=nextToken.fetch_add(1);
        std::lock_guard lock(registryGate);
        if(sessions.size()>=8) return HRESULT_FROM_WIN32(ERROR_TOO_MANY_OPEN_FILES);
        sessions.emplace(token,std::move(value)); *result=token; return S_OK;
    } catch(...) { return to_hresult(); }
}

// Must run on the existing visible owner's HWND creation thread (Swing EDT).
// S_OK means the system pane was requested, never that a receiver accepted data.
extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiShareShow(uint64_t token,HWND hwnd) noexcept {
    try {
        DWORD process=0; DWORD thread=GetWindowThreadProcessId(hwnd,&process);
        if(!hwnd||!IsWindow(hwnd)||!IsWindowVisible(hwnd)||process!=GetCurrentProcessId()||thread!=GetCurrentThreadId()) return E_INVALIDARG;
        auto value=find(token);
        if(value->closed||value->uiThread) return HRESULT_FROM_WIN32(ERROR_INVALID_STATE);
        {
            std::lock_guard lock(registryGate);
            for(auto const& entry:sessions) {
                if(entry.first!=token && !entry.second->closed && entry.second->uiWindow==hwnd)
                    return HRESULT_FROM_WIN32(ERROR_BUSY);
            }
        }
        auto hr=RoInitialize(RO_INIT_SINGLETHREADED); check_hresult(hr);
        value->holdsApartment=true; value->uiThread=thread; value->uiWindow=hwnd;
        value->interop=get_activation_factory<DataTransferManager,IDataTransferManagerInterop>();
        check_hresult(value->interop->GetForWindow(hwnd,guid_of<DataTransferManager>(),put_abi(value->manager)));
        std::weak_ptr<Session> weak=value;
        value->requested=value->manager.DataRequested([weak](DataTransferManager const&,DataRequestedEventArgs const& args) noexcept {
            auto value=weak.lock();
            if(!value || value->closed) return;
            std::lock_guard callbackLock(value->callbackGate);
            if(value->closed) return;
            DataRequest request{nullptr};
            try {
                request=args.Request();
                if(value->dataSupplied) {
                    request.FailWithDisplayText(failureText);
                    return; // Do not replace callbacks/data of a still-unconfirmed operation.
                }
                check_hresult(value->revokePackage());
                value->package=request.Data();
                value->package.Properties().Title(L"BiliPai 日志反馈");
                value->package.Properties().Description(L"请查看附件中的日志文件");
                value->package.SetText(L"请查看附件中的日志文件");
                value->package.RequestedOperation(DataPackageOperation::Copy);
                value->completed=value->package.ShareCompleted([weak](DataPackage const&,ShareCompletedEventArgs const&) {
                    if(auto value=weak.lock();value&&!value->closed) {
                        std::lock_guard lock(value->callbackGate);
                        if(!value->closed && value->state!=4) value->state=3;
                    }
                }); value->hasCompleted=true;
                try {
                    value->canceled=value->package.ShareCanceled([weak](DataPackage const&,winrt::Windows::Foundation::IInspectable const&) {
                        if(auto value=weak.lock();value&&!value->closed) {
                            std::lock_guard lock(value->callbackGate);
                            if(!value->closed && value->state!=3) value->state=4;
                        }
                    }); value->hasCanceled=true;
                } catch(hresult_no_interface const&) {
                    // ShareCanceled was introduced in Windows 10 2004. Missing
                    // cancellation means an unconfirmed lease, not fake completion.
                    value->hasCanceled=false;
                }
                auto items=single_threaded_vector<IStorageItem>(); items.Append(value->file.as<IStorageItem>());
                // Fail closed even if the OS call throws after a possible partial grant.
                // false is evidence of no file-supply attempt, never merely no success result.
                value->dataSupplied=true;
                value->package.SetStorageItems(items,true);
                if(!value->closed && value->state<3) value->state=2;
            } catch(...) {
                value->state=5;
                try { if(request) request.FailWithDisplayText(failureText); } catch(...) {}
            }
        }); value->hasRequested=true;
        check_hresult(value->interop->ShowShareUIForWindow(hwnd));
        int prepared=0; value->state.compare_exchange_strong(prepared,1);
        return S_OK;
    } catch(...) { return to_hresult(); }
}

extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiShareState(uint64_t token,int* state) noexcept {
    if(!state) return E_POINTER;
    try { *state=find(token)->state; return S_OK; } catch(...) { return to_hresult(); }
}

// Shown sessions may only be revoked on the same UI thread. The file copy stays
// on private disk: clearing a prompt or retiring an actor must not invalidate a
// file that a user-chosen receiver may still be reading.
extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiShareClose(uint64_t token) noexcept {
    try {
        auto value=find(token);
        if(value->uiThread && value->uiThread!=GetCurrentThreadId()) return RPC_E_WRONG_THREAD;
        // Drain every resource even if a COM event revocation fails. Report the
        // HRESULT without throwing through noexcept or erasing the retry token.
        check_hresult(value->closeOnOwnerThread());
        std::lock_guard lock(registryGate); sessions.erase(token); return S_OK;
    } catch(...) { return to_hresult(); }
}

// Returns a stable post-drain outcome. The caller may delete a copy only after
// completed/canceled, or when this confirms no storage items were supplied.
// Retirement itself is never interpreted as a receiver completion event.
extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiShareRetire(uint64_t token,int* finalState,int* supplied) noexcept {
    if(!finalState||!supplied) return E_POINTER;
    *finalState=-1; *supplied=1; // fail closed: no evidence authorizing deletion
    try {
        auto value=find(token);
        if(value->uiThread && value->uiThread!=GetCurrentThreadId()) return RPC_E_WRONG_THREAD;
        check_hresult(value->closeOnOwnerThread());
        *finalState=value->state.load(); *supplied=value->dataSupplied.load()?1:0;
        std::lock_guard lock(registryGate); sessions.erase(token); return S_OK;
    } catch(...) { return to_hresult(); }
}

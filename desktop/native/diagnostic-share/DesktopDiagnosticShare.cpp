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
#include <winrt/Windows.Networking.Connectivity.h>
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
struct OwnerDispatcher;
struct Session final {
    std::shared_ptr<OwnerDispatcher> dispatcher;
    std::atomic<bool> ownerReleased{false};
    std::atomic<bool> bound{false};
    std::atomic<DWORD> lastOwnerExecution{0};
    std::atomic<unsigned> callbackDepth{0};
    std::atomic<bool> ownerClosePending{false};
    std::atomic<bool> closingResources{false};
    std::recursive_mutex callbackGate; // safe if COM raises a terminal event during a source callback
    StorageFile file{nullptr};
    bool textOnly=false;
    bool mediaFile=false;
    hstring shareTitle, shareText;
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
    bool beginStorageSupply() noexcept {
        if(closed)return false;
        dataSupplied=true;return true;
    }
    HRESULT revokePackage() noexcept {
        HRESULT failure=S_OK;
        // Projected C++/WinRT event removal is noexcept and discards the ABI
        // HRESULT. Explicit checked ABI calls preserve real retry ownership.
        if(hasCompleted) {
            try {
                if(!package)throw hresult_error(E_UNEXPECTED);
                auto iface=package.as<IDataPackage3>();
                check_hresult(static_cast<winrt::impl::abi_t<IDataPackage3>*>(get_abi(iface))->remove_ShareCompleted(completed));
                hasCompleted=false;
            }catch(...){failure=to_hresult();}
        }
        if(hasCanceled) {
            try {
                if(!package)throw hresult_error(E_UNEXPECTED);
                auto iface=package.as<IDataPackage4>();
                check_hresult(static_cast<winrt::impl::abi_t<IDataPackage4>*>(get_abi(iface))->remove_ShareCanceled(canceled));
                hasCanceled=false;
            }catch(...){if(SUCCEEDED(failure))failure=to_hresult();}
        }
        if(!hasCompleted&&!hasCanceled)package=nullptr;
        return failure;
    }
    HRESULT closeOnOwnerThread() noexcept {
        closed=true;ownerClosePending=true;
        if(uiThread&&uiThread!=GetCurrentThreadId())return RPC_E_WRONG_THREAD;
        // Reentrant same-owner cleanup must never use the recursive gate as
        // evidence that an event body has drained. Do not block an owner STA
        // on a callback that could itself need an outbound COM call to it.
        if(callbackDepth||closingResources)return HRESULT_FROM_WIN32(ERROR_IO_PENDING);
        std::unique_lock callbackDrain(callbackGate,std::try_to_lock);
        if(!callbackDrain.owns_lock()||callbackDepth||closingResources.exchange(true))return HRESULT_FROM_WIN32(ERROR_IO_PENDING);
        HRESULT failure=S_OK;
        if(hasRequested) {
            try {
                if(!manager)throw hresult_error(E_UNEXPECTED);
                auto iface=manager.as<IDataTransferManager>();
                check_hresult(static_cast<winrt::impl::abi_t<IDataTransferManager>*>(get_abi(iface))->remove_DataRequested(requested));
                hasRequested=false;
            }catch(...){failure=to_hresult();}
        }
        auto packageFailure=revokePackage();if(SUCCEEDED(failure))failure=packageFailure;
        if(FAILED(failure)){closingResources=false;return failure;}
        // Failed registrations keep their object and apartment above. Only a
        // successful real revoke of every remaining item releases these refs.
        manager=nullptr;interop=nullptr;file=nullptr;
        if(holdsApartment){holdsApartment=false;RoUninitialize();}
        ownerReleased=true;ownerClosePending=false;closingResources=false;
        return S_OK;
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
struct OwnerDispatcher;
enum class OwnerOperation { Bind, Show, Close };
struct OwnerCommand {
    uint64_t id=0;
    OwnerOperation operation=OwnerOperation::Bind;
    std::shared_ptr<Session> session;
    std::atomic<int> phase{0}; // 0 queued, 1 running, 2 explicitly finished
    std::atomic<bool> canceled{false};
    HRESULT result=E_PENDING;
};
struct OwnerDispatcher {
    HWND window=nullptr;
    DWORD thread=0;
    HHOOK hook=nullptr;
    std::atomic<bool> destroying{false};
    std::atomic<bool> detaching{false};
    bool executing=false; // owner-thread-only; nested messages may not overlap COM work
    std::mutex commandsGate;
    std::map<uint64_t,std::shared_ptr<OwnerCommand>> commands;
    unsigned callbacks=0; // protected by dispatchersGate, never held while executing COM
    HANDLE quiescent=CreateEventW(nullptr,TRUE,TRUE,nullptr);
    ~OwnerDispatcher() { if(quiescent) CloseHandle(quiescent); }
};
// Constructed after the event's recursive gate is held, before its local COM
// values. Those locals therefore die before outermost callback drain resumes.
struct CallbackScope final {
    std::shared_ptr<Session> value;
    explicit CallbackScope(std::shared_ptr<Session> owner):value(std::move(owner)){++value->callbackDepth;}
    ~CallbackScope() noexcept {
        auto last=value->callbackDepth.fetch_sub(1)==1;
        if(!last||!value->ownerClosePending||GetCurrentThreadId()!=value->uiThread)return;
        auto dispatcher=value->dispatcher;
        // During a Bind/Show/Close command, its own final path performs cleanup
        // after the COM operation returns. An asynchronous callback instead
        // drains here on its actual owner after its body/local refs have unwound.
        if(!dispatcher||!dispatcher->executing)value->closeOnOwnerThread();
    }
};
std::mutex dispatchersGate;
std::map<HWND,std::shared_ptr<OwnerDispatcher>> dispatchers;
std::atomic<uint64_t> nextCommand{1};
std::atomic<unsigned> hookRegistrations{0},hookUnregistrations{0},commandCompletions{0},commandTimeouts{0},destroyDrains{0};
LRESULT CALLBACK ownerHook(int code,WPARAM wparam,LPARAM lparam) noexcept;
HRESULT runOwnerCommand(std::shared_ptr<OwnerCommand> const&,std::shared_ptr<OwnerDispatcher> const&) noexcept;
UINT ownerMessage() {
    static UINT const value=RegisterWindowMessageW(L"BiliPai.DiagnosticShare.OwnerCommand.31416e2e-9ca6-411a-9375-0c35e6f20a85");
    check_bool(value!=0); return value;
}
std::shared_ptr<OwnerDispatcher> dispatcherFor(HWND window,DWORD thread) {
    {
        std::lock_guard lock(dispatchersGate);
        auto found=dispatchers.find(window);
        if(found!=dispatchers.end()) {
            check_bool(found->second->thread==thread && !found->second->destroying && !found->second->detaching);
            return found->second;
        }
    }
    auto value=std::make_shared<OwnerDispatcher>();value->window=window;value->thread=thread;check_bool(value->quiescent!=nullptr);
    ownerMessage();
    {
        std::lock_guard lock(dispatchersGate);
        if(dispatchers.contains(window))throw hresult_error(HRESULT_FROM_WIN32(ERROR_BUSY));
        dispatchers.emplace(window,value);
    }
    // This is a checked current-process thread. NULL hmod follows the Win32
    // same-process hook contract; no global hook or DLL injection is requested.
    auto hook=SetWindowsHookExW(WH_CALLWNDPROC,ownerHook,nullptr,thread);
    if(!hook) {std::lock_guard lock(dispatchersGate);dispatchers.erase(window);throw_last_error();}
    value->hook=hook;++hookRegistrations;return value;
}
void finishCommand(std::shared_ptr<OwnerDispatcher> const& dispatcher,std::shared_ptr<OwnerCommand> const& command,HRESULT result) {
    command->result=result;command->phase.store(2,std::memory_order_release);++commandCompletions;
    std::lock_guard lock(dispatcher->commandsGate);dispatcher->commands.erase(command->id);
}
void discardCanceledQueued(std::shared_ptr<OwnerDispatcher> const& dispatcher,std::shared_ptr<Session> const& session) {
    std::map<uint64_t,std::shared_ptr<OwnerCommand>> canceled;
    {
        std::lock_guard lock(dispatcher->commandsGate);
        for(auto const& entry:dispatcher->commands)if(entry.second->session==session&&entry.second->canceled) {
            int queued=0;if(entry.second->phase.compare_exchange_strong(queued,1))canceled.emplace(entry);
        }
    }
    // The exact owner acknowledges cancellation; a missing timed-out Windows
    // message can no longer leave an uncollectable Session->command cycle.
    for(auto const& entry:canceled)finishCommand(dispatcher,entry.second,E_ABORT);
}
// A timeout changes no native state to terminal. An in-flight command retains its
// Session and dispatcher until owner execution/drain acknowledges it explicitly.
HRESULT dispatchOwner(std::shared_ptr<Session> const& session,OwnerOperation operation) noexcept {
    try {
        auto dispatcher=session->dispatcher;
        if(!dispatcher)return E_UNEXPECTED;
        if(dispatcher->destroying && operation!=OwnerOperation::Close)return HRESULT_FROM_WIN32(ERROR_INVALID_WINDOW_HANDLE);
        auto command=std::make_shared<OwnerCommand>();command->id=nextCommand.fetch_add(1);command->operation=operation;command->session=session;
        if(GetCurrentThreadId()==dispatcher->thread) {
            if(dispatcher->executing)return HRESULT_FROM_WIN32(ERROR_BUSY);
            dispatcher->executing=true;command->phase=1;auto result=runOwnerCommand(command,dispatcher);command->result=result;command->phase=2;++commandCompletions;dispatcher->executing=false;return result;
        }
        {
            std::lock_guard lock(dispatcher->commandsGate);
            if(dispatcher->detaching)return HRESULT_FROM_WIN32(ERROR_INVALID_STATE);
            for(auto const& entry:dispatcher->commands)if(entry.second->session==session&&entry.second->operation==operation) {
                if(operation!=OwnerOperation::Close)return HRESULT_FROM_WIN32(ERROR_BUSY);
                // A timed-out Close is safe to resend, using the same owned
                // command/phase. Its cancellation can never grant data. This
                // bounds repeated retry admission while the owner is stalled.
                command=entry.second;break;
            }
            dispatcher->commands.emplace(command->id,command);
        }
        DWORD_PTR ignored=0;
        auto delivered=SendMessageTimeoutW(dispatcher->window,ownerMessage(),static_cast<WPARAM>(command->id),0,
            SMTO_ABORTIFHUNG|SMTO_BLOCK|SMTO_ERRORONEXIT,2000,&ignored);
        if(command->phase.load(std::memory_order_acquire)==2)return command->result;
        // Cancellation prevents a still-queued command from gaining authority.
        // Running work cannot be discarded or interpreted as successful cleanup.
        command->canceled=true;++commandTimeouts;
        return delivered ? E_UNEXPECTED : HRESULT_FROM_WIN32(ERROR_TIMEOUT);
    }catch(...){return to_hresult();}
}
HRESULT detachDispatcher(std::shared_ptr<OwnerDispatcher> const& dispatcher) noexcept {
    try {
        {
            std::lock_guard lock(registryGate);
            for(auto const& entry:sessions)if(entry.second->dispatcher==dispatcher&&!entry.second->ownerReleased)return S_FALSE;
        }
        dispatcher->detaching=true;
        {
            std::lock_guard lock(dispatchersGate);
            auto it=dispatchers.find(dispatcher->window);if(it!=dispatchers.end()&&it->second==dispatcher)dispatchers.erase(it);
        }
        if(dispatcher->hook) {
            if(!UnhookWindowsHookEx(dispatcher->hook)) {
                std::lock_guard lock(dispatchersGate);dispatchers.emplace(dispatcher->window,dispatcher);dispatcher->detaching=false;return HRESULT_FROM_WIN32(GetLastError());
            }
            dispatcher->hook=nullptr;++hookUnregistrations;
        }
        // No future hook callback can acquire this removed registry entry.
        // Existing callbacks decrement/signal under dispatchersGate. Never wait
        // while holding that gate or the native Session registry gate.
        if(WaitForSingleObject(dispatcher->quiescent,2000)!=WAIT_OBJECT_0)return HRESULT_FROM_WIN32(ERROR_TIMEOUT);
        {
            std::lock_guard lock(dispatcher->commandsGate);
            if(!dispatcher->commands.empty())return HRESULT_FROM_WIN32(ERROR_IO_PENDING);
        }
        return S_OK;
    }catch(...){return to_hresult();}
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

// Text/link sharing uses the same owner dispatcher and callback retirement,
// without a temporary file or file-supply lease.
extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiSharePrepareText(wchar_t const* title,wchar_t const* text,uint64_t* result) noexcept {
    if(!title||!text||!result)return E_POINTER;
    *result=0;
    try {
        auto titleLength=wcsnlen_s(title,257), textLength=wcsnlen_s(text,65537);
        if(titleLength==0||titleLength>256||textLength==0||textLength>65536)return E_INVALIDARG;
        apartment init(RO_INIT_MULTITHREADED);
        auto value=std::make_shared<Session>();
        value->textOnly=true;value->shareTitle=hstring(title);value->shareText=hstring(text);
        auto token=nextToken.fetch_add(1);
        std::lock_guard lock(registryGate);
        if(sessions.size()>=8)return HRESULT_FROM_WIN32(ERROR_TOO_MANY_OPEN_FILES);
        sessions.emplace(token,std::move(value));*result=token;return S_OK;
    }catch(...){return to_hresult();}
}

// General video image payload; same Session / owner dispatcher / callback drain.
extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiSharePrepareMedia(wchar_t const* path,wchar_t const* title,wchar_t const* text,uint64_t* result) noexcept {
    if(!path||!title||!text||!result)return E_POINTER;
    *result=0;
    try {
        auto pathLength=wcsnlen_s(path,32761),titleLength=wcsnlen_s(title,257),textLength=wcsnlen_s(text,65537);
        if(pathLength==0||pathLength>=32761||titleLength==0||titleLength>256||textLength>65536)return E_INVALIDARG;
        apartment init(RO_INIT_MULTITHREADED);
        auto attributes=GetFileAttributesW(path);
        if(attributes==INVALID_FILE_ATTRIBUTES||(attributes&(FILE_ATTRIBUTE_DIRECTORY|FILE_ATTRIBUTE_REPARSE_POINT)))return E_INVALIDARG;
        auto value=std::make_shared<Session>();
        value->file=StorageFile::GetFileFromPathAsync(path).get();
        auto size=value->file.GetBasicPropertiesAsync().get().Size();
        auto extension=value->file.FileType();
        if(size==0||size>32ULL*1024*1024 || (extension!=L".jpg"&&extension!=L".jpeg"&&extension!=L".png"&&extension!=L".webp"&&extension!=L".gif"))return E_INVALIDARG;
        value->mediaFile=true;value->shareTitle=hstring(title);value->shareText=hstring(text);
        auto token=nextToken.fetch_add(1);
        std::lock_guard lock(registryGate);
        if(sessions.size()>=8)return HRESULT_FROM_WIN32(ERROR_TOO_MANY_OPEN_FILES);
        sessions.emplace(token,std::move(value));*result=token;return S_OK;
    }catch(...){return to_hresult();}
}

namespace bilipai_native_diagnostic_share_detail {
HRESULT bindOnOwnerThread(std::shared_ptr<Session> const& value,HWND hwnd) {
    DWORD process=0;auto thread=GetWindowThreadProcessId(hwnd,&process);
    if(!hwnd||!IsWindow(hwnd)||process!=GetCurrentProcessId()||thread!=GetCurrentThreadId())return E_INVALIDARG;
    if(value->closed)return HRESULT_FROM_WIN32(ERROR_INVALID_STATE);
    if(value->bound)return S_FALSE;
    if(value->holdsApartment)return HRESULT_FROM_WIN32(ERROR_INVALID_STATE);
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
            CallbackScope callbackLifetime(value);
            DataRequest request{nullptr};
            try {
                request=args.Request();
                if(value->dataSupplied) {
                    request.FailWithDisplayText(failureText);
                    return; // Do not replace callbacks/data of a still-unconfirmed operation.
                }
                check_hresult(value->revokePackage());
                value->package=request.Data();
                value->package.Properties().Title((value->textOnly || value->mediaFile) ? value->shareTitle : hstring(L"BiliPai 日志反馈"));
                value->package.Properties().Description((value->textOnly || value->mediaFile) ? value->shareTitle : hstring(L"请查看附件中的日志文件"));
                if(value->mediaFile)value->package.SetText(value->shareText);
                else if(!value->textOnly)value->package.SetText(L"请查看附件中的日志文件");
                value->package.RequestedOperation(DataPackageOperation::Copy);
                value->completed=value->package.ShareCompleted([weak](DataPackage const&,ShareCompletedEventArgs const&) {
                    if(auto value=weak.lock();value&&!value->closed) {
                        std::lock_guard lock(value->callbackGate);
                        CallbackScope callbackLifetime(value);
                        if(!value->closed && value->state!=4) value->state=3;
                    }
                }); value->hasCompleted=true;
                try {
                    value->canceled=value->package.ShareCanceled([weak](DataPackage const&,winrt::Windows::Foundation::IInspectable const&) {
                        if(auto value=weak.lock();value&&!value->closed) {
                            std::lock_guard lock(value->callbackGate);
                            CallbackScope callbackLifetime(value);
                            if(!value->closed && value->state!=3) value->state=4;
                        }
                    }); value->hasCanceled=true;
                } catch(hresult_no_interface const&) {
                    // ShareCanceled was introduced in Windows 10 2004. Missing
                    // cancellation means an unconfirmed lease, not fake completion.
                    value->hasCanceled=false;
                }
                if(value->textOnly) {
                    if(!value->beginStorageSupply())return;
                    value->package.SetText(value->shareText);
                } else {
                auto items=single_threaded_vector<IStorageItem>(); items.Append(value->file.as<IStorageItem>());
                // Fail closed even if the OS call throws after a possible partial grant.
                // false is evidence of no file-supply attempt, never merely no success result.
                // All preparatory COM calls have finished. Closed authority may
                // not start a new storage attempt; no COM call separates this
                // admission check from its conservative possible-supply flag.
                if(!value->beginStorageSupply())return;
                value->package.SetStorageItems(items,true);
                }
                if(!value->closed && value->state<3) value->state=2;
            } catch(...) {
                value->state=5;
                try { if(request) request.FailWithDisplayText(failureText); } catch(...) {}
            }
        }); value->hasRequested=true;
    value->bound=true;return S_OK;
}
HRESULT runOwnerCommand(std::shared_ptr<OwnerCommand> const& command,std::shared_ptr<OwnerDispatcher> const& dispatcher) noexcept {
    auto value=command->session;HRESULT result=E_UNEXPECTED;
    try {
        if(GetCurrentThreadId()!=dispatcher->thread)return RPC_E_WRONG_THREAD;
        value->lastOwnerExecution=GetCurrentThreadId();
        if(command->operation==OwnerOperation::Close) {discardCanceledQueued(dispatcher,value);return value->closeOnOwnerThread();}
        if(command->canceled)return E_ABORT;
        if(dispatcher->destroying||value->closed)return HRESULT_FROM_WIN32(ERROR_INVALID_STATE);
        if(command->operation==OwnerOperation::Bind)result=bindOnOwnerThread(value,dispatcher->window);
        else {
            if(!value->bound||!IsWindowVisible(dispatcher->window))return E_INVALIDARG;
            check_hresult(value->interop->ShowShareUIForWindow(dispatcher->window));
            int prepared=0;value->state.compare_exchange_strong(prepared,1);result=S_OK;
        }
    }catch(...){result=to_hresult();}
    // A nested WM_NCDESTROY or explicit retire can close authority atomically
    // while COM pumps messages. Cleanup resumes on this same owner after COM
    // work unwinds; never mutate its apartment underneath a running operation.
    if(dispatcher->destroying||value->closed) {
        auto cleanup=value->closeOnOwnerThread();if(FAILED(cleanup))return cleanup;
        return HRESULT_FROM_WIN32(ERROR_CANCELLED);
    }
    return result;
}
LRESULT CALLBACK ownerHook(int code,WPARAM wparam,LPARAM lparam) noexcept {
    std::shared_ptr<OwnerDispatcher> dispatcher;
    try {if(code>=0&&lparam) {
        auto message=reinterpret_cast<CWPSTRUCT const*>(lparam);
        {
            std::lock_guard lock(dispatchersGate);auto it=dispatchers.find(message->hwnd);
            if(it!=dispatchers.end()) {dispatcher=it->second;++dispatcher->callbacks;ResetEvent(dispatcher->quiescent);}
        }
        if(dispatcher) {
            if(message->message==ownerMessage()&&message->lParam==0) {
                std::shared_ptr<OwnerCommand> command;
                {std::lock_guard lock(dispatcher->commandsGate);auto it=dispatcher->commands.find(static_cast<uint64_t>(message->wParam));if(it!=dispatcher->commands.end())command=it->second;}
                if(command&&!dispatcher->executing) {int queued=0;if(command->phase.compare_exchange_strong(queued,1)) {
                    dispatcher->executing=true;finishCommand(dispatcher,command,runOwnerCommand(command,dispatcher));dispatcher->executing=false;
                }}
            }else if(message->message==WM_NCDESTROY) {
                dispatcher->destroying=true;
                std::map<uint64_t,std::shared_ptr<Session>> owned;
                {std::lock_guard lock(registryGate);for(auto const& entry:sessions)if(entry.second->dispatcher==dispatcher)owned.emplace(entry);}
                for(auto const& entry:owned) {entry.second->closed=true;entry.second->lastOwnerExecution=GetCurrentThreadId();if(!dispatcher->executing)entry.second->closeOnOwnerThread();}
                std::map<uint64_t,std::shared_ptr<OwnerCommand>> queued;
                {std::lock_guard lock(dispatcher->commandsGate);queued=dispatcher->commands;}
                for(auto const& entry:queued) {int pending=0;if(entry.second->phase.compare_exchange_strong(pending,1))finishCommand(dispatcher,entry.second,HRESULT_FROM_WIN32(ERROR_INVALID_WINDOW_HANDLE));}
                ++destroyDrains;
            }
        }
    }}catch(...) { /* A failed callback is unacknowledged, never a terminal event. */ }
    if(dispatcher){std::lock_guard lock(dispatchersGate);if(--dispatcher->callbacks==0)SetEvent(dispatcher->quiescent);}
    return CallNextHookEx(nullptr,code,wparam,lparam);
}
} // namespace bilipai_native_diagnostic_share_detail
// Explicit binding exercises the same STA/GetForWindow path as Show. Binding
// alone never shows the pane, requests data or grants storage items.
extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiShareBind(uint64_t token,HWND hwnd) noexcept {
    try {
        DWORD process=0;auto thread=GetWindowThreadProcessId(hwnd,&process);
        if(!hwnd||!IsWindow(hwnd)||process!=GetCurrentProcessId()||!thread)return E_INVALIDARG;
        auto value=find(token);if(value->closed)return HRESULT_FROM_WIN32(ERROR_INVALID_STATE);
        if(value->uiThread&&(value->uiThread!=thread||value->uiWindow!=hwnd))return E_INVALIDARG;
        auto dispatcher=dispatcherFor(hwnd,thread);
        {
            std::lock_guard lock(registryGate);
            for(auto const& entry:sessions)if(entry.first!=token&&entry.second->uiWindow==hwnd&&!entry.second->ownerReleased)return HRESULT_FROM_WIN32(ERROR_BUSY);
            if(value->uiThread&&(value->uiThread!=thread||value->uiWindow!=hwnd))return E_INVALIDARG;
            value->dispatcher=dispatcher;value->uiThread=thread;value->uiWindow=hwnd;
        }
        return dispatchOwner(value,OwnerOperation::Bind);
    }catch(...){return to_hresult();}
}
extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiShareShow(uint64_t token,HWND hwnd) noexcept {
    if(!hwnd||!IsWindowVisible(hwnd))return E_INVALIDARG;
    auto hr=BilipaiShareBind(token,hwnd);if(FAILED(hr))return hr;
    try{return dispatchOwner(find(token),OwnerOperation::Show);}catch(...){return to_hresult();}
}

extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiShareState(uint64_t token,int* state) noexcept {
    if(!state) return E_POINTER;
    try { *state=find(token)->state; return S_OK; } catch(...) { return to_hresult(); }
}

// Close/Retire execute COM revocation on the same actual HWND owner thread.
// A failed/unknown drain retains the token; it never authorizes copy deletion.
HRESULT retireSession(uint64_t token,int* finalState,int* supplied) noexcept {
    try {
        auto value=find(token);
        value->closed=true; // immediately stop grants, even when owner drain times out
        if(!value->ownerReleased) {
            auto hr=value->dispatcher?dispatchOwner(value,OwnerOperation::Close):value->closeOnOwnerThread();
            check_hresult(hr);
        }
        if(value->dispatcher)check_hresult(detachDispatcher(value->dispatcher));
        if(finalState)*finalState=value->state.load();if(supplied)*supplied=value->dataSupplied.load()?1:0;
        std::lock_guard lock(registryGate);sessions.erase(token);return S_OK;
    }catch(...){return to_hresult();}
}
extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiShareClose(uint64_t token) noexcept {return retireSession(token,nullptr,nullptr);}
extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiShareRetire(uint64_t token,int* finalState,int* supplied) noexcept {
    if(!finalState||!supplied)return E_POINTER;*finalState=-1;*supplied=1;
    return retireSession(token,finalState,supplied);
}
// Read-only native lifetime telemetry for isolated own-window acceptance.
extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiShareOwnerState(uint64_t token,DWORD* owner,DWORD* execution,int* bound,int* released) noexcept {
    if(!owner||!execution||!bound||!released)return E_POINTER;
    try {auto value=find(token);*owner=value->uiThread;*execution=value->lastOwnerExecution;*bound=value->bound?1:0;*released=value->ownerReleased?1:0;return S_OK;}catch(...){return to_hresult();}
}
extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiShareDispatchStats(unsigned* registered,unsigned* unregistered,unsigned* completed,unsigned* timedOut,unsigned* destroyed,unsigned* live) noexcept {
    if(!registered||!unregistered||!completed||!timedOut||!destroyed||!live)return E_POINTER;
    *registered=hookRegistrations.load();*unregistered=hookUnregistrations.load();*completed=commandCompletions.load();*timedOut=commandTimeouts.load();*destroyed=destroyDrains.load();
    std::lock_guard lock(dispatchersGate);*live=static_cast<unsigned>(dispatchers.size());return S_OK;
}

// Desktop Home preference transport: fresh preferred-profile snapshot, no cache/actor/event ownership.
extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiHomeNetworkSnapshot(
    int* profilePresent,int* connectivity,unsigned* interfaceType,int* isWwan) noexcept {
    if(!profilePresent||!connectivity||!interfaceType||!isWwan)return E_POINTER;
    *profilePresent=-1;*connectivity=-1;*interfaceType=0;*isWwan=-1;
    struct NetworkApartment final {
        HRESULT result=RoInitialize(RO_INIT_MULTITHREADED);
        ~NetworkApartment(){if(SUCCEEDED(result))RoUninitialize();}
    } apartment;
    // A caller's existing STA remains valid and must not be uninitialized by this query.
    if(FAILED(apartment.result)&&apartment.result!=RPC_E_CHANGED_MODE)return apartment.result;
    try {
        using namespace winrt::Windows::Networking::Connectivity;
        // String overload creates a local factory: no process factory pointer may
        // outlive this query's RoUninitialize on an otherwise uninitialized caller.
        auto factory=winrt::get_activation_factory<INetworkInformationStatics>(
            L"Windows.Networking.Connectivity.NetworkInformation");
        auto profile=factory.GetInternetConnectionProfile();
        if(!profile) {*profilePresent=0;*connectivity=0;*interfaceType=0;*isWwan=0;return S_OK;}
        auto adapter=profile.NetworkAdapter();if(!adapter)return E_UNEXPECTED;
        auto level=profile.GetNetworkConnectivityLevel();
        auto kind=adapter.IanaInterfaceType();
        auto cellular=profile.IsWwanConnectionProfile();
        *profilePresent=1;*connectivity=static_cast<int>(level);*interfaceType=kind;*isWwan=cellular?1:0;
        return S_OK;
    }catch(...){return winrt::to_hresult();}
}

// Extended SAME preferred-profile query. Old four-field ABI remains callable above.
// Bounded private bytes are hashed/wiped by the owning Kotlin platform; never logged.
extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiHomeNetworkIdentitySnapshot(
    int* profilePresent,int* connectivity,unsigned* interfaceType,int* isWwan,
    unsigned char* identity,unsigned capacity,unsigned* written) noexcept {
    if(!profilePresent||!connectivity||!interfaceType||!isWwan||!identity||!written)return E_POINTER;
    *profilePresent=-1;*connectivity=-1;*interfaceType=0;*isWwan=-1;*written=0;
    if(capacity==0||capacity>8192)return E_INVALIDARG;
    struct NetworkApartment final {
        HRESULT result=RoInitialize(RO_INIT_MULTITHREADED);
        ~NetworkApartment(){if(SUCCEEDED(result))RoUninitialize();}
    } apartment;
    if(FAILED(apartment.result)&&apartment.result!=RPC_E_CHANGED_MODE)return apartment.result;
    try {
        using namespace winrt::Windows::Networking::Connectivity;
        // String overload creates a local factory: no process factory pointer may
        // outlive this query's RoUninitialize on an otherwise uninitialized caller.
        auto factory=winrt::get_activation_factory<INetworkInformationStatics>(
            L"Windows.Networking.Connectivity.NetworkInformation");
        auto profile=factory.GetInternetConnectionProfile();
        std::string value="bilipai-preferred-network-v1/";
        int present=0,level=0,cellular=0;unsigned kind=0;
        if(!profile)value+="absent";
        else {
            auto adapter=profile.NetworkAdapter();if(!adapter)return E_UNEXPECTED;
            auto name=winrt::to_string(profile.ProfileName());
            auto adapterId=winrt::to_string(winrt::to_hstring(adapter.NetworkAdapterId()));
            std::string ssid;
            if(profile.IsWlanConnectionProfile()) {
                auto wlan=profile.WlanConnectionProfileDetails();if(!wlan)return E_UNEXPECTED;
                ssid=winrt::to_string(wlan.GetConnectedSsid());
            }
            // Length-prefix fields avoid collisions even when profile names contain separators.
            value+=std::to_string(name.size())+":"+name+std::to_string(adapterId.size())+":"+adapterId+
                std::to_string(ssid.size())+":"+ssid;
            present=1;level=static_cast<int>(profile.GetNetworkConnectivityLevel());
            kind=adapter.IanaInterfaceType();cellular=profile.IsWwanConnectionProfile()?1:0;
        }
        if(value.empty()||value.size()>capacity)return E_BOUNDS;
        for(size_t i=0;i<value.size();++i)identity[i]=static_cast<unsigned char>(value[i]);
        *written=static_cast<unsigned>(value.size());
        *profilePresent=present;*connectivity=level;*interfaceType=kind;*isWwan=cellular;
        return S_OK;
    }catch(...){return winrt::to_hresult();}
}

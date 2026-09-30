from pathlib import Path
import hashlib
HERE=Path(__file__).resolve().parent
original=HERE/'candidate04/DesktopDiagnosticShare.cpp'
assert hashlib.sha256(original.read_bytes()).hexdigest()=='5eba2101f6019c0f04a47df7202396bf0cf526967aee2abbee2f79466ed21b59'
source=original.read_text(encoding='utf-8')
source=source.replace('    std::atomic<DWORD> lastOwnerExecution{0};','    std::atomic<DWORD> lastOwnerExecution{0};\n    std::atomic<unsigned> callbackDepth{0};\n    std::atomic<bool> ownerClosePending{false};\n    std::atomic<bool> closingResources{false};')
begin=source.index('    HRESULT revokePackage() noexcept {');end=source.index('\n};\nstd::mutex registryGate',begin)
replacement='''    HRESULT revokePackage() noexcept {
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
    }'''
source=source[:begin]+replacement+source[end:]
scope='''// Constructed after the event's recursive gate is held, before its local COM
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
'''
where=source.index('std::mutex dispatchersGate;');source=source[:where]+scope+source[where:]
source=source.replace('            if(value->closed) return;\n            DataRequest request{nullptr};','            if(value->closed) return;\n            CallbackScope callbackLifetime(value);\n            DataRequest request{nullptr};')
source=source.replace('                        std::lock_guard lock(value->callbackGate);\n                        if(!value->closed','                        std::lock_guard lock(value->callbackGate);\n                        CallbackScope callbackLifetime(value);\n                        if(!value->closed')
source=source.replace('                            std::lock_guard lock(value->callbackGate);\n                            if(!value->closed','                            std::lock_guard lock(value->callbackGate);\n                            CallbackScope callbackLifetime(value);\n                            if(!value->closed')
# Three distinct event registrations must all hold callback lifetime admission.
assert source.count('CallbackScope callbackLifetime(value);')==3
target=HERE/'candidate05/DesktopDiagnosticShare.cpp';target.parent.mkdir(exist_ok=True)
assert not target.exists();target.write_text(source,encoding='utf-8',newline='\n')
print(hashlib.sha256(target.read_bytes()).hexdigest())

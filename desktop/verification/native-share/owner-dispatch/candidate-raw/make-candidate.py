from pathlib import Path
import hashlib
HERE=Path(__file__).resolve().parent
original=HERE.parent/'native-share-current-main-review/stable-source-a/native/DesktopDiagnosticShare.cpp'
source=original.read_text(encoding='utf-8')
assert hashlib.sha256(original.read_bytes()).hexdigest()=='dc810bdb5b6c43c7842768270795d998d76f0b0a221e84fed97a245c220c2a9a'
source=source.replace('struct Session final {','struct OwnerDispatcher;\nstruct Session final {\n    std::shared_ptr<OwnerDispatcher> dispatcher;\n    std::atomic<bool> ownerReleased{false};\n    std::atomic<bool> bound{false};\n    std::atomic<DWORD> lastOwnerExecution{0};')
source=source.replace('return failure;\n    }\n};','if(SUCCEEDED(failure))ownerReleased=true;\n        return failure;\n    }\n};')
insertion=source.index('constexpr wchar_t failureText')
support=(HERE/'dispatcher-support.txt').read_text(encoding='utf-8')
source=source[:insertion]+support+'\n'+source[insertion:]
start=source.index('// Must run on the existing visible owner')
end=source.index('extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiShareState',start)
old=source[start:end]
body_start=old.index('        auto hr=RoInitialize(RO_INIT_SINGLETHREADED);')
body_end=old.index('        check_hresult(value->interop->ShowShareUIForWindow(hwnd));')
body=old[body_start:body_end]
bind='''namespace bilipai_native_diagnostic_share_detail {
HRESULT bindOnOwnerThread(std::shared_ptr<Session> const& value,HWND hwnd) {
    DWORD process=0;auto thread=GetWindowThreadProcessId(hwnd,&process);
    if(!hwnd||!IsWindow(hwnd)||process!=GetCurrentProcessId()||thread!=GetCurrentThreadId())return E_INVALIDARG;
    if(value->closed)return HRESULT_FROM_WIN32(ERROR_INVALID_STATE);
    if(value->bound)return S_FALSE;
    if(value->holdsApartment)return HRESULT_FROM_WIN32(ERROR_INVALID_STATE);
'''+body+'''    value->bound=true;return S_OK;
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
'''
source=source[:start]+bind+'\n'+source[end:]
start=source.index('// Shown sessions may only')
source=source[:start]+'''// Close/Retire execute COM revocation on the same actual HWND owner thread.
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
'''
candidate=HERE/'candidate04/DesktopDiagnosticShare.cpp';candidate.parent.mkdir(exist_ok=True)
assert not candidate.exists(),'Do not overwrite candidate; use a fresh lane iteration'
candidate.write_text(source,encoding='utf-8',newline='\n')
print('Candidate SHA',hashlib.sha256(candidate.read_bytes()).hexdigest())

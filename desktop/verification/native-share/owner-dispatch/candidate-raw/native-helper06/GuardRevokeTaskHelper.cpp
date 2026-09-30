// TASK ONLY: includes exact candidate source without production body overrides.
// Additional exports create controlled callback scope/faults against its actual
// Session and a real bound WinRT manager. Never calls Show or supplies storage.
#include "../candidate06/DesktopDiagnosticShare.cpp"

namespace task_guard_revoke {
using ManagerAbi=winrt::impl::abi_t<IDataTransferManager>;
struct Record {int* out;unsigned checks=0;void require(bool ok){if(!ok)throw hresult_error(E_FAIL);++checks;out[2]=static_cast<int>(checks);}};
struct FaultManager final : ManagerAbi {
    std::atomic<uint32_t> references{1};DataTransferManager actual;IDataTransferManager actualInterface;std::weak_ptr<Session> session;Record* record;
    bool failFirst=true;FaultManager(DataTransferManager source,std::shared_ptr<Session> owner,Record* result):actual(source),actualInterface(source.as<IDataTransferManager>()),session(owner),record(result){}
    ManagerAbi* abi(){return static_cast<ManagerAbi*>(get_abi(actualInterface));}
    int32_t __stdcall QueryInterface(winrt::guid const& iid,void** result) noexcept override {
        if(!result)return E_POINTER;*result=nullptr;
        if(iid==guid_of<IDataTransferManager>()||iid==guid_of<winrt::Windows::Foundation::IInspectable>()||iid==guid_of<winrt::Windows::Foundation::IUnknown>()) {*result=static_cast<ManagerAbi*>(this);AddRef();return S_OK;}return E_NOINTERFACE;
    }
    uint32_t __stdcall AddRef() noexcept override{return ++references;}
    uint32_t __stdcall Release() noexcept override{auto value=--references;if(!value)delete this;return value;}
    int32_t __stdcall GetIids(uint32_t* count,winrt::guid** values) noexcept override{return abi()->GetIids(count,values);}
    int32_t __stdcall GetRuntimeClassName(void** name) noexcept override{return abi()->GetRuntimeClassName(name);}
    int32_t __stdcall GetTrustLevel(winrt::Windows::Foundation::TrustLevel* level) noexcept override{return abi()->GetTrustLevel(level);}
    int32_t __stdcall add_DataRequested(void* handler,winrt::event_token* token) noexcept override{return abi()->add_DataRequested(handler,token);}
    int32_t __stdcall remove_DataRequested(winrt::event_token token) noexcept override {
        ++record->out[12];
        if(auto value=session.lock()) {
            record->out[14]=value->closeOnOwnerThread();
            record->out[20]=(value->holdsApartment&&value->manager&&value->file)?1:0;
        }
        if(failFirst){failFirst=false;return E_FAIL;} // before real remove: retain actual registration
        ++record->out[13];return abi()->remove_DataRequested(token);
    }
    int32_t __stdcall add_TargetApplicationChosen(void* handler,winrt::event_token* token) noexcept override{return abi()->add_TargetApplicationChosen(handler,token);}
    int32_t __stdcall remove_TargetApplicationChosen(winrt::event_token token) noexcept override{return abi()->remove_TargetApplicationChosen(token);}
};
struct Command {uint64_t id=0,token=0;int mode=0;int* out=nullptr;std::atomic<bool> done{false};HRESULT result=E_PENDING;};
std::mutex gate;std::shared_ptr<Command> current;std::atomic<uint64_t> next{1};
UINT message(){static auto id=RegisterWindowMessageW(L"BiliPai.Task.GuardRevoke.3424f744-abee-4e88-a37f-8a563f8906cd");return id;}
bool refsIntact(std::shared_ptr<Session> const& value){return value->holdsApartment&&value->manager&&value->file&&value->package&&!value->ownerReleased;}
void attachLocalPackage(std::shared_ptr<Session> const& value) {
    value->package=DataPackage();std::weak_ptr<Session> weak=value;
    value->completed=value->package.ShareCompleted([weak](DataPackage const&,ShareCompletedEventArgs const&){if(auto value=weak.lock()){std::lock_guard lock(value->callbackGate);CallbackScope scope(value);}});value->hasCompleted=true;
    value->canceled=value->package.ShareCanceled([weak](DataPackage const&,winrt::Windows::Foundation::IInspectable const&){if(auto value=weak.lock()){std::lock_guard lock(value->callbackGate);CallbackScope scope(value);}});value->hasCanceled=true;
    // This local actual WinRT package is never supplied to a request/receiver.
}
HRESULT exercise(std::shared_ptr<Command> const& command) noexcept {
    try {
        auto value=find(command->token);Record record{command->out};auto out=command->out;
        out[15]=static_cast<int>(GetCurrentThreadId());record.require(value->uiThread==GetCurrentThreadId());
        attachLocalPackage(value);record.require(refsIntact(value));record.require(!value->dataSupplied&&value->state==0);
        if(command->mode<=2) {
            {
                std::lock_guard callbackGate(value->callbackGate);CallbackScope outer(value);
                {
                    CallbackScope inner(value);record.require(value->callbackDepth==2);
                    if(command->mode==2){record.require(DestroyWindow(value->uiWindow)!=0);out[16]=IsWindow(value->uiWindow)?1:0;}
                    int state=66,supplied=66;
                    out[3]=command->mode==0?BilipaiShareClose(command->token):BilipaiShareRetire(command->token,&state,&supplied);
                    out[4]=state;out[5]=supplied;out[6]=refsIntact(value)?1:0;out[7]=value->ownerReleased?1:0;
                    record.require(FAILED(out[3])&&refsIntact(value)&&value->ownerClosePending);
                    record.require(!value->beginStorageSupply()&&!value->dataSupplied);out[21]=1;
                    record.require(find(command->token)==value);
                    if(command->mode!=0)record.require(state==-1&&supplied==1);
                }
                record.require(value->callbackDepth==1&&refsIntact(value)); // inner scope may not drain
            }
            record.require(value->callbackDepth==0&&value->ownerReleased&&!value->holdsApartment&&!value->manager&&!value->package&&!value->file);
            out[9]=value->ownerReleased?1:0;out[10]=value->holdsApartment?1:0;out[11]=value->package?1:0;
            int state=-1,supplied=1;out[17]=BilipaiShareRetire(command->token,&state,&supplied);
            record.require(SUCCEEDED(out[17])&&state==0&&supplied==0);
        }else {
            auto relay=new FaultManager(value->manager,value,&record);
            value->manager=DataTransferManager(static_cast<void*>(static_cast<ManagerAbi*>(relay)),take_ownership_from_abi);
            int state=-1,supplied=1;out[3]=BilipaiShareRetire(command->token,&state,&supplied);out[4]=state;out[5]=supplied;
            out[8]=value->hasRequested?1:0;out[18]=value->ownerReleased?1:0;out[19]=value->holdsApartment?1:0;
            record.require(FAILED(out[3])&&state==-1&&supplied==1);
            record.require(!value->beginStorageSupply()&&!value->dataSupplied);out[21]=1;
            record.require(value->hasRequested&&value->manager&&value->interop&&value->file&&value->holdsApartment&&!value->ownerReleased);
            record.require(!value->hasCompleted&&!value->hasCanceled&&!value->package); // successful items individually cleared
            record.require(out[12]==1&&out[13]==0&&FAILED(out[14])&&out[20]==1);
            record.require(find(command->token)==value);
            out[17]=BilipaiShareRetire(command->token,&state,&supplied);
            record.require(SUCCEEDED(out[17])&&state==0&&supplied==0);
            record.require(out[12]==2&&out[13]==1&&FAILED(out[14])&&out[20]==1);
            record.require(value->ownerReleased&&!value->hasRequested&&!value->manager&&!value->file&&!value->holdsApartment);
            out[9]=value->ownerReleased?1:0;out[10]=value->holdsApartment?1:0;out[11]=value->package?1:0;
        }
        try{find(command->token);record.require(false);}catch(hresult_error const& error){record.require(error.code()==E_HANDLE);}
        out[1]=1;return S_OK;
    }catch(...){return to_hresult();}
}
LRESULT CALLBACK hook(int code,WPARAM wparam,LPARAM lparam) noexcept {
    if(code>=0&&lparam) {
        auto m=reinterpret_cast<CWPSTRUCT const*>(lparam);std::shared_ptr<Command> command;
        {std::lock_guard lock(gate);command=current;}
        if(command&&m->message==message()&&m->wParam==command->id&&m->lParam==0) {
            auto value=find(command->token);
            if(m->hwnd==value->uiWindow&&GetCurrentThreadId()==value->uiThread){command->result=exercise(command);command->done=true;}
        }
    }
    return CallNextHookEx(nullptr,code,wparam,lparam);
}
}
extern "C" __declspec(dllexport) HRESULT WINAPI TaskGuardRevokeExercise(uint64_t token,int mode,int* output) noexcept {
    using namespace task_guard_revoke;if(!output||mode<0||mode>3)return E_INVALIDARG;
    for(int i=0;i<24;++i)output[i]=0;
    HHOOK installed=nullptr;
    try {
        auto value=find(token);auto command=std::make_shared<Command>();command->id=next++;command->token=token;command->mode=mode;command->out=output;
        {std::lock_guard lock(gate);check_bool(!current);current=command;}
        installed=SetWindowsHookExW(WH_CALLWNDPROC,hook,nullptr,value->uiThread);check_bool(installed!=nullptr);
        DWORD_PTR ignored=0;SendMessageTimeoutW(value->uiWindow,message(),static_cast<WPARAM>(command->id),0,SMTO_BLOCK|SMTO_ABORTIFHUNG|SMTO_ERRORONEXIT,4000,&ignored);
        check_bool(UnhookWindowsHookEx(installed)!=0);installed=nullptr;
        {std::lock_guard lock(gate);current=nullptr;}
        if(!command->done)return HRESULT_FROM_WIN32(ERROR_TIMEOUT);output[0]=command->result;return command->result;
    }catch(...){if(installed)UnhookWindowsHookEx(installed);std::lock_guard lock(gate);current=nullptr;return to_hresult();}
}

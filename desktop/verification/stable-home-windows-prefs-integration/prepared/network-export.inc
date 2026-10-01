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
        auto profile=NetworkInformation::GetInternetConnectionProfile();
        if(!profile) {*profilePresent=0;*connectivity=0;*interfaceType=0;*isWwan=0;return S_OK;}
        auto adapter=profile.NetworkAdapter();if(!adapter)return E_UNEXPECTED;
        auto level=profile.GetNetworkConnectivityLevel();
        auto kind=adapter.IanaInterfaceType();
        auto cellular=profile.IsWwanConnectionProfile();
        *profilePresent=1;*connectivity=static_cast<int>(level);*interfaceType=kind;*isWwan=cellular?1:0;
        return S_OK;
    }catch(...){return winrt::to_hresult();}
}

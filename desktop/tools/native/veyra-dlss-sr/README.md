# Independent DLSS runtime authentication

This metadata-only verifier is not an application selector. It authenticates a separately trusted explicit profile and the fixed NVIDIA DLSS runtime. Full mode additionally verifies the selected shared native module and the exact hash-pinned actual build receipt, both ABI source identities, the complete shared source/build closure, adapter versions and eight declared ABI exports. Both ABI consumers share bilipai_veyra_core.dll. Caller adapter version is BiliPai-Veyra-DLSS-SR-2; the actual NGX host engine is BiliPai-Veyra-Core-Shared-1.

RuntimeOnly returns RUNTIME_VERIFIED; full mode may return NATIVE_PROVENANCE_VERIFIED. EngineStatus always remains UNAVAILABLE: production linear color/depth/motion guidance and player/presentation consumer integration are not provided here. Authentication and CPU link evidence prove neither SDK/GPU execution nor displayed output. No DLL is loaded, copied or downloaded.

Module/build receipt/project identities are null in the template. Use the application's own persisted GUID, shared with its v1 profile; never borrow another application's identity. A future host must retain authenticated file identity leases before load and all native quarantine/PIN custody through process exit. This helper's file leases end when it returns.

Each future compatibility build requires an independently reviewed verifier/profile/source/build identity update. Upstream release monitoring does not install or mutate component selection. Fixed runtime is nvngx_dlss.dll 310.7.0.0, with exact NVIDIA leaf identity and bytes verified read-only from the explicit local package. Private filesystem paths and NVIDIA files are not shipped.

# Shared Veyra SR/HDR and standard DLSS SR native core

This target contains both real adapters and one process-owned fixed NgxCoreHost.
The shared bilipai_veyra_core DLL exports the existing four v1 and four v2 cdecl
entrypoints with unchanged wire layouts. v1 remains SR/TrueHDR; v2 is standard
DLSS Super Resolution. NR, DLSS Frame Generation and NvVFX VFG remain pending.

Both adapters hold the same mutex and consume an internal HostLease. Only one
validated Session/device/kind owns the host at a time. The other ABI returns
BUSY. Failed initialization retains its owner; failed or throwing feature,
parameter or observed shutdown permanently retains the entire Session, its
completed-frame/GPU leases and shared host through process exit. Later device
removal never clears that latch. Ordinary incomplete GPU work remains retryable
using the existing bounded completion-value drain.

The original host translation unit is compiled exactly once. Only that TU
redirects Shutdown1 to the shared observer. The owned shared TU calls the real
unrenamed SDK exactly once per lease behind primitive SEH. Same-device Success
with no SEH and a normal upstream return are required before owner/device
custody can clear. Failed init explicitly uses the same observed shutdown path.
NotInitialized and module PIN are not retirement evidence. The fixed host
object is retained for process lifetime, so no static destructor retries it.

Before NGX, the core OS-PINs itself and original feature modules. There are
exactly three known bounded runtime slots under one canonical authenticated
root. v1 preserves its public-header contract by pinning both SR and TrueHDR;
v2 pins DLSS. A partial PIN failure rejects all future NGX entry. The same own
persisted GUID and canonical root remain fixed for process life. Native PIN
does not authenticate runtime bytes or authorize redistribution.

Caller adapter versions remain BiliPai-Veyra-Core-1 for v1 and
BiliPai-Veyra-DLSS-SR-2 for v2, each strictly checked. Both map to the actual
fixed NGX engine version BiliPai-Veyra-Core-Shared-1. This mapping and the common
module/source/build identity require new trusted authentication metadata.
An old profile that only pins the previous v1 CPP/header cannot authenticate
this shared module. No application selection or runtime availability is
enabled by this source change.

CMake preserves and verifies all 18 video and 15 DLSS raw upstream files at
96a7c8de36bc195240161de6814739ad810722f1. Their unique union has 20 files.
Overlapping bytes must agree, and compilation uses one verified generated
header root; duplicate raw trees never shadow each other. Only one host, Log
and NgxResult TU is compiled. Eight owned ABI/host sources and the actual six
official SDK headers are pinned. Link mode additionally verifies the exact
official Release x64 /MT shim at fixed SDK commit
374959484e79a640feaba44c93ac8cfb0a03f5b5. Legacy SDK records in this directory
describe the previous three-header phase-one build; shared-ngx-sdk-pins.json
and the shared manifests define the current complete build closure.

Configure Windows x64 MSVC C++20/SEH with CMAKE_BUILD_TYPE=Release and an
explicit VEYRA_NGX_INCLUDE. BILIVEYRA_COMPILE_ONLY defaults to ON and builds
real core objects plus both ABI assertion object targets. To link, explicitly
set it OFF and supply VEYRA_NGX_STATIC_LIBRARY. Debug shims are rejected.
The neighboring DLSS CMake entry delegates to this same target; it never builds
a second NGX host or a separate DLSS DLL. Source overrides still pass all hashes.
No NVIDIA header, library, feature DLL, user path/account or binary is bundled.

Build and private module receipts stay outside this source directory. A CPU
compile/link proves source closure and symbols only. No SDK/runtime/GPU call,
processed frame or presentation has been validated by this source stage.
Actual linear/depth/motion production, shared-module runtime authentication
and player/timeline/consumer integration remain pending. mpv remains the
intended sole decoder, media clock and audio owner.

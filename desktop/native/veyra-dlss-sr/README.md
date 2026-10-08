# Standard DLSS Super Resolution backend candidate

This independent source target compiles the real fixed Veyra
DlssSrBackend and NgxCoreHost. It is the next-stage DLSS SR candidate.
It is not registered with the application, its current MPV filter, or the
phase-one VSR/TrueHDR verifier. NR, DLSS Frame Generation and NvVFX VFG
remain separate pending paths.

The private local Release /MT build and ABI assertions passed, and a real
x64 DLL linked. The module has not been loaded; NGX and GPU code have not
been invoked. A compiler/linker result does not establish hardware support,
image quality, frame completion or presentation.

## Source and external dependencies

The selected 15 Veyra source/header/LICENSE files are byte-pinned to commit
96a7c8de36bc195240161de6814739ad810722f1. Their Git blobs are also identical
at release 2.0.6 commit e3aa842a00f693a4a17d1fc4c454d0ebc59428f6.
The CMake target imports no whole upstream project, Qt, decoder, audio,
capture, export, NR provider, architecture spoof or runtime patch.

Six official NVIDIA/DLSS headers were actually included according to the
final MSVC /sourceDependencies receipts: ngx, defs, params, helpers,
helpers_d3d and helpers_cuda. The CUDA helper header is an include of the
official umbrella; this target calls only D3D12 DLSS SR, with no CUDA SDK
or CUDA runtime dependency. CMake enforces every header SHA256, the SDK
manifest hash, all 15 upstream hashes, and the three owned ABI source hashes.
Link mode additionally enforces the fixed Release x64 /MT NGX static shim.
The official dependency commit is
374959484e79a640feaba44c93ac8cfb0a03f5b5; Debug shims are not accepted.

The source candidate includes no NVIDIA header, library, feature runtime,
desktop installation data, application account or key. External dependencies
remain locally supplied under their own license. A local link is not a
redistribution approval.

Configure in a local x64 MSVC environment with CMAKE_BUILD_TYPE=Release and
VEYRA_NGX_INCLUDE pointing to the exact external official header directory.
BILIDLSS_COMPILE_ONLY defaults to ON and creates actual object targets.
To link, explicitly set it to OFF and provide VEYRA_NGX_STATIC_LIBRARY.
VEYRA_FIXED_SOURCE defaults to this directory's upstream closure; any
override must pass the same enforced hashes. This project downloads nothing.
Build evidence stays outside this production-source candidate.

## ABI v2 and actual processing

The owned cdecl ABI is 0x00020000. Windows x64 sizes are config=112,
frame=168, result=120 and status=288 bytes, with exact offsets compiled.
create initializes NGX using the caller's own persisted GUID and the engine
version BiliPai-Veyra-DLSS-SR-2, allocates real NGX parameters, and calls
DlssSrBackend::create. process calls its real evaluate implementation.
The upstream implementation uses standard NGX_D3D12_CREATE_DLSS_EXT and
NGX_D3D12_EVALUATE_DLSS_EXT; no Feature18 or compatibility backend is linked.

Input/output are linear RGBA16F BT.709/scRGB with 1.0=80 cd/m2. There is no
implicit gamma, YUV, PQ, BT.2020 or HDR conversion. DLSS's HDR/auto-exposure
creation flags are the actual fixed upstream behavior for linear textures.
Depth is target-resolution R32_FLOAT, conventional non-inverted [0,1] or
a real declared-zero fallback. Motion is target-resolution R16G16_FLOAT,
current-to-previous output pixels, unjittered. These guidance extents match
the fixed Veyra graph. The ABI checks resource format, extent, state,
identity and declared contract; it cannot certify the contents of depth,
motion or color pixels. Producer pixel correctness and DLSS suitability
still need actual runtime validation. Sharpness must be zero, as the pinned
SDK deprecates it. Sampling jitter is finite input-pixel offset within +/-1.

A first frame/new history epoch requires explicit HISTORY_RESET and real
declared-zero motion. A seek/source reset requires strictly newer source
generation and history epoch, drains old work, releases DLSS, replaces its
parameter block and recreates history. Within an epoch frames have monotonic
sequence identities. Same-resolution requests are rejected; original
passthrough belongs to the caller and is not called a DLSS result.

All three input textures, output texture and producer fence are COM-leased.
One producer fence must cover all inputs on the same device/adapter LUID.
The caller supplies a genuine direct queue and owns output/consumer leases.
The module restores input states, records DLSS output in UAV state,
transitions the requested output state, queues the producer wait followed
by actual commands and signals completion on the same queue. OK reports SDK
acceptance and submission; the caller still waits completion before use.
A consumer keeps its own resources through downstream GPU consumption.

One submission is in flight. Drain uses a completion-value loop and absolute
deadline; stale event notifications cannot falsely certify completion.
Timeout retains resources. Failed Signal quarantines all leases; a confirmed
removed device only allows entering the checked SDK retirement path and cannot
certify its success. Keep this candidate module loaded
for process lifetime, including after successful destroy; a failed create
can still return an owned handle. A feature-release failure permanently
quarantines reset/destroy; because the fixed backend clears its handle even
on rejected release, a subsequent empty release must not certify cleanup.
Later device removal never clears that SDK-release latch; the full Session
is retained until process exit/restart. The latch is set before both feature
and parameter release, and is cleared only after explicitly accepted release.
The fixed core shutdown returns void, so only that unchanged translation unit
redirects Shutdown1 to an owned same-signature observer. The owned translation
unit calls the real unrenamed SDK once behind a primitive SEH boundary, records
the exact Session/device/result/SEH, and rejects unarmed or foreign-device use.
The attempt is latched before the external call. Repeated same-device callbacks
return the recorded result without a second SDK call, including upstream
logging/destructor retries. Retirement succeeds only after the upstream call
returns normally and the actual same-device result is Success with no SEH.
Failed initialization explicitly takes the same one-attempt path if the fixed
host would skip shutdown. NotInitialized is not retirement proof. A failure or
throw permanently retains the entire Session even after device removal.
Reset/destroy retain even completed-frame COM leases while checking actual SDK
release/shutdown; failure or throwing paths retain those leases with the entire
Session. Normal processing may retire completed prior-frame leases as usual.
Module PIN is a separate lifetime guarantee and never proves GPU retirement.
All 15 fixed upstream bytes remain unchanged; the redirect is per-source only,
excluded from unity builds, and adds no DLL export. Unifying the process host
with phase one remains pending.

Before any NGX initialization/call, the native create path obtains a real
Windows process-lifetime PIN on its own DLL (including the static shim) and
the exact caller-authenticated nvngx_dlss.dll. The supplied directory and
loaded-module path are canonicalized, and the returned PIN HMODULE must
equal the loaded module. A PIN/path failure rejects before NGX. The first
attempt fixes one canonical directory and one bounded runtime slot; an
incomplete loaded reference is retained through process exit without
additional loads or directory switching. A native PIN does not authenticate
a file: the signed-byte/profile/module validation remains pending and must
precede any runtime create. These loader operations exist only as future
source behavior; none were executed during this source/compile stage.

## Integration gates still pending

The current phase-one MPV bridge supplies encoded RGBA8, not these linear
and guidance resources. No placeholder motion/depth producer or capability
flag was attached to the application. A real guidance producer, conversion
and timeline/consumer integration are pending.

This independent module owns one NGX host. It must not be initialized
concurrently with phase one's separately owned NGX host; a shared process
host must be consolidated before enabling both paths in one application.

The existing runtime verifier authenticates VSR and TrueHDR only. Signed
DLSS runtime authentication, a versioned DLSS profile/module binding, and
the actual producer's DLL identity are pending. Nothing here marks an
installed or upstream-monitored engine AVAILABLE, SUBMITTED or PRESENTED.
mpv remains the intended sole decode/audio/timeline owner.

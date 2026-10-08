# Veyra SR/HDR native core source

This directory contains the real SR/HDR core adapter source. Local Windows x64
MSVC builds have compiled and linked this adapter with the actual NVIDIA NGX
static shim. GPU initialization, processed frames, mpv presentation and HDR
output have not been executed or verified. This source alone does not enable
an application feature and is not a statement that a public package is ready.

`upstream/` is the exact minimal fixed Veyra closure. CMake defaults to this
local directory and verifies all 18 source/header/license hashes plus the
hash manifest itself. An explicit source override must match the same hashes.
No NVIDIA SDK file, runtime, model or compiled binary is included here.

Build with CMake3.25+, Windows x64 MSVC and C++20/SEH. The default
`BILIVEYRA_COMPILE_ONLY=ON` builds actual object targets and still requires an
explicit `VEYRA_NGX_INCLUDE` directory containing the real NGX headers.
To build the DLL, explicitly set `BILIVEYRA_COMPILE_ONLY=OFF`,
`VEYRA_NGX_INCLUDE` and `VEYRA_NGX_STATIC_LIBRARY` to local reviewed SDK paths.
Use a separate build directory. `SDK_PROVENANCE.json` records the fixed SDK
commit and the verified Release x64 shim identity; the build does not fetch it.

The four cdecl C exports accept caller-owned, same-device D3D12 textures,
queue, source/session/generation, rational PTS and producer/completion fences.
SR consumes full-range sRGB BT.709 RGBA8 and returns RGBA8; optional TrueHDR
returns FP16 linear BT.709 scRGB (1=80nits). Native HDR input and VFG are not
implemented by this adapter. An actual mpv GPU frame producer/consumer bridge
must supply resources and correctly tag/display the output. mpv remains the
media-clock and sole audio owner. Never unload the DLL while a handle is live.

See the ABI header for lifetime, completion and reset contracts. The private
build outcome establishes linking only. Runtime processing, fallback,
synchronization, performance and distribution compatibility remain separate
validation work.

The three NVIDIA headers reached by actual MSVC /sourceDependencies and the separately provided Release x64 /MT NGX shim must match enforced fixed hashes. The Debug shim is currently rejected.

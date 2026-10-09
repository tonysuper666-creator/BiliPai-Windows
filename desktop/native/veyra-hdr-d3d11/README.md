# Inactive D3D11 HDR restoration source

This module has no caller, MPV/CMake wiring, HDR admission, NVIDIA invocation,
frame token or renderer permission. MSVC14.44.35207 /TC /std:c11 /Zs /W4 /WX
and Windows SDK10.0.26100.0 passed C syntax compilation. Linking, shader
compilation, GPU execution, quality, performance and display remain unproved.

It uses the four GPL-3.0-or-later shaders already in ../veyra-hdr-video-sr,
derived from Veyra96a7c8de36bc195240161de6814739ad810722f1. The exact shader
source header SHA256 is
ab68fe368080ce9904dd1b084abf8219ea12995d2a551bbf11aa25b88c2f2cdf.
The signed HDR base remains separate from the fixed203nit SDR proxy.
The eventual owner must prove genuine same-source completed SR, decoder and
storage leases, source eligibility/readiness, actual DXGI memory headroom,
allocation success and all final-consumer synchronization. The configurable
texture payload ceiling is not measured free VRAM or a full working-set budget.

SwapDeviceContextState isolates saved pipeline state, but does not affect
asynchronous objects. A pre-existing active query may include these Dispatch
commands. The caller must run begin/finish outside active query scopes or
explicitly accept these commands in its queries. Lock callbacks do not prove
query isolation. See the Microsoft API contract:
https://learn.microsoft.com/en-us/windows/win32/api/d3d11_1/nf-d3d11_1-id3d11devicecontext1-swapdevicecontextstate

The actual final-use fence must be signaled after every final consumer;
core completion or input/SR readiness cannot retire decoder array-slice leases.
Missing or failed completion retains the bounded frame. Current MPV observation
proves the decoder epoch only during synchronous observation, never later GPU
submission, HDR eligibility or display.

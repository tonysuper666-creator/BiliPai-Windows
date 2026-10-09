# Inactive D3D11 HDR restoration source

This module has no application, MPV or CMake caller, no HDR admission or renderer
permission, and no NVIDIA invocation. Source compilation covers its C11 interface
(MSVC 14.44.35207, Windows SDK 10.0.26100.0, /Zs /W4 /WX) and the exact embedded
P010 input shader (offline FXC, main/cs_5_0, strictness and warnings as errors).
These checks do not prove linking, GPU execution, hardware support, quality,
performance or display. The other four shader stages have no new compilation
or runtime acceptance claim from this P010 source change.

The four restoration shaders in ../veyra-hdr-video-sr and the P010 input formulas
in ../veyra-hdr-p010-input derive from GPL-3.0-or-later Veyra source at commit
96a7c8de36bc195240161de6814739ad810722f1. The restoration source header SHA256 is
ab68fe368080ce9904dd1b084abf8219ea12995d2a551bbf11aa25b88c2f2cdf.
The input source header SHA256 is
b7eecdda6d733f4c9c198e21684fd2a429c70175d048edab17a53a0725f727c7.
The signed FP16 HDR base remains separate from the fixed 203-nit SDR proxy.

The separate bv_hdr11_begin_p010 method waits on a real same-device producer
fence, copies the entire padded decoder array slice into an owned single-slice
P010 texture, and creates two explicit plane views. Decoder-only source textures
need no shader-resource bind. Both views select owned slice zero; the shader's
local array index is zero. It extracts each 10-bit sample before chroma
interpolation, discarding the six padding bits rather than presuming they are
zero. PQ/BT.2020 NCL converts directly to signed linear BT.709 scRGB FP16, with
no SDR or RGB10 intermediate. All four actual crop values must be zero.

The input range enum (FULL=1, LIMITED=2) differs from the observer enum
(LIMITED=1, FULL=2); the future caller must explicitly map these values.
Unknown range or chroma location fails. Physical shape validation, copying and
queue submission do not establish CURRENT source color, decoder identity or
an epoch at GPU use. The current locked observation proves the epoch only
while that observation runs. Raw history cannot authorize this inactive method.
Source qualification and HDR admission therefore remain closed.

The producer must already have enqueued a real Signal after writing the retained
slice before this method queues Wait. The source lease must keep the actual
decoder/HW context, immutable slice and required producer owner alive. Fence
readiness does not grant shared keyed-mutex ownership; such resources are
rejected before submission. All resources are allocated before the first Wait
attempt. Once Wait is attempted, any failure retains the frame, its source and
its actual fence until final-consumer retirement. A missing producer Signal can
stall all later work on the shared immediate queue; one retained frame bounds
resources but does not isolate that queue. This module supplies no timeout or
substitute Signal.

The final-use fence must be signaled after every final consumer. Input readiness
or core/SR completion cannot retire decoder array-slice leases. The estimated
texture payload ceiling includes the whole pinned decoder array, owned padded
copy and held output textures, but excludes allocation/driver overhead and is
not measured free VRAM. The future owner must reserve actual DXGI headroom and
prove same-source completed SR, storage leases and final-consumer synchronization.

SwapDeviceContextState isolates saved pipeline state but not asynchronous
objects. The caller must run begin/finish outside active query scopes or accept
these Dispatch commands in its queries. Lock callbacks do not prove query
isolation. See the Microsoft API contract:
https://learn.microsoft.com/en-us/windows/win32/api/d3d11_1/nf-d3d11_1-id3d11devicecontext1-swapdevicecontextstate

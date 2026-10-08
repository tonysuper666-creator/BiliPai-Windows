# Color/prior-current stage, not a DLSS runtime

This internal OBJECT target records genuine D3D12 color preparation. It is not
registered with MPV, the shared NGX host, the application selector or a new ABI.
Only the existing bridge normalized full-range sRGB BT.709 RGBA8 qualifies.
The original MPV ingress already handles its admitted NV12/P010/BGRA/RGBA
range, matrix and transfer; this stage never reinterprets raw YUV as sRGB.

The retained current BGRA8 texture is copied into the previous BGRA8 texture
before writing the real new current frame. A graphics RTV conversion avoids
assuming BGRA typed-UAV support. Genuine EOTF yields RGBA16F linear BT.709 with
white 1.0 = 80 nits, without an invented exposure multiplier. The target-sized
R32_FLOAT texture is physically filled with .5, matching the fixed Veyra 2D
video convention. This is NOT geometry/depth estimation or declared zero depth.
Motion is not produced, and no measured flow or DLSS submission is claimed.

A caller must serialize this object under the existing one native source owner,
and keep a ColorPacket immutable through actual NVOF/DLSS/presentation use.
sealFinalUse only attaches the real caller's same-frame GPU completion receipt;
it does not prove that an arbitrary fence covers a consumer. The eventual
source-aware caller must supply its actual final use. This caller is not yet
implemented. No fresh Root, generic Job or late source getter grants authority.
The next packet cannot reuse descriptors/textures before actual completion.
An untracked Signal failure or unresolved final-use receipt retains resources.
Normal GPU timeout can retry retire after real completion; destructor misuse
retains the heap-owned state instead of releasing possibly in-flight textures.
The eventual one shared NGX owner must prohibit unbounded replacement owners.

The embedded bytecode is the exact author's FXC /Ges /WX /O3 output from the
included own shader sources, tied to shader-source-provenance.json. No NVIDIA
header, binary or runtime is distributed here. Official Optical Flow Windows
5.0 headers, checked registration/retirement, real motion, full bvd consumer,
new ABI/selection, authenticity, device/GPU/presentation validation remain pending.

Frame PTS is preserved as an exact rational receipt, not used as an automatic
temporal-validity authority. Sequence is strictly increasing but not required
to be consecutive. The real caller must reset history on seek/cut/discontinuity;
previousValid only reports a retained color pair, never certified video timing.

All borrowed packet textures are in COMMON at readyFence. The actual consumer
must wait that fence, restore each used texture to COMMON before its final-use
signal, and never read previousEncoded when previousValid is false. A successful
CPU object build does not execute or verify these GPU state transitions.

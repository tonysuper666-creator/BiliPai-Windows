# Private processed-frame presentation source candidate

The RGB10A2 SDR source derivative additionally admits a typed
R10G10B10A2_UNORM input only with BT.709 primaries, sRGB or BT.1886 transfer,
full range and RGB matrix explicitly known at that AVFrame import boundary.
An internal import flag and original range survive the existing default
software-scale/hardware-upload/map/download attribute copies; guesses and
vf_format retags cannot grant a previously false flag. RGB10 raw admission
changes participate in parameter equality and therefore real refqueue reinit.
The current resolved colors must still equal the admitted tuple. This flag is
not permanent bitstream provenance: mp_image_to_av_frame exports current
resolved colors and own opaque parameters with an internal export tag. An
exact-size/tag-checked owned round trip
can only preserve qualification when both old/raw and current tuples match;
it cannot promote a false flag. If an external AV filter discards opaque
parameters, its output creates a new import boundary with its own tags. The
default application RTX chain has no such external AV filter. Other input
formats retain their existing resolved
metadata admission, including MPV's normal guesses.

This adds no uploader or shader. The existing normalized RGB shader still
creates RGBA8 NVIDIA input, so 10-bit storage is quantized at that declared
boundary. Output metadata and processed token remain BGRA8 SDR or genuine
SDR-to-HDR RGB10. Limited-range RGB10A2, P012/P016, unknown new RGB10 tags,
non-BT.709 gamut and original HDR bypass. No new native/shader compilation,
GPU execution, effect or completed presentation result is claimed.

This is a future isolated MPV source variant. It is source only; no new MPV DLL has been built or loaded, no GPU work has run, and the current application does not consume this property. The existing core-v1 source manifest and running build remain unchanged.

The real native bridge emits a scalar token only after the checked core output has been copied into the actual MPV-owned D3D11 output and the final consumer signal succeeds. The token includes immutable session/configuration/stream/sequence, source timestamp, LUID, dimensions, effects and transport. mp_image references retain it with image ownership; writable images, general transformed attributes, fallback output and later user filters invalidate it. Exact pixel copies may retain it. The renderer accepts only a fresh successful single-frame draw with the same image ID, excluding repeat, still, interpolation, cached reuse, custom shaders and renderer error/overlay paths.

The default vo=gpu/gpu-api=d3d11 path prepares the matching token in the real swapchain. Exact Present S_OK plus GetLastPresentCount produces only queued acceptance. A bounded ring connects that measured ID to a later exact FrameStatistics.PresentCount. Unsupported/disjoint/unknown/wrapped/overwritten sequences fail closed; both the stock vsync query and the private query invalidate on DISJOINT. Resize, mode/window/monitor change, dropped/failed/original/cached frames and source identity changes invalidate feedback. The conservative statistics gate requires flip, sync=1, one monitor, a foreground visible unclipped video window, and a bounded cycle-checked GetTopWindow/GetWindow Z chain with no observed fullscreen application or window overlap above the root. Topmost roots, inaccessible or changing windows, child/sibling overlays, or uncertain cloak state are unsupported. Both pre-query and post-query environment checks must succeed; EnumWindows enumeration order is never used. It deliberately rejects conditions that cannot establish reliable visibility.

HDR additionally needs the same checked renderer target/framebuffer transfer and primaries, actual successful swapchain color configuration, expected DXGI format/colorspace, same adapter LUID and an actual HDR monitor descriptor. Every snapshot rereads the current renderer target, swapchain output format/colorspace, device status and actual monitor descriptor. A Windows HDR toggle on the same HMONITOR invalidates old HDR evidence and advances epoch/serial. All invalidation paths clear whole queued/displayed values; failed or unmatched statistics clears displayed values and advances serial. A true transport/color-space observation is not an image quality measurement.

The registered read-only MPV node property is bilipai-rtx-presentation. It is fetched through the existing property and synchronous VO-control dispatch, returning separate queued and displayed records plus serial/epoch/status. There is no assumed automatic property-change notification. A later typed player-actor consumer must explicitly fetch it, validate the trusted new module identity, current session/configuration/stream, monotonic serial/epoch/frame identity, and clear state on unproven or stale feedback. The current JVM Active/HDR flags remain false.

Microsoft documents successful present IDs matched to later statistics in a retained queue, not inferred frame totals:
https://learn.microsoft.com/en-us/windows/win32/direct3ddxgi/dxgi-flip-model
Windowed bitblt lacks supported statistics; mode changes are disjoint, and multiple monitors, other fullscreen applications and delayed hardware flip queues limit reliability:
https://learn.microsoft.com/en-us/windows/win32/api/dxgi/nf-dxgi-idxgiswapchain-getframestatistics
The window gate follows documented actual Z order and rejects uncertain traversal or visibility:
https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-gettopwindow
https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-getwindow
EnumWindows is not used for ordering, including its Windows 8 desktop-app-only limitation:
https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-enumwindows
Current cloak state is measured with DwmGetWindowAttribute; Dwmapi is already a dependency of the fixed MPV Win32 target:
https://learn.microsoft.com/en-us/windows/win32/api/dwmapi/nf-dwmapi-dwmgetwindowattribute
No blocking DwmFlush, guessed timing, time-pos, GPU utilization or SUBMITTED log is used as display proof. FrameStatistics matching is a measured swapchain display-feedback record, not proof of visual quality or all physical pixels scanned out. Runtime acceptance must separately validate the supported environment and shader/color path before any application Active policy is enabled.

Build source material:
- Five exact private sources retain the public v1 C ABI header unchanged.
- Thirteen complete upstream file patches plus the existing three filter/Meson registrations attach the token to actual image, chain, renderer, swapchain, VO and property paths.
- The original NVIDIA D3D11VPP patch is retained with its actual forward/inverse hashes.
- apply-rtx-presentation-source.py applies only this fixed source graph after full hash/count-one/whole-inverse checks and one process-scoped absolute safe.directory Git read. It does not compile, test, download or run native code. The packet's build-staging directory supplies all its exact input texts. The helper has not been executed.

Before any build or selection, the future producer must explicitly support the schema-2 manifest, schema-3 source receipt, all five copies and thirteen upstream patches. It must retain matching registration/recipe/container/source-bundle/actual DLL provenance. Descriptor/fetch/staging, paired assembler, profile/verifier and JVM/Gradle verifier hashes must be advanced together; old 9c0 profiles must never certify this new DLL. See cascade-plan.json. No existing binary or source-bundle identity may be reused. This packet contains no NVIDIA SDK/runtime bytes.

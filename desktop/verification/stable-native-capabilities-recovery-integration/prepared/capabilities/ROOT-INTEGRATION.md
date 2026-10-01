This is a prospective source packet against installed immutable actual73/101.

Install only five manual Kotlin files, preserving their package paths under
desktop/src/main/kotlin. Apply the five literal hunks in mpv-exact-hunks.json to
the single existing MpvPlayer.kt family after LF and source hash preflight.
The complete after family is verification input, not a wholesale replacement
of independently modified production code. There are no library additions,
new upstream source identities or changes to any Android original source.

The same native Session queries decoder-list once after initialize, publishes
the typed result before ready=true, and clears it on detach/close/fatal exit.
Root must await the actual non-null result outside admission gates and mount
its same native surface before original codec selection. A null result is an
uninitialized session, never a default capability. A query failure is explicit
Unavailable, mapped to upstream MediaUtils' failure -> false selection policy
and reported to the existing Root diagnostic sink.

HEVC and AV1 include software decoder support, as in original MediaUtils.
EAC3/JOC decode comes from this core's FFmpeg decoder, not a claim of HDMI or
object-based Atmos output. Dolby Vision profile/display negotiation remains
explicitly unavailable in the current vo=gpu/D3D11 output implementation.
HDR selection requires both actual HEVC decode and this Root HWND's monitor
HDR-specific INFO_2 support. WCG-only advanced color is not treated as HDR.
An unsupported INFO_2 API/driver/remote session is recorded as unavailable.

The Windows port requires the existing Root Window, fullscreen getter/setter,
actual same PiP controller, same Home client policy, shared ProfileChrome DWM
authority, same native brightness-clear function and actual deferred-exit
callback. It must be closed by Root's external retained-owner drainage. It
does not own or close the global player, PiP controller or another route.
Fullscreen registrations nest; stale entry/source exit cannot restore a new
source's placement. Chrome restoration removes this port's own shared DWM
registration and resolves current Root theme/remaining requests. Keep-awake
uses an owned HANDLE and both DisplayRequired/SystemRequired, released even
after the native subject retires. Physical rotation, Android status bars,
Android sourceRectHint and seamless-resize capabilities are explicit mappings
or unavailable, never fabricated Android APIs.

capabilities-01 and capabilities-02 narrow compile pass. native-01 failed the
fixture preflight before starting native: its prefix whitelist omitted the
MpvCallException class declared in the same source family. native-02 admits
that named class and passes 20 checks against the actual DLL and existing
Session. Decoder/player/policy tested class bytes are equal in the current
capabilities-02 compile. Snapshot/DLL/prospective class bytes remain unchanged.
Injected HDR monitor cases verify only selection policy; no physical monitor,
Window lease, complete Holder, account/CDN or Main startup acceptance is claimed.

Primary API references:
- https://mpv.io/manual/master/#decoder-list
- https://github.com/mpv-player/mpv/blob/master/include/mpv/client.h
- https://github.com/microsoft/win32metadata/blob/main/generation/WinSDK/RecompiledIdlHeaders/um/wingdi.h
- https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-powercreaterequest
- https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-powersetrequest
- https://learn.microsoft.com/en-us/windows/win32/api/minwinbase/ns-minwinbase-reason_context

# Windows automatic hardware policy

This layer owns read-only inventory, conservative policy, machine-local learning and the
settings controls. It extends `DesktopNvidiaSystemGpuProbe` / `DesktopWindowsDxgiAdapters`;
there is one DXGI enumeration implementation. It never changes Windows, GPU or audio settings.

`DesktopDiagnostics` retains one `DesktopHardwareOptimizer` for its lifetime. Startup and
30-second topology checks run outside the UI thread. A changed GPU/driver/display/DPI/HDR/audio
fingerprint invalidates prior measurements. Cache corruption falls back to AVC 720p and reduced
glass. A ten-second offscreen decoder budget uses only the bundled synthetic clips, no network,
accounts or user media. Native driver calls themselves cannot be forcibly interrupted; a hung
native call occupies the sole daemon worker while callers continue to see the safe policy.

## Playback integration (TON-1006)

Obtain the retained optimizer from `DesktopDiagnostics.hardwareOptimizer`. Consume its
`state: StateFlow<HardwareOptimizationState>`; do not instantiate a page-owned optimizer.

- Call `setPlaybackActive(true)` before starting real playback and clear it on session retirement.
  This defers/cancels the self-test at its next event boundary. It is not a playback pause command.
- Call `reportWindow(nativeHwnd)` on attachment/moves and `reportRenderer(observedLuid)` once
  the renderer identifies its actual device. These publish the retained policy for that window/card.
- Supply the real renderer identity when deciding a policy. The monitor hosting a window can be
  on Intel while D3D renders on NVIDIA; inventory alone cannot prove the renderer.
- Select codec, dimensions, bit depth and fps within `policy.validatedDecodes`. The current sample
  set validates **8-bit 4:2:0 up to 60 fps**. A HEVC/AV1 10-bit profile appearing in inventory does
  not mean a 10-bit stream passed the sample test. `maxHeight` is the first preferred codec's limit;
  alternate codecs retain their own measured limits. Never soft-decode 4K AV1 as an automatic fallback.
- `enhancementCandidate` is a trial candidate, never an active indicator. Request it using the
  existing native gates. Only the real playback owner's presentation proof and AdaptiveGuard
  may call `reportPlayback(fingerprint, EnhancementMeasurement(...))`.
- A `proved` measurement means **displayed**, not submitted/initialized, frames. For normal
  playback report non-overlapping ten-second windows. Two consecutive windows at >=6% drops
  mark that rung failed; hard native failures set `failed=true` immediately. The policy selects a
  previously proved lower rung or disables enhancement. The failure survives restarts and restore-auto.
- Unknown/mismatched fingerprints and unknown renderer identities are rejected. Keep all existing
  LUID/physical/presentation checks; this API does not create presentation proof or set an active flag.
- `exclusiveAllowed` concerns the **current default endpoint, 48kHz stereo PCM16** capability query.
  It does not open an audio session or promise that exclusive initialization will succeed. Requery
  the actual negotiated format, fall back to shared, and preserve the player's audio ownership rules.

The player flow is intentionally outside this PR's write scope. Without TON-1006's calls the
policy remains conservative and no SR/HDR success is announced.

## UI integration (TON-1001)

Use `policy.glass` to reduce blur on integrated/low-memory/unknown machines. Display refresh
rates preserve their rational value (e.g. 165/1), separately from the 60fps sample's decode limit.
Settings already display optimizer status, support manual preference overrides and restore-auto,
and export through the existing explicit diagnostic chooser. Manual overrides never override a
hardware failure or introduce an unsupported codec.

## Evidence and privacy

`hardware-selftest/CAPABILITIES.md` contains the sourced hardware matrix. The settings export
reuses `DesktopDiagnostics.exportTo`: detection, policy, measurements and bounded recent logs are
in one user-selected local text report. No upload is scheduled. Raw endpoint IDs are hashed;
monitor names/PnP paths, account fields and user paths are excluded or sanitized.

`DesktopHardwareVerification` is a foreground, account-free harness using these exact product
classes. For the Windows host run its jar only through the authorized BiliPai-MacTest launcher,
with `--library=<existing reviewed libmpv-2.dll> --output=<task report> --pause=<tools/PAUSE>`.
The launcher owns game/PAUSE gates. A disabled Sandbox is not silently enabled by this module.

Tests are in `desktop/src/test/kotlin/com/bilipai/desktop/hardware/`. Run with the normal desktop
Gradle test task; include the existing NVIDIA and diagnostic suites when verifying integration.

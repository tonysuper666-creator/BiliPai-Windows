# Playback settings draft / remembered audio — narrow frozen patch

Copy only the two entries in `owned-files.json` from `prepared/`, checking the PlayerSettings.kt baseline raw SHA first. The equivalent minimal change is `patches/PlayerSettings.kt.patch`; there are no Root/Shell hooks, new providers, dependencies, preference fields or upstream source inventory changes.

The existing PlaybackSettingsDialog now captures `latestPreferences` with rememberUpdatedState. Its save button calls the new internal `resolvePlaybackSettingsDraftSave(draft, latest)`. The helper changes only `lastSelectedAudioQuality` to the authoritative latest value, retains every user's editable draft field, applies the existing remembered-speed/default-speed expression, and performs the same existing `normalized()` call.

This fixes the proven sequence: a user selects audio quality, opens settings while the source request remains pending, the actual Controller completes and remembers the selected audio quality, then settings Save used to replace it with the stale invisible draft field. Serial disk submission alone cannot correct that final stale snapshot.

## Actual verification

`compile-run.py` verifies the immutable `settings-tree-product-snapshot` Kotlin/Java/resources jars and every dependency before and after use. It independently compiles the complete actual PlayerSettings.kt, the new JUnit class and the task harness using the cached Kotlin/Compose compiler. It does not run shared Gradle or read live build/classes.

Two executable focused JUnit methods passed:

1. The actual snapshot DesktopPlaybackController opens a source, starts selectAudioQuality(HI_RES), and its injected transport is genuinely suspended on CompletableDeferred. The draft is captured while pending, with deliberate default-audio, codec, hardware, speed and playback-mode edits. The gate then completes; the actual Controller calls onRememberAudioQuality and retains the same native source owner. The original save expression is separately persisted through actual PlayerPreferencesStore and reproduces the old-memory bug. The patched save uses the actual serial DesktopPlayerPreferencesWriter and actual store; after flush a fresh store reader verifies new remembered audio and every deliberate edit.
2. The helper preserves the original remember-speed contract and existing normalization, without mutating either caller snapshot or confusing editable defaultAudioQuality with remembered audio quality.

`proof/result.json`, `compile-evidence.json` and `runtime.log` record normal exit 0. The first method uses an unattached actual MpvPlayer source actor: no Canvas addNotify, HWND, libmpv session or decoded media. This verifies actual Controller request/callback/ownership and disk persistence, not native audio decoding or an actual displayed Dialog. The complete Dialog was compiled; no native window, user settings, account transport or shared Gradle was used. Child LOCALAPPDATA points only to task-owned temporary data; the original environment remains unchanged.

Root can run the new `com.bilipai.desktop.ui.PlaybackSettingsDraftMemoryTest` in its normal focused test task after applying. Both methods explicitly return Unit and carry the existing kotlin.test.Test annotation.

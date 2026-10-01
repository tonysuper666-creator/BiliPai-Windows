# Same-entry original owner Root effects

Install only the TWO new files in `prepared/manual/com/bilipai/desktop/ui` into the matching desktop manual package. No original model/interface/renderer, client, Store, native DLL, actor, or existing family is replaced. Parent retains full original VM/Core/factory ownership.

```kotlin
val network = DesktopOriginalVideoOwnerNetworkBinding(
    platform = sameAppWindowsPreferencesPlatform,
    stillOwned = gate::owns,
)
val effects = DesktopOriginalVideoOwnerDiagnosticsBinding(
    globalStore = sameGlobalPluginStore,
    diagnostics = actualRootDiagnosticInitializationResult,
    stillOwned = gate::owns,
    admission = gate::commit,
)
// The required original Environment receives network, effects as analytics,
// and the SAME effects instance as crash.
```

`gate::owns` must include the actual account epoch, app/entry lifetime and active entry Job; `gate::commit` is the same short Store→entry admission. The complete original VM already checks load/request identity before these effects. Do not create a default ownership flag. A temporarily hidden retained page does not retire the entry.

Network decisions occur OUTSIDE admission. The existing `DesktopHomeWindowsPreferencesPlatform` performs the real, fresh, same-DLL WinRT preferred profile query. Wi-Fi is SDK `IF_TYPE_IEEE80211=71`; cellular remains the existing actual WWAN/243/244 observation. No profile is original false; failed queries propagate the existing typed unavailable exception. Metering/InternetAccess/Ethernet are not cellular/Wi-Fi aliases. Default quality remains the exact existing original owner-interface mapping of the SAME settings context.

Analytics/crash prepare sanitized text before the gate. Inside the short gate they read only the current in-memory global settings snapshot and enqueue `DesktopDiagnostics.record` on the existing serial writer. They never flush/wait/join/create a fatal crash snapshot or perform file/native/network IO there. An accepted enqueue is in-flight; later retirement cannot revoke a previously accepted log. Restore/shutdown must retire the entry first and drain the EXISTING diagnostic writer before freezing its Store, using the already installed Root lifecycle.

The exact keys/defaults are original `analytics_enabled=true` and `crash_tracking_enabled=true`. No setter or second preference namespace is generated. Existing `DesktopDiagnostics` additionally requires enhanced logging for I entries and allows W/E basic local diagnostics. `video_play` omits original sensitive video ID/title/author; `quality_change` retains only original `from_quality`/`to_quality`. Non-fatal video errors omit sensitive BVID, sanitize message/type with the installed Windows+original sanitizer, retain the original 300-character message limit, 60-second duplicate window and memory headroom policy. They do not call `persistLocalCrash`, which is for the existing actual uncaught handler.

`diagnostics` must be the actual Root initialization result. A null failed initialization is explicitly reported by `localDiagnosticConsumerAvailable=false`; it is not an invented successful backend. `firebaseTransportAvailable=false` is a declared Windows capability boundary. This packet does not claim Firebase, Crashlytics custom keys/user/session/last-event state, automatic uploading, or full Root runtime proof.

Registry recipe: feature-union `stable-video-root-effects` into the FOUR existing original source identities listed in `source-audit.json`; preserve each existing mode, SHA and other features. Do not register them as whole-file/direct reuse. Register these TWO new manual source files in the normal desktop source inventory. No generation task/dependency/provenance/native/Gradle change is needed.

`compile/01/result.json` proves these two NEW classes compile against actual71 strict101 with zero product overlap and all dependency byte pins unchanged before/after. This is source-ready only. No native query, diagnostic runtime, account, UI, HTTP or actual whole-Root assembly was executed in this packet.

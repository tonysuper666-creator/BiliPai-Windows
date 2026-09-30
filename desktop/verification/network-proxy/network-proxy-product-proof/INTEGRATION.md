# Actual current product proxy graph and original dialog proof

Root supplied the successful current-product compile snapshot, manifest SHA **c2c892064267e6a1e8c5425a3c4095a2070faacfe5debb1c07f43fae9957016d**, Windows base commit `0794047e98141c8b1f4ed591f323fc19184f6ef5`. The product's first three classpath entries are the immutable actual Kotlin/Java/resource jars from that manifest. Kotlin jar SHA **bc5ebafbdb5d14cfe07d6b5eb8074395e30d806e3e188a7e92363a68b398fa95**; all 234 ordered jar digests were verified before and after execution.

Only two executable fixture sources were compiled. No Repository source override, old helper extraction, replacement renderer, modified original dialog, parallel CookieJar or substitute player was compiled. `ProductProxyFixture.kt` adapts the earlier frozen fixture only as a test: current product classes replace its old source override, sandbox paths and evidence labels are accurate, and it adds current validation/Buvid and actual media-consumer tests. `fixture-origin.json` records that fixture's original byte identity. Its socket server and controlled dispatcher are test facilities, not application networking code.

No main/frozen source was written, shared Gradle started, Main/EXE launched, native window created, real user store read or account requested. Each child inherits an explicitly isolated environment for LOCALAPPDATA/APPDATA/USERPROFILE/HOME/TEMP/TMP and matching `user.home`/`java.io.tmpdir`. SessionStore uses the explicit owned path and `persistent=false`; no session file or real account exists. Socket traffic is only between task-owned loopback origin/proxy endpoints. Production Retrofit login/feed operations are not called: this proves the actual transport objects' route and cookie contracts using synthetic local requests.

## Actual transport/store cases

`proof/transport/result.json`: **15 executable cases passed**, rather than 15 added JUnit tests.

- Exact original host/port/default/sanitizer/summary semantics remain unchanged.
- Actual Repository and Community transport, actual login candidate/captcha, validation Passport and Buvid clients route local requests through the configured proxy.
- The candidate jar is separate; a task-only synthetic cookie added for the Bili Passport domain stays in that jar and is absent from the application jar. This is in-memory cookie scoping, not a Bili request or login.
- **Current validationPassportApi and captcha use NO_COOKIES; current buvidApi shares the application jar.** This corrects the earlier review document's Buvid statement; frozen files were left unchanged. Actual reflection obtains the real Retrofit client's callFactory and verifies the currently compiled graph.
- A warm direct connection remains at the origin when only the original selector's live setting changes. Actual binding persistence and idle-route eviction then route the next request through the task proxy. Disable returns the next request to direct while preserving the original endpoint.
- Actual Repository direct playback client bypasses the enabled proxy. Actual `DesktopDownloadManager(repository)` uses that same direct client; actual initialized `DesktopPluginRepositoryBinding.playbackClient` is also that same client. Local requests through both consumer objects reach only the origin. No download task/mux/native playback is performed.
- Actual HTTPS request emits CONNECT `fixture.invalid:443` to the loopback proxy without Proxy-Authorization; the fixture deliberately rejects the tunnel. No remote DNS destination, real TLS handshake or external proxy service is tested.
- Real suspended call cancellation closes its own proxy socket while an independent request succeeds; it does not globally cancel the client.
- Original invalid/disabled custom config uses the injected system list, then NO_PROXY for an empty list, without changing the global selector.
- A real obstructed disk replacement does not publish changed original settings. Refused loopback proxy increments only the redacted failure counter.
- A fresh JVM reads the original namespace/keys from actual persisted disk. The actual shared store restore fence rejects a late old binding before disk/live state changes.

Active requests are not force-rerouted, and independently built updater/JS/image/lyrics clients are not brought into this setting. There is no additional authenticated/SOCKS proxy schema or invented proxy credentials. The actual constructor transport checks do not constitute a complete download, mux, playback or packaged first-request acceptance.

## Actual original UI/dialog cases

`proof/ui/result.json`: MATERIAL3 and MIUIX each passed **8 real Press/Release interactions and 4 original editor SetText operations**, total **16 pointers / 8 inputs**. The UI uses the product `DesktopNetworkProxySettings` wrapper. Its real address row enters the compiled private original NetworkProxyEditDialog, which enters actual AppAlertDialog/WindowDialog. Scene semantics gain the real dialog layer. Neither dialog nor its body is replaced with a fixture panel.

Each style enables/disables the original live switch, opens the original address dialog, enters the original host/port fields, enables a draft with port zero and clicks Save. Original validation keeps the dialog open without changing settings. A valid sanitized draft then saves and closes; the actual store and disk contain enabled=true, host `127.0.0.1`, port `8123`. Reopening, editing the host and clicking Cancel closes without changing that saved value. Eight PNGs and two exact disk JSON records capture these actual controls/dialogs; representative M3/Miuix original/invalid/saved outputs were visually inspected.

ImageComposeScene supports these actual Compose dialog layers headlessly, and `Window.getWindows()` remains empty. This is stronger than dialog-body-only layout testing, but **not native HWND/address-dialog acceptance**. Keyboard entry, Escape/outside dismissal, OS focus, resize/DPI, real proxy endpoint and packaged startup remain outside this fixture. No crash/log/analytics/consent control is emitted or claimed.

## Reproduction and frozen identities

```powershell
python desktop/.local/network-proxy-product-proof/verify-inputs.py
python desktop/.local/network-proxy-product-proof/compile-run.py
```

The first command is read-only input/target verification: both frozen cohorts' exact bytes, all jars, original sources/resources, platform inputs and ten installed target payload hashes. It does not execute product tests. The second requires the exact Root snapshot pin, verifies all current source identities from its manifest, compiles only the fixtures into a fresh owned directory and runs the two isolated child JVMs. Active fixture classes, complete args/logs, child sandbox paths, immutable dependency identities and source identities are recorded in compile-evidence.json. No mutable build/classes directory is used.

Source manifest counts remain Root's actual **425/201**, with no new source/resource/dependency/task or main patch from this QA lane. Frozen handoff records this bounded current-product proof; future product mutations need a new Root snapshot and review, rather than relabeling these jars as a later EXE.

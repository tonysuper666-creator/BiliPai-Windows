# About icon investigation, 2026-11-02

Baseline: `806574646d2bd0090e88dc42d79f1994f273548b`. No production code or existing test assertion was changed. The generated desktop About implementation calls `desktopOriginalIconPreviewUri(appIconRes, isSystemInDarkTheme())`; this is a local classpath URI, not the integer sent by upstream Android. `DesktopOriginalIconSettingsScreen` resolves icon choices to names. The probe executes the application's actual `DesktopApplicationImageLoader` and the generated desktop About model conversion for 8 options × 3 appearances × 2 system themes: all 48 produced Coil `SuccessResult`, 96 × 96. The missing-file URI negative control produced `ErrorResult`.

The same owner also registers `DesktopUpstreamResourceFetcher.Factory`, but a distinct request using `R.mipmap.ic_launcher_bilipai_round` (2131689485) returned `ErrorResult: Unable to create a fetcher that supports: 2131689485`. This is an integer model check, not the generated desktop About request. The underlying registration/mapping cause was not determined. Do not conclude adaptive decoding succeeded and do not replace the safety assertion solely from URI success.

Reproduce the probe on macOS with JDK 21, cached Gradle dependencies and the repository at the baseline revision:

```sh
desktop/verification/ton-1085-settings-about-icon/run-coil-probe.sh
```

The script copies the probe temporarily to the test source tree, obtains the shared heavy-build lock, checks for bypassing builds, runs `./gradlew -p desktop test --tests com.bilipai.desktop.ui.DesktopAboutIconCoilTest --offline --no-daemon -x upstreamAppCoreSourceTests -x sponsorPresentationTests --rerun`, and removes only its temporary source and lock. Observed exit 1; raw test XML and Gradle log are in `evidence/`. The excluded tasks are unrelated suites; this command is scoped, not a full gate.

SettingsRoot baseline: under the same shared lock, with JDK 21 on PATH, run `python3 desktop/verification/ton-1085-settings-about-icon/baseline.py <new-empty-output-directory>`. The harness exits 0 after verifying JUnit's actual exit 1 and 25 cases, one named failure and zero skipped. Raw XML and both logs are in `evidence/`. There is no corrected head or new-green result.

An earlier unscoped `:test` attempt also ran `upstreamAppCoreSourceTests` (441 cases, 64 failures) and `sponsorPresentationTests` (one failure); it exited 1. These suites, Windows, GUI and the full gate are not validated by this investigation. Resolve the integer model failure or obtain an explicit contract ruling before changing the existing guard. Root still owns registration and PR integration.

# Desktop integer resource Coil evidence

Baseline: `16dfad2db5e83fdf5d5c7868e41eba3942577909` (TON-1085 PR #61). Its existing `desktop/verification/ton-1085-settings-about-icon/evidence/coil-probe.xml` records 48 successful URI decodes, a failed missing-URI negative, and an integer request failing to select a fetcher. The real application owner uses the same extension as that probe. Coil's final EngineInterceptor is bound to its original loader: copying it into a rebuilt registry causes it to handle requests against the old registry before the new engine can run. The fix omits that old engine and lets Coil construct a new one, preserving the preceding components.

Run with JDK 21, holding the shared `~/dev/.coach-heavy.lock`:

```sh
./gradlew -p desktop :test --tests com.bilipai.desktop.ui.DesktopIntegerResourceCoilTest --offline --no-daemon -x upstreamAppCoreSourceTests -x sponsorPresentationTests --rerun
```

Exit 0; `scoped-test.xml`: 1 test, 0 failures, 0 errors, 0 skipped. The actual application owner decoded raster 64×32, vector 96×96 and adaptive 96×96; the selected models and registry mapper output were `java.lang.Integer`. All 48 about-page URI combinations decoded. Invalid integer returned `IllegalArgumentException`; missing file URI returned `ErrorResult`. `scoped-test.log.gz` records the command's output.

The initial command used unqualified `test` with the same other arguments. Its root-project test passed (`first-test.xml`), but the overall exit was 1 because `:upstream-core:test` was also selected and had no matching test; see `first-test.log.gz`. The successful run explicitly selected `:test` instead. These are isolated tests, with the two listed suites excluded; full gates, Windows GUI and packaging were not verified.

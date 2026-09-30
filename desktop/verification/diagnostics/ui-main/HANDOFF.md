# Corrected immutable diagnostics UI proof

**24 actual flows PASS** against snapshot `5065fe8052e80c0c73f6342a4afc03fd62814fb0a144b6de46feeb5ddce1d3ae`: eight complete original-controls/consent/viewer flows, eight independent initial IO-denial flows, four large-font viewer/export/clear/dismiss flows, and four large-font IO-denial flows. There are **96 real Press/Release pairs and 72 actual PNGs**. These are isolated UI flows, not 24 new JUnit methods.

| Renderer | Theme | Height | Complete normal flow | Actual pointer pairs |
|---|---|---:|---|---:|
| MATERIAL3 | LIGHT | 900 | PASS | 9 |
| MATERIAL3 | LIGHT | 720 | PASS | 9 |
| MATERIAL3 | DARK | 900 | PASS | 9 |
| MATERIAL3 | DARK | 720 | PASS | 9 |
| MIUIX | LIGHT | 900 | PASS | 9 |
| MIUIX | LIGHT | 720 | PASS | 9 |
| MIUIX | DARK | 900 | PASS | 9 |
| MIUIX | DARK | 720 | PASS | 9 |

The additional fontScale=1.5 groups use the real Compose LocalDensity at finite 960×720 for both styles/themes. Their original renderer and true window-configuration consumer are unchanged. `after-export-action-bounds.json` and `after-clear-action-bounds.json` record the actual OnClick-ancestor rectangles for all three actions, assert positive width/height, and assert the entire action rectangle remains within the viewport. The four large-font viewer cases additionally record initial bounds. Long text remains in the real scrolling log box; complete bytes are verified in actual exported files. These checks do not claim every long log line is simultaneously visible, nor that the original 40dp dialog action target became 48dp.

All eight normal groups use the real main initialization factory / installed upstream Logger bridges / process lifecycle, and persist the original global `settings/enhanced_diagnostic_logging_enabled` key in actual DesktopPluginStore. Default false, original consent cancellation without setting changes, original confirmation, successful atomic disk state, detailed recording, disable-and-delete, real viewer read, fixture-selected real file export, clear and actual close are tested. A second real store facade observes the same key. The lifecycle observes a **synthetic finite PlayerState flow**, verifies that title/subtitle text is excluded, and is drained/retired. No fake Store, actor, renderer or production class is compiled.

All 12 IO-failure groups lock only their task-owned actual basic.log with Win32 `CreateFileW` share=0. A separate JVM Files.readAllBytes call proves a real IOException before the original viewer starts. The actor remains active. The viewer displays safe messages, removes loading text, neither leaks file paths nor escapes coroutine errors, and closes through a real topmost-dialog pointer. After releasing the handle, original file bytes remain readable. No retired-actor replacement is used.

The only compiler input is DiagnosticProductUiFixture.kt under its own `.proof` package. The three immutable product JARs are SHA-bound; 231 historical external dependency entries (226 distinct JARs, five duplicate entries) are verified before/after execution. Fourteen loaded production class resources are SHA-verified and their CodeSource origins must be the actual new main-kotlin.jar; nine are the requested core diagnostics/control consumers. Window metrics use the actual LocalWindowInfo / LocalDensity implementation from main. The headless ImageComposeScene is a fixture host, not a substituted production Renderer.

Accepted run: `a86126c1`. Export chooser callbacks provide paths exclusively inside each fixture case; **native OS chooser is not tested**. No HWND, mpv, account data, HTTP, full Main or PluginRuntime is started. No shared Gradle, main source, or frozen 18-method actor test was changed. Root's previous 31 shared methods remain previous test scope and are not presented as re-executed for the Viewer change.

The old snapshot's failed matrix remains separately frozen in diagnostics-product-ui-proof (`4fda0a50144fe9848a4ab75135044994938eab92e6b1b1ee577c48d91c7f573c`); all 178 artifact hashes are rechecked here. Its Miuix failure has not been overwritten. The new result proves the same producer/viewer interaction on Root's narrow total-text-height / weighted-scroll change, with original WindowDialog and action bodies.

One earlier attempt in this new directory stopped before any UI checks: the longer fixture user.home made Skiko's native ICU extraction path exceed the Win32 loader limit. Its hs_err/runtime log is preserved in runs/31e038fc and is an infrastructure failure, not a Viewer verdict. The accepted JVM uses a newly-created short task-owned bp-dg-* environment; no user's existing settings or cache is read. Native extraction files are separately hashed. Only the accepted run/classes and explicitly listed historical crash evidence form this frozen handoff.

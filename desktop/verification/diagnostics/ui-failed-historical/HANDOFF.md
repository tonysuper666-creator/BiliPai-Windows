# Immutable diagnostics UI proof — failed product matrix preserved

The original main snapshot `5e8fc360372557bf004b55a1ded9fe4be1a3b6bae33090cb037d8fe600006fda` is the only production code used. The fixture compiles one new package only; 0 production overrides and 0 Store/Renderer replacements. Thirteen actual class-resource digests and CodeSource origins include the requested nine core consumers. The historical 231 external dependency entries contain 226 distinct JARs and five duplicate entries; every entry is verified before and after execution.

| Renderer | Theme | Height | Complete normal flow | Actual pointer pairs |
|---|---|---:|---|---:|
| MATERIAL3 | LIGHT | 900 | PASS | 9 |
| MATERIAL3 | LIGHT | 720 | PASS | 9 |
| MATERIAL3 | DARK | 900 | PASS | 9 |
| MATERIAL3 | DARK | 720 | PASS | 9 |
| MIUIX | LIGHT | 900 | FAIL: viewer-clear-after-export | 7 |
| MIUIX | LIGHT | 720 | FAIL: viewer-export | 6 |
| MIUIX | DARK | 900 | FAIL: viewer-clear-after-export | 7 |
| MIUIX | DARK | 720 | FAIL: viewer-export | 6 |

All eight independent initial real IO failure flows PASS: Win32 `CreateFileW` share=0 denies reading the fixture's actual basic.log; a direct JVM read first proves IOException. The real viewer shows the safe message, removes its loading text, does not expose the path or escape a coroutine exception, and dismisses through a real topmost-dialog pointer. After unlocking, the original task file is unchanged/readable. This is not a retired-actor substitute.

There are **70 actual Press/Release pairs and 50 PNGs** in the accepted run `43da3bd6`. All eight styles/themes/heights pass original consent cancel/confirm, same global settings key disk persistence and enhanced detail removal. Four Material3 flows also pass actual viewer/export/clear/dismiss. For Miuix 900, the actual exported file exists but its success result consumes the action row. For Miuix 720, the large initial log already consumes the action row, so the fixture correctly refuses to click a same-text underlay setting. The whole product matrix is **failed**, not a success gate. `failed-flow-actual.png` and `failed-flow-semantics.json` bind each defect to actual pixels and zero-size action bounds. The two 900 flows additionally preserve `after-export-result.png` / `after-export-semantics.json` and their real exported files.

Source cause: exact pinned Miuix `DialogContentLayout.kt:351` limits large-screen content to 2/3 of actual window height; original `AdaptiveDialogComponents.kt` measures its text box before its action row. DesktopLocalDiagnosticViewer fixes log height at 420dp and appends result outside that scrolling box. A narrow text-slot height cap plus weighted scroll, leaving all renderer/body/action classes unchanged, is suitable for the next immutable snapshot. The real reusable window metric is `DesktopWindowConfiguration.current.screenHeightDp`, sourced from LocalWindowInfo container pixels / LocalDensity.

Each normal case opens the actual main factory, installs both upstream logging bridges, uses the actual lifecycle to observe a **synthetic finite PlayerState flow** and drains it before retirement. This verifies neither mpv state production nor the entire Main/PluginRuntime. The fixture creates no HWND, starts no native player, reads no user data and sends no HTTP/account request. The export chooser is an explicitly fixture-supplied local Path; no native OS chooser acceptance is claimed. Fresh task-only env roots and stores retain the evidence files. Main and the frozen 18-method test file are untouched.

Earlier incomplete attempts in this directory are historical only; only the accepted run, accepted classes and pinned files in artifact-manifest.json form this frozen result. No old result will be replaced by the corrected snapshot's outcome.

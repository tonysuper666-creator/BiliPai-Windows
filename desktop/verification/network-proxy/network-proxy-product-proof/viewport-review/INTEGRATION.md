# Final original dialog viewport and animation review

This review preserves the initial 64-file frozen cohort (SHA `4fcbafc0c0ec4600586fcd8ea073751fad3ed0eb9f42d1a39327a1b5d24012b6`) unchanged. It uses the same actual product snapshot and all original renderer classes; only the new fixture viewport and frame settling changed. No main/extractor/renderer/dialog source or previous evidence was edited.

The initial MIUIX 720×720 dialog image was captured after eight frames, 240ms of logical animation time. The original cached Miuix source `DialogContentLayout.kt` shows an Animatable spring, small-screen translation `(1f - progress) * window height`, and 12dp outside margin. The initial animation was not fully settled, so part of the bottom rounded edge remained below the canvas. This is recorded as an early fixture capture, not final visual acceptance or a product clipping fix.

The new fixture advances 80 actual scene frames, 2400ms of logical animation time, at each step. It then repeats the complete original switch/address dialog flow in **MATERIAL3 and MIUIX at both 720×720 and 720×900**. All four flows pass original editor validation, invalid Save guard, normalized actual disk save, and Cancel preserving saved state. These are **32 real pointer interactions / 16 original editor operations**, with 16 PNGs and four actual disk JSON snapshots. The final dialog screenshots show full lower rounded corners. Neither the body nor the original AppAlertDialog/WindowDialog layer was replaced by a panel.

`pixel-evidence.json` analyzes actual PNG pixels without editing them: initial MIUIX center dialog background reaches row719 in the 720px canvas; settled 720px view ends at row707, and settled 900px view ends at row887. Both settled views retain the exact original 12px bottom margin, with black bottom center pixels. Complete MIUIX 720/900 original, invalid 900 and M3 900 dialog images were visually inspected. This isolates the initial effect to animation settling rather than viewport insufficiency or a main renderer change.

The first new run used an unnecessarily deeper sandbox path and failed during Skia Unicode initialization before the UI opened: native icudtl.dat could not load. Its JVM crash log is retained under diagnostics; it is not a product proxy/UI failure or a passing UI run. A fresh owned sandbox beside the initial proof's child directories avoids that Windows native data-loader path limit. ErrorFile is explicitly routed to this lane. The successful run uses the same fixture source and actual renderer, no native window, no HTTP, and no real account/user settings. The sandbox-path adjustment changes no product behavior.

`compile-evidence.json` records successful current-product compilation of the one viewport fixture, exact source/dependency identities and child sandbox. It checks all initial 64 frozen bytes before and after execution. Product Kotlin/Java/resources remain the same Root snapshot. This is an actual headless Compose dialog-layer interaction/render proof; OS focus, keyboard entry, native HWND, physical DPI/resize and packaged startup are still outside this scope.

Reproduce while keeping the exact pinned product snapshot:

```powershell
python desktop/.local/network-proxy-product-proof/viewport-review/compile-run.py
```

For Root's final record, use the initial transport 15 case proof plus this settled four-flow UI proof. Keep the initial short-settle PNGs as historical evidence of the capture issue, not final visual snapshots. No additional upstream source/resource/dependency/task/main patch is introduced.

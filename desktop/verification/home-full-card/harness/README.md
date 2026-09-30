# Root product-only full Home Card proof

Status: **prepared, not executed**. Wait for Root confirmation of the new compiled immutable product snapshot before compile/run.

This lane copies only final13 fixture code. It never emits production packages or recompiles Card, Store, Discovery, palette, original renderer, API, or any other product closure. The original 151-artifact handoff remains unchanged.

Inputs supplied by Root:

1. Newly compiled immutable product snapshot manifest with exactly Kotlin/Java/resources JAR artifacts and raw hashes.
2. Manifest raw SHA-256.
3. Actual Gradle ordered runtime CP exported as JSON `[{"path":"...jar","sha256Bytes":"..."}, ...]` (or `{ "entries": [...] }`). Replace only production output directories at their original positions with corresponding immutable snapshot JARs. Keep every external dependency and its order. Do not use historic 231-dependency ordering or prepared closure directories.

Commands after Root confirms the snapshot:

```text
python desktop/.local/home-full-card-product-ui-proof/runner.py verify --snapshot <manifest> --snapshot-sha <sha> --runtime-cp <ordered-cp-json>
python desktop/.local/home-full-card-product-ui-proof/runner.py compile --snapshot <manifest> --snapshot-sha <sha> --runtime-cp <ordered-cp-json> --attempt 01
python desktop/.local/home-full-card-product-ui-proof/runner.py run --snapshot <manifest> --snapshot-sha <sha> --runtime-cp <ordered-cp-json> --attempt 01
```

Compile emits only UiFixture.kt and ColdDiskReader.kt into a uniquely named fixture JAR. It rejects class overlap with any runtime JAR. Seventeen actual production classes are pinned by first-match CP origin and raw class SHA, including real Store/Discovery/original Card/new preferences/palette/API adapter. Every pin must originate in the immutable product 3 JARs. No production override is accepted.

Expected actual proof (not yet claimed): MATERIAL3 and MIUIX × LIGHT/DARK, 56 press/release pointer pairs, 16 UI screenshots, 4 local synthetic cover images, actual Coil/Skia palette, exact raw BV/CID/MID navigation, guest Watch Later -> login without POST, original reply priority, retained source page, and 4 independent cold JVM reads of original settings keys. Each cell retains the existing final13 14-pointer path. A later cell failure preserves successful prior cells and failed PNG/semantics; attempts cannot overwrite evidence.

Scope: task-owned synthetic raw VideoItem and local images; task-owned global PluginStore root; immutable production classes; finite 1100×1050 ImageComposeScene. No HWND, OS chooser, live account, Bili HTTP, real media playback, whole Main/Runtime, mounted wallpaper/blur, shared transition host, or platform native haptic hardware acceptance. The producer under test is real product DiscoveryContentScreen and the real visual settings section, mounted by fixture rather than the entire Root Shell.

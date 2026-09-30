Use `stage2-contract.json`, `stage2-artifact-manifest.json` and `owned-files-final.json` for integration.

The initial metadata freeze included Python's generated `__pycache__` file as a 21st product-owned entry. It is not a product source. The final handoff removes that entry, leaving exactly 20 owned files. The initial metadata manifests remain unchanged for traceability. No Kotlin/source, compiled class, dependency, PNG, test report or runtime evidence changed in this correction.

`INTEGRATION.md` is the full integration contract. The final run directly uses Root's successful immutable foundation jars; it does not load the earlier Stage 1 class directory or fixture-only PluginStore override. Only `classes-foundation-product` is the accepted compiled output.

Verified: 15 actual Unit fixture methods, 28 real headless Compose pointer actions over Material3/Miuix × light/dark, 3 source checks. Not verified: native document picker/clipboard, actual Bilibili account sync/profile requests, Root integration/desktop deployment.

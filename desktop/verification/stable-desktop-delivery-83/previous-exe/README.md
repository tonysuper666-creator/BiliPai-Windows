# Actual previous EXE forwarding boundary

This updater-only run uses the original 0.2.406.5 ZIP and the validated/deployed 0.2.415.1 ZIP from source 6fd5bbd804f272650942f5a445385ba5428ffa9b. Preflight proves the old ZIP EXE, launcher cfg and product JAR match the three corresponding old desktop program files by bytes and embedded version. No account data was read and the desktop EXE was not run.

Only existing updaterSmoke ran, with updatePreviousPackage pointing to the original old ZIP. Children and registry files use the existing test temporary case root and isolated LocalAppData. The original previous launcher was extracted into that case root, without editing it or the old ZIP. Its real result appears in forwarding-summary.json and the unchanged raw report. Existing negative fixtures do not replace the real old package.

Neither archive, desktop payload/metadata, production source nor tests were changed. No repackaging, native mux rerun, public release or actual user shortcut test occurred. Real account playback and whole feature parity are separate. This new receipt supplements attempt 02 without rewriting its historical tested=false evidence.

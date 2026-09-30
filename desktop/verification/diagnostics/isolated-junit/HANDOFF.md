# Diagnostics — actual JUnit conversion

Only new production-tree file written by this lane:
`desktop/src/test/kotlin/com/bilipai/desktop/diagnostics/DesktopDiagnosticsTest.kt`.
No main Kotlin, build/manifest, frozen fixture, native window, account or user data was changed.

## Actual evidence

`compile-and-run.py` verifies the **final** blacklist snapshot manifest raw SHA
`38efc073ee76d2148e700582e0be468d4c0eea24cea1b5111943ac865402a919`, then uses its
three immutable jars and pinned runtime dependencies. Four reviewed diagnostic
actor/runtime/bridge sources and four generated original policy/settings sources
are explicit overrides/additions, listed by raw identity in `compile-evidence.json`.
No PluginStore, PlayerState, decoder or appearance renderer is overridden.
Cached JDK21/Kotlin2.4 compile and actual JUnit Platform/Jupiter execution completed:
**18 discovered, 18 successful, zero skipped**. No shared Gradle was run.

## Meaningful method groups

The original 14 actor/disk groups became 14 independently discoverable Unit methods:

1. False default: basic W/E and fixed startup are retained; unconsented detail absent.
2. Atomic consent disk save/publication and the one `settings/enhanced_diagnostic_logging_enabled` global key.
3. Actual PlayerState observer: finite state only, excluding media titles/subtitles/URLs/error text.
4. Both real upstream bridge shapes: sanitize before ring, disk and export.
5. Serial off operation removes detail and old/new queued detail cannot resurrect it.
6. Original ring eviction, message truncation, real basic/runtime byte caps and UTF-8 validity.
7. Original burst duplicate suppression while a later real event remains accepted.
8. Actual explicit export bound, CREATE_NEW refusal and active-log overwrite refusal.
9. Actual oversized historical file is bounded and sanitized again on read.
10. A task-owned thread's uncaught handler: local crash snapshot independent of enhanced consent; chained handler retained.
11. Serialized clear affects known files/ring only, retaining an unrelated file in the log directory.
12. Restore shutdown drains accepted records, rejects the retired facade, then allows backing freeze.
13. Actual consent atomic persistence failure keeps false state and successfully retries.
14. Blocked real writer: close cannot return before accepted disk work finishes and rejects late records.

Four additional methods cover the requested process/OS boundaries:

15. An actual cold child JVM reads saved consent from the same key before first detailed record.
16. Real Windows logs-directory junction refuses read/export/clear and preserves task victim bytes.
17. Real Windows ancestor junction refuses read/export/clear and preserves task victim bytes.
18. A real Win32 exclusive file handle first proves OS read denial, then checks safe product read/export failure, no output file, unchanged source and recovery after handle close.

The cold probe has its own `user.home`, temp and APPDATA environment, closes its actor,
and is **not counted as another JUnit method**. Junctions are checked as `isOther`
and not symbolic links; the exact links are removed before JUnit's temp cleanup.
The three Windows-only methods explicitly abort on other OSes; all passed on this Windows run.
No symlink privilege fallback, weakened assertion or dummy test was used.

Root can run the one normal suite after its Main/Shell integration:
`com.bilipai.desktop.diagnostics.DesktopDiagnosticsTest`. All 18 methods explicitly
return Unit, and actual JUnit discovery was checked against that count.

## Limits

This suite covers real local actor/disk/process/Win32 boundaries. It does not claim
Main installation timing, actual Compose consent/dialog gestures, a native save
dialog, Firebase/analytics, process-wide crash-handler installation, or packaged EXE
acceptance. The observer consumes the real PlayerState model with synthetic data;
it creates no native player. Existing frozen producer/review UI evidence remains
separate and unchanged. Root's future shared suite proves the integrated main,
whereas this isolated run honestly identifies its diagnostic overrides.

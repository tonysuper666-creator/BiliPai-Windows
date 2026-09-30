# Actual diagnostics product lifecycle and Root startup proof

This is a fixture-only proof against Root's immutable product snapshot
`5e8fc360372557bf004b55a1ded9fe4be1a3b6bae33090cb037d8fe600006fda`.
Only `DiagnosticProductLifecycleFixture.kt` was compiled; the three real product
jars lead both compiler and runtime classpaths. No diagnostics, Store, Runtime,
Root, policy, renderer, or repository implementation was overridden.

`snapshot-3` is the final run: seven fresh JVM scenarios, **70 executable
assertions**, no failures. These are executable fixture assertions, not JUnit
test counts. Separate read-only source verification passed 15 contracts and
verified 29 pinned source/generated identities.

| Actual product case | Evidence and fixture boundary |
| --- | --- |
| startup | Same persisted global consent before bridge events and actual Repository construction; independent facade shares authority. Disable writes the original key and removes detail logs. Object and array consent values give explicit Failure/null actor and preserve corrupt bytes. Repository uses its real session implementation with task-owned persistent=false storage. Main Window itself is not run. |
| observers | Both actual observePlayback Jobs consume separately tracked offline PlayerState streams, reach the real local file viewer, omit source titles/subtitle text, and cancel/join before actor completion. These are offline state inputs, not native mpv decode. |
| accepted-writer | A real supported ThreadPoolExecutor is blocked. Actual accepted consent write queues behind it. Runtime enters its actual beforeStoreFreeze hook, cancels/joins both observers, rejects new observations/records, and cannot freeze global storage until the write drains and persists. Existing held peer facade then rejects writes. |
| close-bypass | Same barrier through the actual Runtime.close asynchronous disposal path. Explicit repeat is idempotent and the hook executes once. One Runtime per JVM; no multiple-Runtime guarantee. |
| hook-failure | Actual first hook failure propagates and prevents store retirement. Same-instance explicit retry completes drain/freeze; subsequent calls are idempotent. This is a hook failure, not an actor 10-second timeout reproduction. |
| Root startup M3/Miuix | Actual DesktopApp, actual appearance preferences, original AppSurface and controls. Malformed diagnostic key + corrupt guest discovery prevents ReadyApp. Both safe error messages render. Press/Release retry is backed by another actual file-read safety check. Corrupt bytes remain. Actual registered Root shutdown retires the guard and freezes both already-held global facades. Original restart Press/Release calls the supplied fixture callback, without launching a process. |

The two styles produced six final 1080×800 PNGs. All pixels are opaque and text
contrasts with the actual background. Each final PNG exactly matches its capture
from a separate prior fresh-JVM run. Startup/stopped representative images were
visually reviewed; the initial consent error remains visible after retirement.
There is no fixture AppSurface or panel replacement.

A fixture Java 21 safety fence refused outbound connections, listeners,
multicast and subprocess launches; **all seven cases recorded zero attempts**.
Every home/appdata/session/log directory is task-owned. Actual Runtime validates
the current fixed worker catalog
`fcad2583c2862e66870f100a314db53534bbc782f4bca8b144ae7eaf3c0fe628`
at `desktop/build/jw/876b361fa45913e2/r`. Its 13 CP entries and 137 resource
entries are checked by the actual product factory. No JS worker is executed.

Main's first construction order and the two full ReadyApp backup/normal shutdown
seams are pinned **source contracts**. Actual Runtime/lifecycle and Root failed
startup wrapper are executable proof. This does not claim full native Main
Window, native player, packaged EXE, first real API request, chooser, fatal native
crash, process restart, Firebase, account login, or archive restore.

## Initial fixture limitation retained

`snapshot-1/accepted-writer.log` preserves the first fixture failure. It wrongly
expected a newly constructed facade **after** freeze to share the retired
generation. Actual backingFor intentionally constructs a fresh generation after
retirement. The corrected final fixture constructs and retains its peer before
freeze, proving the intended old-generation boundary. No product fix was needed.
`snapshot-2` passed before the final extra retry-file-read assertion; its PNGs
are retained as independent convergence evidence. Historical compile metadata
describes its own earlier fixture source hash; it is not relabelled as snapshot-3.

No main/shared Gradle/build/manifest or previous frozen payload was modified by
this lane. No native window was created.

After the 29 pinned-source checks passed, Root continued its separate local
viewer work and changed `DesktopLocalDiagnosticViewer.kt`. The freeze records
that later source digest separately. The three immutable jars stayed unchanged;
this cohort does not claim execution of the later viewer implementation.

Reproduce into a new named evidence directory (existing runs refuse overwrite):

```powershell
python desktop/.local/diagnostics-product-lifecycle-proof/compile-run-product-fixture.py --snapshot desktop/.local/diagnostics-main-product-snapshot/manifest.json --snapshot-sha 5e8fc360372557bf004b55a1ded9fe4be1a3b6bae33090cb037d8fe600006fda --worker-root desktop/build/jw/876b361fa45913e2/r --run-name reviewed-new-run
```

The final cohort is listed in `frozen-artifacts.json`; it excludes itself.

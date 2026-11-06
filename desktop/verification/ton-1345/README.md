# TON-1345: CDN cooldown behavior and session ownership

Fixed base: `761af1ac927898804701a31a1103d1d110d8eb36`, targeting
`desktop/integration-263-claude-main`. The product defect reproduced here is a
retired single transport recording a host failure after its synchronous open or
read throws. That failure affects later playback through the shared host table.
The patch checks the captured platform ownership before recording completion,
matching the existing parallel transport's cancellation boundary. Active counts
are still released. It does not introduce another cooldown policy.

## Existing production chain

The current source set mixes generated legacy bodies and migrated root bodies.
The final compile inputs, not just matching names, determine the active code:

1. `DesktopPluginRuntime.kt:233` calls `CdnRegionPlugin.rewritePlaybackCandidates`.
   Generated `plugins/.../CdnRegionPlugin.kt:139` configures/registers signed
   routes, sorts safe candidates and calls `CdnTransferRuntime.rank`.
   The wrapper at line 657 delegates to that original plugin.
2. `DesktopMediaByteCache.kt:376` constructs `DesktopCdnMediaRangeSource`.
   Its check binds the caller job and captured playback owner; candidate
   authorization at line 78 requires exact signed URL membership. It creates
   the generated `CdnPlaybackDataSource`. Single and parallel transfer feedback
   ultimately calls `CdnTransferRuntime.finish`.
3. `extractOriginalCdnTransfer` retains five original bodies, with recipe input,
   Git blob, generated output and exact inverse checks. However, M4d migration
   excludes its generated Runtime from compilation. The active Runtime is
   `app/src/main/java/.../CdnTransferRuntime.kt`, registered as a migrated root
   source. DataSource remains in `desktop/upstream-core/m4d/later-steps.tsv`
   and is compiled from the producer output.
4. The existing automatic `desktop/upstream-core/patches/**/*.patch` channel
   (`desktop/build.gradle.kts:2828-2877`) applies the clock-only Runtime patch
   to `build/patched-upstream`; source-set filtering excludes the raw root
   copy and retains that patched output. No root build registration changed.
   The runtime still writes automatic host cooldown in `finish`, manual
   cooldown in `avoid`, and reads both in `rank`. Video registration clears
   preferred routes but preserves host health; network/restore reset health.

An initial full Gradle run exposed three clock-boundary failures because a
same-named generated Runtime was excluded. That ineffective recipe change was
removed, preserving every test assertion. The final clock seam targets the
actual compiled root Runtime through the existing patch channel. Evidence keeps
this initial failed pair separately; task-name equality alone would have hidden
its three new assertion failures.

The issue's 60-second video / two-minute global rules belong to a different
algorithm, `PlaybackCooldownManager`. The video-state producer also retains
its complete body (`extract-upstream-video-state-core.py:332`, logger binding
only). Its immutable original SHA-256 is
`f14bec14a699587e36fbaa7e94636f1274fcd0e69b3bbb3d0c8309aa0170e440`.
`DesktopRepository.kt:704` already calls `clearAll` on account/session reset;
`DesktopOriginalPlaybackFailure.kt` consumes the playback API error policy.
No transport cooldown duration was replaced by these API durations.

| Existing rule | Behavior verified with injected time |
| --- | --- |
| Host transport failure | Same host ranks behind another signed host for 30 s; returns at exactly 30,000 ms |
| Manual host avoidance | 60 s; a success does not clear it early |
| Video change / success | Automatic host cooldown persists; preferred paths reset on video change |
| Network change / restore | Host cooldown resets while signed route membership remains bounded |
| All hosts cooled | Finite signed candidates remain; a failing real single delegate returns without launching parallel HTTP calls |
| Transport 429 | Connection limit reduced for 30 s |
| Recent speed | Retained at 120,000 ms, expires at 120,001 ms |
| Playback API video failures | Third failure cools that video for 60 s; another video remains ready |
| Playback API global failures | Eighth consecutive failure cools across videos for 120 s |
| Playback API success / session reset | Success clears global state but retains an already cooled video; `clearAll` clears both and failure counters |
| Retired synchronous open/read | Both throw as before, release active counts and no longer penalize the shared host |

## Reproduce the measured behavior

Use JDK 21. The runner uses the project's already cached, pinned Kotlin 2.4.0,
coroutines 1.11.0, OkHttp 4.12.0 and JUnit 5.10.2 jars; it does not download
dependencies. Run from the repository root, with distinct output directories:

```sh
python3 desktop/verification/ton-1345/run_cooldown.py --output ../evidence/source-old --before-fix
python3 desktop/verification/ton-1345/run_cooldown.py --output ../evidence/source-new
git diff --check
```

Measured results: before fix **12 started, 10 passed, 2 failed, exit 1**;
after fix **12 started, 12 passed, exit 0**. The same two test files and clock
binding are used in both runs. The old run substitutes only the immutable base
DataSource recipe. Both failures are the retired-session open/read cases:
expected `[a, b]`, observed `[b, a]` after the stale error.

The runner executes the actual unchanged producer, applies and reverse-checks
the native Runtime patch against the merged root input, and compiles full
runtime, transport, policy, platform and SDK source files. It borrows no precompiled
classes from another checkout and substitutes no cooldown implementation.
Only the upstream I/O boundary is scripted; unexpected parallel HTTP fails.
Each output contains:

- `commands.json`: actual compiler/producer/launcher argv, exit and raw output.
- `producer.json`: all five `inverseOriginalBodyExact: true` receipts.
- `receipt.json`: source, jar, producer and recipe SHA-256 values, plus the
  active root Runtime input/patch/output and exact inverse receipt.

The eight transport tests live in the normal Gradle test source set. Four API
tests are explicitly standalone verification: the runner replaces exactly four
wall-clock reads in the complete pinned API body and proves exact inverse
reconstruction. Its logger is a test sink. These tests establish the existing
algorithm's boundaries, not execution of the live account or API request chain.
The product API implementation and its clock were not changed.

The production clock seam preserves monotonic `System.nanoTime` milliseconds.
Only tests override it, restoring it afterward. Session tests retire ownership
inside the scripted synchronous I/O callback; they do not prove atomicity
against every possible concurrent owner change between check and state write.

## Normal Gradle acceptance

Both fixed base and final working tree ran through the current whole-machine
heavy FIFO with identical argv, all existing finalizers preserved:

```sh
./gradlew -p desktop :test --tests com.android.purebilibili.feature.plugin.DesktopCdnCooldownTest --console=plain -Pkotlin.incremental=false --no-build-cache
```

The extra compiler flags force compilation of the current inputs without build
cache reuse. Final target result: **8 tests, 0 failures, 0 skipped**. The whole
command still exits **1** on both base and head: `upstreamAppCoreSourceTests`
has 46 failures and `sponsorPresentationTests` has 1. XML identities compare
(task, class, test, failure type, message): **47 shared, 0 head-only, 0 base-only**.
The base also reports the expected missing new target test. These inherited
failures are not a green full gate and were neither skipped nor weakened.

Raw logs, argv/exit receipts, XML and the mechanical diff are attached to the
issue. The attachment separately retains the initial pair with three clock
failures, corrected by the final native Runtime patch. Final Gradle generated
producer outputs match the final standalone output in all five families; the
actual patched Runtime also matches byte for byte. Compiled bytecode confirms
both the injected clock call and the synchronous ownership guard.

## Compatibility and remaining acceptance

The producer, recipe, playback platform and range adapter at the fixed base are
byte-identical to candidate v0.7.8 base
`7f3b3af5bd7ea4febe7b5f3fd7562d642e99950f`. This is compatibility evidence,
not a claim that v0.7.8's complete build or UI passes. The active root Runtime
is different there (enabled StateFlow). The native clock patch applies to that
candidate and reverses to its exact original bytes; its complete build was not run.

Windows/GUI, live account changes, real production HTTP and timing under actual
network load remain unverified. No VM or physical-machine measurement was run.
Independent review is required before accepting or merging this change.

# TON-1073 single-file dialog slice

PR #58 remains a **draft**. Test-contract corrections in
`e1afd7bc23f9c30d1f9d1aade3f2eeb8dc6bdc60` are **not validated**.
This slice does not complete the platform interface card.

## Two failure contracts

Original head `99891bdb69705a1328479d2a1ee5ae4eb2703797` ran 12 new
file-dialog cases: 10 passed, two failed. Their raw XML is in the issue evidence;
these two failures are not baseline failures.

- `nativeFailureIsPropagatedAndTheNextRequestStillWorks`: the interface
  promises failures throw, not exception-object identity across suspension.
  The pinned kotlinx.coroutines 1.10.2
  [stacktrace recovery contract](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/docs/topics/debugging.md#stacktrace-recovery)
  permits copying eligible exceptions with the original as their cause.
  Debugging, including JVM assertions in tests, enables recovery. Assertions
  now check exact class, message and original identity (directly or as cause).
  The next-request check and factory, owner and native-link failure coverage
  remain. No production behavior or coroutine debugging option changed.
- `saveErrorsFromTheRealFileAdapterRemainVisibleAndPreserveExistingData`:
  `DesktopCommentFraudHistoryFiles.writeUtf8` checks the selected fingerprint
  with `require` before creating a temporary file (line 127 at fixed base).
  An external change therefore throws `IllegalArgumentException`.
  The test requires that type and exact message
  `Selected export destination changed`; it still checks external contents
  and that no temporary file leaked. The file adapter was not modified.

## Current attempt: blocked, 2026-11-01

The issue archive contains the executed `run-suite.sh`, copied Coach `heavy`
entry with bounded wait, full log/exit code, clean snapshot SHAs/status,
original failure XML and conflict process evidence.

- The attempted command below exited **1** after 2m 8s. Unqualified `test`
  also selected `:upstream-core:test`, which had no matching tests. The root
  project's 12 cases did **not** run; there is no new passing XML.
- `compileKotlin FROM-CACHE`, `compileTestKotlin` and
  `verifyUniqueClassNames` completed (60,918 classes, no duplicates).
  This is not a successful targeted test result.
- The initial process guard checked macOS's truncated `comm` field and
  incorrectly reported no build. Checking the full executable in `args`
  subsequently found TON-1004's unguarded Gradle PID 23094 and worker 48414;
  `lsof` confirmed the daemon's working directory was its `wt/pr18` checkout.
  This attempt did not establish an exclusive build window.
- No base/head comparison started. No process was terminated, no other lock
  removed and no retry or wakeup scheduled. The owned build exited and its
  lock was released.

Attempted command (inside the copied entry's `nice -n 15` child):

```sh
./gradlew -p desktop --no-daemon --max-workers=2 --console=plain \
  -I "$TON1073_INIT" test \
  --tests com.bilipai.desktop.platform.files.AwtDesktopFileDialogsTest
```

## Resume only after coordination

Coordinate all heavy builds onto Coach's mkdir lock first. Use a private copy
of its entry, wait at most 900 seconds once, and check the full executable in
`args` after acquiring it (macOS truncates `comm`). Conflict or timeout returns
the card blocked. Keep `nice -n 15`, `--max-workers=2`, and base/head serial
under one lock. Archive `resume-suite.sh` corrects the task selector and process
guard but is **unexecuted**.

Prepare canonical external temporary paths without changing production guards:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cp desktop/verification/ton-1073-file-dialogs/verification.init.gradle ../ton1073-test.init.gradle
export TON1073_INIT="$(cd .. && pwd -P)/ton1073-test.init.gradle"
mkdir -p ../ton1073-test-tmp
export TON1073_TEST_TMP="$(cd ../ton1073-test-tmp && pwd -P)"
nice -n 15 ./gradlew -p desktop --no-daemon --max-workers=2 --console=plain \
  -I "$TON1073_INIT" :test \
  --tests com.bilipai.desktop.platform.files.AwtDesktopFileDialogsTest
```

Only after all 12 pass without skips, run the following on clean fixed base
`fb32f0b18ba03f8a99f34f7441545d8bf91aa03d` and new head, archiving complete
logs/XML separately:

```sh
nice -n 15 ./gradlew -p desktop --no-daemon --max-workers=2 --console=plain \
  -I "$TON1073_INIT" \
  compileKotlin compileTestKotlin test upstreamDesignSourceTests \
  upstreamAppCoreSourceTests :upstream-core:test verifyUniqueClassNames --continue
```

Compare totals, skips and every task/class/test/exception identity; preserve
all base cases. The previous complete observation was 7,551 cases/164 failures
on base, 7,563/166 on old head. It does not validate this fix. Archive
`compare.py` is prepared for a complete run and **unexecuted**.
Historical `mac-verify.sh` is absent from this checkout.

## Remaining acceptance

No client launch, GUI measurement, winbox or account/QR session was used.
Native Windows appearance, filtering, overwrite prompts and mounted-window
interaction remain unverified. After the gate passes, request the current
different-vendor reviewer for this finite slice; keep the rest of TON-1073
open. TON-1116 owns settings screen/service/ViewModel migration.
Multi-image picking, sharing, image operations and scheduling are later slices.
Desktop notifications reuse TON-999.

# TON-1073 single-file dialog slice

PR #58 is a **draft**, blocked on local validation. This is a partial delivery of
TON-1073, not completion of the platform interface card.

The four implementation/test files are in commit
`d213560274ca0281cb278e89d9d7cc410383e11e`, based on integration commit
`fb32f0b18ba03f8a99f34f7441545d8bf91aa03d`.

## Actual checks

- `git diff --check fb32f0b18 HEAD`: passed.
- Two baseline Gradle attempts were stopped with exit 143 when another local
  project began a concurrent build. Only this card's exact child PIDs were
  stopped. Intermediate waiting windows also expired without starting Gradle.
- The last baseline attempt reached `compileKotlin UP-TO-DATE`,
  `compileTestKotlin FROM-CACHE`, and `verifyUniqueClassNames UP-TO-DATE`.
  Its test task was interrupted; it has **no complete test result**.
- Candidate compilation, the 12 new behavioral tests, candidate unique-class
  verification, total JVM counts and the baseline/candidate failure comparison
  are **unverified**. An interrupted run's empty XML summary is not a test pass.
- The issue's historical `mac-verify.sh` was not run. The current checkout has
  no such script; use the repository's Gradle tasks below without adding source
  exclusions or stubs.

## Resume validation

First obtain an exclusive Mac build window across BiliPai **and Coach**. A
process-list check alone did not reserve the slot: other builds started after
this card's Gradle had started. Do not keep restarting when that happens.
Keep `nice -n 15` and `--max-workers=2` until 2026-11-02 15:00 GMT.

Run the following from the repository root on both the clean base and this PR
head, retaining each complete log and test XML before switching revisions:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cp desktop/verification/ton-1073-file-dialogs/verification.init.gradle ../ton1073-test.init.gradle
export TON1073_INIT="$(cd .. && pwd -P)/ton1073-test.init.gradle"
mkdir -p ../ton1073-test-tmp
export TON1073_TEST_TMP="$(cd ../ton1073-test-tmp && pwd -P)"
nice -n 15 ./gradlew -p desktop --no-daemon --max-workers=2 --console=plain \
  -I "$TON1073_INIT" \
  compileKotlin compileTestKotlin test upstreamDesignSourceTests \
  upstreamAppCoreSourceTests :upstream-core:test verifyUniqueClassNames --continue
```

The preparation lines run on the PR head before switching to the
base; reuse those environment variables for both runs. The init file gives
tests a canonical temporary directory on macOS; it does not alter test
selection, assertions or production path guards. Both temporary paths stay
outside the worktree.

Compare complete XML totals and every failing task/class/test/exception between
base and head. Expect 12 additional cases in `AwtDesktopFileDialogsTest`; do not
claim no regression from a partial run. The new cases exercise the production
EDT coordinator and nested AWT event loop, real file IO and the real settings
export/import codec. Only the native dialog peer is replaced.

## Remaining acceptance

Do not launch/close the client or touch the winvm account/QR session while
TON-1068 owns it. Native Windows dialog appearance, filename filtering,
overwrite confirmation, mounted-window interaction and GUI cancellation remain
unverified. No GUI measurement was performed for this card.

After validation, request the first available reviewer of a different vendor,
review only this slice, and keep the rest of TON-1073 open. TON-1116 still owns
the original settings screen/service/ViewModel migration. Multi-image picking,
sharing, image operations and background scheduling are later slices.

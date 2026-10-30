# Scheduled-task descendant evidence

These scripts are the source copy of `bp-mac-verify\tools\launch.ps1` and the
opt-in TON-1047 session. They do not change client feature code.

The existing launcher's PAUSE, canonical apps path, reparse-point, fullscreen
and GPU gates are retained. Requests without `capture_tree: true` retain the
previous single-PID behavior. `Invoke-BpJob.ps1` remains unchanged; its process
name/time fallback cannot prove which launch owns a window.

Opt-in requests add `capture_tree`, `evidence_dir` (inside the verification
directory), and optionally `session_script` (inside `apps`). Tree mode measures
`Win32_Process.ParentProcessId` and creation time, admits only descendants of
the measured root identity, enumerates their top-level windows including
hidden dialogs, and captures only a revalidated owned HWND. Missing parents
are never guessed by process name. Processes that exit between CIM samples
may be missed; that gap is not evidence of an unobserved descendant. Polling
also cannot prove that a transient dialog never existed between observations.

Each PNG has a `.window.json` mapping to PID, HWND and process identity.
`pid-tree.json` records measured edges, timestamps, window observations and
dialog counts. Cleanup rechecks creation times before stopping the observed
tree. JSON uses PowerShell's serializer and a BOM-free UTF-8 writer with atomic
replacement. PowerShell source scripts may retain their BOM for PS 5.1 Chinese
source compatibility; JSON artifacts must not contain it. The launcher's legacy
result writer also emits BOM-free UTF-8.

TON-1047 order (do not run automatically):

1. `Prepare-TON1047.ps1` builds the pinned PR #19 head using JDK 21, an isolated
   checkout and Gradle cache already inside the verification directory. It
   retains the previous package, supplements libmpv/ffmpeg and writes hashes.
   Python extracts the exact pinned legacy archive because Windows bsdtar
   rejects one Unicode filename; tracked source is unchanged.
2. `Invoke-SelfTest.ps1` runs one harmless CMD → PowerShell → WinForms child
   through `BiliPai-MacTest`, proving descendant capture and cleanup. Do not
   repeat this on TON-1047.
3. `Build-UiProbe.ps1`, `Prepare-ProbeRuntime.ps1` and `Test-Offline.ps1` prepare
   and check the driver without opening a GUI. The test runtime uses the same
   JDK 21 version and adds `java.instrument` for accessibility inspection.
   Original runtime is retained; product JARs/exe/cfg are unchanged. Disclose
   this runtime difference alongside resulting evidence.
4. After the member's decision is recorded in `approval.json`, `Invoke-Once.ps1`
   checks isolation and creates an exclusive durable launch marker. It invokes
   the scheduled task once, waits in the foreground, and restores the prior
   request. Never remove the marker to retry.

`Verify-TON1047.ps1` uses SMTC metadata, status and timeline progression.
In-process accessibility and guarded keyboard input navigate to the second
video and settings language restart confirmation. A PID change plus visible
settings navigation in the new process is required for responsiveness. The
driver refuses ambiguous/missing controls. Every failure ends the attempt.

SMTC API evidence cannot prove that the Windows shell's media flyout was
displayed. That flyout belongs to a shell process outside this launch tree;
owned-window screenshots do not capture it.

The wrapper redirects Local/Roaming data, user home, XDG directories, TEMP/TMP
and HotSpot home/temp properties beneath `ton1020\profile`. Java restarts
inherit these settings. It rejects absent executables and reparse points.
Configuration/JVM properties support isolation but cannot replace a file-access
audit. Do not inspect the real profile to try to prove a negative.

The approved TON-1047 attempt on 2026-10-29 stopped before a visible client
window was measured: invoking the callback with `&` lost its script-scoped
tree directory. The result was a null `Path` error, 0 client screenshots and
5 measured processes, all cleaned up. Actual jpackage parent/child PIDs and
the child's hidden AWT handles were measured; visible-window ownership,
playback, SMTC and restart were not validated. `Invoke-BpEvidenceSession`
now dot-sources the callback in the owning scope. `Test-CallbackContext.ps1`
reproduces the original error and verifies the fix using this shell's real
PID and file output, without a client, GUI or process termination. After review,
the regression executes `Verify-TON1047.ps1 -OfflineContextCheck` through its
real first tree update and error/finally reporting path. It requires the exact
recorded null `Path` message, parameter name and Join-Path error ID; unrelated
exceptions fail the test. It then exercises that same script via the fixed
callback and checks the JSON has no BOM. This fix has not had another client run.
The existing single-use marker must remain.

`request-before.json` is the preceding shared configuration, not the request
submitted for the acceptance attempt. Future sessions retain its exact bytes as
`request-before.raw`, write a BOM-free JSON view, and separately save the actual
`request-submitted.json`. The historical submitted request was not captured and
must not be presented as an original observation when reconstructed from code.

`Restore-OriginalRuntime.ps1` restores the retained original runtime only while
MacTest is Ready, both cooperative locks are free, and Windows Restart Manager
reports no owners of any current runtime resource. It retains the probe runtime,
verifies the original release has no `java.instrument`, records file hashes and
checks that the single-use launch marker is byte-for-byte unchanged. It neither
edits the scheduled task/shared launcher nor starts a client.

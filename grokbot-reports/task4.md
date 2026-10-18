# Task 4: nvidia262-auto-tier compile and test verification (Linux)

- Source: `origin/desktop/nvidia262-auto-tier` @ `64eaf32ce`, which is 8 commits (S1–S7 plus integration fixes) on Native261 head `4f38f23cb`.
- Branch: `grokbot/nvidia262-compile-fix`. **No code changes**: compilation and all requested tests passed, so this commit adds only this report.
- Scope of the 8 commits: 51 files (desktop/src/main 24, desktop/src/test 16, desktop/tools/nvidia 11). No protected paths and no CR bytes.
- Environment: Linux box, Temurin 21, Gradle 9.5.0 via gradlew, `--no-daemon --max-workers=1 --no-parallel -Pkotlin.compiler.execution.strategy=in-process`, `-Dorg.gradle.jvmargs=-Xmx6g -Xss16m ...`. The build dir was deleted beforehand (clean build).
  - Linux-only workarounds, the same as in Tasks 1–5: a PYTHON_EXECUTABLE shim that strips the `\\?\` prefix, plus stubs for the outputs of the excluded tasks.
  - Excluded tasks: `-x prepareNativeDiagnosticShare -x fetchJsWorkerJdk -x prepareJsWorker`, plus `-x prepareJsWorkerResources` for the test runs.

## Pre-existing blocker (not fixed, protected file)

`:prepareVeyraRuntimeVerifier` fails with `Veyra verifier source identity changed.`

- Base commit `4f38f23cb` edited `desktop/tools/native/veyra/verify-veyra-runtime.ps1` (new presentation hashes). The file's SHA-256 is now `5e265f07…a104102`.
- `desktop/build.gradle.kts:2336` still pins `ee94c395…b517ff8f`, which is the hash of the file as it was at `17705422e`.
- The nvidia262 commits touch neither file. This is a base-head problem, not an nvidia262 problem.
- `processResources`, `prepareAppResources` and every JavaExec depend on the task, so on any platform it should block processResources and therefore test and run. *Inference, not verified on Windows cloud.*
- A fix needs either the build.gradle.kts pin (build logic, forbidden) or a desktop/tools/native .ps1 change (outside the allowed fix scope). **The user must decide.**
- For local verification only, I added `-x prepareVeyraRuntimeVerifier` to the commands below. Nothing was committed to work around it.

## Compile (Linux)

| Step | Result |
|---|---|
| compileKotlin + compileJava | BUILD SUCCESSFUL, 5m12s, 455 warnings, 0 errors |
| compileTestKotlin + compileTestJava | First attempt failed on the Veyra pin above. With `-x prepareVeyraRuntimeVerifier`: BUILD SUCCESSFUL in 1m45s, 0 errors |

## JVM tests (Linux, `--tests` per class)

| Class | Tests | Fail | Skip |
|---|---|---|---|
| nvidia.DesktopNvidiaGpuCapabilityTest | 28 | 0 | 0 |
| nvidia.DesktopNvidiaTierProfileTest | 31 | 0 | 0 |
| nvidia.DesktopNvidiaActivationTest | 37 | 0 | 0 |
| nvidia.DesktopNvidiaStatusTextTest | 12 | 0 | 0 |
| nvidia.DesktopNvidiaAdaptiveGuardTest | 61 | 0 | 0 |
| nvidia.DesktopNvidiaHealthStoreTest | 21 | 0 | 0 |
| nvidia.DesktopNvidiaMpvScalerProfileTest | 16 | 0 | 0 |
| nvidia.DesktopNvidiaCapabilityMonitorTest | 34 | 0 | 0 |
| player.DesktopNvidiaVideoAutoPolicyTest | 33 | 0 | 0 |
| player.DesktopVideoEnhancementSessionTest | 25 | 0 | 0 |
| player.PlayerNvidiaVideoTest | 20 | 0 | 0 |
| player.PlayerNvidiaActorWithdrawalTest | 36 | 0 | 0 |
| player.DesktopWindowsDxgiAdaptersTest | 18 | 0 | 0 |
| plugins.DesktopVideoEnhancementConfigurationTest | 23 | 0 | 0 |
| **Total** | **395** | **0** | **0** |

- CapabilityMonitorTest was run 3 more times with `--rerun`. Each run: 34/34 passed, 0 skipped, about 2.67s.
- No test failed or was skipped, so there are no Windows-only failures to label.

## Python

| Command | Plain | With `\\?\` shim |
|---|---|---|
| discover desktop/tools/nvidia `test_nvidia_*.py` | 323 OK | 323 OK |
| test_original_player_nvidia_slot | 0 run, 1 error (Windows `\\?\` path, Linux-only) | 3 OK |
| test_extract_video_enhancement | 7 OK | 7 OK |
| test_windows_audio_output_focus_sources | 9 OK | 9 OK |
| test_rtx_identity_postfilters | 9 OK | 9 OK |
| test_rtx_hdr_color (known baseline) | 1/7 fail | 1/7 fail |
| test_extract_upstream_brand_success_mount (known baseline) | 3/3 error (`\\?\`) | 2/3 fail |
| test_extract_upstream_up_danmaku (known baseline) | 2/10 fail | 2/10 fail |

I ran the three known-baseline modules in a worktree at base `4f38f23cb` as well. The failing test names are identical on the base and on nvidia262, so the baseline is unchanged:

- rtx_hdr_color: `test_actual_delivery_source_identities_follow_the_corrected_shader`
- brand_success_mount: `test_actual_members_pass_the_existing_unmodified_exact_member_gate`, `test_business_bridge_has_one_inverse_and_changes_no_original_vm_or_protocol`
- up_danmaku: `test_actual_owner_last_stage_inverse_equals_full_generated_original_before_guard`, `test_changed_guard_anchor_and_double_application_fail_closed`

## Evidence classes

- **Linux compile:** main and test compile succeed. Test compile needs the local Veyra exclusion.
- **Python:** as shown in the table above (Linux).
- **JVM:** 395/395 passed on Linux, plus CapabilityMonitor 3×34/34.
- **Unverified:** Windows cloud validation (the Veyra pin will probably block it), real NVIDIA hardware, DXGI/RTX runtime behaviour, UI, audio and installer. This report makes no claims about any of them.

# Task 6a: R1 Veyra pin verification (Linux)

- Branch `grokbot/r1-test-currency` was created locally from `origin/desktop/r1-veyra-pins` @ `d5708390a` ("Re-pin the Native261 verifier and presentation source identities"). It is not pushed.
- Clean build: `desktop/build` and `desktop/resources/common/native/veyra-core` were deleted first.
- Workarounds used: the `\\?\` Python shim, the two stubs, `-Xmx6g -Xss16m`, and `-x prepareNativeDiagnosticShare -x fetchJsWorkerJdk -x prepareJsWorker`. **prepareVeyraRuntimeVerifier was NOT excluded.**

| Check | Result |
|---|---|
| `prepareVeyraRuntimeVerifier` + compileKotlin + compileJava + processResources | BUILD SUCCESSFUL in 5m17s. `:prepareVeyraRuntimeVerifier` executed. The string "Veyra verifier source identity changed" appears 0 times in the log. 457 warnings, 0 errors. |
| Pin consistency | `desktop/tools/native/veyra/verify-veyra-runtime.ps1` sha256 = `a0e1e04c…3ed55` = the `build.gradle.kts:2336` pin. The generated `desktop/resources/common/native/veyra-core/verify-veyra-runtime.ps1` has the same sha256. |
| `desktop/tools/tests/test_rtx_hdr_color.py` (real Gradle output present) | **7/7 OK** both plain and with the shim. Before R1 it was 1/7 failing. |
| compileTestKotlin + compileTestJava | BUILD SUCCESSFUL in 1m43s, **0 errors** |

## Evidence classes
- **Linux compile:** main and test compile pass with no Veyra exclusion.
- **Python:** test_rtx_hdr_color 7/7 on Linux.
- **JVM:** covered by Tasks 6b–6d.
- **Unverified:**
  - Windows cloud validation.
  - The native runtime has not been rebuilt. The R1 commit itself notes that an installed component still fails verification until the runtime is rebuilt.
  - Real device, NVIDIA, UI, audio and installer.

# VERIFY desktop/integration-263-claude-main@7e47d7bc5c2a0bb0aa81f048af087e268e900a93

- Linux, openjdk version "21.0.12.1" 2026-08-18 LTS, Python Python 3.13.5, 99 min for the Gradle run (clean worktree, JS-worker and diagnostic stubs, Linux only)
- Gradle: BUILD FAILED in 1h 39m 52s, exit 1; kotlin errors (e:) 0; 'shadowed by a member' warnings 0
- Evidence: Linux only. Linux pass is not a Windows validation and not a hardware proof.

## JVM
classes: 541  tests: 4406  failed: 9  errors: 0  skipped: 5
known JS-worker classes failing (expected on Linux): 3
OTHER failing classes: 2
  com.bilipai.desktop.data.DesktopDynamicCacheTest (1)
  com.bilipai.desktop.plugins.DesktopPreferenceReplacementRetryWindowsTest (5)

## log tail

[Incubating] Problems report is available at: file:///workspace/v-7e47d7bc5/desktop/build/reports/problems/problems-report.html

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':test'.
> There were failing tests. See the report at: file:///workspace/v-7e47d7bc5/desktop/build/reports/tests/test/index.html

* Try:
> Run with --scan to get full insights from a Build Scan (powered by Develocity).

BUILD FAILED in 1h 39m 52s
171 actionable tasks: 170 executed, 1 from cache
EXIT=1

## Python
- tests/test_sync_upstream.py: ---------------------------------------------------------------------- Ran 74 tests in 76.680s  OK 
- tests/test_validate_source_cohort.py: ---------------------------------------------------------------------- Ran 25 tests in 0.094s  OK (skipped=1) 
- tests/test_package_source_qualification.py: ---------------------------------------------------------------------- Ran 14 tests in 0.009s  OK 
- tests/test_symbol_closure.py: ---------------------------------------------------------------------- Ran 99 tests in 1107.591s  OK (skipped=2) 
- tests/test_v046_*_source.py: Ran 127 tests in 183.121s  FAILED (failures=1) {"wholeScreens": 2, "managerClosureMembers": 351, "managerLines": 1892, "outputs": 9, "sourcePins": 13, "sourceProofSha256": "759c57717b7945c365fd5d56346ebf7223b1b846251e80e
- tests/test_third_party_notices_source.py: ---------------------------------------------------------------------- Ran 5 tests in 0.017s  OK 
- nvidia/test_nvidia_*.py: ---------------------------------------------------------------------- Ran 323 tests in 0.887s  OK 
- ux/test_ux_*.py:                 ~~~~~~~~~~~~~~~^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^   File "/usr/lib/python3.13/unittest/loader.py", line 308, in discover     raise ImportError('Start directory is not importable: %r' % start_dir) Import
